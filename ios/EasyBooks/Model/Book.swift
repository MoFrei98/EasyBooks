import Foundation
import EasyBooksCore

/// Ein Buch der Bibliothek. Die PDF liegt im Bibliotheksordner unter [path].
struct Book: Identifiable, Equatable {
    /// Eindeutige ID. Ändert sich nie, auch nicht beim Verschieben oder Umbenennen der Datei.
    var uuid: String = UUID().uuidString
    var title: String
    var authorFirstName: String?
    var authorLastName: String?
    var publishYear: Int?
    var pageCount: Int = 0
    /// Pfad relativ zum Bibliotheksordner, z. B. "Meer/Abendrot.pdf".
    var path: String
    var fileSize: Int64 = -1
    /// SHA-256 des Dateiinhalts.
    var sha256: String?
    /// Zuletzt gelesene Seite (0-basiert) und Stelle darin (0...1, für "Groß").
    var lastPage: Int = 0
    var lastPageOffset: Float = 0
    /// Titel/Autor/Jahr wurden von Hand bearbeitet und werden nicht mehr automatisch überschrieben.
    var detailsEdited = false
    var addedAt = Date()
    var lastOpenedAt = Date()

    var id: String { uuid }

    var fileName: String { FolderPaths.name(path) }

    /// Ordner im Bibliotheksordner ("" = oberste Ebene).
    var folder: String { FolderPaths.parent(path) }

    /// Autor zur Anzeige, z. B. "Dan Abnett".
    var author: String? {
        let name = [authorFirstName, authorLastName].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " ")
        return name.isEmpty ? nil : name
    }

    /// Normalisierter Text für die Suche.
    var searchText: String {
        SearchText.normalize([title, fileName, authorFirstName, authorLastName, publishYear.map(String.init)]
            .compactMap { $0 }.joined(separator: " "))
    }

    var readState: ReadState {
        if pageCount > 1 && lastPage >= pageCount - 1 { return .finished }
        if lastPage <= 0 { return .unread }
        return .started
    }

    /// Lesefortschritt von 0 bis 1.
    var progress: Double { pageCount <= 0 ? 0 : Double(lastPage + 1) / Double(pageCount) }

    static func titleFromFileName(_ name: String) -> String {
        let base = name.lowercased().hasSuffix(".pdf") ? String(name.dropLast(4)) : name
        let title = base.replacingOccurrences(of: "_", with: " ").trimmingCharacters(in: .whitespaces)
        return title.isEmpty ? name : title
    }
}

enum ReadState { case unread, started, finished }
