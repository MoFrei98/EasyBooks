import XCTest
@testable import EasyBooksCore

/// Testfälle aus `shared/fixtures` (dieselben prüft die Android-App).
enum Fixtures {
    static var directory: URL {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { url.deleteLastPathComponent() }
        return url.appendingPathComponent("shared/fixtures")
    }

    static func rows(_ name: String) throws -> [[String]] {
        let text = try String(contentsOf: directory.appendingPathComponent(name), encoding: .utf8)
        return text.split(separator: "\n", omittingEmptySubsequences: true)
            .map { String($0).trimmingCharacters(in: CharacterSet(charactersIn: "\r")) }
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }
            .map { $0.split(separator: "\t", omittingEmptySubsequences: false).map(String.init) }
    }

    static func value(_ s: String) -> String? { s == "-" ? nil : s }
}

final class MetadataTests: XCTestCase {

    func testSharedFileNames() throws {
        let rows = try Fixtures.rows("metadata-filenames.tsv")
        XCTAssertFalse(rows.isEmpty)
        for r in rows {
            let expected = BookMetadata(title: Fixtures.value(r[1]), firstName: Fixtures.value(r[2]),
                                        lastName: Fixtures.value(r[3]), year: Fixtures.value(r[4]).flatMap { Int($0) })
            XCTAssertEqual(BookMetadataParser.fromFileName(r[0]), expected, r[0])
        }
    }

    func testSharedAuthors() throws {
        let rows = try Fixtures.rows("metadata-authors.tsv")
        XCTAssertFalse(rows.isEmpty)
        for r in rows {
            let (first, last) = BookMetadataParser.splitAuthor(r[0])
            XCTAssertEqual(first, Fixtures.value(r[1]), r[0])
            XCTAssertEqual(last, Fixtures.value(r[2]), r[0])
        }
    }

    func testInfoDictionaryWithLiteralStrings() {
        let pdf = "1 0 obj << /Title (Aufstieg des Horus) /Author (Dan Abnett) /Producer (x) >> endobj"
        let m = BookMetadataParser.fromPdf([Data(pdf.utf8)])
        XCTAssertEqual(m, BookMetadata(title: "Aufstieg des Horus", firstName: "Dan", lastName: "Abnett"))
    }

    func testEscapesAndUtf16Hex() {
        // "Märchen (neu)" als Literal mit Oktal-Escape für ä (Latin-1 0xE4) und Klammern
        XCTAssertEqual(BookMetadataParser.pdfString(#"/Title (M\344rchen \(neu\))"#, key: "Title"), "Märchen (neu)")
        // "Jörg" als UTF-16BE mit BOM
        XCTAssertEqual(BookMetadataParser.pdfString("/Author <FEFF004A00F60072 0067>", key: "Author"), "Jörg")
    }

    func testJunkTitlesAreIgnored() {
        let pdf = "<< /Title (Microsoft Word - entwurf.docx) /Author (Unknown) >>"
        XCTAssertEqual(BookMetadataParser.fromPdf([Data(pdf.utf8)]), BookMetadata())
    }

    func testXmpWinsAndIsUtf8() {
        let xmp = """
        <x:xmpmeta><rdf:RDF><rdf:Description>
          <dc:title><rdf:Alt><rdf:li xml:lang="x-default">Die Straße</rdf:li></rdf:Alt></dc:title>
          <dc:creator><rdf:Seq><rdf:li>Jörg M&#252;ller</rdf:li></rdf:Seq></dc:creator>
          <prism:publicationDate>2019-03-01</prism:publicationDate>
        </rdf:Description></rdf:RDF></x:xmpmeta>
        << /Title (Anderer Titel) >>
        """
        let m = BookMetadataParser.fromPdf([Data(xmp.utf8)])
        XCTAssertEqual(m, BookMetadata(title: "Die Straße", firstName: "Jörg", lastName: "Müller", year: 2019))
    }

    func testWordExportFallsBackToFileName() {
        let pdf = "<< /Title (Microsoft Word - Warhammer _40K_ - Dan Abnett - DGB 01 - Aufstieg des Horus) /Author (jo) >>"
        let fromPdf = BookMetadataParser.fromPdf([Data(pdf.utf8)])
        XCTAssertEqual(fromPdf, BookMetadata())
        let fromName = BookMetadataParser.fromFileName("[Abnett_Dan]_Aufstieg_des_Horus(libcats.org).pdf")
        XCTAssertEqual(fromPdf.orElse(fromName), BookMetadata(title: "Aufstieg des Horus", firstName: "Dan", lastName: "Abnett"))
    }

    func testAttributesFromPdfKitUseSameFilters() {
        XCTAssertEqual(BookMetadataParser.fromAttributes(title: "Microsoft Word - x.docx", author: "jo"), BookMetadata())
        XCTAssertEqual(BookMetadataParser.fromAttributes(title: " Nordlicht ", author: "Lea Sommer"),
                       BookMetadata(title: "Nordlicht", firstName: "Lea", lastName: "Sommer"))
    }

    func testSharedSearch() throws {
        let rows = try Fixtures.rows("search.tsv")
        XCTAssertFalse(rows.isEmpty)
        for r in rows {
            XCTAssertEqual(SearchText.matches(r[0], Array(r.dropFirst(2))), r[1] == "true", r.joined(separator: " | "))
        }
    }

    func testSearchNormalization() {
        XCTAssertEqual(SearchText.normalize("Café_Straße-Märchen"), "cafe strasse maerchen")
        XCTAssertTrue(SearchText.matches("  ", ["x"]))
    }
}

final class FolderPathsTests: XCTestCase {
    func testParentNameChild() {
        XCTAssertEqual(FolderPaths.parent("Warhammer"), "")
        XCTAssertEqual(FolderPaths.parent("Warhammer/Horus Heresy"), "Warhammer")
        XCTAssertEqual(FolderPaths.name("Warhammer/Horus Heresy"), "Horus Heresy")
        XCTAssertEqual(FolderPaths.child("", "Krimis"), "Krimis")
        XCTAssertEqual(FolderPaths.child("Krimis", "Neu"), "Krimis/Neu")
    }

    func testDepthAndInside() {
        XCTAssertEqual(FolderPaths.depth(""), 0)
        XCTAssertEqual(FolderPaths.depth("A/B/C"), 3)
        XCTAssertTrue(FolderPaths.isInside("A/B", "A"))
        XCTAssertTrue(FolderPaths.isInside("A", ""))
        XCTAssertFalse(FolderPaths.isInside("AB", "A"))
        XCTAssertFalse(FolderPaths.isInside("A", "A/B"))
    }

    func testSubfoldersAndTreeOrder() {
        XCTAssertEqual(FolderPaths.subfolders(["b", "A", "A/x", "A/y/z", "c"], of: ""), ["A", "b", "c"])
        XCTAssertEqual(FolderPaths.treeOrder(["Zeta", "Alpha/Beta", "Alpha", "Alpha-2", ""]),
                       ["Alpha", "Alpha/Beta", "Alpha-2", "Zeta"])
    }

    func testCleanName() {
        XCTAssertEqual(FolderPaths.cleanName(" Sci/Fi "), "Sci_Fi")
        XCTAssertEqual(FolderPaths.cleanName(" .. "), "")
        XCTAssertEqual(FolderPaths.cleanName("Krimis"), "Krimis")
    }
}

final class CropMathTests: XCTestCase {
    /// Weiße Seite (RGBA) mit einem farbigen Rechteck von (x0,y0) bis exklusive (x1,y1).
    private func page(_ w: Int, _ h: Int, _ x0: Int, _ y0: Int, _ x1: Int, _ y1: Int,
                      color: (UInt8, UInt8, UInt8) = (0, 0, 0), background: (UInt8, UInt8, UInt8) = (255, 255, 255)) -> [UInt8] {
        var px = [UInt8](repeating: 255, count: w * h * 4)
        for y in 0..<h {
            for x in 0..<w {
                let c = (x0..<x1).contains(x) && (y0..<y1).contains(y) ? color : background
                let i = (y * w + x) * 4
                px[i] = c.0
                px[i + 1] = c.1
                px[i + 2] = c.2
            }
        }
        return px
    }

    func testFindsContentRectangle() {
        let a = CropMath.analyze(rgba: page(100, 200, 10, 20, 90, 180), width: 100, height: 200)
        XCTAssertEqual(a.bounds, Crop(0.1, 0.1, 0.9, 0.9))
    }

    func testBlankAndLightGreyHaveNoBounds() {
        XCTAssertNil(CropMath.analyze(rgba: page(50, 50, 0, 0, 0, 0), width: 50, height: 50).bounds)
        let grey = page(10, 10, 0, 0, 0, 0, background: (0xF0, 0xF0, 0xF0))
        XCTAssertNil(CropMath.analyze(rgba: grey, width: 10, height: 10).bounds)
    }

    func testImageDetection() {
        XCTAssertFalse(CropMath.analyze(rgba: page(100, 200, 10, 20, 90, 30), width: 100, height: 200).isImage)
        XCTAssertTrue(CropMath.analyze(rgba: page(100, 200, 0, 0, 100, 160), width: 100, height: 200).isImage)
        let scanned = page(100, 100, 0, 0, 0, 0, background: (0xE8, 0xDF, 0xC8))
        XCTAssertFalse(CropMath.analyze(rgba: scanned, width: 100, height: 100).isImage)
    }

    func testCropRules() {
        let typical = Crop(0.15, 0.1, 0.85, 0.9)
        let wideTable = Crop(0.05, 0.1, 0.95, 0.9)
        let crop = CropMath.cropFor(page: wideTable, typical: typical)
        XCTAssertLessThanOrEqual(crop.left, wideTable.left)
        XCTAssertGreaterThanOrEqual(crop.right, wideTable.right)
        let heading = Crop(0.4, 0.4, 0.6, 0.45)
        XCTAssertEqual(CropMath.cropFor(page: heading, typical: typical), CropMath.cropFor(page: typical, typical: typical))
        XCTAssertEqual(CropMath.cropFor(page: Crop(0.005, 0, 1, 0.995), typical: nil), .full)
        XCTAssertEqual(CropMath.cropFor(page: nil, typical: nil), .full)
        let padded = CropMath.cropFor(page: Crop(0.01, 0.2, 0.7, 0.8), typical: nil)
        XCTAssertEqual(padded.left, 0, accuracy: 0.0001)
        XCTAssertEqual(padded.right, 0.72, accuracy: 0.0001)
        let content = Crop(0.1, 0.1, 0.9, 0.9)
        let tight = CropMath.cropFor(page: content, typical: content, padding: CropMath.paddingTight)
        XCTAssertLessThan(tight.width, CropMath.cropFor(page: content, typical: content).width)
        XCTAssertTrue(tight.left < content.left && tight.right > content.right)
    }

    func testTypicalBounds() {
        let samples: [(Int, Crop)] = [
            (2, Crop(0.2, 0.1, 0.9, 0.9)), (4, Crop(0.2, 0.1, 0.9, 0.9)), (6, Crop(0, 0, 1, 1)),
            (1, Crop(0.1, 0.1, 0.8, 0.9)), (3, Crop(0.1, 0.1, 0.8, 0.9)), (5, Crop(0.1, 0.1, 0.8, 0.9)),
        ]
        let typical = TypicalBounds.from(samples)
        XCTAssertEqual(typical.forPage(10), Crop(0.2, 0.1, 0.9, 0.9))
        XCTAssertEqual(typical.forPage(11), Crop(0.1, 0.1, 0.8, 0.9))
        XCTAssertEqual(TypicalBounds.from([(1, Crop(0.1, 0.1, 0.9, 0.9))]).forPage(4), Crop(0.1, 0.1, 0.9, 0.9))
    }
}

final class PageColorsTests: XCTestCase {
    private let paper: (Float, Float, Float) = (0x1F / 255, 0x1D / 255, 0x1A / 255)
    private let ink: (Float, Float, Float) = (0xDC / 255, 0xD5 / 255, 0xCA / 255)

    func testNightMatrix() {
        let m = PageColors.nightMatrix(paper: paper, ink: ink)
        let white = PageColors.apply(m, r: 255, g: 255, b: 255)
        XCTAssertEqual(white.0, paper.0 * 255, accuracy: 1)
        XCTAssertEqual(white.2, paper.2 * 255, accuracy: 1)
        let black = PageColors.apply(m, r: 0, g: 0, b: 0)
        XCTAssertEqual(black.0, ink.0 * 255, accuracy: 1)
        XCTAssertEqual(black.1, ink.1 * 255, accuracy: 1)
        let red = PageColors.apply(m, r: 200, g: 30, b: 30)
        XCTAssertTrue(red.0 > red.1 + 40 && red.0 > red.2 + 40)
        XCTAssertEqual(m[18], 1)
        XCTAssertEqual(m[19], 0)
    }
}
