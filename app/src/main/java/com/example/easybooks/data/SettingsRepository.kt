package com.example.easybooks.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Helles oder dunkles Design. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Schrift der App-Oberfläche. Die Schrift in den Büchern ändert sich nie. */
enum class AppFont {
    /** Systemschrift. */
    NORMAL,

    /** Alte Buchschriften: Tangerine für Titel, IM Fell English und Alegreya für den Rest. */
    DECORATED,
}

/** Papier der Buchseiten im Reader. */
enum class PaperStyle {
    /** Vergilbtes, altes Papier mit brauner Tinte. */
    AGED,

    /** Helles Papier wie bisher. */
    PLAIN,
}

/** Reihenfolge der Bücher in der Bibliothek. */
enum class LibrarySort { RECENT, TITLE }

/** Wie die Seiten beim Blättern animiert werden. */
enum class PageTurn {
    /** Die obere Seite gleitet weg und gibt die darunterliegende frei (wie im Buch). */
    CURL,

    /** Beide Seiten schieben sich gemeinsam zur Seite. */
    SLIDE,
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Seitenausschnitt im Reader, gilt für alle Bücher. */
    val pageSize: ZoomMode = ZoomMode.NORMAL,
    val pageTurn: PageTurn = PageTurn.CURL,
    /** Papiergeräusch beim Umblättern. */
    val pageTurnSound: Boolean = false,
    /** Bibliotheksordner auf dem Gerät (Tree-URI) oder `null`, solange keiner gewählt ist. */
    val libraryTree: String? = null,
    /** Lesbare Bezeichnung des Bibliotheksordners, z. B. "Documents/Easy Books". */
    val libraryLabel: String? = null,
    val sort: LibrarySort = LibrarySort.RECENT,
    val filter: BookFilter = BookFilter.ALL,
    val appFont: AppFont = AppFont.DECORATED,
    val paper: PaperStyle = PaperStyle.AGED,
)

class SettingsRepository(private val context: Context) {

    private val themeKey = stringPreferencesKey("theme")
    private val pageTurnKey = stringPreferencesKey("page_turn")
    private val pageTurnSoundKey = booleanPreferencesKey("page_turn_sound")
    private val pageSizeKey = stringPreferencesKey("page_size")
    private val libraryTreeKey = stringPreferencesKey("library_tree")
    private val libraryLabelKey = stringPreferencesKey("library_label")
    private val sortKey = stringPreferencesKey("sort")
    private val filterKey = stringPreferencesKey("filter")
    private val appFontKey = stringPreferencesKey("app_font")
    private val paperKey = stringPreferencesKey("paper")

    val settings: Flow<AppSettings> = context.easyBooksDataStore.data
        .map { prefs ->
            AppSettings(
                theme = enumOrDefault(prefs[themeKey], ThemeMode.SYSTEM),
                pageSize = ZoomMode.fromStored(prefs[pageSizeKey]),
                pageTurn = enumOrDefault(prefs[pageTurnKey], PageTurn.CURL),
                pageTurnSound = prefs[pageTurnSoundKey] ?: false,
                libraryTree = prefs[libraryTreeKey],
                libraryLabel = prefs[libraryLabelKey],
                sort = enumOrDefault(prefs[sortKey], LibrarySort.RECENT),
                filter = enumOrDefault(prefs[filterKey], BookFilter.ALL),
                appFont = enumOrDefault(prefs[appFontKey], AppFont.DECORATED),
                paper = enumOrDefault(prefs[paperKey], PaperStyle.AGED),
            )
        }
        .distinctUntilChanged()

    suspend fun setTheme(value: ThemeMode) {
        context.easyBooksDataStore.edit { it[themeKey] = value.name }
    }

    suspend fun setPageSize(value: ZoomMode) {
        context.easyBooksDataStore.edit { it[pageSizeKey] = value.name }
    }

    suspend fun setPageTurn(value: PageTurn) {
        context.easyBooksDataStore.edit { it[pageTurnKey] = value.name }
    }

    suspend fun setPageTurnSound(value: Boolean) {
        context.easyBooksDataStore.edit { it[pageTurnSoundKey] = value }
    }

    suspend fun setLibraryFolder(treeUri: String, label: String) {
        context.easyBooksDataStore.edit {
            it[libraryTreeKey] = treeUri
            it[libraryLabelKey] = label
        }
    }

    suspend fun setFilter(value: BookFilter) {
        context.easyBooksDataStore.edit { it[filterKey] = value.name }
    }

    suspend fun setAppFont(value: AppFont) {
        context.easyBooksDataStore.edit { it[appFontKey] = value.name }
    }

    suspend fun setPaper(value: PaperStyle) {
        context.easyBooksDataStore.edit { it[paperKey] = value.name }
    }

    suspend fun setSort(value: LibrarySort) {
        context.easyBooksDataStore.edit { it[sortKey] = value.name }
    }
}

internal inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
