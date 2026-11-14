import CoreGraphics
import EasyBooksCore
import Foundation
import PDFKit

/// Rendert Seiten einer PDF im Hintergrund. PDFKit ist nicht threadsicher, deshalb laufen alle
/// Zugriffe auf das Dokument nacheinander in einer eigenen Warteschlange.
final class PDFRenderer: @unchecked Sendable {
    private let queue = DispatchQueue(label: "easybooks.pdf", qos: .userInitiated)
    private let document: PDFDocument
    let pageCount: Int

    init?(url: URL) {
        guard let document = PDFDocument(url: url) else { return nil }
        if document.isLocked { return nil }
        self.document = document
        pageCount = document.pageCount
    }

    /// Führt [work] mit dem Dokument in der Warteschlange aus.
    func run<T>(_ work: @escaping (PDFDocument) -> T) async -> T {
        await withCheckedContinuation { continuation in
            queue.async { continuation.resume(returning: work(self.document)) }
        }
    }

    /// Seitengröße in Punkten, wie angezeigt (Drehung berücksichtigt).
    func pageSize(_ index: Int) async -> CGSize {
        await run { doc in
            guard let page = doc.page(at: index) else { return CGSize(width: 1, height: 1) }
            return PDFRenderer.displaySize(page)
        }
    }

    static func displaySize(_ page: PDFPage) -> CGSize {
        let b = page.bounds(for: .cropBox)
        return page.rotation % 180 == 0 ? b.size : CGSize(width: b.height, height: b.width)
    }

    /// Eine gerenderte Fläche als RGBA-Pixel (zeilenweise, oben links beginnend).
    struct Pixels {
        var data: [UInt8]
        var width: Int
        var height: Int
    }

    /// Rendert [area] (in Punkten, Ursprung oben links) mit [scale] Pixeln pro Punkt auf Weiß.
    static func render(_ page: PDFPage, scale: CGFloat, area: CGRect) -> CGImage? {
        let w = max(1, Int(ceil(area.width * scale)))
        let h = max(1, Int(ceil(area.height * scale)))
        guard let ctx = context(width: w, height: h, data: nil) else { return nil }
        draw(page, into: ctx, scale: scale, area: area, width: w, height: h)
        return ctx.makeImage()
    }

    static func renderPixels(_ page: PDFPage, scale: CGFloat) -> Pixels? {
        let size = displaySize(page)
        let area = CGRect(origin: .zero, size: size)
        let w = max(1, Int(ceil(size.width * scale)))
        let h = max(1, Int(ceil(size.height * scale)))
        var data = [UInt8](repeating: 255, count: w * h * 4)
        let ok = data.withUnsafeMutableBytes { buffer -> Bool in
            guard let ctx = context(width: w, height: h, data: buffer.baseAddress) else { return false }
            draw(page, into: ctx, scale: scale, area: area, width: w, height: h)
            return true
        }
        return ok ? Pixels(data: data, width: w, height: h) : nil
    }

    private static func context(width: Int, height: Int, data: UnsafeMutableRawPointer?) -> CGContext? {
        CGContext(
            data: data, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )
    }

    private static func draw(_ page: PDFPage, into ctx: CGContext, scale: CGFloat, area: CGRect, width: Int, height: Int) {
        ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        ctx.interpolationQuality = .high
        let size = displaySize(page)
        // PDF-Koordinaten beginnen unten links: Die Unterkante des Bereichs liegt am Bildrand unten.
        ctx.translateBy(x: -area.minX * scale, y: -(size.height - area.maxY) * scale)
        ctx.scaleBy(x: scale, y: scale)
        page.draw(with: .cropBox, to: ctx)
    }

    /// Analysiert eine kleine Vorschau: Inhaltsgrenzen und ob die Seite ein Bild ist.
    func analyze(_ index: Int) async -> PageAnalysis {
        let pixels: Pixels? = await run { doc in
            guard let page = doc.page(at: index) else { return nil }
            let size = PDFRenderer.displaySize(page)
            return PDFRenderer.renderPixels(page, scale: 300 / max(1, size.width))
        }
        guard let pixels else { return PageAnalysis(bounds: nil, luminance: 1) }
        return CropMath.analyze(rgba: pixels.data, width: pixels.width, height: pixels.height)
    }
}
