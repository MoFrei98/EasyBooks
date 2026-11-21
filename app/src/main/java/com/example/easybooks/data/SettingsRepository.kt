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

/** Wie die Bücher im Reader aussehen und blättern. */
enum class ReadingView {
    /** Helles Papier, die Seiten gleiten zur Seite. */
    SLIDESHOW,

    /**
     * Ein aufgeschlagenes Buch auf dem Tisch: altes Papier mit ausgefransten Kanten. Rechte
     * Seiten werden umgeblättert, von der linken zur rechten Seite gleitet der Blick weiter.
     */
    OLD_BOOK,
}

/** Reihenfolge der Bücher in der Bibliothek. */
enum class LibrarySort { RECENT, TITLE }

/** Wie die Seiten beim Blättern animiert werden (folgt aus der [ReadingView]). */
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
    val view: ReadingView = ReadingView.OLD_BOOK,
    /** Papiergeräusch beim Umblättern. */
    val pageTurnSound: Boolean = false,
    /** Bibliotheksordner auf dem Gerät (Tree-URI) oder `null`, solange keiner gewählt ist. */
    val libraryTree: String? = null,
    /** Lesbare Bezeichnung des Bibliotheksordners, z. B. "Documents/Easy Books". */
    val libraryLabel: String? = null,
    val sort: LibrarySort = LibrarySort.RECENT,
    val filter: BookFilter = BookFilter.ALL,
) {
    /** Altes Buch: Seiten biegen sich beim Umblättern, Slideshow: sie gleiten. */
    val pageTurn: PageTurn get() = if (view == ReadingView.OLD_BOOK) PageTurn.CURL else PageTurn.SLIDE

    /** Buchseiten auf altem Papier mit ausgefransten Kanten. */
    val agedPaper: Boolean get() = view == ReadingView.OLD_BOOK
}

class SettingsRepository(private val context: Context) {

    private val themeKey = stringPreferencesKey("theme")
    private val viewKey = stringPreferencesKey("view")

    /** Früher eigene Einstellung "Blättern"; nur noch zum Übernehmen einer gewählten Slideshow. */
    private val pageTurnKey = stringPreferencesKey("page_turn")
    private val pageTurnSoundKey = booleanPreferencesKey("page_turn_sound")
    private val pageSizeKey = stringPreferencesKey("page_size")
    private val libraryTreeKey = stringPreferencesKey("library_tree")
    private val libraryLabelKey = stringPreferencesKey("library_label")
    private val sortKey = stringPreferencesKey("sort")
    private val filterKey = stringPreferencesKey("filter")

    val settings: Flow<AppSettings> = context.easyBooksDataStore.data
        .map { prefs ->
            AppSettings(
                theme = enumOrDefault(prefs[themeKey], ThemeMode.SYSTEM),
                pageSize = ZoomMode.fromStored(prefs[pageSizeKey]),
                view = prefs[viewKey]?.let { enumOrDefault(it, ReadingView.OLD_BOOK) }
                    ?: if (prefs[pageTurnKey] == "SLIDE") ReadingView.SLIDESHOW else ReadingView.OLD_BOOK,
                pageTurnSound = prefs[pageTurnSoundKey] ?: false,
                libraryTree = prefs[libraryTreeKey],
                libraryLabel = prefs[libraryLabelKey],
                sort = enumOrDefault(prefs[sortKey], LibrarySort.RECENT),
                filter = enumOrDefault(prefs[filterKey], BookFilter.ALL),
            )
        }
        .distinctUntilChanged()

    suspend fun setTheme(value: ThemeMode) {
        context.easyBooksDataStore.edit { it[themeKey] = value.name }
    }

    suspend fun setPageSize(value: ZoomMode) {
        context.easyBooksDataStore.edit { it[pageSizeKey] = value.name }
    }

    suspend fun setView(value: ReadingView) {
        context.easyBooksDataStore.edit { it[viewKey] = value.name }
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

    suspend fun setSort(value: LibrarySort) {
        context.easyBooksDataStore.edit { it[sortKey] = value.name }
    }
}

internal inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
