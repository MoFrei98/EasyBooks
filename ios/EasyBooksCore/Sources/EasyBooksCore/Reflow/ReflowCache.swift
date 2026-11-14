import Foundation

/// Gespeicherte Analyse eines Buchs, damit "Groß" beim nächsten Öffnen sofort bereit ist.
public struct ReflowAnalysis: Equatable {
    public var pageCount: Int
    /// Größe der PDF-Datei; ändert sie sich, wird neu analysiert.
    public var fileSize: Int64
    /// Analyse-Pixel pro PDF-Punkt.
    public var scale: Float
    public var style: BookStyle
    public var pages: [PageText?]

    public init(pageCount: Int, fileSize: Int64, scale: Float, style: BookStyle, pages: [PageText?]) {
        self.pageCount = pageCount
        self.fileSize = fileSize
        self.scale = scale
        self.style = style
        self.pages = pages
    }
}

/// Einfaches Binärformat (Big Endian). Die App komprimiert es zusätzlich.
public enum ReflowCache {
    private static let magic: UInt32 = 0x4542_4652 // "EBFR" (alter App-Name; bleibt, damit gespeicherte Analysen gültig bleiben)
    private static let version: UInt32 = 1

    public static func encode(_ analysis: ReflowAnalysis) -> Data {
        var w = Writer()
        w.u32(magic)
        w.u32(version)
        w.i32(analysis.pageCount)
        w.i64(analysis.fileSize)
        w.f32(analysis.scale)
        let s = analysis.style
        for v in [s.xHeight, s.pitch, s.ascent, s.descent, s.textTop, s.textBottom, s.columnLeft, s.columnRight] {
            w.f32(v)
        }
        for page in analysis.pages {
            guard let page else {
                w.bool(false)
                continue
            }
            w.bool(true)
            w.i32(page.width)
            w.i32(page.height)
            w.bool(page.image)
            w.i32(page.lines.count)
            for l in page.lines {
                for v in [l.top, l.bottom, l.baseline, l.xHeight, l.left, l.right] { w.i16(v) }
                w.bool(l.figure)
                w.i16(l.words.count)
                for word in l.words {
                    w.i16(word.left)
                    w.i16(word.right)
                    w.i16(word.stemRight)
                    w.i16(word.hyphenLeft)
                }
            }
        }
        return w.data
    }

    /// Liest die Analyse oder `nil`, wenn die Daten kaputt sind oder nicht zum Buch passen.
    public static func decode(_ data: Data, pageCount: Int? = nil, fileSize: Int64? = nil) -> ReflowAnalysis? {
        var r = Reader(bytes: [UInt8](data))
        guard r.u32() == magic, r.u32() == version,
              let count = r.i32(), let size = r.i64(), let scale = r.f32() else { return nil }
        if let pageCount, pageCount != count { return nil }
        if let fileSize, fileSize != size { return nil }
        var s: [Float] = []
        for _ in 0..<8 {
            guard let v = r.f32() else { return nil }
            s.append(v)
        }
        let style = BookStyle(xHeight: s[0], pitch: s[1], ascent: s[2], descent: s[3],
                              textTop: s[4], textBottom: s[5], columnLeft: s[6], columnRight: s[7])
        guard count >= 0 else { return nil }
        var pages: [PageText?] = []
        pages.reserveCapacity(count)
        for _ in 0..<count {
            guard let present = r.bool() else { return nil }
            if !present {
                pages.append(nil)
                continue
            }
            guard let width = r.i32(), let height = r.i32(), let image = r.bool(), let lineCount = r.i32(),
                  lineCount >= 0 else { return nil }
            var lines: [TextLine] = []
            for _ in 0..<lineCount {
                guard let top = r.i16(), let bottom = r.i16(), let baseline = r.i16(), let xHeight = r.i16(),
                      let left = r.i16(), let right = r.i16(), let figure = r.bool(), let wordCount = r.i16(),
                      wordCount >= 0 else { return nil }
                var words: [Word] = []
                for _ in 0..<wordCount {
                    guard let l = r.i16(), let rt = r.i16(), let stem = r.i16(), let hyphen = r.i16() else { return nil }
                    words.append(Word(left: l, right: rt, stemRight: stem, hyphenLeft: hyphen))
                }
                lines.append(TextLine(top: top, bottom: bottom, baseline: baseline, xHeight: xHeight,
                                      left: left, right: right, words: words, figure: figure))
            }
            pages.append(PageText(width: width, height: height, lines: lines, image: image))
        }
        return ReflowAnalysis(pageCount: count, fileSize: size, scale: scale, style: style, pages: pages)
    }

    private struct Writer {
        var data = Data()

        mutating func u32(_ v: UInt32) { withUnsafeBytes(of: v.bigEndian) { data.append(contentsOf: $0) } }
        mutating func i32(_ v: Int) { u32(UInt32(bitPattern: Int32(truncatingIfNeeded: v))) }
        mutating func i64(_ v: Int64) { withUnsafeBytes(of: v.bigEndian) { data.append(contentsOf: $0) } }
        mutating func f32(_ v: Float) { u32(v.bitPattern) }
        mutating func i16(_ v: Int) {
            withUnsafeBytes(of: Int16(truncatingIfNeeded: v).bigEndian) { data.append(contentsOf: $0) }
        }
        mutating func bool(_ v: Bool) { data.append(v ? 1 : 0) }
    }

    private struct Reader {
        let bytes: [UInt8]
        var position = 0

        init(bytes: [UInt8]) { self.bytes = bytes }

        private mutating func take(_ n: Int) -> ArraySlice<UInt8>? {
            guard position + n <= bytes.count else { return nil }
            defer { position += n }
            return bytes[position..<(position + n)]
        }

        mutating func u32() -> UInt32? {
            guard let b = take(4) else { return nil }
            return b.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        }

        mutating func i32() -> Int? { u32().map { Int(Int32(bitPattern: $0)) } }

        mutating func i64() -> Int64? {
            guard let b = take(8) else { return nil }
            return Int64(bitPattern: b.reduce(UInt64(0)) { ($0 << 8) | UInt64($1) })
        }

        mutating func f32() -> Float? { u32().map { Float(bitPattern: $0) } }

        mutating func i16() -> Int? {
            guard let b = take(2) else { return nil }
            let v = (UInt16(b[b.startIndex]) << 8) | UInt16(b[b.startIndex + 1])
            return Int(Int16(bitPattern: v))
        }

        mutating func bool() -> Bool? {
            guard let b = take(1) else { return nil }
            return b[b.startIndex] != 0
        }
    }
}
