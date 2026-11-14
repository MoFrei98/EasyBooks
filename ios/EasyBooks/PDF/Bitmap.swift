import CoreGraphics
import Foundation

/// RGBA-Bitmap im Speicher (zeilenweise, oben links beginnend), zum Zeichnen und Umfärben.
final class Bitmap {
    let width: Int
    let height: Int
    private(set) var data: [UInt8]

    /// Neue weiße Bitmap.
    init(width: Int, height: Int) {
        self.width = max(1, width)
        self.height = max(1, height)
        data = [UInt8](repeating: 255, count: self.width * self.height * 4)
    }

    init(pixels: PDFRenderer.Pixels) {
        width = pixels.width
        height = pixels.height
        data = pixels.data
    }

    /// Zeichnet in die Bitmap. Achtung: Koordinaten von CoreGraphics beginnen unten links.
    func draw(_ body: (CGContext) -> Void) {
        data.withUnsafeMutableBytes { buffer in
            guard let ctx = CGContext(
                data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ) else { return }
            body(ctx)
        }
    }

    /// Wendet eine 4x5-Farbmatrix an (wie Androids ColorMatrix, Kanäle 0...255).
    func apply(_ m: [Float]) {
        let count = width * height
        data.withUnsafeMutableBufferPointer { px in
            for i in 0..<count {
                let o = i * 4
                let r = Float(px[o]), g = Float(px[o + 1]), b = Float(px[o + 2]), a = Float(px[o + 3])
                px[o] = Bitmap.clamp(m[0] * r + m[1] * g + m[2] * b + m[3] * a + m[4])
                px[o + 1] = Bitmap.clamp(m[5] * r + m[6] * g + m[7] * b + m[8] * a + m[9])
                px[o + 2] = Bitmap.clamp(m[10] * r + m[11] * g + m[12] * b + m[13] * a + m[14])
            }
        }
    }

    private static func clamp(_ v: Float) -> UInt8 { UInt8(max(0, min(255, v))) }

    func makeImage() -> CGImage? {
        guard let provider = CGDataProvider(data: Data(data) as CFData) else { return nil }
        return CGImage(
            width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent
        )
    }

    /// Farbmatrizen für die Seiten.
    enum Colors {
        /// Leicht warmes Papier im hellen Design.
        static let day: [Float] = scale(1, 0.992, 0.973)
        /// Bildseiten im Dark Mode: nicht invertieren, nur etwas abdunkeln.
        static let dimmedImage: [Float] = scale(0.8, 0.8, 0.8)

        static func scale(_ r: Float, _ g: Float, _ b: Float) -> [Float] {
            [r, 0, 0, 0, 0,
             0, g, 0, 0, 0,
             0, 0, b, 0, 0,
             0, 0, 0, 1, 0]
        }
    }
}
