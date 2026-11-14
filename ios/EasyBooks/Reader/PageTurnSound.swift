import AVFoundation

/// Papierrascheln beim Umblättern (dieselben Geräusche wie in der Android-App).
/// Die Audio-Kategorie "ambient" sorgt dafür, dass im Lautlos-Modus nichts zu hören ist.
final class PageTurnSound {
    private var book: [AVAudioPlayer] = []
    private var slide: AVAudioPlayer?
    private var next = 0

    init() {
        try? AVAudioSession.sharedInstance().setCategory(.ambient, mode: .default, options: [.mixWithOthers])
        book = ["page_turn_1", "page_turn_2", "page_turn_3"].compactMap(PageTurnSound.player)
        slide = PageTurnSound.player("page_slide")
    }

    private static func player(_ name: String) -> AVAudioPlayer? {
        guard let url = Bundle.main.url(forResource: name, withExtension: "wav") else { return nil }
        let player = try? AVAudioPlayer(contentsOf: url)
        player?.volume = 0.7
        player?.prepareToPlay()
        return player
    }

    func play(bookPages: Bool) {
        let player: AVAudioPlayer?
        if bookPages, !book.isEmpty {
            // Abwechselnd verschiedene Varianten, damit es nicht immer gleich klingt.
            player = book[next % book.count]
            next += 1
        } else {
            player = slide
        }
        player?.currentTime = 0
        player?.play()
    }
}
