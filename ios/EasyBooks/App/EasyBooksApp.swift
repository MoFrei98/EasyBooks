import SwiftUI

/// Easy Books – PDFs lesen wie ein Buch. Komplett offline, ohne Konto und ohne Datenübertragung.
@main
struct EasyBooksApp: App {
    @StateObject private var settings = AppSettings()
    @StateObject private var library = Library()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(settings)
                .environmentObject(library)
                .preferredColorScheme(settings.theme.colorScheme)
                .tint(Theme.accent)
                .onOpenURL { url in
                    // "Öffnen mit Easy Books" / Teilen aus einer anderen App.
                    Task { await library.openExternal(url) }
                }
        }
    }
}
