import Foundation

/// Automatisch erkannte Buch-Details; alles optional.
public struct BookMetadata: Equatable {
    public var title: String?
    public var firstName: String?
    public var lastName: String?
    public var year: Int?

    public init(title: String? = nil, firstName: String? = nil, lastName: String? = nil, year: Int? = nil) {
        self.title = title
        self.firstName = firstName
        self.lastName = lastName
        self.year = year
    }

    /// Füllt fehlende Angaben aus [other] auf.
    public func orElse(_ other: BookMetadata) -> BookMetadata {
        BookMetadata(
            title: title ?? other.title,
            firstName: lastName != nil ? firstName : other.firstName,
            lastName: lastName ?? other.lastName,
            year: year ?? other.year
        )
    }
}

/// Erkennt Titel, Autor und Erscheinungsjahr – aus dem Dateinamen, z. B.
/// "[Abnett_Dan]_Aufstieg_des_Horus(libcats.org).pdf" oder "Abnett, Dan - Aufstieg des Horus (2006).pdf",
/// und aus den Metadaten der PDF (Info-Dictionary und XMP), soweit sie unkomprimiert vorliegen.
/// Gleiche Regeln wie in der Android-App.
public enum BookMetadataParser {
    private static let siteTag = Re(#"\((?:www\.)?[\w-]+\.[a-z]{2,}\)"#, ignoreCase: true)
    private static let bracketAuthor = Re(#"^\s*\[([^\]]+)]\s*[_\-–\s]*(.+)$"#)
    private static let commaAuthorDash = Re(#"^\s*([^,\-–]+,\s*[^\-–]+?)\s+[-–]\s+(.+)$"#)
    private static let yearInName = Re(#"[(\[]((?:1[5-9]|20)\d{2})[)\]]"#)
    private static let whitespace = Re(#"\s+"#)
    private static let andSeparator = Re(#"\s+(and|und)\s+"#, ignoreCase: true)
    private static let junkTitle = Re(
        #"^(untitled|unbenannt|ohne titel|document|dokument|microsoft word\s*-.*|.*\.(docx?|pdf|indd|qxd|rtf|odt|txt))$"#,
        ignoreCase: true
    )
    private static let junkAuthor = Re(#"^(unknown|unbekannt|admin(istrator)?|user|owner|author|autor|\W+)$"#, ignoreCase: true)

    // MARK: Dateiname

    public static func fromFileName(_ fileName: String) -> BookMetadata {
        var name = fileName.lowercased().hasSuffix(".pdf") ? String(fileName.dropLast(4)) : fileName
        name = siteTag.replace(name, with: " ")
        let year = yearInName.firstMatch(name)?[1].flatMap { Int($0) }
        if year != nil { name = yearInName.replace(name, with: " ") }

        if let m = bracketAuthor.firstMatch(name), let author = m[1], let rest = m[2] {
            // "[Nachname_Vorname]_Titel" bzw. "[Nachname, Vorname] Titel"
            let (first, last) = splitBracketAuthor(author)
            return BookMetadata(title: cleanTitle(rest), firstName: first, lastName: last, year: year)
        }
        if let m = commaAuthorDash.firstMatch(name.replacingOccurrences(of: "_", with: " ")),
           let author = m[1], let rest = m[2] {
            let (first, last) = splitAuthor(author)
            return BookMetadata(title: cleanTitle(rest), firstName: first, lastName: last, year: year)
        }
        return BookMetadata(title: cleanTitle(name), year: year)
    }

    /// "[Abnett_Dan]" ist üblicherweise "Nachname_Vorname".
    private static func splitBracketAuthor(_ raw: String) -> (String?, String?) {
        let text = raw.trimmed
        if text.contains(",") { return splitAuthor(text) }
        let parts = text.split(whereSeparator: { $0 == "_" || $0 == " " }).map(String.init).filter { !$0.trimmed.isEmpty }
        switch parts.count {
        case 0: return (nil, nil)
        case 1: return (nil, parts[0])
        default: return (parts.dropFirst().joined(separator: " "), parts[0])
        }
    }

    // MARK: Autor

    /// Zerlegt einen Autor in Vor- und Nachname. "Dan Abnett" und "Abnett, Dan" ergeben beide
    /// ("Dan", "Abnett"). Bei mehreren Autoren zählt der erste.
    public static func splitAuthor(_ raw: String?) -> (String?, String?) {
        guard let raw else { return (nil, nil) }
        let separators: Set<Character> = [";", "&", "/", "|", "\n"]
        var first = String(raw.split(omittingEmptySubsequences: false, whereSeparator: { separators.contains($0) }).first ?? "")
        first = andSeparator.prefixBeforeFirstMatch(first)
        first = whitespace.replace(first.replacingOccurrences(of: "_", with: " "), with: " ").trimmed
        if first.isEmpty || junkAuthor.contains(first) || looksLikeUserName(first) { return (nil, nil) }
        if let comma = first.firstIndex(of: ",") {
            let last = String(first[..<comma]).trimmed
            let given = String(first[first.index(after: comma)...]).trimmed
            return (given.nilIfEmpty, last.nilIfEmpty)
        }
        let words = first.split(separator: " ", omittingEmptySubsequences: false).map(String.init)
        if words.count == 1 { return (nil, words[0]) }
        return (words.dropLast().joined(separator: " "), words.last)
    }

    /// PDF-Programme tragen oft den Benutzernamen des Rechners als Autor ein, z. B. "jo" oder
    /// "user01". Echte Namen haben Großbuchstaben und keine Ziffern oder @.
    private static func looksLikeUserName(_ name: String) -> Bool {
        !name.contains(where: { $0.isUppercase }) || name.contains(where: { $0.isNumber || $0 == "@" })
    }

    // MARK: PDF

    /// Liest Metadaten aus Rohdaten der PDF (Anfang und Ende der Datei, dort stehen XMP-Daten
    /// und das Info-Dictionary üblicherweise).
    public static func fromPdf(_ chunks: [Data]) -> BookMetadata {
        var result = BookMetadata()
        for chunk in chunks {
            let bytes = [UInt8](chunk)
            // ISO-8859-1 bildet jedes Byte 1:1 auf ein Zeichen ab.
            let text = String(bytes.map { Character(Unicode.Scalar($0)) })
            result = result.orElse(fromXmp(text)).orElse(fromInfo(text, bytes))
        }
        return result
    }

    /// Titel und Autor, wie sie PDFKit aus dem Info-Dictionary liest (gleiche Filter wie oben).
    public static func fromAttributes(title: String?, author: String?) -> BookMetadata {
        let t = title.map { whitespace.replace($0, with: " ").trimmed }.flatMap { goodTitle($0) ? $0 : nil }
        let (first, last) = splitAuthor(author)
        return BookMetadata(title: t, firstName: first, lastName: last)
    }

    private static func fromInfo(_ text: String, _ bytes: [UInt8]) -> BookMetadata {
        let title = pdfString(text, bytes, key: "Title").flatMap { goodTitle($0) ? $0 : nil }
        let (first, last) = splitAuthor(pdfString(text, bytes, key: "Author"))
        return BookMetadata(title: title, firstName: first, lastName: last)
    }

    private static func fromXmp(_ text: String) -> BookMetadata {
        func tag(_ name: String) -> String? {
            guard let block = Re("<\(name)\\b[^>]*>(.*?)</\(name)>", dotAll: true).firstMatch(text)?[1] else { return nil }
            let li = Re(#"<rdf:li\b[^>]*>(.*?)</rdf:li>"#, dotAll: true).firstMatch(block)?[1]
            let value = Re("<[^>]+>").replace(li ?? block, with: "")
            return xmlUnescape(utf8(value)).trimmed.nilIfEmpty
        }
        let title = tag("dc:title").flatMap { goodTitle($0) ? $0 : nil }
        let (first, last) = splitAuthor(tag("dc:creator"))
        let year = tag("prism:publicationDate").flatMap { Int($0.prefix(4)) }.flatMap { (1450...2100).contains($0) ? $0 : nil }
        return BookMetadata(title: title, firstName: first, lastName: last, year: year)
    }

    /// Wert eines Eintrags wie `/Author (Dan Abnett)` oder `/Author <FEFF0044…>`.
    static func pdfString(_ text: String, _ bytes: [UInt8], key: String) -> String? {
        guard let ranges = Re("/\(key)\\s*([(<])").firstMatchRanges(text) else { return nil }
        // Latin-1: Position im Text (UTF-16) = Position in den Bytes.
        let start = ranges[1].location
        let raw = bytes[start] == UInt8(ascii: "(") ? literalBytes(bytes, start) : hexBytes(bytes, start)
        guard let raw else { return nil }
        return whitespace.replace(decodePdfText(raw), with: " ").trimmed.nilIfEmpty
    }

    /// Zum Testen mit Text statt Bytes.
    static func pdfString(_ text: String, key: String) -> String? {
        let bytes = text.unicodeScalars.map { UInt8(truncatingIfNeeded: $0.value) }
        return pdfString(text, bytes, key: key)
    }

    private static func literalBytes(_ b: [UInt8], _ open: Int) -> [UInt8]? {
        var out = [UInt8]()
        var depth = 0
        var i = open
        while i < b.count {
            let c = b[i]
            if c == 0x5C, i + 1 < b.count { // Backslash
                let n = b[i + 1]
                switch n {
                case UInt8(ascii: "n"): out.append(0x0A)
                case UInt8(ascii: "r"): out.append(0x0D)
                case UInt8(ascii: "t"): out.append(0x09)
                case UInt8(ascii: "b"): out.append(8)
                case UInt8(ascii: "f"): out.append(12)
                case 0x0D, 0x0A: break // Zeilenfortsetzung
                case 0x30...0x37:
                    var j = i + 1
                    var value = 0
                    while j < b.count, j < i + 4, (0x30...0x37).contains(b[j]) {
                        value = value * 8 + Int(b[j] - 0x30)
                        j += 1
                    }
                    out.append(UInt8(truncatingIfNeeded: value))
                    i = j
                    continue
                default: out.append(n)
                }
                i += 2
                continue
            }
            if c == 0x28 { // (
                if depth > 0 { out.append(c) }
                depth += 1
            } else if c == 0x29 { // )
                depth -= 1
                if depth == 0 { return out }
                out.append(c)
            } else {
                out.append(c)
            }
            i += 1
        }
        return nil
    }

    private static func hexBytes(_ b: [UInt8], _ open: Int) -> [UInt8]? {
        guard let close = b[open...].firstIndex(of: UInt8(ascii: ">")) else { return nil }
        var hex = b[(open + 1)..<close].filter { (0x30...0x39).contains($0) || (0x41...0x5A).contains($0) || (0x61...0x7A).contains($0) }
        if hex.count % 2 == 1 { hex.append(UInt8(ascii: "0")) }
        var out = [UInt8]()
        out.reserveCapacity(hex.count / 2)
        var i = 0
        while i < hex.count {
            guard let v = UInt8(String(bytes: hex[i..<(i + 2)], encoding: .ascii) ?? "", radix: 16) else { return nil }
            out.append(v)
            i += 2
        }
        return out
    }

    /// UTF-16 mit BOM oder PDFDocEncoding (für Text praktisch Latin-1).
    private static func decodePdfText(_ bytes: [UInt8]) -> String {
        if bytes.count >= 2, bytes[0] == 0xFE, bytes[1] == 0xFF,
           let s = String(bytes: bytes[2...], encoding: .utf16BigEndian) { return s }
        if bytes.count >= 2, bytes[0] == 0xFF, bytes[1] == 0xFE,
           let s = String(bytes: bytes[2...], encoding: .utf16LittleEndian) { return s }
        if bytes.count >= 3, bytes[0] == 0xEF, bytes[1] == 0xBB, bytes[2] == 0xBF {
            return String(decoding: bytes[3...], as: UTF8.self)
        }
        return String(bytes.map { Character(Unicode.Scalar($0)) })
    }

    /// XMP ist UTF-8, wurde aber als Latin-1 eingelesen.
    private static func utf8(_ latin1: String) -> String {
        String(decoding: latin1.unicodeScalars.map { UInt8(truncatingIfNeeded: $0.value) }, as: UTF8.self)
    }

    private static func xmlUnescape(_ s: String) -> String {
        var text = Re(#"&#(\d+);"#).replace(s) { g in
            g[1].flatMap { UInt32($0) }.flatMap { Unicode.Scalar($0) }.map { String(Character($0)) } ?? ""
        }
        text = Re(#"&#x([0-9a-fA-F]+);"#).replace(text) { g in
            g[1].flatMap { UInt32($0, radix: 16) }.flatMap { Unicode.Scalar($0) }.map { String(Character($0)) } ?? ""
        }
        return text
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&apos;", with: "'")
            .replacingOccurrences(of: "&amp;", with: "&")
    }

    // MARK: Hilfen

    public static func goodTitle(_ title: String) -> Bool {
        title.count >= 2 && !junkTitle.contains(title.trimmed) && title.contains(where: { $0.isLetter })
    }

    private static func cleanTitle(_ raw: String) -> String? {
        whitespace.replace(raw.replacingOccurrences(of: "_", with: " "), with: " ")
            .trimming([" ", "-", "–", ".", ","])
            .nilIfEmpty
    }
}
