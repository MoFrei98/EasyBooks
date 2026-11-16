package com.example.easybooks.ui.library

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.easybooks.R
import com.example.easybooks.data.Book
import com.example.easybooks.data.BookFilter
import com.example.easybooks.data.FolderPaths
import com.example.easybooks.data.LibrarySort
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.example.easybooks.ui.theme.BrassBrush
import com.example.easybooks.ui.theme.EasyBooksType
import com.example.easybooks.ui.theme.Fleur
import com.example.easybooks.ui.theme.FlourishDivider
import com.example.easybooks.ui.theme.LeatherFrame
import com.example.easybooks.ui.theme.LeatherTopBar
import com.example.easybooks.ui.theme.LocalDarkTheme
import com.example.easybooks.ui.theme.Palette
import com.example.easybooks.ui.theme.Plaque
import com.example.easybooks.ui.theme.PlaqueStyle
import com.example.easybooks.ui.theme.RibbonProgress
import com.example.easybooks.ui.theme.Textures
import com.example.easybooks.ui.theme.parchmentBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Offene Dialoge der Bibliothek. */
private sealed interface LibraryDialog {
    data object ChooseLibraryFolder : LibraryDialog
    data class CreateFolder(val moveInto: Book? = null) : LibraryDialog
    data class RenameFolder(val path: String) : LibraryDialog
    data class DeleteFolder(val path: String) : LibraryDialog
    data class MoveBook(val book: Book) : LibraryDialog
    data class EditDetails(val book: Book) : LibraryDialog
    data class RemoveBook(val book: Book) : LibraryDialog
    data class DeleteBook(val book: Book) : LibraryDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onOpenBook: (String) -> Unit,
    onChooseLibraryFolder: () -> Unit,
    topBarActions: @Composable () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }
    val pickPdf: () -> Unit = { picker.launch(arrayOf("application/pdf")) }

    var dialog by remember { mutableStateOf<LibraryDialog?>(null) }
    val ui = state
    val inFolder = ui != null && ui.path.isNotEmpty()
    val searchOpen = ui?.query != null
    BackHandler(enabled = inFolder) { vm.navigateUp() }
    // Später deklariert = hat Vorrang: zuerst die Suche schließen, dann eine Ebene hoch.
    BackHandler(enabled = searchOpen) { vm.closeSearch() }

    val dark = LocalDarkTheme.current
    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.parchmentBackground(dark),
        topBar = {
            if (searchOpen) {
                SearchTopBar(
                    query = ui?.query.orEmpty(),
                    onQueryChange = vm::setQuery,
                    onClose = vm::closeSearch,
                )
            } else LeatherTopBar(
                title = {
                    Column {
                        Text(
                            if (inFolder) FolderPaths.name(ui!!.path) else stringResource(R.string.library_title),
                            style = EasyBooksType.screenTitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (inFolder && FolderPaths.depth(ui!!.path) > 1) {
                            Text(
                                FolderPaths.parent(ui.path).replace("/", " › "),
                                style = MaterialTheme.typography.labelMedium,
                                color = Palette.BrassLight.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (inFolder) {
                        IconButton(onClick = { vm.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::openSearch) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                    }
                    topBarActions()
                    LibraryMenu(
                        sort = ui?.sort ?: LibrarySort.RECENT,
                        hasLibraryFolder = ui?.hasLibraryFolder == true,
                        onNewFolder = {
                            dialog = if (ui?.hasLibraryFolder == true) LibraryDialog.CreateFolder()
                            else LibraryDialog.ChooseLibraryFolder
                        },
                        onSort = vm::setSort,
                        onRefresh = vm::refresh,
                    )
                },
            )
        },
        floatingActionButton = {
            if (ui != null && !searchOpen && (ui.totalBooks > 0 || ui.folders.isNotEmpty() || inFolder)) {
                Plaque(
                    text = stringResource(R.string.add_pdf),
                    onClick = pickPdf,
                    icon = Icons.Filled.Add,
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                if (ui != null && ui.totalBooks > 0) {
                    FilterChips(filter = ui.filter, onFilter = vm::setFilter)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when {
                        ui == null -> Unit
                        ui.books.isEmpty() && ui.folders.isEmpty() -> when {
                            ui.searching -> NoResults(
                                text = stringResource(R.string.no_results, ui.query.orEmpty().trim()),
                            )
                            ui.filter != BookFilter.ALL && ui.totalBooks > 0 -> NoResults(
                                text = stringResource(R.string.no_filter_results),
                                action = stringResource(R.string.reset_filter),
                                onAction = { vm.setFilter(BookFilter.ALL) },
                            )
                            else -> EmptyState(
                                inFolder = inFolder,
                                hasLibraryFolder = ui.hasLibraryFolder,
                                onAdd = pickPdf,
                                onChooseLibraryFolder = { dialog = LibraryDialog.ChooseLibraryFolder },
                            )
                        }
                        else -> LibraryGrid(
                            ui = ui,
                            vm = vm,
                            onOpenBook = onOpenBook,
                            onDialog = { dialog = it },
                        )
                    }
                }
            }
            if (busy) {
                LinearProgressIndicator(
                    color = Palette.Brass,
                    trackColor = Palette.Brass.copy(alpha = 0.2f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }
        }
    }

    val close = { dialog = null }
    when (val d = dialog) {
        null -> Unit
        LibraryDialog.ChooseLibraryFolder -> LibraryFolderDialog(onChoose = onChooseLibraryFolder, onDismiss = close)
        is LibraryDialog.CreateFolder -> NameDialog(
            title = stringResource(R.string.new_folder),
            initial = "",
            confirmLabel = stringResource(R.string.create),
            onConfirm = { name ->
                vm.createFolder(name, moveInto = d.moveInto)
                close()
            },
            onDismiss = close,
        )
        is LibraryDialog.RenameFolder -> NameDialog(
            title = stringResource(R.string.rename_folder),
            initial = FolderPaths.name(d.path),
            confirmLabel = stringResource(R.string.save),
            onConfirm = { name ->
                vm.renameFolder(d.path, name)
                close()
            },
            onDismiss = close,
        )
        is LibraryDialog.DeleteFolder -> ConfirmDialog(
            title = stringResource(R.string.delete_folder_title),
            text = stringResource(R.string.delete_folder_text, FolderPaths.name(d.path)),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                vm.deleteFolder(d.path)
                close()
            },
            onDismiss = close,
        )
        is LibraryDialog.MoveBook -> MoveBookDialog(
            book = d.book,
            folders = ui?.allFolders.orEmpty(),
            onMove = { path ->
                vm.moveBook(d.book, path)
                close()
            },
            onNewFolder = { dialog = LibraryDialog.CreateFolder(moveInto = d.book) },
            onDismiss = close,
        )
        is LibraryDialog.EditDetails -> BookDetailsDialog(
            book = d.book,
            onSave = { title, first, last, year ->
                vm.editDetails(d.book, title, first, last, year)
                close()
            },
            onDismiss = close,
        )
        is LibraryDialog.RemoveBook -> ConfirmDialog(
            title = stringResource(R.string.remove_title),
            text = stringResource(R.string.remove_text, d.book.title),
            confirmLabel = stringResource(R.string.remove),
            onConfirm = {
                vm.remove(d.book)
                close()
            },
            onDismiss = close,
        )
        is LibraryDialog.DeleteBook -> ConfirmDialog(
            title = stringResource(R.string.delete_book_title),
            text = stringResource(R.string.delete_book_text, d.book.title),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                vm.deleteBook(d.book)
                close()
            },
            onDismiss = close,
        )
    }
}

@Composable
private fun LibraryGrid(
    ui: LibraryUiState,
    vm: LibraryViewModel,
    onOpenBook: (String) -> Unit,
    onDialog: (LibraryDialog) -> Unit,
) {
    val rootLabel = stringResource(R.string.library_title)
    // In der Suche steht bei jedem Treffer, wo er liegt.
    fun location(folder: String?): String? = when {
        !ui.searching -> null
        folder.isNullOrEmpty() -> rootLabel
        else -> folder.replace("/", " › ")
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(ui.folders, key = { "folder:" + it.path }) { tile ->
            FolderCard(
                tile = tile,
                location = location(FolderPaths.parent(tile.path)),
                coverFile = vm::coverFile,
                onOpen = { vm.openFolder(tile.path) },
                onRename = { onDialog(LibraryDialog.RenameFolder(tile.path)) },
                onDelete = { onDialog(LibraryDialog.DeleteFolder(tile.path)) },
            )
        }
        items(ui.books, key = { "book:" + it.uuid }) { book ->
            BookCard(
                book = book,
                location = location(book.folder),
                coverFile = vm.coverFile(book),
                onClick = { onOpenBook(book.uuid) },
                onMove = {
                    onDialog(if (ui.hasLibraryFolder) LibraryDialog.MoveBook(book) else LibraryDialog.ChooseLibraryFolder)
                },
                onEditDetails = { onDialog(LibraryDialog.EditDetails(book)) },
                onRemove = {
                    onDialog(if (book.inLibraryFolder) LibraryDialog.DeleteBook(book) else LibraryDialog.RemoveBook(book))
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    // Das Feld hält seinen Text selbst. Würde es den Text erst über die (asynchrone)
    // Suche zurückbekommen, gingen beim schnellen Tippen Buchstaben verloren.
    var text by rememberSaveable { mutableStateOf(query) }
    val update: (String) -> Unit = {
        text = it
        onQueryChange(it)
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LeatherTopBar(
        medallion = false,
        title = {
            TextField(
                value = text,
                onValueChange = update,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Palette.Brass,
                    unfocusedIndicatorColor = Palette.Brass.copy(alpha = 0.5f),
                    focusedTextColor = Palette.OnWood,
                    unfocusedTextColor = Palette.OnWood,
                    cursorColor = Palette.BrassLight,
                    focusedPlaceholderColor = Palette.BrassLight.copy(alpha = 0.6f),
                    unfocusedPlaceholderColor = Palette.BrassLight.copy(alpha = 0.6f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.search_close))
            }
        },
        actions = {
            if (text.isNotEmpty()) {
                IconButton(onClick = { update("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.clear))
                }
            }
        },
    )
}

@Composable
private fun FilterChips(filter: BookFilter, onFilter: (BookFilter) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .horizontalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    ) {
        listOf(
            BookFilter.ALL to R.string.filter_all,
            BookFilter.UNREAD to R.string.filter_unread,
            BookFilter.STARTED to R.string.filter_started,
            BookFilter.FINISHED to R.string.filter_finished,
        ).forEach { (option, label) ->
            val selected = filter == option
            Plaque(
                text = stringResource(label),
                onClick = { onFilter(option) },
                style = if (selected) PlaqueStyle.BRASS else PlaqueStyle.OUTLINE,
                small = true,
                selected = selected,
            )
        }
    }
}

@Composable
private fun NoResults(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(80.dp))
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        FlourishDivider(MaterialTheme.colorScheme.primary, Modifier.padding(vertical = 12.dp), width = 160.dp, height = 16.dp)
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Plaque(text = action, onClick = onAction, style = PlaqueStyle.OUTLINE, small = true)
        }
    }
}

@Composable
private fun LocationLabel(location: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
        Icon(
            painterResource(R.drawable.ic_folder),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            location,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LibraryMenu(
    sort: LibrarySort,
    hasLibraryFolder: Boolean,
    onNewFolder: () -> Unit,
    onSort: (LibrarySort) -> Unit,
    onRefresh: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.new_folder)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                onClick = {
                    open = false
                    onNewFolder()
                },
            )
            if (hasLibraryFolder) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.refresh)) },
                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    onClick = {
                        open = false
                        onRefresh()
                    },
                )
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.sort_by),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            listOf(LibrarySort.RECENT to R.string.sort_recent, LibrarySort.TITLE to R.string.sort_title)
                .forEach { (option, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        trailingIcon = { if (option == sort) Icon(Icons.Filled.Check, contentDescription = null) },
                        onClick = {
                            open = false
                            onSort(option)
                        },
                    )
                }
        }
    }
}

// ------------------------------------------------------------------- Ordner

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    tile: FolderTile,
    location: String?,
    coverFile: (Book) -> File,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onOpen, onLongClick = { menuOpen = true })
            .padding(8.dp)
    ) {
        ShelfCompartment(tile, coverFile)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = tile.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.folder_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
        location?.let { LocationLabel(it) }
        Spacer(Modifier.height(4.dp))
        Text(
            pluralStringResource(R.plurals.book_count, tile.bookCount, tile.bookCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Ein Ordner als Regalfach aus Holz: Bis zu drei Bücher stehen auf dem Regalboden,
 * oben rechts zeigt ein Messingschild die Anzahl.
 */
@Composable
private fun ShelfCompartment(tile: FolderTile, coverFile: (Book) -> File) {
    val dark = LocalDarkTheme.current
    val shape = RoundedCornerShape(4.dp)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.707f)
            .shadow(4.dp, shape, clip = false)
            .clip(shape)
            .drawBehind { drawShelf(dark, board = BoardHeight.toPx()) },
    ) {
        if (tile.covers.isEmpty()) {
            Fleur(
                Palette.BrassLight.copy(alpha = 0.3f),
                Modifier.align(Alignment.Center),
                width = 34.dp,
            )
        } else {
            // Mittleres Buch vorn, die äußeren lehnen sich leicht an.
            val placements = when (tile.covers.size) {
                1 -> listOf(0f to 0f)
                2 -> listOf(-0.17f to -3f, 0.17f to 3f)
                else -> listOf(-0.27f to -4f, 0.27f to 4f, 0f to 0f)
            }
            val order = if (tile.covers.size >= 3) listOf(0, 2, 1) else tile.covers.indices.toList()
            val shift = with(LocalDensity.current) { maxWidth.toPx() }
            order.forEachIndexed { slot, coverIndex ->
                val book = tile.covers[coverIndex]
                val (dx, angle) = placements[slot]
                CoverImage(
                    book = book,
                    file = coverFile(book),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = BoardHeight + 1.dp)
                        .fillMaxWidth(0.5f)
                        .aspectRatio(0.707f)
                        .graphicsLayer {
                            translationX = shift * dx
                            rotationZ = angle
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                        }
                        .shadow(3.dp, RoundedCornerShape(2.dp)),
                )
            }
        }
        // Messingschild mit der Anzahl der Bücher
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 9.dp, end = 9.dp)
                .shadow(2.dp, RoundedCornerShape(5.dp), clip = false)
                .clip(RoundedCornerShape(5.dp))
                .background(BrassBrush)
                .border(1.dp, Palette.BrassDark, RoundedCornerShape(5.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_folder),
                contentDescription = null,
                tint = Palette.OnBrass,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            // Zahlen in Alegreya: In den alten Fell-Typen sieht die 1 wie ein I aus.
            Text(
                tile.bookCount.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = Palette.OnBrass,
            )
        }
    }
}

/** Höhe des Regalbodens im Regalfach. */
private val BoardHeight = 13.dp

/** Holzfach: Rückwand, Seitenwände mit Schatten und ein Regalboden. */
private fun DrawScope.drawShelf(dark: Boolean, board: Float) {
    drawRect(Textures.woodBrush)
    // Rückwand liegt tiefer: oben und an den Seiten im Schatten.
    drawRect(
        Brush.verticalGradient(
            0f to Color.Black.copy(alpha = 0.5f),
            0.45f to Color.Black.copy(alpha = 0.15f),
            1f to Color.Black.copy(alpha = 0.32f),
        )
    )
    val wall = 5.dp.toPx()
    drawRect(Palette.WoodLight, size = Size(wall, size.height))
    drawRect(Palette.WoodLight, topLeft = Offset(size.width - wall, 0f), size = Size(wall, size.height))
    drawRect(Palette.WoodLight, size = Size(size.width, wall))
    drawRect(Color.Black.copy(alpha = 0.35f), topLeft = Offset(wall, wall), size = Size(3.dp.toPx(), size.height))
    drawRect(Color.Black.copy(alpha = 0.3f), topLeft = Offset(wall, wall), size = Size(size.width - 2 * wall, 3.dp.toPx()))
    // Regalboden
    val top = size.height - board
    drawRect(
        Brush.verticalGradient(listOf(Color(0xFF8A5530), Color(0xFF5A331D)), startY = top, endY = size.height),
        topLeft = Offset(0f, top),
        size = Size(size.width, board),
    )
    drawRect(Color(0xFFB07A48), topLeft = Offset(0f, top), size = Size(size.width, 1.dp.toPx()))
    drawRect(Textures.grainBrush, alpha = 0.8f)
    if (dark) drawRect(Color.Black.copy(alpha = 0.22f))
}

// ------------------------------------------------------------------- Bücher

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: Book,
    location: String?,
    coverFile: File,
    onClick: () -> Unit,
    onMove: () -> Unit,
    onEditDetails: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
            .padding(8.dp)
    ) {
        LeatherFrame(Modifier.fillMaxWidth()) {
            CoverImage(
                book = book,
                file = coverFile,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.707f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.book_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_to_folder)) },
                        leadingIcon = { Icon(painterResource(R.drawable.ic_folder), contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onMove()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.edit_details)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEditDetails()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(stringResource(if (book.inLibraryFolder) R.string.delete else R.string.remove))
                        },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRemove()
                        },
                    )
                }
            }
        }
        // Autor und Jahr; immer eine Zeile, damit die Karten gleich hoch bleiben.
        Text(
            text = listOfNotNull(book.author, book.publishYear?.toString()).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            minLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        location?.let { LocationLabel(it) }
        Spacer(Modifier.height(6.dp))
        if (book.pageCount > 0) {
            RibbonProgress(progress = book.progress)
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.page_of, book.lastPage + 1, book.pageCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.book_new),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CoverImage(book: Book, file: File, modifier: Modifier = Modifier) {
    // pageCount ändert sich, sobald ein neu gefundenes Buch sein Cover bekommen hat.
    val cover by produceState<ImageBitmap?>(null, file.path, book.pageCount) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
        }
    }
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = cover
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = stringResource(R.string.cover_description, book.title),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painterResource(R.drawable.ic_book),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

@Composable
private fun EmptyState(
    inFolder: Boolean,
    hasLibraryFolder: Boolean,
    onAdd: () -> Unit,
    onChooseLibraryFolder: () -> Unit,
) {
    // Scrollbar, damit "Ziehen zum Aktualisieren" auch hier funktioniert.
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(72.dp))
        Fleur(MaterialTheme.colorScheme.primary, width = 52.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(if (inFolder) R.string.empty_folder_title else R.string.empty_title),
            style = EasyBooksType.emptyTitle,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        FlourishDivider(MaterialTheme.colorScheme.primary, Modifier.padding(vertical = 8.dp), width = 170.dp, height = 16.dp)
        Text(
            stringResource(if (inFolder) R.string.empty_folder_text else R.string.empty_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Plaque(text = stringResource(R.string.add_pdf), onClick = onAdd, icon = Icons.Filled.Add)
        if (!inFolder && !hasLibraryFolder) {
            Spacer(Modifier.height(12.dp))
            Plaque(
                text = stringResource(R.string.library_folder_choose),
                onClick = onChooseLibraryFolder,
                style = PlaqueStyle.OUTLINE,
            )
        }
    }
}
