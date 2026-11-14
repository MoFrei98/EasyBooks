import EasyBooksCore
import SwiftUI
import UIKit

/// Der Reader: Seiten blättern, Leisten oben und unten, Seitenregler.
struct ReaderView: View {
    @EnvironmentObject private var library: Library
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.displayScale) private var displayScale

    @StateObject private var model: ReaderModel
    @State private var barsVisible = !UserDefaults.standard.bool(forKey: "demoHideBars")
    @State private var current = 0
    @State private var seek: Int?
    @State private var sliderValue: Double?
    @State private var showSettings = false
    @State private var sound: PageTurnSound?

    @MainActor
    init(book: Book, library: Library) {
        let id = book.uuid
        _model = StateObject(wrappedValue: ReaderModel(
            book: book, url: library.url(for: book), cacheURL: library.reflowCacheURL(for: book)
        ) { [weak library] position in
            library?.updateProgress(id, page: position.page, offset: position.fraction)
        })
    }

    private var dark: Bool { colorScheme == .dark }
    private var palette: ReaderPalette { .of(dark: dark) }

    var body: some View {
        ZStack {
            Color(palette.backdrop).ignoresSafeArea()
            GeometryReader { geo in
                content(size: geo.size)
            }
            bars
        }
        .statusBarHidden(!barsVisible)
        .persistentSystemOverlays(barsVisible ? .automatic : .hidden)
        .task {
            await model.load()
            library.markOpened(model.bookID, pageCount: model.pageCount)
        }
        .onAppear { updateSound() }
        .onChange(of: settings.sound) { updateSound() }
        .onDisappear { model.close() }
        .sheet(isPresented: $showSettings) { SettingsView() }
    }

    private func updateSound() { sound = settings.sound ? PageTurnSound() : nil }

    @ViewBuilder
    private func content(size: CGSize) -> some View {
        switch model.state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case let .failed(message):
            VStack(spacing: 24) {
                Text(message).multilineTextAlignment(.center)
                Button("Zurück") { dismiss() }.buttonStyle(.bordered)
            }
            .padding(32)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .ready:
            if settings.textSize == .large {
                reflowPages(size: size)
            } else {
                pdfPages(size: size)
            }
        }
    }

    // MARK: Klein und Normal

    private func pdfPages(size: CGSize) -> some View {
        let textSize = settings.textSize
        let scale = displayScale
        let dark = self.dark
        let model = self.model
        let provider: @MainActor (Int) async -> PageVisual? = { index in
            await model.pageVisual(index, textSize: textSize, available: size, scale: scale, dark: dark)
        }
        return PagerView(
            pageCount: model.pageCount,
            initialIndex: model.position.page,
            curl: settings.pageTurn == .book,
            palette: palette,
            darkPaper: dark,
            provider: provider,
            onSettled: { index in
                current = index
                model.settled(pdfPage: index)
                barsVisible = false
            },
            onTapCenter: { barsVisible.toggle() },
            onTurn: { sound?.play(bookPages: settings.pageTurn == .book) },
            seek: $seek
        )
        .id("pdf-\(textSize)-\(settings.pageTurn)-\(dark)-\(Int(size.width))x\(Int(size.height))")
        .onAppear {
            current = model.position.page
            model.setPrefetch { index in Task { _ = await provider(index) } }
        }
    }

    // MARK: Groß

    private func reflowParams(size: CGSize) -> ReflowParams {
        let s = displayScale
        let width = (size.width - 2 * 2) * s
        let height = (size.height - 2 * 10) * s
        // Etwa so groß wie eine 23 Punkt große Systemschrift; folgt der eingestellten Textgröße.
        let xHeight = UIFontMetrics.default.scaledValue(for: 11.5) * s
        return ReflowParams(
            frame: ReflowFrame(width: Int(width), height: Int(height), marginX: Int(14 * s),
                               marginTop: Int(18 * s), marginBottom: Int(22 * s)),
            targetXHeight: Float(xHeight)
        )
    }

    @ViewBuilder
    private func reflowPages(size: CGSize) -> some View {
        let params = reflowParams(size: size)
        Group {
            if let state = model.reflow, state.params == params, let start = model.reflowIndex(state) {
                let scale = displayScale
                let dark = self.dark
                let model = self.model
                let provider: @MainActor (Int) async -> PageVisual? = { index in
                    guard let latest = model.reflow, latest.params == params else { return nil }
                    return await model.reflowVisual(latest, index, scale: scale, dark: dark)
                }
                PagerView(
                    pageCount: state.pages.count,
                    initialIndex: start,
                    curl: settings.pageTurn == .book,
                    palette: palette,
                    darkPaper: dark,
                    provider: provider,
                    onSettled: { index in
                        current = index
                        if let latest = model.reflow { model.settled(reflowIndex: index, in: latest) }
                        barsVisible = false
                    },
                    onTapCenter: { barsVisible.toggle() },
                    onTurn: { sound?.play(bookPages: settings.pageTurn == .book) },
                    seek: $seek
                )
                .id("reflow-\(params.hashValue)-\(settings.pageTurn)-\(dark)")
                .onAppear {
                    current = start
                    model.setPrefetch { index in Task { _ = await provider(index) } }
                }
            } else {
                VStack(spacing: 16) {
                    ProgressView(value: max(0.02, model.reflowProgress))
                        .progressViewStyle(.circular)
                    Text("Große Schrift wird vorbereitet … \(Int(model.reflowProgress * 100)) %")
                    Text("Das passiert nur beim ersten Mal. Der Rest des Buchs wird beim Lesen vorbereitet.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(32)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .task(id: params) { model.startReflow(params) }
    }

    // MARK: Leisten

    private var pageInfo: (count: Int, shown: Int, estimated: Bool) {
        if settings.textSize == .large, let state = model.reflow {
            return (state.pages.count, max(state.estimatedCount, state.pages.count), !state.complete)
        }
        return (model.pageCount, model.pageCount, false)
    }

    private var bars: some View {
        VStack(spacing: 0) {
            if barsVisible {
                topBar.transition(.move(edge: .top).combined(with: .opacity))
            }
            Spacer()
            if barsVisible, case .ready = model.state {
                bottomBar.transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: barsVisible)
    }

    private var topBar: some View {
        HStack(spacing: 16) {
            Button { dismiss() } label: {
                Image(systemName: "chevron.left").font(.title3.weight(.semibold))
            }
            .accessibilityLabel("Zurück")
            Text(model.title).font(.headline).lineLimit(1)
            Spacer()
            Button { settings.theme = dark ? .light : .dark } label: {
                Image(systemName: dark ? "sun.max" : "moon")
            }
            .accessibilityLabel(dark ? "Helles Design" : "Dunkles Design")
            Button { showSettings = true } label: { Image(systemName: "gearshape") }
                .accessibilityLabel("Einstellungen")
        }
        .font(.title3)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(.bar)
    }

    private var bottomBar: some View {
        let info = pageInfo
        let shownPage = Int(sliderValue ?? Double(current)) + 1
        return VStack(spacing: 4) {
            Text(info.estimated ? "Seite \(shownPage) von ca. \(info.shown)" : "Seite \(shownPage) von \(info.shown)")
                .font(.subheadline.weight(.medium))
            if info.count > 1 {
                Slider(
                    value: Binding(get: { sliderValue ?? Double(current) }, set: { sliderValue = $0 }),
                    in: 0...Double(info.count - 1),
                    step: 1,
                    onEditingChanged: { editing in
                        if !editing, let value = sliderValue {
                            // Springen über den Regler ist kein Umblättern: kein Geräusch.
                            seek = Int(value)
                            current = Int(value)
                            sliderValue = nil
                        }
                    }
                )
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 10)
        .background(.bar)
    }
}
