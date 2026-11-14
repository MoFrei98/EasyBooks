import CryptoKit
import EasyBooksCore
import Foundation
import PDFKit
import UIKit

/// Die Bibliothek: Bücher und Ordner im Bibliotheksordner, gespeichert in der lokalen Datenbank.
///
/// Der Bibliotheksordner ist standardmäßig der Dokumente-Ordner der App. Er erscheint in der
/// Dateien-App unter "Auf meinem iPhone › Easy Books"; Unterordner sind echte Ordner. Optional kann ein
/// anderer Ordner gewählt werden (z. B. in iCloud Drive).
@MainActor
final class Library: ObservableObject {
    @Published private(set) var books: [Book] = []
    @Published private(set) var folders: [String] = []
    @Published private(set) var syncing = false
    /// Kurze Meldung für den Nutzer (Fehler oder Info).
    @Published var notice: String?
    /// Buch, das im Reader geöffnet werden soll.
    @Published var openBookID: String?

    private let db: BookDatabase
    private(set) var root: URL
    private(set) var customRoot: Bool
    private let support: URL

    init() {
        let fm = FileManager.default
        support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? fm.createDirectory(at: support, withIntermediateDirectories: true)
        // Alter Dateiname aus der Zeit als "EBF"; bleibt, damit bestehende Bibliotheken erhalten bleiben.
        db = BookDatabase(url: support.appendingPathComponent("ebf.db"))
        (root, customRoot) = Library.resolveRoot()
        for dir in ["covers", "reflow"] {
            var url = support.appendingPathComponent(dir, isDirectory: true)
            try? fm.createDirectory(at: url, withIntermediateDirectories: true)
            // Cover und Analysen lassen sich jederzeit neu erzeugen: nicht ins Backup.
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? url.setResourceValues(values)
        }
        reload()
    }

    // MARK: Bibliotheksordner

    static var documents: URL { FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0] }

    var rootName: String { customRoot ? root.lastPathComponent : "Auf meinem iPhone › Easy Books" }

    private static func resolveRoot() -> (URL, Bool) {
        if let data = UserDefaults.standard.data(forKey: "libraryBookmark") {
            var stale = false
            if let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale),
               url.startAccessingSecurityScopedResource() {
                return (url, true)
            }
        }
        return (documents, false)
    }

    /// Wählt einen anderen Bibliotheksordner (aus der Ordnerauswahl).
    func setRoot(_ url: URL) async {
        guard url.startAccessingSecurityScopedResource() else {
            notice = "Auf diesen Ordner darf Easy Books nicht zugreifen."
            return
        }
        do {
            let data = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
            UserDefaults.standard.set(data, forKey: "libraryBookmark")
            if customRoot { root.stopAccessingSecurityScopedResource() }
            root = url
            customRoot = true
            await sync()
        } catch {
            url.stopAccessingSecurityScopedResource()
            notice = "Der Ordner konnte nicht gespeichert werden."
        }
    }

    /// Zurück zum Ordner "Auf meinem iPhone › Easy Books".
    func resetRoot() async {
        UserDefaults.standard.removeObject(forKey: "libraryBookmark")
        if customRoot { root.stopAccessingSecurityScopedResource() }
        root = Library.documents
        customRoot = false
        await sync()
    }

    func url(for book: Book) -> URL { root.appendingPathComponent(book.path) }
    func folderURL(_ path: String) -> URL { path.isEmpty ? root : root.appendingPathComponent(path, isDirectory: true) }
    func coverURL(for book: Book) -> URL { support.appendingPathComponent("covers/\(book.uuid).jpg") }
    func reflowCacheURL(for book: Book) -> URL { support.appendingPathComponent("reflow/\(book.uuid).bin") }

    func book(_ id: String) -> Book? { books.first { $0.uuid == id } }

    private func reload() {
        books = db.allBooks()
        folders = FolderPaths.treeOrder(db.folders())
    }

    private func save(_ book: Book) {
        db.upsert(book, location: url(for: book).path)
    }

    private func deleteCaches(_ book: Book) {
        try? FileManager.default.removeItem(at: coverURL(for: book))
        try? FileManager.default.removeItem(at: reflowCacheURL(for: book))
    }

    // MARK: Abgleich mit dem Ordner

    private struct ScannedFile: Sendable {
        let path: String
        let size: Int64
    }

    /// Liest den Bibliotheksordner und gleicht die Datenbank ab: neue PDFs kommen dazu,
    /// verschobene behalten ihren Lesestand, gelöschte verschwinden.
    func sync() async {
        if syncing { return }
        syncing = true
        defer { syncing = false }
        let root = self.root
        let customRoot = self.customRoot
        let (files, folders) = await Task.detached(priority: .userInitiated) {
            Library.scan(root: root, collectInbox: !customRoot)
        }.value

        var existing = Dictionary(db.allBooks().map { ($0.path, $0) }, uniquingKeysWith: { a, _ in a })
        let scannedPaths = Set(files.map(\.path))
        var missing = existing.values.filter { !scannedPaths.contains($0.path) }
        for file in files {
            if var book = existing[file.path] {
                if book.fileSize != file.size {
                    book.fileSize = file.size
                    book.sha256 = nil
                    save(book)
                }
                continue
            }
            let name = FolderPaths.name(file.path)
            if let i = missing.firstIndex(where: { $0.fileName == name && $0.fileSize == file.size }) {
                // Außerhalb der App verschoben oder umbenannt: Lesestand behalten.
                var book = missing.remove(at: i)
                existing.removeValue(forKey: book.path)
                book.path = file.path
                save(book)
                continue
            }
            let meta = BookMetadataParser.fromFileName(name)
            save(Book(
                title: meta.title ?? Book.titleFromFileName(name),
                authorFirstName: meta.firstName, authorLastName: meta.lastName, publishYear: meta.year,
                path: file.path, fileSize: file.size
            ))
        }
        for book in missing {
            db.delete(book.uuid)
            deleteCaches(book)
        }
        db.setFolders(folders)
        reload()
        await index()
    }

    /// Ordner und PDFs im Bibliotheksordner. Dateien aus "Inbox" (von anderen Apps geteilt)
    /// werden in die oberste Ebene verschoben.
    nonisolated private static func scan(root: URL, collectInbox: Bool) -> ([ScannedFile], [String]) {
        let fm = FileManager.default
        if collectInbox {
            let inbox = root.appendingPathComponent("Inbox", isDirectory: true)
            if let items = try? fm.contentsOfDirectory(at: inbox, includingPropertiesForKeys: nil) {
                for item in items where item.pathExtension.lowercased() == "pdf" {
                    try? fm.moveItem(at: item, to: uniqueURL(in: root, name: item.lastPathComponent))
                }
            }
        }
        var files: [ScannedFile] = []
        var folders: [String] = []
        let keys: [URLResourceKey] = [.isDirectoryKey, .fileSizeKey]
        guard let enumerator = fm.enumerator(at: root, includingPropertiesForKeys: keys,
                                             options: [.skipsHiddenFiles, .skipsPackageDescendants]) else {
            return ([], [])
        }
        let rootPath = root.standardizedFileURL.path
        for case let url as URL in enumerator {
            let full = url.standardizedFileURL.path
            guard full.hasPrefix(rootPath) else { continue }
            var relative = String(full.dropFirst(rootPath.count))
            while relative.hasPrefix("/") { relative.removeFirst() }
            if relative.isEmpty { continue }
            let values = try? url.resourceValues(forKeys: Set(keys))
            if values?.isDirectory == true {
                if collectInbox && relative == "Inbox" {
                    enumerator.skipDescendants()
                    continue
                }
                folders.append(relative)
            } else if url.pathExtension.lowercased() == "pdf" {
                files.append(ScannedFile(path: relative, size: Int64(values?.fileSize ?? -1)))
            }
        }
        return (files, folders)
    }

    /// Seitenzahl, Prüfsumme, Buchdetails und Cover für neue oder geänderte Bücher.
    private func index() async {
        let fm = FileManager.default
        let pending = books.filter { $0.pageCount == 0 || $0.sha256 == nil || !fm.fileExists(atPath: coverURL(for: $0).path) }
        for book in pending {
            let url = url(for: book)
            let cover = coverURL(for: book)
            let readDetails = !book.detailsEdited
            let info = await Task.detached(priority: .utility) {
                Library.inspect(url: url, cover: cover, readDetails: readDetails)
            }.value
            guard let info, var current = self.book(book.uuid) else { continue }
            current.pageCount = info.pageCount
            current.sha256 = info.sha256
            if let meta = info.metadata, !current.detailsEdited {
                current.title = meta.title ?? current.title
                current.authorFirstName = meta.firstName
                current.authorLastName = meta.lastName
                current.publishYear = meta.year
            }
            save(current)
            reload()
        }
    }

    private struct Inspection: Sendable {
        let pageCount: Int
        let sha256: String?
        let metadata: BookMetadata?
    }

    nonisolated private static func inspect(url: URL, cover: URL, readDetails: Bool) -> Inspection? {
        guard let document = PDFDocument(url: url) else { return nil }
        if !FileManager.default.fileExists(atPath: cover.path), let page = document.page(at: 0) {
            let image = page.thumbnail(of: CGSize(width: 360, height: 540), for: .cropBox)
            try? image.jpegData(compressionQuality: 0.85)?.write(to: cover)
        }
        var metadata: BookMetadata?
        if readDetails {
            let name = url.lastPathComponent
            let attributes = document.documentAttributes ?? [:]
            let fromKit = BookMetadataParser.fromAttributes(
                title: attributes[PDFDocumentAttribute.titleAttribute] as? String,
                author: attributes[PDFDocumentAttribute.authorAttribute] as? String
            )
            metadata = BookMetadataParser.fromPdf(rawChunks(url)).orElse(fromKit).orElse(BookMetadataParser.fromFileName(name))
        }
        return Inspection(pageCount: document.pageCount, sha256: sha256(url), metadata: metadata)
    }

    /// Anfang und Ende der Datei; dort stehen XMP-Daten und das Info-Dictionary üblicherweise.
    nonisolated private static func rawChunks(_ url: URL) -> [Data] {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return [] }
        defer { try? handle.close() }
        let size = (try? handle.seekToEnd()) ?? 0
        let chunk: UInt64 = 256 * 1024
        try? handle.seek(toOffset: size > chunk ? size - chunk : 0)
        let tail = (try? handle.readToEnd()) ?? Data()
        try? handle.seek(toOffset: 0)
        let head = (try? handle.read(upToCount: Int(chunk))) ?? Data()
        return [tail, head]
    }

    nonisolated private static func sha256(_ url: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        var hasher = SHA256()
        while let data = try? handle.read(upToCount: 1 << 20), !data.isEmpty {
            hasher.update(data: data)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    nonisolated static func uniqueURL(in folder: URL, name: String) -> URL {
        let fm = FileManager.default
        var url = folder.appendingPathComponent(name)
        let base = (name as NSString).deletingPathExtension
        let ext = (name as NSString).pathExtension
        var n = 2
        while fm.fileExists(atPath: url.path) {
            url = folder.appendingPathComponent("\(base) (\(n))" + (ext.isEmpty ? "" : ".\(ext)"))
            n += 1
        }
        return url
    }

    // MARK: Import

    /// Kopiert PDFs in den Ordner [folder] der Bibliothek. Liefert das erste importierte Buch.
    @discardableResult
    func importFiles(_ urls: [URL], into folder: String) async -> Book? {
        let target = folderURL(folder)
        var imported: [String] = []
        for source in urls {
            let access = source.startAccessingSecurityScopedResource()
            defer { if access { source.stopAccessingSecurityScopedResource() } }
            // Liegt die Datei schon in der Bibliothek, nur öffnen.
            if let existing = bookPath(of: source) {
                imported.append(existing)
                continue
            }
            let destination = Library.uniqueURL(in: target, name: source.lastPathComponent)
            do {
                if source.path.contains("/Inbox/") {
                    try FileManager.default.moveItem(at: source, to: destination)
                } else {
                    try FileManager.default.copyItem(at: source, to: destination)
                }
                imported.append(FolderPaths.child(folder, destination.lastPathComponent))
            } catch {
                notice = "„\(source.lastPathComponent)“ konnte nicht importiert werden."
            }
        }
        await sync()
        return imported.first.flatMap { path in books.first { $0.path == path } }
    }

    /// Pfad relativ zur Bibliothek, falls die Datei schon darin liegt.
    private func bookPath(of url: URL) -> String? {
        let rootPath = root.standardizedFileURL.path + "/"
        let full = url.standardizedFileURL.path
        guard full.hasPrefix(rootPath) else { return nil }
        let relative = String(full.dropFirst(rootPath.count))
        return relative.hasPrefix("Inbox/") ? nil : relative
    }

    /// "Öffnen mit Easy Books" aus einer anderen App.
    func openExternal(_ url: URL) async {
        if let book = await importFiles([url], into: FolderPaths.root) {
            openBookID = book.uuid
        }
    }

    // MARK: Bücher bearbeiten

    func updateDetails(_ book: Book, title: String, firstName: String, lastName: String, year: String) {
        guard var b = self.book(book.uuid) else { return }
        let clean = { (s: String) -> String? in
            let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
            return t.isEmpty ? nil : t
        }
        b.title = clean(title) ?? b.title
        b.authorFirstName = clean(firstName)
        b.authorLastName = clean(lastName)
        b.publishYear = Int(year.trimmingCharacters(in: .whitespaces))
        b.detailsEdited = true
        save(b)
        reload()
    }

    func updateProgress(_ id: String, page: Int, offset: Float) {
        guard var b = book(id) else { return }
        b.lastPage = page
        b.lastPageOffset = offset
        b.lastOpenedAt = Date()
        save(b)
        if let i = books.firstIndex(where: { $0.uuid == id }) { books[i] = b }
    }

    func markOpened(_ id: String, pageCount: Int) {
        guard var b = book(id) else { return }
        b.lastOpenedAt = Date()
        if pageCount > 0 { b.pageCount = pageCount }
        save(b)
        if let i = books.firstIndex(where: { $0.uuid == id }) { books[i] = b }
    }

    func move(_ book: Book, to folder: String) {
        guard folder != book.folder else { return }
        let destination = Library.uniqueURL(in: folderURL(folder), name: book.fileName)
        do {
            try FileManager.default.moveItem(at: url(for: book), to: destination)
            var b = book
            b.path = FolderPaths.child(folder, destination.lastPathComponent)
            save(b)
            reload()
            notice = "Verschoben nach „\(folder.isEmpty ? "Bibliothek" : FolderPaths.name(folder))“"
        } catch {
            notice = "Die PDF konnte nicht in den Ordner gelegt werden."
        }
    }

    func delete(_ book: Book) {
        do {
            try FileManager.default.removeItem(at: url(for: book))
        } catch {
            if FileManager.default.fileExists(atPath: url(for: book).path) {
                notice = "Löschen ist fehlgeschlagen."
                return
            }
        }
        db.delete(book.uuid)
        deleteCaches(book)
        reload()
    }

    // MARK: Ordner

    func createFolder(_ name: String, in parent: String) -> String? {
        let clean = FolderPaths.cleanName(name)
        if clean.isEmpty {
            notice = "Bitte einen gültigen Namen eingeben."
            return nil
        }
        let path = FolderPaths.child(parent, clean)
        if folders.contains(path) || FileManager.default.fileExists(atPath: folderURL(path).path) {
            notice = "Einen Ordner mit diesem Namen gibt es hier schon."
            return nil
        }
        do {
            try FileManager.default.createDirectory(at: folderURL(path), withIntermediateDirectories: false)
            db.setFolders(folders + [path])
            reload()
            return path
        } catch {
            notice = "Der Ordner konnte nicht angelegt werden."
            return nil
        }
    }

    func renameFolder(_ path: String, to name: String) {
        let clean = FolderPaths.cleanName(name)
        if clean.isEmpty {
            notice = "Bitte einen gültigen Namen eingeben."
            return
        }
        let newPath = FolderPaths.child(FolderPaths.parent(path), clean)
        if newPath == path { return }
        if FileManager.default.fileExists(atPath: folderURL(newPath).path) {
            notice = "Einen Ordner mit diesem Namen gibt es hier schon."
            return
        }
        do {
            try FileManager.default.moveItem(at: folderURL(path), to: folderURL(newPath))
            for var b in books where FolderPaths.isInside(b.folder, path) {
                b.path = newPath + String(b.path.dropFirst(path.count))
                save(b)
            }
            db.setFolders(folders.map { FolderPaths.isInside($0, path) ? newPath + String($0.dropFirst(path.count)) : $0 })
            reload()
        } catch {
            notice = "Der Ordner konnte nicht umbenannt werden."
        }
    }

    /// Löscht einen leeren Ordner.
    func deleteFolder(_ path: String) {
        let url = folderURL(path)
        let content = (try? FileManager.default.contentsOfDirectory(atPath: url.path))?.filter { !$0.hasPrefix(".") } ?? []
        if !content.isEmpty {
            notice = "Der Ordner ist nicht leer. Verschiebe oder lösche zuerst seinen Inhalt."
            return
        }
        do {
            try FileManager.default.removeItem(at: url)
            db.setFolders(folders.filter { $0 != path })
            reload()
        } catch {
            notice = "Löschen ist fehlgeschlagen."
        }
    }
}
