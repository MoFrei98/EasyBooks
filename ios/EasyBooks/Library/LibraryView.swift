import EasyBooksCore
import SwiftUI
import UniformTypeIdentifiers

/// Startansicht: Bibliothek mit Ordnern; Bücher öffnen sich im Vollbild-Reader.
struct RootView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var settings: AppSettings
    @State private var showSettings = UserDefaults.standard.bool(forKey: "demoSettings")

    private struct OpenBook: Identifiable {
        let book: Book
        var id: String { book.uuid }
    }

    var body: some View {
        NavigationStack {
            FolderView(folder: FolderPaths.root, showSettings: $showSettings)
                .navigationDestination(for: String.self) { path in
                    FolderView(folder: path, showSettings: $showSettings)
                }
        }
        .fullScreenCover(item: Binding<OpenBook?>(
            get: { library.openBookID.flatMap { library.book($0) }.map(OpenBook.init) },
            set: { library.openBookID = $0?.book.uuid }
        )) { open in
            ReaderView(book: open.book, library: library)
                .environmentObject(library)
                .environmentObject(settings)
                .preferredColorScheme(settings.theme.colorScheme)
        }
        .sheet(isPresented: $showSettings) { SettingsView() }
        .overlay(alignment: .bottom) { NoticeView() }
        .task {
            await library.sync()
            // Für Screenshots: Buch direkt öffnen ("-demoOpen Titel").
            if let title = UserDefaults.standard.string(forKey: "demoOpen") {
                library.openBookID = library.books.first { $0.title.localizedCaseInsensitiveContains(title) }?.uuid
            }
        }
    }
}

/// Kurze Meldung am unteren Rand.
private struct NoticeView: View {
    @EnvironmentObject private var library: Library

    var body: some View {
        if let notice = library.notice {
            Text(notice)
                .font(.subheadline)
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(.thinMaterial, in: Capsule())
                .padding(.bottom, 90)
                .padding(.horizontal, 24)
                .transition(.opacity)
                .task(id: notice) {
                    try? await Task.sleep(nanoseconds: 3_000_000_000)
                    library.notice = nil
                }
        }
    }
}

/// Inhalt eines Ordners (oder der obersten Ebene): Unterordner und Bücher als Kacheln.
struct FolderView: View {
    let folder: String
    @Binding var showSettings: Bool

    @EnvironmentObject private var library: Library
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.colorScheme) private var colorScheme

    @State private var query = ""
    @State private var importing = false
    @State private var newFolderName = ""
    @State private var askNewFolder = false
    @State private var renaming: String?
    @State private var renameText = ""
    @State private var deletingFolder: String?
    @State private var deletingBook: Book?
    @State private var editing: Book?
    @State private var moving: Book?

    private let columns = [GridItem(.adaptive(minimum: 150, maximum: 220), spacing: 16)]

    private var searching: Bool { !query.trimmingCharacters(in: .whitespaces).isEmpty }

    private func sorted(_ books: [Book]) -> [Book] {
        switch settings.sort {
        case .recent: books.sorted { $0.lastOpenedAt > $1.lastOpenedAt }
        case .title: books.sorted { $0.title.localizedCompare($1.title) == .orderedAscending }
        }
    }

    private var subfolders: [String] { FolderPaths.subfolders(library.folders, of: folder) }

    private var books: [Book] {
        sorted(library.books.filter { $0.folder == folder && settings.filter.accepts($0) })
    }

    private var results: [Book] {
        sorted(library.books.filter { SearchText.matches(query, [$0.title, $0.fileName, $0.authorFirstName, $0.authorLastName, $0.publishYear.map(String.init)]) })
    }

    private var folderResults: [String] {
        library.folders.filter { SearchText.matches(query, [FolderPaths.name($0)]) }
    }

    var body: some View {
        ScrollView {
            if searching {
                searchResults
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    FilterChips(filter: $settings.filter)
                    grid
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 100)
            }
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle(folder.isEmpty ? "Bibliothek" : FolderPaths.name(folder))
        .searchable(text: $query, prompt: "Bücher und Ordner suchen")
        .refreshable { await library.sync() }
        .toolbar { toolbar }
        .overlay(alignment: .bottomTrailing) { addButton }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.pdf], allowsMultipleSelection: true) { result in
            if case let .success(urls) = result {
                Task {
                    if let book = await library.importFiles(urls, into: folder), urls.count == 1 {
                        library.openBookID = book.uuid
                    }
                }
            }
        }
        .alert("Neuer Ordner", isPresented: $askNewFolder) {
            TextField("Name", text: $newFolderName)
            Button("Anlegen") { _ = library.createFolder(newFolderName, in: folder) }
            Button("Abbrechen", role: .cancel) {}
        }
        .alert("Ordner umbenennen", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Name", text: $renameText)
            Button("Speichern") { if let path = renaming { library.renameFolder(path, to: renameText) } }
            Button("Abbrechen", role: .cancel) {}
        }
        .alert("Ordner löschen?", isPresented: Binding(get: { deletingFolder != nil }, set: { if !$0 { deletingFolder = nil } })) {
            Button("Löschen", role: .destructive) { if let path = deletingFolder { library.deleteFolder(path) } }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("Der Ordner „\(FolderPaths.name(deletingFolder ?? ""))“ wird vom Gerät gelöscht. Das geht nur, wenn er leer ist.")
        }
        .alert("PDF löschen?", isPresented: Binding(get: { deletingBook != nil }, set: { if !$0 { deletingBook = nil } })) {
            Button("Löschen", role: .destructive) { if let book = deletingBook { library.delete(book) } }
            Button("Abbrechen", role: .cancel) {}
        } message: {
            Text("„\(deletingBook?.title ?? "")“ liegt im Bibliotheksordner und wird vom Gerät gelöscht. Das lässt sich nicht rückgängig machen.")
        }
        .sheet(item: $editing) { book in BookDetailsSheet(book: book) }
        .sheet(item: $moving) { book in MoveBookSheet(book: book) }
    }

    // MARK: Inhalt

    @ViewBuilder
    private var grid: some View {
        if subfolders.isEmpty && books.isEmpty {
            EmptyStateView(
                title: emptyTitle,
                text: emptyText,
                actionTitle: settings.filter == .all ? "PDF hinzufügen" : "Filter zurücksetzen"
            ) {
                if settings.filter == .all { importing = true } else { settings.filter = .all }
            }
        } else {
            LazyVGrid(columns: columns, alignment: .leading, spacing: 20) {
                ForEach(subfolders, id: \.self) { path in
                    NavigationLink(value: path) {
                        FolderCard(path: path)
                    }
                    .buttonStyle(.plain)
                    .contextMenu { folderMenu(path) }
                }
                ForEach(books) { book in
                    BookCard(book: book, location: nil, menu: { bookMenu(book) })
                        .onTapGesture { library.openBookID = book.uuid }
                        .contextMenu { bookMenu(book) }
                }
            }
        }
    }

    private var emptyTitle: String {
        if settings.filter != .all { return "Keine Bücher für diesen Filter" }
        if folder.isEmpty && library.books.isEmpty { return "Deine Bibliothek ist leer" }
        return "Dieser Ordner ist leer"
    }

    private var emptyText: String {
        if settings.filter != .all { return "" }
        if folder.isEmpty && library.books.isEmpty {
            return "Füge eine PDF hinzu, um mit dem Lesen zu beginnen. PDFs aus anderen Apps öffnest du über „Teilen → Easy Books“. In der Dateien-App findest du die Bibliothek unter „Auf meinem iPhone › Easy Books“."
        }
        return "Füge eine PDF hinzu oder verschiebe Bücher über ⋯ → In Ordner verschieben hierher."
    }

    private var searchResults: some View {
        VStack(alignment: .leading, spacing: 16) {
            if results.isEmpty && folderResults.isEmpty {
                Text("Keine Bücher gefunden für „\(query)“")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 60)
            }
            LazyVGrid(columns: columns, alignment: .leading, spacing: 20) {
                ForEach(folderResults, id: \.self) { path in
                    NavigationLink(value: path) { FolderCard(path: path) }.buttonStyle(.plain)
                }
                ForEach(results) { book in
                    BookCard(book: book, location: book.folder.isEmpty ? "Bibliothek" : book.folder, menu: { bookMenu(book) })
                        .onTapGesture { library.openBookID = book.uuid }
                        .contextMenu { bookMenu(book) }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 100)
    }

    // MARK: Menüs

    @ViewBuilder
    private func bookMenu(_ book: Book) -> some View {
        Button { editing = book } label: { Label("Details bearbeiten", systemImage: "pencil") }
        Button { moving = book } label: { Label("In Ordner verschieben", systemImage: "folder") }
        Button(role: .destructive) { deletingBook = book } label: { Label("Löschen", systemImage: "trash") }
    }

    @ViewBuilder
    private func folderMenu(_ path: String) -> some View {
        Button {
            renameText = FolderPaths.name(path)
            renaming = path
        } label: { Label("Umbenennen", systemImage: "pencil") }
        Button(role: .destructive) { deletingFolder = path } label: { Label("Löschen", systemImage: "trash") }
    }

    @ToolbarContentBuilder
    private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            Button {
                settings.theme = colorScheme == .dark ? .light : .dark
            } label: {
                Image(systemName: colorScheme == .dark ? "sun.max" : "moon")
            }
            .accessibilityLabel(colorScheme == .dark ? "Helles Design" : "Dunkles Design")
            Button { showSettings = true } label: { Image(systemName: "gearshape") }
                .accessibilityLabel("Einstellungen")
            Menu {
                Button {
                    newFolderName = ""
                    askNewFolder = true
                } label: { Label("Neuer Ordner", systemImage: "folder.badge.plus") }
                Picker("Sortieren nach", selection: $settings.sort) {
                    ForEach(SortOrder.allCases) { Text($0.label).tag($0) }
                }
                Button {
                    Task { await library.sync() }
                } label: { Label("Aktualisieren", systemImage: "arrow.clockwise") }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .accessibilityLabel("Weitere Optionen")
        }
    }

    private var addButton: some View {
        Button { importing = true } label: {
            Label("PDF hinzufügen", systemImage: "plus")
                .font(.headline)
                .padding(.horizontal, 20)
                .padding(.vertical, 14)
                .foregroundStyle(colorScheme == .dark ? Color(UIColor(hex: 0x452B10)) : .white)
                .background(Theme.accent, in: RoundedRectangle(cornerRadius: 16))
                .shadow(radius: 3, y: 1)
        }
        .padding(20)
    }
}

/// Filter nach Lesestand.
private struct FilterChips: View {
    @Binding var filter: BookFilter

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(BookFilter.allCases) { f in
                    Button { filter = f } label: {
                        HStack(spacing: 6) {
                            if filter == f { Image(systemName: "checkmark").font(.caption.weight(.bold)) }
                            Text(f.label)
                        }
                        .font(.subheadline.weight(.medium))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(filter == f ? Theme.chip : Color.clear, in: RoundedRectangle(cornerRadius: 8))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(filter == f ? Color.clear : Theme.outline))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, 4)
        }
    }
}

/// Leerer Ordner, leere Bibliothek oder kein Treffer für den Filter.
private struct EmptyStateView: View {
    let title: String
    let text: String
    let actionTitle: String
    let action: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "books.vertical")
                .font(.system(size: 48))
                .foregroundStyle(Theme.accent)
            Text(title).font(.title3.weight(.semibold))
            if !text.isEmpty {
                Text(text).multilineTextAlignment(.center).foregroundStyle(Theme.secondaryText)
            }
            Button(actionTitle, action: action).buttonStyle(.borderedProminent).padding(.top, 8)
        }
        .padding(32)
        .frame(maxWidth: .infinity)
        .padding(.top, 40)
    }
}

/// Cover eines Buchs (wird im Hintergrund geladen).
struct CoverImage: View {
    let url: URL
    let version: Int
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Rectangle().fill(Theme.card)
                Image(systemName: "book.closed").font(.largeTitle).foregroundStyle(Theme.secondaryText)
            }
        }
        .task(id: "\(url.path)-\(version)") {
            let url = self.url
            image = await Task.detached { UIImage(contentsOfFile: url.path) }.value
        }
    }
}

/// Kachel eines Buchs: Cover, Titel, Autor und Jahr, Fortschritt.
private struct BookCard<Menu: View>: View {
    let book: Book
    /// Ordner, in dem das Buch liegt (nur in den Suchergebnissen).
    let location: String?
    @ViewBuilder let menu: () -> Menu
    @EnvironmentObject private var library: Library

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            CoverImage(url: library.coverURL(for: book), version: book.pageCount + (book.sha256 == nil ? 0 : 1))
                .aspectRatio(0.7, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .shadow(color: .black.opacity(0.2), radius: 3, y: 1)
                .accessibilityLabel("Cover von \(book.title)")
            HStack(alignment: .top) {
                Text(book.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(2, reservesSpace: true)
                Spacer(minLength: 4)
                SwiftUI.Menu { menu() } label: {
                    Image(systemName: "ellipsis")
                        .frame(width: 28, height: 28)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("Optionen")
            }
            // Autor und Jahr; immer eine Zeile, damit die Kacheln gleich hoch bleiben.
            Text([book.author, book.publishYear.map(String.init)].compactMap { $0 }.joined(separator: " · ").ifEmpty(" "))
                .font(.caption)
                .foregroundStyle(Theme.secondaryText)
                .lineLimit(1)
            if let location {
                Label(location, systemImage: "folder").font(.caption2).foregroundStyle(Theme.secondaryText).lineLimit(1)
            }
            if book.pageCount > 0 {
                ProgressView(value: book.progress).tint(Theme.accent)
                Text(book.readState == .unread ? "Neu · \(book.pageCount) Seiten" : "Seite \(book.lastPage + 1) von \(book.pageCount)")
                    .font(.caption2)
                    .foregroundStyle(Theme.secondaryText)
            }
        }
        .contentShape(Rectangle())
    }
}

/// Kachel eines Ordners mit den Covern der Bücher darin.
private struct FolderCard: View {
    let path: String
    @EnvironmentObject private var library: Library

    var body: some View {
        let inside = library.books.filter { FolderPaths.isInside($0.folder, path) }
        let covers = Array(inside.sorted { $0.lastOpenedAt > $1.lastOpenedAt }.prefix(2))
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .bottomLeading) {
                RoundedRectangle(cornerRadius: 12).fill(Theme.folderCard)
                ZStack {
                    ForEach(Array(covers.enumerated().reversed()), id: \.element.uuid) { i, book in
                        CoverImage(url: library.coverURL(for: book), version: book.pageCount)
                            .frame(width: 92, height: 132)
                            .clipShape(RoundedRectangle(cornerRadius: 4))
                            .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
                            .rotationEffect(.degrees(covers.count > 1 ? (i == 0 ? 6 : -8) : 0))
                            .offset(x: covers.count > 1 ? (i == 0 ? 18 : -18) : 0)
                    }
                    if covers.isEmpty {
                        Image(systemName: "folder").font(.system(size: 44)).foregroundStyle(Theme.accent)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                Label("\(inside.count)", systemImage: "folder.fill")
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(.black.opacity(0.55), in: Capsule())
                    .foregroundStyle(.white)
                    .padding(10)
            }
            .aspectRatio(0.7, contentMode: .fit)
            Text(FolderPaths.name(path))
                .font(.subheadline.weight(.semibold))
                .lineLimit(2, reservesSpace: true)
            Text(inside.count == 1 ? "1 Buch" : "\(inside.count) Bücher")
                .font(.caption)
                .foregroundStyle(Theme.secondaryText)
        }
        .contentShape(Rectangle())
    }
}

private extension String {
    func ifEmpty(_ replacement: String) -> String { isEmpty ? replacement : self }
}
