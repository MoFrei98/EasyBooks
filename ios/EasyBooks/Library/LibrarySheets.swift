import EasyBooksCore
import SwiftUI
import UniformTypeIdentifiers

/// Titel, Autor und Erscheinungsjahr eines Buchs bearbeiten.
struct BookDetailsSheet: View {
    let book: Book
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var firstName = ""
    @State private var lastName = ""
    @State private var year = ""

    private var yearValid: Bool {
        let t = year.trimmingCharacters(in: .whitespaces)
        return t.isEmpty || (Int(t).map { (1000...2100).contains($0) } ?? false)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Titel", text: $title)
                    TextField("Vorname des Autors", text: $firstName)
                    TextField("Nachname des Autors", text: $lastName)
                    TextField("Erscheinungsjahr", text: $year)
                        .keyboardType(.numberPad)
                        .onChange(of: year) { year = String(year.filter(\.isNumber).prefix(4)) }
                }
                Section {
                    if book.pageCount > 0 { Text("\(book.pageCount) Seiten") }
                    Text("Speicherort: \(library.url(for: book).path)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Buchdetails")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Speichern") {
                        library.updateDetails(book, title: title, firstName: firstName, lastName: lastName, year: year)
                        dismiss()
                    }
                    .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty || !yearValid)
                }
            }
            .onAppear {
                title = book.title
                firstName = book.authorFirstName ?? ""
                lastName = book.authorLastName ?? ""
                year = book.publishYear.map(String.init) ?? ""
            }
        }
    }
}

/// Zielordner für ein Buch auswählen.
struct MoveBookSheet: View {
    let book: Book
    @EnvironmentObject private var library: Library
    @Environment(\.dismiss) private var dismiss
    @State private var askNewFolder = false
    @State private var newFolderName = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    row(label: "Bibliothek (oberste Ebene)", depth: 0, path: FolderPaths.root)
                    ForEach(library.folders, id: \.self) { path in
                        row(label: FolderPaths.name(path), depth: FolderPaths.depth(path), path: path)
                    }
                } header: {
                    Text(book.title).lineLimit(1)
                }
            }
            .navigationTitle("In Ordner verschieben")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        newFolderName = ""
                        askNewFolder = true
                    } label: { Label("Neuer Ordner", systemImage: "folder.badge.plus") }
                }
            }
            .alert("Neuer Ordner", isPresented: $askNewFolder) {
                TextField("Name", text: $newFolderName)
                Button("Anlegen") {
                    if let path = library.createFolder(newFolderName, in: FolderPaths.root) {
                        library.move(book, to: path)
                        dismiss()
                    }
                }
                Button("Abbrechen", role: .cancel) {}
            }
        }
    }

    private func row(label: String, depth: Int, path: String) -> some View {
        Button {
            library.move(book, to: path)
            dismiss()
        } label: {
            HStack {
                Image(systemName: "folder").foregroundStyle(Theme.accent)
                Text(label).foregroundStyle(.primary)
                Spacer()
                if book.folder == path { Image(systemName: "checkmark").foregroundStyle(Theme.accent) }
            }
            .padding(.leading, CGFloat(max(0, depth - 1)) * 16)
        }
        .disabled(book.folder == path)
    }
}

/// Einstellungen: Bibliotheksordner, Design, Schriftgröße, Blättern, Umblätter-Sound.
struct SettingsView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @State private var choosingFolder = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Bibliotheksordner") {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(library.rootName)
                        Text("Ordner, in dem Easy Books deine Bücher sortiert. Du findest ihn auch in der Dateien-App.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    Button("Anderen Ordner wählen") { choosingFolder = true }
                    if library.customRoot {
                        Button("Zurück zu „Auf meinem iPhone“") { Task { await library.resetRoot() } }
                    }
                }
                Section("Design") {
                    choice(ThemeChoice.allCases, selected: settings.theme, label: \.label, hint: { _ in nil }) {
                        settings.theme = $0
                    }
                }
                Section("Schriftgröße") {
                    choice(TextSize.allCases, selected: settings.textSize, label: \.label, hint: { $0.hint }) {
                        settings.textSize = $0
                    }
                }
                Section("Blättern") {
                    choice(PageTurn.allCases, selected: settings.pageTurn, label: \.label, hint: { $0.hint }) {
                        settings.pageTurn = $0
                    }
                }
                Section {
                    Toggle(isOn: $settings.sound) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Umblätter-Sound")
                            Text("Papierrascheln beim Umblättern. Still im Lautlos-Modus.")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("Einstellungen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { dismiss() } }
            }
            .fileImporter(isPresented: $choosingFolder, allowedContentTypes: [.folder]) { result in
                if case let .success(url) = result {
                    Task { await library.setRoot(url) }
                }
            }
        }
    }

    /// Auswahl mit Häkchen und optionalem Hinweis unter jeder Option.
    private func choice<T: Identifiable & Equatable>(
        _ options: [T], selected: T, label: KeyPath<T, String>, hint: @escaping (T) -> String?,
        onSelect: @escaping (T) -> Void
    ) -> some View {
        ForEach(options) { option in
            Button { onSelect(option) } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(option[keyPath: label]).foregroundStyle(.primary)
                        if let h = hint(option) {
                            Text(h).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    if option == selected {
                        Image(systemName: "checkmark").foregroundStyle(Theme.accent)
                    }
                }
            }
        }
    }
}
