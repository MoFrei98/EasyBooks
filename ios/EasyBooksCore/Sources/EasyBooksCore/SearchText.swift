import Foundation

/// Vergleich für die Suche: Groß-/Kleinschreibung, Umlaute und Akzente spielen keine Rolle.
public enum SearchText {
    private static let separators = Re(#"[_\-.]+"#)
    private static let whitespace = Re(#"\s+"#)

    public static func normalize(_ text: String) -> String {
        let lower = text.lowercased()
            .replacingOccurrences(of: "ä", with: "ae")
            .replacingOccurrences(of: "ö", with: "oe")
            .replacingOccurrences(of: "ü", with: "ue")
            .replacingOccurrences(of: "ß", with: "ss")
        // In Grundbuchstaben und Akzente zerlegen, Akzente weglassen.
        var scalars = String.UnicodeScalarView()
        for scalar in lower.decomposedStringWithCanonicalMapping.unicodeScalars {
            switch scalar.properties.generalCategory {
            case .nonspacingMark, .spacingMark, .enclosingMark: continue
            default: scalars.append(scalar)
            }
        }
        let plain = String(scalars)
        return whitespace.replace(separators.replace(plain, with: " "), with: " ").trimmed
    }

    /// Alle Wörter der Suche müssen in mindestens einem der Felder vorkommen.
    public static func matches(_ query: String, _ fields: [String?]) -> Bool {
        let words = normalize(query).split(separator: " ").map(String.init)
        if words.isEmpty { return true }
        let haystack = fields.compactMap { $0 }.map(normalize).joined(separator: " ")
        return words.allSatisfy { haystack.contains($0) }
    }
}
