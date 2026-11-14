import Foundation

/// Ausschnitt einer Seite, angegeben als Anteile von 0 bis 1 (Ursprung oben links).
public struct Crop: Equatable, Hashable {
    public var left: Float
    public var top: Float
    public var right: Float
    public var bottom: Float

    public init(_ left: Float, _ top: Float, _ right: Float, _ bottom: Float) {
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }

    public static let full = Crop(0, 0, 1, 1)

    public var width: Float { right - left }
    public var height: Float { bottom - top }

    public func union(_ other: Crop) -> Crop {
        Crop(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
    }

    public func expand(_ amount: Float) -> Crop {
        Crop(max(0, left - amount), max(0, top - amount), min(1, right + amount), min(1, bottom + amount))
    }
}

/// Ergebnis der Analyse einer kleinen Seitenvorschau.
public struct PageAnalysis: Equatable {
    /// Bereich mit Inhalt oder `nil` für eine leere Seite.
    public var bounds: Crop?
    /// Mittlere Helligkeit von 0 (schwarz) bis 1 (weiß).
    public var luminance: Float

    public init(bounds: Crop?, luminance: Float) {
        self.bounds = bounds
        self.luminance = luminance
    }

    /// Überwiegend dunkles Bild (z. B. Cover). Solche Seiten werden im Dark Mode nicht invertiert.
    public var isImage: Bool { luminance < 0.55 }
}

/// Typische Inhaltsgrenzen eines Dokuments, getrennt nach geraden und ungeraden Seiten.
public struct TypicalBounds {
    let even: Crop?
    let odd: Crop?
    let all: Crop?

    public func forPage(_ page: Int) -> Crop? { (page % 2 == 0 ? even : odd) ?? all }

    public static func from(_ samples: [(Int, Crop)]) -> TypicalBounds {
        let even = samples.filter { $0.0 % 2 == 0 }.map { $0.1 }
        let odd = samples.filter { $0.0 % 2 == 1 }.map { $0.1 }
        return TypicalBounds(
            even: even.count >= 3 ? median(even) : nil,
            odd: odd.count >= 3 ? median(odd) : nil,
            all: samples.isEmpty ? nil : median(samples.map { $0.1 })
        )
    }

    private static func median(_ crops: [Crop]) -> Crop {
        func mid(_ v: [Float]) -> Float { v.sorted()[v.count / 2] }
        return Crop(mid(crops.map(\.left)), mid(crops.map(\.top)), mid(crops.map(\.right)), mid(crops.map(\.bottom)))
    }
}

public enum CropMath {
    /// Etwas Luft um den Inhalt, damit nichts am Rand klebt.
    public static let padding: Float = 0.02
    /// Für "Normal": nur so viel Luft, dass helle Kanten von Buchstaben sicher sichtbar bleiben.
    public static let paddingTight: Float = 0.006

    private static let whiteThreshold: UInt8 = 232

    /// Inhaltsgrenzen und mittlere Helligkeit einer Seitenvorschau (RGBA, zeilenweise, ohne Lücken).
    public static func analyze(rgba: [UInt8], width: Int, height: Int) -> PageAnalysis {
        var luminanceSum: Double = 0
        var minX = width, maxX = -1, minY = height, maxY = -1
        rgba.withUnsafeBufferPointer { px in
            for y in 0..<height {
                let row = y * width * 4
                for x in 0..<width {
                    let i = row + x * 4
                    let r = px[i], g = px[i + 1], b = px[i + 2]
                    luminanceSum += 0.2126 * Double(r) + 0.7152 * Double(g) + 0.0722 * Double(b)
                    if r < whiteThreshold || g < whiteThreshold || b < whiteThreshold {
                        if x < minX { minX = x }
                        if x > maxX { maxX = x }
                        if y < minY { minY = y }
                        if y > maxY { maxY = y }
                    }
                }
            }
        }
        let luminance = Float(luminanceSum / (255.0 * Double(max(1, width * height))))
        guard maxX >= 0 else { return PageAnalysis(bounds: nil, luminance: luminance) }
        let bounds = Crop(
            Float(minX) / Float(width), Float(minY) / Float(height),
            Float(maxX + 1) / Float(width), Float(maxY + 1) / Float(height)
        )
        return PageAnalysis(bounds: bounds, luminance: luminance)
    }

    /// Ausschnitt ohne Ränder. Der Inhalt der Seite wird mit den typischen Grenzen des Dokuments
    /// vereinigt: Nie wird Inhalt abgeschnitten, und kurze Seiten werden nicht stärker vergrößert.
    public static func cropFor(page: Crop?, typical: Crop?, padding: Float = padding) -> Crop {
        let base: Crop
        if let page, let typical {
            base = page.union(typical)
        } else if let one = page ?? typical {
            base = one
        } else {
            return .full
        }
        let padded = base.expand(padding)
        if padded.width > 0.97 && padded.height > 0.97 { return .full }
        return padded
    }
}
