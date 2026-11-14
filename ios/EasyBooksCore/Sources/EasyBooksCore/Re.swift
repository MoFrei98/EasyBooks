import Foundation

/// Kleine Hülle um `NSRegularExpression` (ICU, wie die Regex von Java/Kotlin; läuft auch unter Linux).
struct Re {
    let regex: NSRegularExpression

    init(_ pattern: String, ignoreCase: Bool = false, dotAll: Bool = false) {
        var options: NSRegularExpression.Options = []
        if ignoreCase { options.insert(.caseInsensitive) }
        if dotAll { options.insert(.dotMatchesLineSeparators) }
        // Die Muster sind fest im Code, ein Fehler wäre ein Programmierfehler.
        regex = try! NSRegularExpression(pattern: pattern, options: options)
    }

    private func range(_ text: String) -> NSRange {
        NSRange(location: 0, length: text.utf16.count)
    }

    private static func substring(_ text: String, _ r: NSRange) -> String? {
        guard r.location != NSNotFound, let range = Range(r, in: text) else { return nil }
        return String(text[range])
    }

    /// Erster Treffer: Index 0 ist der ganze Treffer, danach die Gruppen (`nil` = nicht beteiligt).
    func firstMatch(_ text: String) -> [String?]? {
        guard let m = regex.firstMatch(in: text, options: [], range: range(text)) else { return nil }
        return (0..<m.numberOfRanges).map { Re.substring(text, m.range(at: $0)) }
    }

    /// Position (UTF-16) des ersten Treffers und seiner Gruppen.
    func firstMatchRanges(_ text: String) -> [NSRange]? {
        guard let m = regex.firstMatch(in: text, options: [], range: range(text)) else { return nil }
        return (0..<m.numberOfRanges).map { m.range(at: $0) }
    }

    func contains(_ text: String) -> Bool {
        regex.firstMatch(in: text, options: [], range: range(text)) != nil
    }

    /// Ersetzt alle Treffer durch festen Text.
    func replace(_ text: String, with replacement: String) -> String {
        regex.stringByReplacingMatches(
            in: text, options: [], range: range(text),
            withTemplate: NSRegularExpression.escapedTemplate(for: replacement)
        )
    }

    /// Ersetzt alle Treffer durch das Ergebnis von [transform] (bekommt die Gruppen).
    func replace(_ text: String, transform: ([String?]) -> String) -> String {
        let matches = regex.matches(in: text, options: [], range: range(text))
        var result = text
        for m in matches.reversed() {
            guard let r = Range(m.range, in: result) else { continue }
            let groups = (0..<m.numberOfRanges).map { Re.substring(text, m.range(at: $0)) }
            result.replaceSubrange(r, with: transform(groups))
        }
        return result
    }

    /// Text vor dem ersten Treffer (oder der ganze Text).
    func prefixBeforeFirstMatch(_ text: String) -> String {
        guard let m = regex.firstMatch(in: text, options: [], range: range(text)),
              let r = Range(m.range, in: text) else { return text }
        return String(text[..<r.lowerBound])
    }
}

extension String {
    /// Entfernt die angegebenen Zeichen an Anfang und Ende (wie Kotlins `trim(vararg chars)`).
    func trimming(_ characters: Set<Character>) -> String {
        var s = Substring(self)
        while let c = s.first, characters.contains(c) { s = s.dropFirst() }
        while let c = s.last, characters.contains(c) { s = s.dropLast() }
        return String(s)
    }

    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }

    var nilIfEmpty: String? { isEmpty ? nil : self }
}
