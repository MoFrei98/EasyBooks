package com.example.easybooks.ui.reader

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.easybooks.R
import com.example.easybooks.data.AppSettings
import com.example.easybooks.data.ReadingView
import com.example.easybooks.data.PageTurn
import com.example.easybooks.data.ZoomMode
import com.example.easybooks.reflow.ReflowFrame
import com.example.easybooks.ui.settings.ThemeToggleButton
import com.example.easybooks.ui.theme.BrassRule
import com.example.easybooks.ui.theme.LeatherTopBar
import com.example.easybooks.ui.theme.LocalDarkTheme
import com.example.easybooks.ui.theme.Palette
import com.example.easybooks.ui.theme.Plaque
import com.example.easybooks.ui.theme.PlaqueStyle
import com.example.easybooks.ui.theme.brassSliderColors
import com.example.easybooks.ui.theme.leatherBackground
import com.example.easybooks.ui.theme.woodBackground
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    bookId: String,
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleDark: () -> Unit,
) {
    val vm: ReaderViewModel = viewModel(factory = ReaderViewModel.factory(bookId))
    val state by vm.state.collectAsStateWithLifecycle()
    LightNavigationIcons()

    Box(
        Modifier
            .fillMaxSize()
            // Die Seiten liegen auf einem Lesetisch aus Holz.
            .woodBackground(LocalDarkTheme.current)
    ) {
        when (val s = state) {
            ReaderState.Loading -> CircularProgressIndicator(color = Palette.BrassLight, modifier = Modifier.align(Alignment.Center))
            is ReaderState.Failed -> ReaderError(
                state = s,
                onBack = onBack,
                onRemove = {
                    vm.removeBook()
                    onBack()
                },
                modifier = Modifier.align(Alignment.Center),
            )
            is ReaderState.Ready -> Reader(vm, s, settings, onBack, onOpenSettings, onToggleDark)
        }
    }
}

@Composable
private fun Reader(
    vm: ReaderViewModel,
    ready: ReaderState.Ready,
    settings: AppSettings,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleDark: () -> Unit,
) {
    val actions: @Composable RowScope.() -> Unit = {
        ThemeToggleButton(onToggle = onToggleDark)
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
        }
    }
    if (settings.pageSize == ZoomMode.CLOSE) {
        ReflowReader(vm, ready, settings, onBack, actions)
    } else {
        // Startseite nur beim Betreten festlegen, nicht bei jedem Umblättern neu.
        val initialPage = remember { vm.position.page }
        PagedReader(
            title = ready.title,
            settings = settings,
            initialPage = initialPage,
            pageCount = ready.pageCount,
            shownCount = ready.pageCount,
            estimated = false,
            onSettled = vm::onPageSettled,
            onBack = onBack,
            actions = actions,
        ) { page, pageOffset, grabRootY, onTap ->
            PageView(
                vm = vm,
                page = page,
                pageCount = ready.pageCount,
                zoom = settings.pageSize,
                pageTurn = settings.pageTurn,
                spread = settings.spread,
                // Im Dark Mode werden die Seiten immer abgedunkelt.
                darkPages = LocalDarkTheme.current,
                pageOffset = pageOffset,
                grabRootY = grabRootY,
                onTap = onTap,
            )
        }
    }
}

/**
 * Schriftgröße "Groß": Der Text wird neu umbrochen. Beim ersten Öffnen eines Buchs wird es
 * dafür vorbereitet, bis die Lesestelle erreicht ist; der Rest folgt beim Lesen.
 */
@Composable
private fun ReflowReader(
    vm: ReaderViewModel,
    ready: ReaderState.Ready,
    settings: AppSettings,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val cutout = WindowInsets.displayCutout.asPaddingValues()
        // Im aufgeschlagenen Buch braucht es außen, oben und unten Platz für Einband und Buchblock.
        val book = settings.spread
        val params = with(density) {
            val width = maxWidth - cutout.calculateLeftPadding(direction) -
                cutout.calculateRightPadding(direction) - (if (book) BookOuterMargin else PageHPaddingClose * 2)
            val height = maxHeight - cutout.calculateTopPadding() - cutout.calculateBottomPadding() -
                (if (book) BookVerticalMargin else ReflowVPadding) * 2
            ReflowParams(
                frame = ReflowFrame(
                    width = width.roundToPx(),
                    height = height.roundToPx(),
                    marginX = ReflowMarginX.roundToPx(),
                    marginTop = ReflowMarginTop.roundToPx(),
                    marginBottom = ReflowMarginBottom.roundToPx(),
                ),
                targetXHeight = LargeTextXHeight.toPx(),
            )
        }
        LaunchedEffect(params) { vm.startReflow(params) }
        val layout by vm.reflowLayout.collectAsStateWithLifecycle()
        val current = layout?.takeIf { it.params == params }
        val initialPage = current?.let { vm.reflowIndexOfPosition(it) }

        if (current == null || initialPage == null) {
            val progress by vm.reflowProgress.collectAsStateWithLifecycle()
            ReflowPreparing(progress, ready.title, onBack, actions)
            return@BoxWithConstraints
        }
        // Neue Bildschirmgröße = neue Seiten: Pager mit der Lesestelle neu beginnen.
        key(params) {
            val start = remember { initialPage }
            PagedReader(
                title = ready.title,
                settings = settings,
                initialPage = start,
                pageCount = current.pages.size,
                shownCount = current.estimatedCount,
                estimated = !current.complete,
                onSettled = { vm.onReflowPageSettled(current, it) },
                onBack = onBack,
                actions = actions,
            ) { index, pageOffset, grabRootY, onTap ->
                ReflowPageView(
                    vm = vm,
                    layout = current,
                    index = index,
                    pageTurn = settings.pageTurn,
                    spread = settings.spread,
                    darkPages = LocalDarkTheme.current,
                    pageOffset = pageOffset,
                    grabRootY = grabRootY,
                    onTap = onTap,
                )
            }
        }
    }
}

@Composable
private fun ReflowPreparing(
    progress: Float,
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                progress = { progress.coerceIn(0.02f, 1f) },
                color = Palette.BrassLight,
                trackColor = Palette.BrassLight.copy(alpha = 0.2f),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.reflow_preparing, (progress * 100).roundToInt()),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = Palette.OnWood,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.reflow_preparing_hint),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = Palette.BrassLight.copy(alpha = 0.8f),
            )
        }
        ReaderTopBar(title = title, onBack = onBack, actions = actions)
    }
}

/**
 * Pager mit Leisten, Blätter-Zonen und Geräusch, für beide Darstellungen.
 *
 * @param pageCount Seiten, zu denen man blättern kann (wächst bei "Groß" während der Vorbereitung)
 * @param shownCount Angezeigte Seitenzahl, bei [estimated] geschätzt
 */
@Composable
private fun PagedReader(
    title: String,
    settings: AppSettings,
    initialPage: Int,
    pageCount: Int,
    shownCount: Int,
    estimated: Boolean,
    onSettled: (Int) -> Unit,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    pageContent: @Composable (
        page: Int,
        pageOffset: () -> Float,
        grabRootY: () -> Float,
        onTap: (x: Float, width: Float) -> Unit,
    ) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, max(0, pageCount - 1))) { pageCount }
    val scope = rememberCoroutineScope()
    var barsVisible by rememberSaveable { mutableStateOf(true) }
    val currentOnSettled by rememberUpdatedState(onSettled)

    // Fortschritt speichern und die Leisten beim Weiterblättern ausblenden.
    LaunchedEffect(pagerState) {
        var firstPage = true
        snapshotFlow { pagerState.settledPage }.collect { page ->
            currentOnSettled(page)
            if (!firstPage) barsVisible = false
            firstPage = false
        }
    }

    ImmersiveMode(enabled = !barsVisible)

    val bookPages = settings.pageTurn == PageTurn.CURL
    val spread = settings.spread
    val context = LocalContext.current
    val sound = remember(settings.pageTurnSound) {
        if (settings.pageTurnSound) PageTurnSound(context.applicationContext) else null
    }
    DisposableEffect(sound) { onDispose { sound?.close() } }

    // Seite, für die das Geräusch zuletzt gespielt wurde (verhindert doppeltes Rascheln).
    val soundedPage = remember { IntArray(1) { -1 } }
    LaunchedEffect(pagerState, sound, bookPages, spread) {
        if (sound == null) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage to pagerState.settledPage }.collect { (current, settled) ->
            when {
                current == settled -> soundedPage[0] = settled
                // Beim Wischen kippt die Seite über die Mitte: jetzt raschelt es.
                abs(current - settled) == 1 && current != soundedPage[0] -> {
                    soundedPage[0] = current
                    // Von der linken zur rechten Seite gehen nur die Augen weiter: kein Rascheln.
                    if (curlsAfter(min(current, settled), spread)) sound.play(bookPages)
                }
            }
        }
    }

    /**
     * Umblättern per Tippen auf den Rand: langsam genug, dass man die Animation sieht.
     * Schnelles Mehrfach-Tippen zählt vom Ziel der laufenden Animation weiter.
     */
    fun turnBy(delta: Int) {
        val base = if (pagerState.isScrollInProgress) pagerState.targetPage else pagerState.currentPage
        val target = (base + delta).coerceIn(0, pagerState.pageCount - 1)
        if (target == base) return
        soundedPage[0] = target
        // Bei Doppelseiten wird nur nach einer rechten Seite umgeblättert, sonst gleiten die Seiten.
        val turnsLeaf = bookPages && curlsAfter(min(base, target), spread)
        if (!spread || turnsLeaf) sound?.play(bookPages)
        scope.launch {
            pagerState.animateScrollToPage(
                page = target,
                animationSpec = tween(
                    durationMillis = if (turnsLeaf) TAP_TURN_BOOK_MS else TAP_TURN_SLIDE_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Wo greift der Finger die Seite? Danach richtet sich die Faltung beim Umblättern.
        // Wird nur beobachtet, nicht verbraucht – Wischen und Tippen funktionieren normal.
        var grabRootY by remember { mutableFloatStateOf(Float.NaN) }
        val pagerTop = remember { FloatArray(1) }

        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            key = { it },
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { pagerTop[0] = it.positionInRoot().y }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull() ?: continue
                            // Nur beim Aufsetzen: Beim Wischen fährt ein Daumen meist im
                            // Bogen nach unten, die Seite soll aber dort gegriffen bleiben.
                            if (change.pressed && !change.previousPressed) {
                                grabRootY = pagerTop[0] + change.position.y
                            }
                        }
                    }
                },
        ) { page ->
            pageContent(
                page,
                { (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction },
                { grabRootY },
                { x, width ->
                    when {
                        x < width * 0.25f -> turnBy(-1)
                        x > width * 0.75f -> turnBy(+1)
                        else -> barsVisible = !barsVisible
                    }
                },
            )
        }

        AnimatedVisibility(
            visible = barsVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(title = title, onBack = onBack, actions = actions)
        }

        AnimatedVisibility(
            visible = barsVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                currentPage = pagerState.currentPage,
                pageCount = pageCount,
                shownCount = shownCount,
                estimated = estimated,
                onSeek = { page ->
                    // Springen über den Regler ist kein Umblättern: kein Geräusch.
                    soundedPage[0] = page
                    scope.launch { pagerState.scrollToPage(page) }
                },
            )
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    LeatherTopBar(
        medallion = false,
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        actions = actions,
    )
}

@Composable
private fun ReaderBottomBar(
    currentPage: Int,
    pageCount: Int,
    shownCount: Int,
    estimated: Boolean,
    onSeek: (Int) -> Unit,
) {
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shownPage = dragValue?.roundToInt() ?: currentPage
    val night = LocalDarkTheme.current
    Column(
        Modifier
            .fillMaxWidth()
            .leatherBackground(night, flip = true)
    ) {
        BrassRule(medallion = false, modifier = Modifier.graphicsLayer { rotationZ = 180f })
        Column(
            Modifier
                .navigationBarsPadding()
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(
                    if (estimated) R.string.page_of_estimated else R.string.page_of,
                    shownPage + 1,
                    max(shownCount, pageCount),
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.BrassLight,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (pageCount > 1) {
                Slider(
                    colors = brassSliderColors(),
                    value = dragValue ?: currentPage.toFloat(),
                    onValueChange = { dragValue = it },
                    onValueChangeFinished = {
                        dragValue?.let { onSeek(it.roundToInt()) }
                        dragValue = null
                    },
                    valueRange = 0f..(pageCount - 1).toFloat(),
                )
            }
        }
    }
}

@Composable
private fun ReaderError(
    state: ReaderState.Failed,
    onBack: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = Palette.OnWood,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Plaque(text = stringResource(R.string.back), onClick = onBack, style = PlaqueStyle.LEATHER, small = true)
            if (state.canRemove) {
                Plaque(text = stringResource(R.string.remove_from_library), onClick = onRemove, small = true)
            }
        }
    }
}

/** Unten liegt im Reader eine Lederleiste oder Holz: helle Symbole in der Navigationsleiste. */
@Composable
private fun LightNavigationIcons() {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    val dark = LocalDarkTheme.current
    DisposableEffect(dark) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightNavigationBars = false
        onDispose { controller.isAppearanceLightNavigationBars = !dark }
    }
}

/** Blendet Status- und Navigationsleiste aus, solange [enabled] gilt. */
@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    DisposableEffect(enabled) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** x-Höhe der Schrift bei "Groß" (wie eine etwa 23 sp große Systemschrift). */
private val LargeTextXHeight = 11.5.sp

/** Ränder des Texts auf dem Blatt bei "Groß". */
private val ReflowMarginX = 14.dp
private val ReflowMarginTop = 18.dp
private val ReflowMarginBottom = 22.dp

/** Dauer des Umblätterns beim Tippen auf den Rand. */
private const val TAP_TURN_BOOK_MS = 1000
private const val TAP_TURN_SLIDE_MS = 450

/**
 * Lesen wie in einem aufgeschlagenen Buch (Ansicht "Altes Buch"): Dann liegen immer eine
 * linke und eine rechte Seite nebeneinander (siehe [curlsAfter]).
 */
private val AppSettings.spread: Boolean
    get() = view == ReadingView.OLD_BOOK
