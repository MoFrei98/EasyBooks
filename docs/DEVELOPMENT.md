# Development

Notes for building and releasing Easy Books. The user-facing guide is the [README](../README.md).

The Android app lives on `main`. The iPhone app is developed on the `ios-port` branch, which also contains everything from `main`.

## Android

- **Run on a phone:** enable developer options (**Settings → About phone** → tap **Build number** seven times), turn on **USB debugging**, connect the phone, open the project in Android Studio and click **Run ▶**.
- **Tests:** `./gradlew testDebugUnitTest`
- **App ID:** `com.example.ebf` stays from the time the app was called EBF. Changing it would make Android treat Easy Books as a different app, and existing installs would lose their library. The same applies to the database file `ebf.db` and the DataStore name `ebf`.
- **Signing:** release builds are signed with the local debug key (`~/.android/debug.keystore`), see `app/build.gradle.kts`. Build releases on the same machine, otherwise the new APK can't be installed over the old one.
- **Look ("Alte Bücherei", issue #30):** colors, textures, ornaments and shared components live in `ui/theme` (`Palette`, `Textures`, `Ornaments`, `Components`). The concept is in [docs/design/stilkonzept-alte-buecherei.pdf](design/stilkonzept-alte-buecherei.pdf). Paper, leather and wood textures are generated in code; aged book pages are printed onto procedurally generated paper with frayed edges in `ui/reader/AgedPaper.kt`. The setting *Ansicht* (`ReadingView`) chooses between *Slideshow* (plain paper, pages slide) and *Altes Buch* (default): aged paper, an open book on the table (`drawOpenBook` in `ui/reader/Paper.kt`), pages alternating between right (turned with the curl) and left (slide to the next page), see `curlsAfter` in `ui/reader/PageView.kt`. If the first page is a picture, it becomes a hard cover that swings open rigidly around the spine (`ui/reader/HardCover.kt`) with its own sound (`cover_open.wav`, made by `tools/make_page_sounds.py`). The app font is always the decorated one. The iPhone app keeps its plain look.
- **Fonts:** Tangerine, IM Fell English and Alegreya in `res/font`, all under the SIL Open Font License (texts in `docs/licenses`).
- **App icon:** `python tools/make_icon.py` writes the adaptive icon, the monochrome icon and the splash wordmark (the letters are converted to paths from Tangerine with fontTools). `pwsh tools/render_icon.ps1` renders `docs/icon.png` with Microsoft Edge.

## iPhone (branch `ios-port`)

- **Build:** on a Mac, install Xcode and [XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`), run `xcodegen` in the `ios` folder and open `EasyBooks.xcodeproj`.
- **Core tests:** `swift test --package-path ios/EasyBooksCore` (also runs on Linux).
- **CI:** the **iOS** workflow tests the core on Linux, builds the app on macOS, takes simulator screenshots and uploads an unsigned `EasyBooks.ipa` as an artifact.
- **Bundle ID:** `com.example.ebf`, kept for the same reason as on Android.

## Releases

Release titles and file names:

| Platform | Tag | Title | File |
| --- | --- | --- | --- |
| Android | `v<version>` | Easy Books for Android `<version>` | `EasyBooks-<version>.apk` |
| iPhone | `ios-v<version>` | Easy Books for iPhone `<version>` | `EasyBooks-ios-<version>.ipa` |

**Android**

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts` and commit.
2. `./gradlew assembleRelease` and rename `app/build/outputs/apk/release/app-release.apk` to `EasyBooks-<version>.apk`.
3. `gh release create v<version> EasyBooks-<version>.apk --target main --title "Easy Books for Android <version>" --latest --notes-file <notes>`

**iPhone**

1. On `ios-port`, raise `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` in `ios/project.yml`, commit and push.
2. Push the tag: `git tag ios-v<version> && git push origin ios-v<version>`. The iOS workflow builds the app and creates a **draft** pre-release with `EasyBooks-ios-<version>.ipa`. It stops if the tag doesn't match `MARKETING_VERSION`.
3. Add the release notes and publish the draft. Update the iPhone link in the README to the new tag.

iPhone releases are created with `--latest=false`, so **Latest** always points to the Android release.

## Project layout

```
app/src/main/java/com/example/easybooks/   Android app (Kotlin, Jetpack Compose)
├── MainActivity.kt          navigation, "Open with", folder picker
├── EasyBooksApp.kt          application, repositories
├── data/                    SQLite database, library and folder sync, metadata detection, settings
├── pdf/                     PDF rendering, margin detection
├── reflow/                  text size Groß: page analysis, line breaking, cache
└── ui/                      library, reader, settings, theme
ios/                                       iPhone app (Swift, SwiftUI) – branch ios-port
├── project.yml              Xcode project, generated with XcodeGen
├── EasyBooksCore/           shared logic as a Swift package, same rules as the Android app
└── EasyBooks/               app: library, reader, PDF rendering
shared/                                    branch ios-port
├── fixtures/                test cases checked by both apps
└── samples/                 sample books for screenshots
tools/
├── make_icon.py             app icon, monochrome icon and splash wordmark (fontTools)
├── render_icon.ps1          renders docs/icon.png from the icon SVG
├── make_page_sounds.py      synthesizes the page-turn sounds (no third-party recordings)
└── make_sample_books.py     generates the sample books (branch ios-port)
```
