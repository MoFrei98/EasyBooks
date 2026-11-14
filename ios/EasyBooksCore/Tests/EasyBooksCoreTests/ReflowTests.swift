import XCTest
@testable import EasyBooksCore

/// Künstliche Seiten wie in den Android-Tests: "Buchstaben" sind schwarze Rechtecke
/// (x-Höhe 14 px, etwa jeder vierte mit Oberlänge), Buchstabenabstand 2 px, Wortabstand 10 px.
private struct SyntheticPage {
    let width: Int
    let height: Int
    var pixels: [UInt8]

    init(width: Int = 600, height: Int = 900) {
        self.width = width
        self.height = height
        pixels = [UInt8](repeating: 255, count: width * height * 4)
    }

    mutating func rect(_ x: Int, _ y: Int, _ w: Int, _ h: Int, _ c: (UInt8, UInt8, UInt8) = (0, 0, 0)) {
        for yy in y..<(y + h) {
            for xx in x..<(x + w) {
                let i = (yy * width + xx) * 4
                pixels[i] = c.0
                pixels[i + 1] = c.1
                pixels[i + 2] = c.2
            }
        }
    }

    @discardableResult
    mutating func word(_ x: Int, _ baseline: Int, _ letters: Int) -> Int {
        var cx = x
        for i in 0..<letters {
            let h = i % 4 == 1 ? 20 : 14
            rect(cx, baseline - h, 6, h)
            cx += 8
        }
        return cx - 2
    }

    mutating func hyphen(_ x: Int, _ baseline: Int) -> Int {
        rect(x + 2, baseline - 8, 6, 2)
        return x + 8
    }

    @discardableResult
    mutating func line(_ x: Int, _ baseline: Int, _ words: [Int], hyphenAtEnd: Bool = false) -> Int {
        var cx = x
        for (i, n) in words.enumerated() {
            if i > 0 { cx += 10 }
            cx = word(cx, baseline, n)
        }
        if hyphenAtEnd { cx = hyphen(cx, baseline) }
        return cx
    }

    func analyze() -> PageText { ReflowAnalyzer.analyze(rgba: pixels, width: width, height: height) }
}

private func prosePage(paragraphs: Int = 3, linesPerParagraph: Int = 5, hyphenLine: Int = -1) -> SyntheticPage {
    var page = SyntheticPage()
    var baseline = 60
    var lineNo = 0
    for _ in 0..<paragraphs {
        for l in 0..<linesPerParagraph {
            let last = l == linesPerParagraph - 1
            let indent = l == 0 ? 30 : 0
            var words = last ? [4, 3, 5] : [5, 3, 6, 4, 7, 3, 5, 4, 6]
            if !last && indent > 0 { words.removeLast() }
            page.line(40 + indent, baseline, words, hyphenAtEnd: lineNo == hyphenLine)
            baseline += 30
            lineNo += 1
        }
    }
    return page
}

final class ReflowAnalyzerTests: XCTestCase {
    func testFindsLinesAndWords() {
        let text = prosePage(paragraphs: 2, linesPerParagraph: 4).analyze()
        XCTAssertFalse(text.image)
        XCTAssertEqual(text.lines.count, 8)
        XCTAssertEqual(text.lines[0].words.count, 8)
        XCTAssertEqual(text.lines[0].baseline, 60)
        XCTAssertEqual(text.lines[0].xHeight, 14)
        XCTAssertEqual(text.lines[0].left, 70)
        XCTAssertEqual(text.lines[3].words.count, 3)
    }

    func testHyphenOnlyAtHyphenatedLineEnd() {
        let text = prosePage(hyphenLine: 2).analyze()
        let hyphenated = text.lines.enumerated().filter { $0.element.words.last?.hyphenated == true }.map(\.offset)
        XCTAssertEqual(hyphenated, [2])
        let word = text.lines[2].words.last!
        XCTAssertTrue(word.stemRight > word.left && word.stemRight < word.hyphenLeft)
        XCTAssertEqual(word.right, word.hyphenLeft + 6)
    }

    func testDotsStayWithTheWord() {
        var page = SyntheticPage()
        for b in [60, 90, 120, 150] {
            var x = page.word(40, b, 5)
            for _ in 0..<3 {
                page.rect(x + 4, b - 3, 3, 3)
                x += 7
            }
            page.line(x + 10, b, [4, 6, 3])
        }
        let text = page.analyze()
        XCTAssertEqual(text.lines.count, 4)
        XCTAssertEqual(text.lines[0].words.count, 4)
    }

    func testTwoColumns() {
        var page = SyntheticPage(width: 700)
        for i in 0..<8 {
            page.line(30, 60 + 30 * i, [5, 4, 6])
            page.line(400, 60 + 30 * i, [4, 6, 5])
        }
        let text = page.analyze()
        XCTAssertEqual(text.lines.count, 16)
        XCTAssertTrue(text.lines.prefix(8).allSatisfy { $0.right < 350 })
        XCTAssertTrue(text.lines.suffix(8).allSatisfy { $0.left >= 400 })
    }

    func testCenteredTitlePageIsOneColumn() {
        var page = SyntheticPage()
        for (i, words) in [[9, 12], [5, 7, 4, 6, 3], [8], [3, 10, 4, 9]].enumerated() {
            let width = words.reduce(0, +) * 8 - 2 + (words.count - 1) * 10
            page.line((600 - width) / 2, 150 + 140 * i, words)
        }
        let text = page.analyze()
        XCTAssertEqual(text.lines.map(\.words.count), [2, 5, 1, 4])
    }

    func testImagePageAndFigure() {
        var cover = SyntheticPage()
        cover.rect(50, 50, 500, 800, (0x20, 0x30, 0x40))
        let c = cover.analyze()
        XCTAssertTrue(c.image)
        XCTAssertEqual(c.lines.count, 1)

        var page = prosePage(paragraphs: 1, linesPerParagraph: 4)
        page.rect(100, 250, 300, 200, (0x80, 0x80, 0x80))
        let text = page.analyze()
        XCTAssertEqual(text.lines.filter(\.figure).count, 1)
        XCTAssertEqual(text.lines.filter { !$0.figure }.count, 4)
    }
}

final class ReflowLayouterTests: XCTestCase {
    private let pages = (0..<3).map { prosePage(hyphenLine: $0 == 0 ? 2 : -1).analyze() }
    private lazy var style = BookStyle.from(pages)
    private let frame = ReflowFrame(width: 400, height: 700, marginX: 20, marginTop: 20, marginBottom: 20)

    private func layout(_ scale: Float) -> [ScreenPage] {
        let layouter = ReflowLayouter(style: style, frame: frame, scale: scale)
        for (i, p) in pages.enumerated() { layouter.add(i, p) }
        return layouter.finish()
    }

    func testStyle() {
        XCTAssertEqual(style.xHeight, 14)
        XCTAssertEqual(style.pitch, 30)
        XCTAssertEqual(style.columnLeft, 40)
    }

    func testEveryWordOnceInOrder() {
        let placed = layout(2).flatMap(\.placements).map { [$0.page, $0.srcTop, $0.srcLeft] }
        let expected = pages.enumerated().flatMap { p, text in
            text.lines.flatMap { l in l.words.map { [p, l.top, $0.left] } }
        }
        XCTAssertEqual(placed, expected)
    }

    func testPlacementsStayInsideTheTextArea() {
        for scale: Float in [1, 2, 3] {
            for page in layout(scale) {
                for p in page.placements {
                    XCTAssertGreaterThanOrEqual(p.dstX, Float(frame.marginX) - 0.5)
                    XCTAssertLessThanOrEqual(p.dstX + p.dstWidth, Float(frame.width - frame.marginX) + 0.5)
                    XCTAssertGreaterThanOrEqual(p.dstY, 0)
                    XCTAssertLessThanOrEqual(p.dstY + p.dstHeight, Float(frame.height - frame.marginBottom) + 0.5)
                }
            }
        }
    }

    func testBiggerTextNeedsMorePages() {
        XCTAssertLessThan(layout(1).count, layout(2.5).count)
    }

    func testHyphenatedWordIsJoinedOrKeepsHyphenAtLineEnd() {
        let all = layout(1).flatMap(\.placements)
        let line = pages[0].lines[2]
        let word = line.words.last!
        let piece = all.first { $0.page == 0 && $0.srcTop == line.top && $0.srcLeft == word.left }!
        XCTAssertTrue(piece.srcRight == word.stemRight || piece.srcRight == word.right)
        if piece.srcRight == word.stemRight {
            let next = all.first { $0.page == 0 && $0.srcTop == pages[0].lines[3].top }!
            XCTAssertEqual(next.dstX - (piece.dstX + piece.dstWidth), Float(word.hyphenLeft - word.stemRight), accuracy: 0.5)
        }
    }

    func testParagraphsStartOnNewIndentedLine() {
        let all = layout(1).flatMap(\.placements)
        let first = pages[0].lines[5]
        let index = all.firstIndex { $0.page == 0 && $0.srcTop == first.top && $0.srcLeft == first.left }!
        XCTAssertGreaterThan(all[index].dstX, Float(frame.marginX) + 5)
        XCTAssertLessThan(all[index - 1].dstY, all[index].dstY)
    }

    func testReadingPositionRoundTrip() {
        let result = layout(2)
        XCTAssertGreaterThan(result.count, 3)
        let lineCount = { (page: Int) in self.pages[page].lines.count }
        for (i, page) in result.enumerated() {
            XCTAssertEqual(result.index(of: page.position(lineCount: lineCount(page.startPage)), lineCount: lineCount, complete: true), i)
        }
        XCTAssertNil(Array(result.prefix(2)).index(of: ReadingPosition(page: 2, fraction: 0.5), lineCount: lineCount, complete: false))
    }

    func testImagePageGetsOwnScreenPage() {
        var cover = SyntheticPage()
        cover.rect(0, 0, 600, 900, (0x20, 0x30, 0x40))
        let layouter = ReflowLayouter(style: style, frame: frame, scale: 2)
        layouter.add(0, cover.analyze())
        layouter.add(1, pages[0])
        let result = layouter.finish()
        XCTAssertTrue(result[0].imageOnly)
        XCTAssertEqual(result[0].placements.count, 1)
        XCTAssertEqual(result[1].startPage, 1)
    }

    func testScaleNeverBelowFullWidth() {
        XCTAssertEqual(reflowScale(style: style, frame: frame, targetXHeight: 1), 0.98 * Float(frame.textWidth) / style.columnWidth, accuracy: 0.001)
        XCTAssertEqual(reflowScale(style: style, frame: frame, targetXHeight: 30), 30 / 14, accuracy: 0.001)
    }

    func testCacheRoundTrip() {
        let texts: [PageText?] = [prosePage(hyphenLine: 1).analyze(), nil]
        let analysis = ReflowAnalysis(pageCount: 2, fileSize: 1234, scale: 2.5, style: BookStyle.from(texts.compactMap { $0 }), pages: texts)
        let data = ReflowCache.encode(analysis)
        XCTAssertEqual(ReflowCache.decode(data, pageCount: 2, fileSize: 1234), analysis)
        XCTAssertNil(ReflowCache.decode(data, pageCount: 3, fileSize: 1234))
        XCTAssertNil(ReflowCache.decode(Data([1, 2, 3])))
    }
}
