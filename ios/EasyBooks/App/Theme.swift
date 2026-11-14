import SwiftUI
import UIKit

/// Warme, papierähnliche Farben wie in der Android-App.
enum Theme {
    static let accent = Color(light: 0x8B5A2B, dark: 0xE6BC8F)
    static let background = Color(light: 0xF8F3EC, dark: 0x15120F)
    static let card = Color(light: 0xEFE6DC, dark: 0x221E1A)
    static let folderCard = Color(light: 0xF0E0CF, dark: 0x54432F)
    static let chip = Color(light: 0xF5DEC4, dark: 0x603F21)
    static let secondaryText = Color(light: 0x51443A, dark: 0xD6C3B5)
    static let outline = Color(light: 0xD6C3B5, dark: 0x51443A)
}

/// Farben rund um die Seiten im Reader.
struct ReaderPalette {
    /// Hintergrund hinter den Seiten ("Tisch").
    let backdrop: UIColor
    /// Papierfarbe einer Seite.
    let paper: UIColor
    /// Kanten der darunterliegenden Blätter.
    let paperEdge: UIColor
    /// Schatten am Bundsteg und unter dem Blatt.
    let shade: UIColor
    /// Schriftfarbe auf dunklen Seiten.
    let ink: UIColor

    static let light = ReaderPalette(
        backdrop: UIColor(hex: 0xE4DBCE), paper: UIColor(hex: 0xFFFDF8), paperEdge: UIColor(hex: 0xD9CFC1),
        shade: UIColor(hex: 0x3A2A1A), ink: UIColor(hex: 0x000000)
    )
    static let dark = ReaderPalette(
        backdrop: UIColor(hex: 0x0C0B0A), paper: UIColor(hex: 0x1F1D1A), paperEdge: UIColor(hex: 0x3A3631),
        shade: UIColor(hex: 0x000000), ink: UIColor(hex: 0xDCD5CA)
    )

    static func of(dark: Bool) -> ReaderPalette { dark ? .dark : .light }
}

extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat = 1) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: alpha
        )
    }

    /// RGB-Anteile 0...1.
    var rgb: (Float, Float, Float) {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        return (Float(r), Float(g), Float(b))
    }
}

extension Color {
    /// Farbe, die sich mit hellem und dunklem Design ändert.
    init(light: UInt32, dark: UInt32) {
        self.init(UIColor { traits in
            traits.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light)
        })
    }
}
