package com.example.easybooks

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.easybooks.ui.library.LibraryScreen
import com.example.easybooks.ui.library.LibraryViewModel
import com.example.easybooks.data.AppSettings
import com.example.easybooks.data.LibraryTree
import com.example.easybooks.data.SettingsRepository
import com.example.easybooks.data.ThemeMode
import com.example.easybooks.ui.settings.ThemeToggleButton
import com.example.easybooks.ui.reader.ReaderScreen
import com.example.easybooks.ui.settings.SettingsDialog
import com.example.easybooks.ui.theme.EasyBooksTheme
import com.example.easybooks.ui.theme.LocalDarkTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val libraryViewModel: LibraryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        val settingsRepository = easyBooksApp.settings
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle(initialValue = null)
            // Kurz warten, bis die Einstellungen geladen sind, damit nichts flackert.
            val current = settings ?: return@setContent
            val darkTheme = when (current.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            EasyBooksTheme(
                darkTheme = darkTheme,
                agedPaper = current.agedPaper,
            ) {
                EasyBooksNavigation(libraryViewModel, current, settingsRepository)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Öffnen mit → Easy Books" aus einer anderen App. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { libraryViewModel.import(it, intoCurrentFolder = false) }
        }
    }
}

private object Routes {
    const val LIBRARY = "library"
    const val READER = "reader/{id}"
    fun reader(bookId: String) = "reader/" + Uri.encode(bookId)
}

@Composable
private fun EasyBooksNavigation(
    libraryViewModel: LibraryViewModel,
    settings: AppSettings,
    settingsRepository: SettingsRepository,
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val openSettings = { showSettings = true }
    // System-Dialog zur Wahl des Bibliotheksordners, startet in "Documents".
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) libraryViewModel.setLibraryFolder(uri)
    }
    val chooseLibraryFolder: () -> Unit = { treePicker.launch(LibraryTree.DOCUMENTS_HINT) }
    val darkTheme = LocalDarkTheme.current
    val toggleDark: () -> Unit = {
        scope.launch { settingsRepository.setTheme(if (darkTheme) ThemeMode.LIGHT else ThemeMode.DARK) }
    }

    LaunchedEffect(libraryViewModel) {
        libraryViewModel.openRequests.collect { bookId ->
            navController.navigate(Routes.reader(bookId)) {
                popUpTo(Routes.LIBRARY)
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = navController, startDestination = Routes.LIBRARY) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                vm = libraryViewModel,
                onOpenBook = { navController.navigate(Routes.reader(it)) },
                onChooseLibraryFolder = chooseLibraryFolder,
                topBarActions = {
                    ThemeToggleButton(onToggle = toggleDark)
                    IconButton(onClick = openSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        }
        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val bookId = entry.arguments?.getString("id").orEmpty()
            ReaderScreen(
                bookId = bookId,
                settings = settings,
                onBack = { navController.popBackStack() },
                onOpenSettings = openSettings,
                onToggleDark = toggleDark,
            )
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            onThemeChange = { scope.launch { settingsRepository.setTheme(it) } },
            onPageSizeChange = { scope.launch { settingsRepository.setPageSize(it) } },
            onViewChange = { scope.launch { settingsRepository.setView(it) } },
            onPageTurnSoundChange = { scope.launch { settingsRepository.setPageTurnSound(it) } },
            onChooseLibraryFolder = {
                showSettings = false
                chooseLibraryFolder()
            },
            onDismiss = { showSettings = false },
        )
    }
}
