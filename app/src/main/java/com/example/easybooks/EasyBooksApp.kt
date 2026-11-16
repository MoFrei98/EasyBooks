package com.example.easybooks

import android.app.Application
import android.content.Context
import com.example.easybooks.data.LibraryRepository
import com.example.easybooks.data.SettingsRepository
import com.example.easybooks.ui.theme.Textures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EasyBooksApp : Application() {
    /** Für Schreibvorgänge, die auch nach dem Schließen eines Bildschirms fertig werden sollen. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings by lazy { SettingsRepository(this) }
    val library by lazy { LibraryRepository(this, settings) }

    override fun onCreate() {
        super.onCreate()
        // Papier-, Leder- und Holzstrukturen im Hintergrund erzeugen, bevor sie gebraucht werden.
        appScope.launch { Textures.warmUp() }
    }
}

val Context.easyBooksApp: EasyBooksApp get() = applicationContext as EasyBooksApp
