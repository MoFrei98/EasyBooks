import Foundation
import SQLite3

/// Lokale SQLite-Datenbank der Bibliothek (`ebf.db` in Application Support), gleiche Felder wie
/// in der Android-App. Nutzt das in iOS eingebaute SQLite, keine zusätzliche Bibliothek.
/// Die PDFs selbst liegen nicht in der Datenbank, sondern als Dateien im Bibliotheksordner.
final class BookDatabase {
    private var db: OpaquePointer?
    private static let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)
    private static let version: Int32 = 1

    init(url: URL) {
        if sqlite3_open_v2(url.path, &db, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil) != SQLITE_OK {
            db = nil
        }
        migrate()
    }

    deinit { sqlite3_close(db) }

    private func migrate() {
        var current: Int32 = 0
        query("PRAGMA user_version") { current = sqlite3_column_int($0, 0) }
        if current < 1 {
            exec("""
            CREATE TABLE IF NOT EXISTS books (
                uuid             TEXT PRIMARY KEY NOT NULL,
                title            TEXT NOT NULL,
                author_firstname TEXT,
                author_lastname  TEXT,
                publish_year     INTEGER,
                page_count       INTEGER NOT NULL DEFAULT 0,
                pdf_location     TEXT,
                pdf_path         TEXT NOT NULL UNIQUE,
                file_name        TEXT,
                file_size        INTEGER NOT NULL DEFAULT -1,
                sha256           TEXT,
                folder           TEXT,
                last_page        INTEGER NOT NULL DEFAULT 0,
                last_page_offset REAL NOT NULL DEFAULT 0,
                details_edited   INTEGER NOT NULL DEFAULT 0,
                added_at         INTEGER NOT NULL,
                last_opened_at   INTEGER NOT NULL,
                search_text      TEXT NOT NULL DEFAULT ''
            )
            """)
            exec("CREATE INDEX IF NOT EXISTS books_folder ON books(folder)")
            exec("CREATE TABLE IF NOT EXISTS folders (path TEXT PRIMARY KEY NOT NULL)")
            exec("PRAGMA user_version = \(BookDatabase.version)")
        }
    }

    // MARK: Bücher

    func allBooks() -> [Book] {
        var books: [Book] = []
        query("""
        SELECT uuid, title, author_firstname, author_lastname, publish_year, page_count, pdf_path, file_size,
               sha256, last_page, last_page_offset, details_edited, added_at, last_opened_at FROM books
        """) { s in
            books.append(Book(
                uuid: BookDatabase.text(s, 0) ?? UUID().uuidString,
                title: BookDatabase.text(s, 1) ?? "",
                authorFirstName: BookDatabase.text(s, 2),
                authorLastName: BookDatabase.text(s, 3),
                publishYear: sqlite3_column_type(s, 4) == SQLITE_NULL ? nil : Int(sqlite3_column_int64(s, 4)),
                pageCount: Int(sqlite3_column_int64(s, 5)),
                path: BookDatabase.text(s, 6) ?? "",
                fileSize: sqlite3_column_int64(s, 7),
                sha256: BookDatabase.text(s, 8),
                lastPage: Int(sqlite3_column_int64(s, 9)),
                lastPageOffset: Float(sqlite3_column_double(s, 10)),
                detailsEdited: sqlite3_column_int(s, 11) == 1,
                addedAt: Date(timeIntervalSince1970: Double(sqlite3_column_int64(s, 12)) / 1000),
                lastOpenedAt: Date(timeIntervalSince1970: Double(sqlite3_column_int64(s, 13)) / 1000)
            ))
        }
        return books
    }

    /// Fügt ein Buch ein oder ersetzt es (gleiche uuid). [location] ist der absolute Pfad zur Anzeige.
    func upsert(_ b: Book, location: String) {
        run("""
        INSERT OR REPLACE INTO books (uuid, title, author_firstname, author_lastname, publish_year, page_count,
            pdf_location, pdf_path, file_name, file_size, sha256, folder, last_page, last_page_offset,
            details_edited, added_at, last_opened_at, search_text)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, [
            b.uuid, b.title, b.authorFirstName, b.authorLastName, b.publishYear.map { Int64($0) }, Int64(b.pageCount),
            location, b.path, b.fileName, b.fileSize, b.sha256, b.folder, Int64(b.lastPage), Double(b.lastPageOffset),
            Int64(b.detailsEdited ? 1 : 0), Int64(b.addedAt.timeIntervalSince1970 * 1000),
            Int64(b.lastOpenedAt.timeIntervalSince1970 * 1000), b.searchText,
        ])
    }

    func delete(_ uuid: String) {
        run("DELETE FROM books WHERE uuid = ?", [uuid])
    }

    // MARK: Ordner

    func folders() -> [String] {
        var result: [String] = []
        query("SELECT path FROM folders ORDER BY path") { s in
            if let p = BookDatabase.text(s, 0) { result.append(p) }
        }
        return result
    }

    func setFolders(_ paths: [String]) {
        exec("BEGIN")
        run("DELETE FROM folders", [])
        for p in Set(paths) where !p.isEmpty { run("INSERT INTO folders (path) VALUES (?)", [p]) }
        exec("COMMIT")
    }

    // MARK: Hilfen

    private func exec(_ sql: String) {
        sqlite3_exec(db, sql, nil, nil, nil)
    }

    private func run(_ sql: String, _ args: [Any?]) {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return }
        defer { sqlite3_finalize(stmt) }
        bind(stmt, args)
        sqlite3_step(stmt)
    }

    private func query(_ sql: String, _ args: [Any?] = [], row: (OpaquePointer) -> Void) {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { return }
        defer { sqlite3_finalize(stmt) }
        bind(stmt, args)
        while sqlite3_step(stmt) == SQLITE_ROW { row(stmt) }
    }

    private func bind(_ stmt: OpaquePointer?, _ args: [Any?]) {
        for (i, arg) in args.enumerated() {
            let index = Int32(i + 1)
            switch arg {
            case let v as String: sqlite3_bind_text(stmt, index, v, -1, BookDatabase.transient)
            case let v as Int64: sqlite3_bind_int64(stmt, index, v)
            case let v as Int: sqlite3_bind_int64(stmt, index, Int64(v))
            case let v as Double: sqlite3_bind_double(stmt, index, v)
            default: sqlite3_bind_null(stmt, index)
            }
        }
    }

    private static func text(_ stmt: OpaquePointer, _ column: Int32) -> String? {
        guard sqlite3_column_type(stmt, column) != SQLITE_NULL, let c = sqlite3_column_text(stmt, column) else { return nil }
        return String(cString: c)
    }
}
