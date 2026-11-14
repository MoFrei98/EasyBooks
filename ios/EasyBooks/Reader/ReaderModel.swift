import EasyBooksCore
import Foundation
import PDFKit
import UIKit

/// Bildschirmgröße und gewünschte Schriftgröße für "Groß" (in Pixeln).
struct ReflowParams: Equatable, Hashable {
    let frame: ReflowFrame
    let targetXHeight: Float
}

/// Neu umbrochene Seiten für "Groß". Während die Analyse läuft, kommen am Ende Seiten dazu.
struct ReflowState {
    let params: ReflowParams
    let pages: [ScreenPage]
    let complete: Bool
    let analyzed: Int
    let total: Int

    var estimatedCount: Int {
        if complete || analyzed <= 0 { return pages.count }
        return max(pages.count, pages.count * total / analyzed)
    }
}

/// Ein gerendertes Blatt für die Anzeige.
struct PageVisual {
    let image: UIImage
    /// Größe des Blatts in Punkten.
    let sheetSize: CGSize
    /// Höher als der Bildschirm: wird senkrecht gescrollt.
    let scrollable: Bool
    /// Anteil schon gelesener Seiten (für die Blattkanten).
    let readFraction: CGFloat
}

private final class VisualBox {
    let visual: PageVisual
    init(_ visual: PageVisual) { self.visual = visual }
}

/// Schritte der Hintergrund-Analyse für "Groß".
private enum AnalysisEvent {
    case style(BookStyle, Float)
    case page(Int, PageText)
}

@MainActor
final class ReaderModel: ObservableObject {
    enum State {
        case loading
        case ready
        case failed(String)
    }

    @Published private(set) var state: State = .loading
    @Published private(set) var reflow: ReflowState?
    @Published private(set) var reflowProgress: Double = 0

    let bookID: String
    let title: String
    private let url: URL
    private let cacheURL: URL
    private let fileSize: Int64
    private let onPosition: @MainActor (ReadingPosition) -> Void

    private(set) var renderer: PDFRenderer?
    private(set) var pageCount = 0
    /// Aktuelle Lesestelle, unabhängig von der Schriftgröße.
    private(set) var position: ReadingPosition

    private var analyses: [Int: PageAnalysis] = [:]
    private var typicalTask: Task<TypicalBounds, Never>?
    private let visuals = NSCache<NSString, VisualBox>()

    // Groß
    private var reflowParams: ReflowParams?
    private var reflowTexts: [PageText?] = []
    private var reflowStyle: BookStyle?
    private var analyzed = -1
    private var layouter: ReflowLayouter?
    private var layoutParams: ReflowParams?
    private var layoutFinished = false
    private var fed = 0
    private var publishedAt = 0
    private var analysisTask: Task<Void, Never>?

    init(book: Book, url: URL, cacheURL: URL, onPosition: @escaping @MainActor (ReadingPosition) -> Void) {
        bookID = book.uuid
        title = book.title
        self.url = url
        self.cacheURL = cacheURL
        fileSize = book.fileSize
        self.onPosition = onPosition
        position = ReadingPosition(page: max(0, book.lastPage), fraction: min(1, max(0, book.lastPageOffset)))
        visuals.countLimit = 10
    }

    func load() async {
        guard case .loading = state else { return }
        let url = self.url
        let renderer = await Task.detached(priority: .userInitiated) { PDFRenderer(url: url) }.value
        guard let renderer else {
            state = .failed(FileManager.default.fileExists(atPath: url.path)
                ? "Die PDF konnte nicht geöffnet werden. Ist sie beschädigt oder passwortgeschützt?"
                : "Die Datei ist nicht mehr erreichbar. Wurde sie verschoben oder gelöscht?")
            return
        }
        if renderer.pageCount == 0 {
            state = .failed("Die PDF enthält keine Seiten.")
            return
        }
        self.renderer = renderer
        pageCount = renderer.pageCount
        reflowTexts = Array(repeating: nil, count: pageCount)
        position.page = min(position.page, pageCount - 1)
        if let demo = UserDefaults.standard.string(forKey: "demoPage").flatMap(Int.init) {
            position = ReadingPosition(page: min(max(0, demo), pageCount - 1))
        }
        state = .ready
    }

    /// Beendet die Hintergrund-Analyse (Reader wird geschlossen).
    func close() {
        analysisTask?.cancel()
        analysisTask = nil
    }

    // MARK: Lesestelle

    func settled(pdfPage: Int) {
        // Innerhalb derselben Seite die genauere Stelle aus "Groß" behalten.
        if pdfPage != position.page { save(ReadingPosition(page: pdfPage)) }
        prefetch(around: pdfPage)
    }

    private func save(_ pos: ReadingPosition) {
        position = pos
        onPosition(pos)
    }

    // MARK: Klein und Normal

    /// Blatt für PDF-Seite [index] bei Klein oder Normal.
    func pageVisual(_ index: Int, textSize: TextSize, available: CGSize, scale: CGFloat, dark: Bool) async -> PageVisual? {
        guard let renderer else { return nil }
        let key = "p\(index)-\(textSize.rawValue)-\(Int(available.width))x\(Int(available.height))-\(dark)" as NSString
        if let cached = visuals.object(forKey: key) { return cached.visual }

        let crop = textSize == .small ? Crop.full : await tightCrop(index)
        let pageSize = await renderer.pageSize(index)
        let cropW = pageSize.width * CGFloat(crop.width)
        let cropH = pageSize.height * CGFloat(crop.height)
        guard cropW > 0, cropH > 0 else { return nil }
        let aspect = cropH / cropW
        let hPad: CGFloat = textSize == .small ? 12 : 2
        let vPad: CGFloat = 16
        let availableWidth = available.width - 2 * hPad
        let availableHeight = available.height - 2 * vPad
        let width = textSize == .small ? min(availableWidth, availableHeight / aspect) : availableWidth
        let height = width * aspect
        var pxPerPoint = scale * width / cropW
        let pixels = cropW * cropH * pxPerPoint * pxPerPoint
        if pixels > 6_000_000 { pxPerPoint *= sqrt(6_000_000 / pixels) }
        let area = CGRect(x: pageSize.width * CGFloat(crop.left), y: pageSize.height * CGFloat(crop.top), width: cropW, height: cropH)
        var imagePage = false
        if dark { imagePage = await analysis(index).isImage }
        let matrix = colorMatrix(dark: dark, imagePage: imagePage)

        let cgImage: CGImage? = await renderer.run { doc in
            guard let page = doc.page(at: index), let rendered = PDFRenderer.render(page, scale: pxPerPoint, area: area) else { return nil }
            let bitmap = Bitmap(width: rendered.width, height: rendered.height)
            bitmap.draw { $0.draw(rendered, in: CGRect(x: 0, y: 0, width: rendered.width, height: rendered.height)) }
            bitmap.apply(matrix)
            return bitmap.makeImage()
        }
        guard let cgImage else { return nil }
        let visual = PageVisual(
            image: UIImage(cgImage: cgImage, scale: scale, orientation: .up),
            sheetSize: CGSize(width: width, height: height),
            scrollable: height > availableHeight + 1,
            readFraction: pageCount > 1 ? CGFloat(index) / CGFloat(pageCount - 1) : 0
        )
        visuals.setObject(VisualBox(visual), forKey: key)
        return visual
    }

    private func colorMatrix(dark: Bool, imagePage: Bool) -> [Float] {
        if !dark { return Bitmap.Colors.day }
        if imagePage { return Bitmap.Colors.dimmedImage }
        return PageColors.nightMatrix(paper: ReaderPalette.dark.paper.rgb, ink: ReaderPalette.dark.ink.rgb)
    }

    private func analysis(_ index: Int) async -> PageAnalysis {
        if let a = analyses[index] { return a }
        guard let renderer else { return PageAnalysis(bounds: nil, luminance: 1) }
        let a = await renderer.analyze(index)
        analyses[index] = a
        return a
    }

    /// Ausschnitt ohne Ränder (für "Normal").
    private func tightCrop(_ index: Int) async -> Crop {
        let typical = await typicalBounds()
        let bounds = await analysis(index).bounds
        return CropMath.cropFor(page: bounds, typical: typical.forPage(index), padding: CropMath.paddingTight)
    }

    /// Typische Ränder aus einer Stichprobe über das ganze Dokument (ohne Cover).
    private func typicalBounds() async -> TypicalBounds {
        if let task = typicalTask { return await task.value }
        let count = pageCount
        let task = Task { () -> TypicalBounds in
            let first = count > 2 ? 1 : 0
            let available = count - first
            let samples = min(16, available)
            var result: [(Int, Crop)] = []
            var seen = Set<Int>()
            for i in 0..<samples {
                let page = first + available * i / samples
                if !seen.insert(page).inserted { continue }
                if let b = await analysis(page).bounds { result.append((page, b)) }
            }
            return TypicalBounds.from(result)
        }
        typicalTask = task
        return await task.value
    }

    private var prefetchRequest: (Int) -> Void = { _ in }

    /// Merkt sich, wie Nachbarseiten vorab gerendert werden (die Ansicht kennt die Bildschirmgröße).
    func setPrefetch(_ request: @escaping (Int) -> Void) { prefetchRequest = request }

    private func prefetch(around index: Int) {
        for i in [index + 1, index - 1] where i >= 0 { prefetchRequest(i) }
    }

    // MARK: Groß

    func startReflow(_ params: ReflowParams) {
        reflowParams = params
        updateLayout(force: true)
        guard analysisTask == nil, let renderer else { return }
        let cacheURL = self.cacheURL
        let fileSize = self.fileSize
        let count = pageCount
        analysisTask = Task.detached(priority: .utility) { [weak self] in
            await ReaderModel.runAnalysis(renderer: renderer, cacheURL: cacheURL, fileSize: fileSize, count: count) { event in
                await self?.receive(event)
            }
        }
    }

    private func receive(_ event: AnalysisEvent) {
        switch event {
        case let .style(style, _):
            reflowStyle = style
            analyzed = 0
        case let .page(index, text):
            reflowTexts[index] = text
            analyzed = index + 1
        }
        updateLayout(force: false)
    }

    private func updateLayout(force: Bool) {
        guard let params = reflowParams, let style = reflowStyle, analyzed >= 0 else { return }
        if layouter == nil || layoutParams != params {
            layouter = ReflowLayouter(style: style, frame: params.frame,
                                      scale: reflowScale(style: style, frame: params.frame, targetXHeight: params.targetXHeight))
            layoutParams = params
            layoutFinished = false
            fed = 0
        }
        guard let layouter else { return }
        while fed < analyzed {
            if let text = reflowTexts[fed] { layouter.add(fed, text) }
            fed += 1
        }
        let complete = analyzed >= pageCount
        reflowProgress = Double(analyzed) / Double(max(1, pageCount))
        // Nicht nach jeder Seite neu anzeigen, das kostet unnötig Zeit.
        if !force && !complete && reflow?.params == params && analyzed - publishedAt < 8 { return }
        if complete && !layoutFinished {
            layouter.finish()
            layoutFinished = true
        }
        publishedAt = analyzed
        reflow = ReflowState(params: params, pages: layouter.completedPages, complete: complete,
                             analyzed: analyzed, total: pageCount)
    }

    private func lineCount(_ page: Int) -> Int {
        page < reflowTexts.count ? reflowTexts[page]?.lines.count ?? 0 : 0
    }

    /// Bildschirmseite der Lesestelle oder `nil`, solange sie noch nicht gesetzt ist.
    func reflowIndex(_ state: ReflowState) -> Int? {
        state.pages.index(of: position, lineCount: lineCount, complete: state.complete)
    }

    func settled(reflowIndex index: Int, in state: ReflowState) {
        guard index < state.pages.count else { return }
        let page = state.pages[index]
        save(page.position(lineCount: lineCount(page.startPage)))
    }

    /// Setzt eine Bildschirmseite für "Groß" zusammen.
    func reflowVisual(_ state: ReflowState, _ index: Int, scale: CGFloat, dark: Bool) async -> PageVisual? {
        guard let renderer, index < state.pages.count else { return nil }
        let key = "r\(state.params.hashValue)-\(index)-\(dark)" as NSString
        if let cached = visuals.object(forKey: key) { return cached.visual }
        let page = state.pages[index]
        let frame = state.params.frame
        let texts = reflowTexts
        let matrix = colorMatrix(dark: dark, imagePage: page.imageOnly)
        let image: Bitmap? = await renderer.run { doc in ReaderModel.compose(doc, page, frame, texts) }
        guard let image else { return nil }
        let cgImage: CGImage? = await Task.detached(priority: .userInitiated) {
            image.apply(matrix)
            return image.makeImage()
        }.value
        guard let cgImage else { return nil }
        let count = state.estimatedCount
        let visual = PageVisual(
            image: UIImage(cgImage: cgImage, scale: scale, orientation: .up),
            sheetSize: CGSize(width: CGFloat(image.width) / scale, height: CGFloat(image.height) / scale),
            scrollable: false,
            readFraction: count > 1 ? min(1, CGFloat(index) / CGFloat(count - 1)) : 0
        )
        visuals.setObject(VisualBox(visual), forKey: key)
        return visual
    }

    // MARK: Hintergrund

    /// Analysiert das Buch Seite für Seite von vorn und speichert das Ergebnis.
    nonisolated private static func runAnalysis(
        renderer: PDFRenderer, cacheURL: URL, fileSize: Int64, count: Int,
        send: @escaping (AnalysisEvent) async -> Void
    ) async {
        var texts = [PageText?](repeating: nil, count: count)
        var style: BookStyle
        var scale: Float
        var fromCache = false
        if let data = try? Data(contentsOf: cacheURL),
           let raw = try? (data as NSData).decompressed(using: .zlib) as Data,
           let cached = ReflowCache.decode(raw, pageCount: count, fileSize: fileSize) {
            texts = cached.pages
            style = cached.style
            scale = cached.scale
            fromCache = true
        } else {
            // Probe-Seite: Wie groß ist die Schrift?
            var xHeight: Float = 0
            for probe in Array(Set([count / 2, count / 3, count * 2 / 3, 0])).sorted(by: >) {
                let text = await analyze(renderer, probe, 2)
                let heights = text.textLines.filter { $0.words.count >= 3 }.map { Float($0.xHeight) }.sorted()
                if heights.count >= 3 {
                    xHeight = heights[heights.count / 2]
                    break
                }
            }
            scale = xHeight > 0 ? min(4, max(1.2, 2 * 12 / xHeight)) : 2
            let samples = min(12, count)
            let first = count > 4 ? 1 : 0
            var sampled: [Int] = []
            for i in 0..<samples {
                let p = first + (count - first) * i / samples
                if !sampled.contains(p) { sampled.append(p) }
            }
            for p in sampled {
                if Task.isCancelled { return }
                texts[p] = await analyze(renderer, p, scale)
            }
            let pages = sampled.compactMap { texts[$0] }
            let full = pages.filter { $0.textLines.count >= 5 }
            style = BookStyle.from(full.isEmpty ? pages : full)
        }
        await send(.style(style, scale))

        var fresh = 0
        for i in 0..<count {
            if Task.isCancelled { break }
            if texts[i] == nil {
                texts[i] = await analyze(renderer, i, scale)
                fresh += 1
                if fresh % 25 == 0 { save(texts, style, scale, count, fileSize, cacheURL) }
            }
            if let text = texts[i] { await send(.page(i, text)) }
        }
        if fresh > 0 || !fromCache { save(texts, style, scale, count, fileSize, cacheURL) }
    }

    nonisolated private static func analyze(_ renderer: PDFRenderer, _ index: Int, _ scale: Float) async -> PageText {
        let pixels: PDFRenderer.Pixels? = await renderer.run { doc in
            guard let page = doc.page(at: index) else { return nil }
            let size = PDFRenderer.displaySize(page)
            let area = max(1, size.width * size.height)
            let s = min(CGFloat(scale), sqrt(4_500_000 / area))
            return PDFRenderer.renderPixels(page, scale: s)
        }
        guard let pixels else { return PageText(width: 1, height: 1, lines: []) }
        return ReflowAnalyzer.analyze(rgba: pixels.data, width: pixels.width, height: pixels.height)
    }

    nonisolated private static func save(_ texts: [PageText?], _ style: BookStyle, _ scale: Float, _ count: Int,
                                         _ fileSize: Int64, _ url: URL) {
        let analysis = ReflowAnalysis(pageCount: count, fileSize: fileSize, scale: scale, style: style, pages: texts)
        let data = ReflowCache.encode(analysis)
        if let compressed = try? (data as NSData).compressed(using: .zlib) {
            try? (compressed as Data).write(to: url, options: .atomic)
        }
    }

    /// Setzt eine Bildschirmseite aus Ausschnitten der PDF zusammen. Wörter werden nur abgedunkelt
    /// eingezeichnet, damit ihr weißer Rand nie die Schrift eines Nachbarn überdeckt.
    nonisolated private static func compose(_ doc: PDFDocument, _ page: ScreenPage, _ frame: ReflowFrame, _ texts: [PageText?]) -> Bitmap? {
        // Ganzseitige Bilder bekommen ein Blatt in ihrer eigenen Größe.
        let image = page.imageOnly && page.placements.count == 1 ? page.placements[0] : nil
        let width = image.map { max(1, Int($0.dstWidth.rounded())) } ?? frame.width
        let height = image.map { max(1, Int($0.dstHeight.rounded())) } ?? frame.height
        let offsetX = CGFloat(image?.dstX ?? 0)
        let offsetY = CGFloat(image?.dstY ?? 0)
        let bitmap = Bitmap(width: width, height: height)
        bitmap.draw { ctx in
            for band in bands(page.placements) {
                let first = band[0]
                guard let pdfPage = doc.page(at: first.page), first.page < texts.count, let text = texts[first.page] else { continue }
                let size = PDFRenderer.displaySize(pdfPage)
                // Analyse-Pixel pro Punkt dieser Seite.
                let pageScale = CGFloat(text.width) / max(1, size.width)
                let s = CGFloat(first.scale)
                let left = band.map(\.srcLeft).min()!
                let top = band.map(\.srcTop).min()!
                let right = band.map(\.srcRight).max()!
                let bottom = band.map(\.srcBottom).max()!
                let area = CGRect(x: CGFloat(left) / pageScale, y: CGFloat(top) / pageScale,
                                  width: CGFloat(right - left) / pageScale, height: CGFloat(bottom - top) / pageScale)
                guard let rendered = PDFRenderer.render(pdfPage, scale: s * pageScale, area: area) else { continue }
                for p in band {
                    let src = CGRect(
                        x: (CGFloat(p.srcLeft - left) * s).rounded(), y: (CGFloat(p.srcTop - top) * s).rounded(),
                        width: (CGFloat(p.srcRight - p.srcLeft) * s).rounded(), height: (CGFloat(p.srcBottom - p.srcTop) * s).rounded()
                    ).intersection(CGRect(x: 0, y: 0, width: rendered.width, height: rendered.height))
                    guard !src.isEmpty, let piece = rendered.cropping(to: src) else { continue }
                    let x = CGFloat(p.dstX) - offsetX
                    let yTop = CGFloat(p.dstY) - offsetY
                    // CoreGraphics zählt von unten.
                    let dst = CGRect(x: x, y: CGFloat(height) - yTop - src.height, width: src.width, height: src.height)
                    ctx.setBlendMode(p.word ? .darken : .normal)
                    ctx.draw(piece, in: dst)
                }
            }
        }
        return bitmap
    }

    /// Stücke, die mit einem Aufruf gerendert werden: gleiche Seite, gleicher Maßstab, senkrecht nah beieinander.
    nonisolated private static func bands(_ placements: [Placement]) -> [[Placement]] {
        var result: [[Placement]] = []
        var groups: [String: [Placement]] = [:]
        var order: [String] = []
        for p in placements {
            let key = "\(p.page)-\(p.scale)"
            if groups[key] == nil { order.append(key) }
            groups[key, default: []].append(p)
        }
        for key in order {
            let sorted = groups[key]!.sorted { $0.srcTop < $1.srcTop }
            var current: [Placement] = []
            var top = 0
            var bottom = Int.min
            for p in sorted {
                let gapTooBig = !current.isEmpty && p.srcTop - bottom > max(40, (bottom - top) / 4)
                let tooTall = !current.isEmpty && Float(p.srcBottom - top) * p.scale > 1600
                if gapTooBig || tooTall {
                    result.append(current)
                    current = []
                }
                if current.isEmpty {
                    top = p.srcTop
                    bottom = p.srcBottom
                }
                current.append(p)
                bottom = max(bottom, p.srcBottom)
            }
            if !current.isEmpty { result.append(current) }
        }
        return result
    }
}
