import Foundation

/// Farbmatrizen für die Seiten (wie in der Android-App).
///
/// Für den Dark Mode wird die Helligkeit umgekehrt, der Farbton bleibt aber erhalten
/// (Invertieren + 180° Farbtondrehung). Weißes Papier wird dunkel, schwarze Schrift hell.
public enum PageColors {
    private static let lr: Float = 0.213
    private static let lg: Float = 0.715
    private static let lb: Float = 0.072

    /// 4x5-Matrix (zeilenweise, Kanäle 0...255 wie bei Android): Weiß wird zu [paper], Schwarz zu [ink].
    /// Farben als RGB 0...1.
    public static func nightMatrix(paper: (Float, Float, Float), ink: (Float, Float, Float)) -> [Float] {
        let base: [[Float]] = [
            [1 - 2 * lr, -2 * lg, -2 * lb],
            [-2 * lr, 1 - 2 * lg, -2 * lb],
            [-2 * lr, -2 * lg, 1 - 2 * lb],
        ]
        let low = [paper.0, paper.1, paper.2].map { $0 * 255 }
        let high = [ink.0, ink.1, ink.2].map { $0 * 255 }
        var values = [Float](repeating: 0, count: 20)
        for row in 0..<3 {
            let k = (high[row] - low[row]) / 255
            for col in 0..<3 { values[row * 5 + col] = base[row][col] * k }
            values[row * 5 + 4] = low[row] + 255 * k
        }
        values[18] = 1 // Alpha unverändert
        return values
    }

    /// Wendet eine 4x5-Matrix auf eine Farbe (0...255) an, wie Androids ColorMatrix.
    public static func apply(_ m: [Float], r: Float, g: Float, b: Float, a: Float = 255) -> (Float, Float, Float) {
        func row(_ i: Int) -> Float {
            min(255, max(0, m[i * 5] * r + m[i * 5 + 1] * g + m[i * 5 + 2] * b + m[i * 5 + 3] * a + m[i * 5 + 4]))
        }
        return (row(0), row(1), row(2))
    }
}
