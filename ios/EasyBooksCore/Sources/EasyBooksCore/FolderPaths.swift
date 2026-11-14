import Foundation

/// Ordner werden als Pfade relativ zum Bibliotheksordner beschrieben:
/// "" ist die oberste Ebene, "Warhammer/Horus Heresy" ein Unterordner.
public enum FolderPaths {
    public static let root = ""

    private static let forbidden = Re(#"[\\/:*?"<>|]"#)

    public static func parent(_ path: String) -> String {
        guard let slash = path.lastIndex(of: "/") else { return root }
        return String(path[..<slash])
    }

    public static func name(_ path: String) -> String {
        guard let slash = path.lastIndex(of: "/") else { return path }
        return String(path[path.index(after: slash)...])
    }

    public static func child(_ parent: String, _ name: String) -> String {
        parent.isEmpty ? name : "\(parent)/\(name)"
    }

    public static func depth(_ path: String) -> Int {
        path.isEmpty ? 0 : path.filter { $0 == "/" }.count + 1
    }

    /// Liegt [path] in [ancestor] (oder ist es selbst)? Die oberste Ebene enthält alles.
    public static func isInside(_ path: String, _ ancestor: String) -> Bool {
        ancestor.isEmpty || path == ancestor || path.hasPrefix(ancestor + "/")
    }

    /// Direkte Unterordner von [parent], alphabetisch.
    public static func subfolders(_ all: [String], of parent: String) -> [String] {
        all.filter { !$0.isEmpty && self.parent($0) == parent }
            .sorted { name($0).lowercased() < name($1).lowercased() }
    }

    /// Alle Ordner in Baum-Reihenfolge: Eltern vor Kindern, Geschwister alphabetisch.
    public static func treeOrder(_ all: [String]) -> [String] {
        func key(_ s: String) -> [UInt32] {
            s.lowercased().unicodeScalars.map { $0 == "/" ? 0 : $0.value }
        }
        return all.filter { !$0.isEmpty }.sorted { key($0).lexicographicallyPrecedes(key($1)) }
    }

    /// Macht aus einer Eingabe einen gültigen Ordnernamen (oder "" wenn nichts übrig bleibt).
    public static func cleanName(_ input: String) -> String {
        let cleaned = forbidden.replace(input, with: "_").trimmed.trimming(["."]).trimmed
        return String(cleaned.prefix(80))
    }
}
