import Foundation

/// Typische Maße des Fließtexts eines Buchs (in Analyse-Pixeln), aus einer Stichprobe von Seiten.
public struct BookStyle: Equatable {
    public var xHeight: Float
    /// Abstand von Grundlinie zu Grundlinie.
    public var pitch: Float
    /// Oberlänge (Grundlinie bis Oberkante) und Unterlänge einer Zeile.
    public var ascent: Float
    public var descent: Float
    /// Wo der Text auf einer normalen Seite beginnt und endet.
    public var textTop: Float
    public var textBottom: Float
    public var columnLeft: Float
    public var columnRight: Float

    public init(xHeight: Float, pitch: Float, ascent: Float, descent: Float,
                textTop: Float, textBottom: Float, columnLeft: Float, columnRight: Float) {
        self.xHeight = xHeight
        self.pitch = pitch
        self.ascent = ascent
        self.descent = descent
        self.textTop = textTop
        self.textBottom = textBottom
        self.columnLeft = columnLeft
        self.columnRight = columnRight
    }

    public var columnWidth: Float { columnRight - columnLeft }

    public static func from(_ pages: [PageText]) -> BookStyle {
        let body = pages.flatMap { $0.textLines.filter { $0.words.count >= 3 } }
        let all = pages.flatMap { $0.textLines }
        let lines = body.isEmpty ? all : body
        let xh = median(lines.map { Float($0.xHeight) }) ?? 12
        let lineHeight = median(lines.map { Float($0.height) }) ?? xh * 2
        var pitches: [Float] = []
        for p in pages {
            let tl = p.textLines
            if tl.count < 2 { continue }
            for i in 1..<tl.count {
                let d = Float(tl[i].baseline - tl[i - 1].baseline)
                if d > lineHeight * 0.7 && d < lineHeight * 2.2 { pitches.append(d) }
            }
        }
        let full = pages.filter { $0.textLines.count >= 5 }
        let columns = full.compactMap { columnOf($0) }
        let width = Float(pages.first?.width ?? 1000)
        let height = Float(pages.first?.height ?? 1400)
        return BookStyle(
            xHeight: xh,
            pitch: median(pitches) ?? lineHeight * 1.25,
            ascent: median(lines.map { Float($0.baseline - $0.top) }) ?? xh * 1.4,
            descent: median(lines.map { Float($0.bottom - $0.baseline) }) ?? xh * 0.45,
            textTop: median(full.map { Float($0.textLines[0].top) }) ?? 0,
            textBottom: median(full.map { Float($0.textLines[$0.textLines.count - 1].bottom) }) ?? height,
            columnLeft: median(columns.map(\.0)) ?? 0,
            columnRight: median(columns.map(\.1)) ?? width
        )
    }

    /// Linke und rechte Kante des Fließtexts auf einer Seite (Ränder der meisten Zeilen).
    public static func columnOf(_ page: PageText) -> (Float, Float)? {
        let lines = page.textLines.filter { $0.words.count >= 3 }
        if lines.count < 3 { return nil }
        let lefts = lines.map(\.left).sorted()
        let rights = lines.map(\.right).sorted()
        return (Float(lefts[lefts.count / 10]), Float(rights[min(rights.count - 1, rights.count * 9 / 10)]))
    }

    static func median(_ values: [Float]) -> Float? {
        if values.isEmpty { return nil }
        let sorted = values.sorted()
        return sorted[sorted.count / 2]
    }
}

/// Wählt die Vergrößerung: Die x-Höhe des Fließtexts soll [targetXHeight] Bildschirm-Pixel haben.
/// Ist der Text schon bei voller Breite größer, bleibt es bei voller Breite.
public func reflowScale(style: BookStyle, frame: ReflowFrame, targetXHeight: Float) -> Float {
    let byTarget = targetXHeight / style.xHeight
    let fitWidth = style.columnWidth > 0 ? 0.98 * Float(frame.textWidth) / style.columnWidth : byTarget
    return max(byTarget, fitWidth)
}

/// Setzt die analysierten Seiten eines Buchs neu, Seite für Seite in Lesereihenfolge.
///
/// Der Text fließt über die Grenzen der PDF-Seiten hinweg. Neue Bildschirmseiten beginnen,
/// wenn die aktuelle voll ist, vor Kapitelanfängen und für ganzseitige Bilder.
public final class ReflowLayouter {
    /// Wortabstand in x-Höhen (etwa die Breite eines Leerzeichens).
    public static let wordGapFactor: Float = 0.62
    /// Blocksatz nur, solange die Lücken nicht mehr als so viele Wortabstände wachsen.
    private static let maxJustify: Float = 5

    private struct Token {
        let page: Int
        let lineIndex: Int
        let line: TextLine
        let word: Word
        let lastInLine: Bool
    }

    private struct Piece {
        let token: Token
        let right: Int
        let dx: Float
    }

    private struct Segment {
        let pieces: [Piece]
        let width: Float
    }

    private struct OutLine {
        let segments: [Segment]
        let width: Float
    }

    private struct LineRef {
        let line: TextLine
        let page: Int
        let column: (Float, Float)
        let figure: Bool
        let centered: Bool
    }

    private struct ParagraphStyle {
        var indent: Float = 0
        var centered = false
        var spaceBefore: Float = 0
    }

    private let style: BookStyle
    private let frame: ReflowFrame
    private let k: Float
    private let xh: Float
    private let wordGap: Float
    private let pitchOut: Float
    private let nominalAscent: Float
    private let nominalDescent: Float
    private let leading: Float
    private let limitY: Float

    private var pages: [ScreenPage] = []
    private var current: [Placement] = []
    private var currentStartPage = -1
    private var currentStartLine = 0
    private var currentImageOnly = false
    private var cursorY: Float

    private var tokens: [Token] = []
    private var paragraph = ParagraphStyle()
    private var previous: LineRef?
    private var breakBeforeNext = false
    private var finished = false

    /// - Parameter scale: Bildschirm-Pixel pro Analyse-Pixel (bestimmt die Schriftgröße)
    public init(style: BookStyle, frame: ReflowFrame, scale: Float) {
        self.style = style
        self.frame = frame
        k = scale
        xh = style.xHeight
        wordGap = style.xHeight * ReflowLayouter.wordGapFactor * scale
        pitchOut = style.pitch * scale
        nominalAscent = style.ascent * scale
        nominalDescent = style.descent * scale
        leading = max(pitchOut - nominalAscent - nominalDescent, style.xHeight * scale * 0.15)
        limitY = Float(frame.height - frame.marginBottom)
        cursorY = Float(frame.marginTop)
    }

    /// Fertige Bildschirmseiten (die gerade gefüllte Seite fehlt noch).
    public var completedPages: [ScreenPage] { pages }

    public func add(_ index: Int, _ page: PageText) {
        precondition(!finished, "Layout already finished")
        if page.image {
            flushParagraph()
            if let figure = page.lines.first { placeImagePage(index, figure) }
            previous = nil
            breakBeforeNext = true
            return
        }
        let column = BookStyle.columnOf(page) ?? (style.columnLeft, style.columnRight)
        let lines = bodyLines(page, column)
        guard let firstIndex = lines.first, let lastIndex = lines.last else {
            // Leere Seite, oft zwischen Kapiteln.
            breakBeforeNext = true
            return
        }
        let first = page.lines[firstIndex]
        if breakBeforeNext || Float(first.top) > style.textTop + 3 * style.pitch {
            flushParagraph()
            closePage()
            previous = nil
        }
        breakBeforeNext = false

        for lineIndex in lines {
            let line = page.lines[lineIndex]
            if line.figure {
                flushParagraph()
                placeFigure(index, lineIndex, line)
                previous = LineRef(line: line, page: index, column: column, figure: true, centered: false)
                continue
            }
            if line.words.isEmpty { continue }
            let centered = isCentered(line, column)
            if let start = paragraphStart(line, index, column, centered) {
                flushParagraph()
                paragraph = start
            }
            for (i, word) in line.words.enumerated() {
                tokens.append(Token(page: index, lineIndex: lineIndex, line: line, word: word,
                                    lastInLine: i == line.words.count - 1))
            }
            previous = LineRef(line: line, page: index, column: column, figure: false, centered: centered)
        }
        if Float(page.lines[lastIndex].bottom) < style.textBottom - 3 * style.pitch { breakBeforeNext = true }
    }

    /// Schließt das Layout ab und liefert alle Bildschirmseiten.
    @discardableResult
    public func finish() -> [ScreenPage] {
        if !finished {
            flushParagraph()
            closePage()
            finished = true
        }
        return pages
    }

    // MARK: Struktur

    /// Zeilen ohne Kopf- und Fußzeilen (Seitenzahlen, lebende Kolumnentitel).
    private func bodyLines(_ page: PageText, _ column: (Float, Float)) -> [Int] {
        var indices = Array(page.lines.indices)
        let text = indices.filter { !page.lines[$0].figure }
        if text.count >= 3 {
            let colW = column.1 - column.0
            let first = page.lines[text[0]]
            let second = page.lines[text[1]]
            if Float(second.baseline - first.baseline) > 2.2 * style.pitch &&
                Float(first.width) < 0.5 * colW && Float(first.xHeight) < 0.85 * xh {
                indices.removeAll { $0 == text[0] }
            }
            let last = page.lines[text[text.count - 1]]
            let beforeLast = page.lines[text[text.count - 2]]
            if Float(last.baseline - beforeLast.baseline) > 2.0 * style.pitch &&
                Float(last.width) < 0.15 * colW && Float(last.top) > Float(page.height) * 0.8 {
                indices.removeAll { $0 == text[text.count - 1] }
            }
        }
        return indices
    }

    private func isCentered(_ line: TextLine, _ column: (Float, Float)) -> Bool {
        let colW = column.1 - column.0
        let indent = Float(line.left) - column.0
        let rightSpace = column.1 - Float(line.right)
        return indent > 1.5 * xh && abs(indent - rightSpace) < max(1.5 * xh, 0.05 * colW) &&
            Float(line.width) < 0.8 * colW
    }

    /// Beginnt mit dieser Zeile ein neuer Absatz? `nil` = Fortsetzung.
    private func paragraphStart(_ line: TextLine, _ page: Int, _ column: (Float, Float), _ centered: Bool) -> ParagraphStyle? {
        let colW = column.1 - column.0
        let indent = Float(line.left) - column.0
        // Leerzeile(n) davor, z. B. unter einer Überschrift: Abstand übernehmen.
        var space: Float = 0
        if let prev = previous, !prev.figure, prev.page == page {
            let gap = Float(line.baseline - prev.line.baseline)
            if gap > 1.6 * style.pitch { space = min((gap - style.pitch) * k, 2.5 * pitchOut) }
        }
        let start: Bool
        if let prev = previous, !prev.figure {
            if centered || prev.centered {
                start = true
            } else if space > 0 {
                start = true
            } else if prev.line.words[prev.line.words.count - 1].hyphenated {
                start = false
            } else if indent > max(0.9 * xh, 0.025 * colW) {
                start = true
            } else {
                // Hätte das erste Wort noch in die vorige Zeile gepasst, endete dort ein Absatz.
                let firstWord = Float(line.words[0].width)
                start = Float(prev.line.right) + ReflowLayouter.wordGapFactor * xh + firstWord < prev.column.1 - 0.3 * xh
            }
        } else {
            start = true
        }
        if !start { return nil }
        let indentOut = !centered && indent > 0.9 * xh ? min(indent * k, 2.5 * xh * k) : 0
        return ParagraphStyle(indent: indentOut, centered: centered, spaceBefore: space)
    }

    // MARK: Absätze

    private func flushParagraph() {
        if tokens.isEmpty {
            paragraph = ParagraphStyle()
            return
        }
        let lines = breakLines(units(tokens), paragraph)
        for (i, line) in lines.enumerated() {
            placeLine(line, paragraph, first: i == 0, last: i == lines.count - 1)
        }
        tokens.removeAll()
        paragraph = ParagraphStyle()
    }

    /// Wörter, die über einen Trennstrich am Zeilenende zusammengehören, bilden eine Einheit.
    private func units(_ tokens: [Token]) -> [[Token]] {
        var units: [[Token]] = []
        var current: [Token] = []
        for (i, t) in tokens.enumerated() {
            current.append(t)
            let joinsNext = t.lastInLine && t.word.hyphenated && i + 1 < tokens.count
            if !joinsNext {
                units.append(current)
                current = []
            }
        }
        if !current.isEmpty { units.append(current) }
        return units
    }

    /// Teil [from]...[to] einer Einheit als starres Stück. Vor einem Umbruch innerhalb der Einheit
    /// bleibt der Trennstrich stehen, sonst werden die Teile ohne Strich verbunden.
    private func segment(_ unit: [Token], _ from: Int, _ to: Int) -> Segment {
        var pieces: [Piece] = []
        var x: Float = 0
        for i in from...to {
            let t = unit[i]
            let right = i < to ? t.word.stemRight : t.word.right
            pieces.append(Piece(token: t, right: right, dx: x))
            x += Float(right - t.word.left) * k
            if i < to { x += Float(t.word.hyphenLeft - t.word.stemRight) * k }
        }
        return Segment(pieces: pieces, width: x)
    }

    private func breakLines(_ units: [[Token]], _ style: ParagraphStyle) -> [OutLine] {
        var lines: [OutLine] = []
        var segments: [Segment] = []
        var width: Float = 0
        var available = Float(frame.textWidth) - style.indent
        func emit() {
            lines.append(OutLine(segments: segments, width: width))
            segments = []
            width = 0
            available = Float(frame.textWidth)
        }
        for unit in units {
            var start = 0
            while start < unit.count {
                let gap = segments.isEmpty ? 0 : wordGap
                let whole = segment(unit, start, unit.count - 1)
                if width + gap + whole.width <= available {
                    segments.append(whole)
                    width += gap + whole.width
                    start = unit.count
                    continue
                }
                var placed = false
                if unit.count - 1 > start {
                    for m in stride(from: unit.count - 2, through: start, by: -1) {
                        let part = segment(unit, start, m)
                        if width + gap + part.width <= available {
                            segments.append(part)
                            width += gap + part.width
                            emit()
                            start = m + 1
                            placed = true
                            break
                        }
                    }
                }
                if placed { continue }
                if !segments.isEmpty {
                    emit()
                    continue
                }
                // Selbst allein zu breit: wird beim Platzieren verkleinert.
                let single = segment(unit, start, start)
                segments.append(single)
                width = single.width
                start += 1
                if start < unit.count { emit() }
            }
        }
        if !segments.isEmpty { emit() }
        return lines
    }

    private func placeLine(_ line: OutLine, _ style: ParagraphStyle, first: Bool, last: Bool) {
        let indent = first ? style.indent : 0
        let available = Float(frame.textWidth) - indent
        let shrink = line.width > available ? available / line.width : 1
        var ascent: Float = 0
        var descent: Float = 0
        for segment in line.segments {
            for piece in segment.pieces {
                let l = piece.token.line
                ascent = max(ascent, Float(l.baseline - l.top) * k * shrink)
                descent = max(descent, Float(l.bottom - l.baseline) * k * shrink)
            }
        }
        // Gleichmäßiger Zeilenabstand: Nur deutlich größere Schrift bekommt mehr Platz.
        ascent = ascent > nominalAscent * 1.35 ? ascent : nominalAscent
        descent = descent > nominalDescent * 1.6 ? descent : nominalDescent
        var y = cursorY + (first && !current.isEmpty ? style.spaceBefore : 0)
        if y + ascent + descent > limitY && !current.isEmpty {
            closePage()
            y = cursorY
        }
        let baselineY = y + ascent

        var gap = wordGap * shrink
        var x = Float(frame.marginX) + indent
        if style.centered {
            x = Float(frame.marginX) + (Float(frame.textWidth) - line.width * shrink) / 2
        } else if !last && line.segments.count > 1 {
            let extra = (available - line.width * shrink) / Float(line.segments.count - 1)
            if extra > 0 && extra < ReflowLayouter.maxJustify * wordGap { gap += extra }
        }
        let firstToken = line.segments[0].pieces[0].token
        markStart(firstToken.page, firstToken.lineIndex)
        for (i, segment) in line.segments.enumerated() {
            if i > 0 { x += gap }
            for piece in segment.pieces {
                let t = piece.token
                current.append(Placement(
                    page: t.page,
                    srcLeft: t.word.left,
                    srcTop: t.line.top,
                    srcRight: piece.right,
                    srcBottom: t.line.bottom,
                    dstX: x + piece.dx * shrink,
                    dstY: baselineY - Float(t.line.baseline - t.line.top) * k * shrink,
                    scale: k * shrink
                ))
            }
            x += segment.width * shrink
        }
        cursorY = baselineY + descent + leading
    }

    // MARK: Bilder

    private func placeFigure(_ page: Int, _ lineIndex: Int, _ line: TextLine) {
        let w = Float(max(1, line.width))
        let h = Float(max(1, line.height))
        let s = min(k, Float(frame.textWidth) / w, Float(frame.textHeight) / h)
        var y = cursorY
        if y + h * s > limitY && !current.isEmpty {
            closePage()
            y = cursorY
        }
        markStart(page, lineIndex)
        current.append(Placement(
            page: page, srcLeft: line.left, srcTop: line.top, srcRight: line.right, srcBottom: line.bottom,
            dstX: Float(frame.marginX) + (Float(frame.textWidth) - w * s) / 2,
            dstY: y, scale: s, word: false
        ))
        cursorY = y + h * s + leading
    }

    /// Ganzseitiges Bild (z. B. Cover): eigene Seite, so groß wie möglich.
    private func placeImagePage(_ page: Int, _ figure: TextLine) {
        closePage()
        let w = Float(max(1, figure.width))
        let h = Float(max(1, figure.height))
        let s = min(Float(frame.width) / w, Float(frame.height) / h)
        markStart(page, 0)
        current.append(Placement(
            page: page, srcLeft: figure.left, srcTop: figure.top, srcRight: figure.right, srcBottom: figure.bottom,
            dstX: (Float(frame.width) - w * s) / 2,
            dstY: (Float(frame.height) - h * s) / 2,
            scale: s, word: false
        ))
        currentImageOnly = true
        closePage()
    }

    // MARK: Seiten

    private func markStart(_ page: Int, _ line: Int) {
        if current.isEmpty {
            currentStartPage = page
            currentStartLine = line
        }
    }

    private func closePage() {
        if !current.isEmpty {
            pages.append(ScreenPage(placements: current, startPage: currentStartPage,
                                    startLine: currentStartLine, imageOnly: currentImageOnly))
        }
        current = []
        currentImageOnly = false
        cursorY = Float(frame.marginTop)
    }
}
