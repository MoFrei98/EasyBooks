import Foundation

// Neu umbrochener Text für die Schriftgröße "Groß" (gleiche Logik wie in der Android-App).
//
// Die PDF-Seite wird als Bild analysiert: Zeilen, Wörter, Trennstriche und Bilder. Danach werden
// die Wortbilder vergrößert in kürzere Zeilen gesetzt, wie in einem E-Book. Alle Koordinaten der
// Analyse sind Pixel der Analyse-Bitmap (Ursprung oben links).

/// Ein Wort einer Zeile.
public struct Word: Equatable {
    public var left: Int
    public var right: Int
    /// Endet das Wort mit einem Trennstrich: rechter Rand ohne den Strich, sonst -1.
    public var stemRight: Int
    /// Linker Rand des Trennstrichs, sonst -1.
    public var hyphenLeft: Int

    public init(left: Int, right: Int, stemRight: Int = -1, hyphenLeft: Int = -1) {
        self.left = left
        self.right = right
        self.stemRight = stemRight
        self.hyphenLeft = hyphenLeft
    }

    public var hyphenated: Bool { stemRight >= 0 }
    public var width: Int { right - left }
}

/// Eine Zeile (oder ein Bild, wenn [figure] gilt).
public struct TextLine: Equatable {
    public var top: Int
    public var bottom: Int
    /// Grundlinie: Unterkante der Kleinbuchstaben.
    public var baseline: Int
    /// Höhe der Kleinbuchstaben ohne Ober- und Unterlängen.
    public var xHeight: Int
    public var left: Int
    public var right: Int
    public var words: [Word]
    public var figure: Bool

    public init(top: Int, bottom: Int, baseline: Int, xHeight: Int, left: Int, right: Int, words: [Word], figure: Bool = false) {
        self.top = top
        self.bottom = bottom
        self.baseline = baseline
        self.xHeight = xHeight
        self.left = left
        self.right = right
        self.words = words
        self.figure = figure
    }

    public var width: Int { right - left }
    public var height: Int { bottom - top }
}

/// Ergebnis der Analyse einer PDF-Seite.
public struct PageText: Equatable {
    public var width: Int
    public var height: Int
    public var lines: [TextLine]
    /// Die ganze Seite ist ein Bild (z. B. ein Cover).
    public var image: Bool

    public init(width: Int, height: Int, lines: [TextLine], image: Bool = false) {
        self.width = width
        self.height = height
        self.lines = lines
        self.image = image
    }

    public var textLines: [TextLine] { lines.filter { !$0.figure } }
}

/// Bildschirmfläche einer Seite in Pixeln, mit Rändern für den Text.
public struct ReflowFrame: Equatable, Hashable {
    public var width: Int
    public var height: Int
    public var marginX: Int
    public var marginTop: Int
    public var marginBottom: Int

    public init(width: Int, height: Int, marginX: Int, marginTop: Int, marginBottom: Int) {
        self.width = width
        self.height = height
        self.marginX = marginX
        self.marginTop = marginTop
        self.marginBottom = marginBottom
    }

    public var textWidth: Int { width - 2 * marginX }
    public var textHeight: Int { height - marginTop - marginBottom }
}

/// Ein Stück einer PDF-Seite, das auf eine Bildschirmseite kopiert wird.
/// [scale] = Bildschirm-Pixel pro Analyse-Pixel; Text ([word]) wird nur abgedunkelt eingezeichnet.
public struct Placement: Equatable {
    public var page: Int
    public var srcLeft: Int
    public var srcTop: Int
    public var srcRight: Int
    public var srcBottom: Int
    public var dstX: Float
    public var dstY: Float
    public var scale: Float
    public var word: Bool

    public init(page: Int, srcLeft: Int, srcTop: Int, srcRight: Int, srcBottom: Int, dstX: Float, dstY: Float, scale: Float, word: Bool = true) {
        self.page = page
        self.srcLeft = srcLeft
        self.srcTop = srcTop
        self.srcRight = srcRight
        self.srcBottom = srcBottom
        self.dstX = dstX
        self.dstY = dstY
        self.scale = scale
        self.word = word
    }

    public var dstWidth: Float { Float(srcRight - srcLeft) * scale }
    public var dstHeight: Float { Float(srcBottom - srcTop) * scale }
}

/// Eine Bildschirmseite im Modus "Groß".
public struct ScreenPage: Equatable {
    public var placements: [Placement]
    /// PDF-Seite und Zeile (Index in `PageText.lines`), wo der Inhalt dieser Seite beginnt.
    public var startPage: Int
    public var startLine: Int
    /// Seite zeigt nur eine Bildseite (wird im Dark Mode nicht invertiert).
    public var imageOnly: Bool

    public init(placements: [Placement], startPage: Int, startLine: Int, imageOnly: Bool = false) {
        self.placements = placements
        self.startPage = startPage
        self.startLine = startLine
        self.imageOnly = imageOnly
    }

    /// Lesestelle am Anfang dieser Seite.
    public func position(lineCount: Int) -> ReadingPosition {
        ReadingPosition(page: startPage, fraction: lineCount > 0 ? Float(startLine) / Float(lineCount) : 0)
    }
}

/// Lesestelle unabhängig von der Schriftgröße: PDF-Seite und Anteil (0...1) innerhalb der Seite.
public struct ReadingPosition: Equatable {
    public var page: Int
    public var fraction: Float

    public init(page: Int, fraction: Float = 0) {
        self.page = page
        self.fraction = fraction
    }
}

extension Array where Element == ScreenPage {
    /// Index der Bildschirmseite, auf der [position] liegt, oder `nil`, wenn diese Stelle noch nicht gesetzt ist.
    public func index(of position: ReadingPosition, lineCount: (Int) -> Int, complete: Bool) -> Int? {
        if isEmpty { return nil }
        let line = Int(position.fraction * Float(lineCount(position.page)))
        var result = 0
        for (i, p) in enumerated() {
            if p.startPage < position.page || (p.startPage == position.page && p.startLine <= line) {
                result = i
            } else {
                return result
            }
        }
        return complete ? result : nil
    }
}
