import SwiftUI

enum ThemeChoice: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }

    var label: String {
        switch self {
        case .system: "System"
        case .light: "Hell"
        case .dark: "Dunkel"
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

/// Schriftgröße im Reader. In keiner Stufe wird links oder rechts Text abgeschnitten.
enum TextSize: String, CaseIterable, Identifiable {
    /// Die ganze Seite ist sichtbar.
    case small
    /// Weiße Ränder ganz weg, der Text füllt die Breite.
    case normal
    /// Der Text wird neu umbrochen und deutlich vergrößert.
    case large
    var id: String { rawValue }

    var label: String {
        switch self {
        case .small: "Klein"
        case .normal: "Normal"
        case .large: "Groß"
        }
    }

    var hint: String {
        switch self {
        case .small: "Die ganze Seite ist sichtbar"
        case .normal: "Ränder ganz weg, Text füllt die Breite"
        case .large: "Text wird neu umbrochen, viel größere Schrift. Mehr Seiten."
        }
    }
}

enum PageTurn: String, CaseIterable, Identifiable {
    case book, slide
    var id: String { rawValue }

    var label: String { self == .book ? "Buchseiten" : "Slideshow" }
    var hint: String { self == .book ? "Die Seite biegt sich um wie in einem Buch" : "Die Seiten gleiten einfach zur Seite" }
}

enum SortOrder: String, CaseIterable, Identifiable {
    case recent, title
    var id: String { rawValue }
    var label: String { self == .recent ? "Zuletzt gelesen" : "Titel A–Z" }
}

enum BookFilter: String, CaseIterable, Identifiable {
    case all, unread, started, finished
    var id: String { rawValue }

    var label: String {
        switch self {
        case .all: "Alle"
        case .unread: "Ungelesen"
        case .started: "Angefangen"
        case .finished: "Gelesen"
        }
    }

    func accepts(_ book: Book) -> Bool {
        switch self {
        case .all: true
        case .unread: book.readState == .unread
        case .started: book.readState == .started
        case .finished: book.readState == .finished
        }
    }
}

/// Einstellungen, gespeichert in den UserDefaults. Startargumente wie `-textSize large`
/// überschreiben sie (praktisch für Screenshots).
@MainActor
final class AppSettings: ObservableObject {
    private let defaults = UserDefaults.standard

    @Published var theme: ThemeChoice { didSet { defaults.set(theme.rawValue, forKey: "theme") } }
    @Published var textSize: TextSize { didSet { defaults.set(textSize.rawValue, forKey: "textSize") } }
    @Published var pageTurn: PageTurn { didSet { defaults.set(pageTurn.rawValue, forKey: "pageTurn") } }
    @Published var sound: Bool { didSet { defaults.set(sound, forKey: "sound") } }
    @Published var sort: SortOrder { didSet { defaults.set(sort.rawValue, forKey: "sort") } }
    @Published var filter: BookFilter { didSet { defaults.set(filter.rawValue, forKey: "filter") } }

    init() {
        theme = ThemeChoice(rawValue: defaults.string(forKey: "theme") ?? "") ?? .system
        textSize = TextSize(rawValue: defaults.string(forKey: "textSize") ?? "") ?? .normal
        pageTurn = PageTurn(rawValue: defaults.string(forKey: "pageTurn") ?? "") ?? .book
        sound = defaults.bool(forKey: "sound")
        sort = SortOrder(rawValue: defaults.string(forKey: "sort") ?? "") ?? .recent
        filter = BookFilter(rawValue: defaults.string(forKey: "filter") ?? "") ?? .all
    }
}
