package com.example.easybooks.ui.reader

import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.easybooks.R
import com.example.easybooks.data.PageTurn
import com.example.easybooks.data.ZoomMode
import com.example.easybooks.pdf.Crop
import com.example.easybooks.ui.theme.DarkReaderColors
import com.example.easybooks.ui.theme.LocalReaderColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val PageHPadding = 12.dp
internal val PageHPaddingClose = 2.dp
private val PageVPadding = 16.dp
internal val ReflowVPadding = 10.dp

/** Andere Papierflecken für neu umbrochene Seiten als für PDF-Seiten mit gleicher Nummer. */
private const val REFLOW_SEED = 50021

/**
 * Eine einzelne PDF-Seite im Reader (Schriftgröße Klein oder Normal).
 *
 * Links und rechts wird nie etwas abgeschnitten: Die Seite ist höchstens so breit
 * wie der Bildschirm. Ist sie dadurch höher als der Bildschirm, wird vertikal gescrollt.
 *
 * @param pageOffset Abstand dieser Seite zur aktuell sichtbaren Position im Pager:
 *   0 = sichtbar, > 0 = liegt links (schon umgeblättert), < 0 = liegt rechts (kommt noch).
 * @param darkPages PDF-Seite dunkel darstellen (Dark Mode).
 * @param grabRootY Höhe, an der der Finger zuletzt aufgesetzt hat (Bildschirmkoordinaten, NaN = unbekannt).
 *   Beim Umblättern hebt sich die Seite zuerst an dieser Stelle.
 * @param onTap Tippen mit x-Position und Breite der Fläche (für die Blätter-Zonen).
 */
@Composable
internal fun PageView(
    vm: ReaderViewModel,
    page: Int,
    pageCount: Int,
    zoom: ZoomMode,
    pageTurn: PageTurn,
    spread: Boolean,
    darkPages: Boolean,
    pageOffset: () -> Float,
    grabRootY: () -> Float,
    onTap: (x: Float, width: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val side = pageSide(page, spread)
    PageBox(page, pageTurn, spread, side, pageOffset, onTap, modifier) {
        val pageSize by produceState<Size?>(null, page) { value = vm.pageSize(page) }
        val crop = rememberResult(page, zoom) {
            when (zoom) {
                ZoomMode.WIDE -> Crop.FULL
                // "Normal": Ränder ganz weg, der Text füllt die Breite. ("Groß" wird neu
                // umbrochen und läuft über ReflowPageView.)
                ZoomMode.NORMAL, ZoomMode.CLOSE -> vm.pageCrop(page, tight = true)
            }
        }
        // Seitengröße der PDF in Punkten. Bewusst nicht "size" genannt: In den Zeichen-
        // Lambdas meint "size" die Fläche in Pixeln (DrawScope.size).
        val pdfSize = pageSize
        if (pdfSize == null || crop == null) return@PageBox

        // Seitenverhältnis des sichtbaren Ausschnitts.
        val aspect = (pdfSize.height * crop.height) / (pdfSize.width * crop.width)
        // Ohne Ränder zählt jeder Millimeter, dort ist der Abstand zum Bildschirmrand schmaler.
        // Im aufgeschlagenen Buch liegt die Seite am Bundsteg bündig, außen sind Einband und Buchblock.
        val hPadding = if (zoom == ZoomMode.WIDE) PageHPadding else PageHPaddingClose
        val vPadding = if (side == PageSide.SINGLE) PageVPadding else BookVerticalMargin
        val availableWidth = if (side == PageSide.SINGLE) maxWidth - hPadding * 2 else maxWidth - BookOuterMargin
        val availableHeight = maxHeight - vPadding * 2
        val width = when (zoom) {
            ZoomMode.WIDE -> minOf(availableWidth, availableHeight / aspect)
            ZoomMode.NORMAL, ZoomMode.CLOSE -> availableWidth
        }
        val height = width * aspect
        val scrollable = height > availableHeight + 1.dp

        val density = LocalDensity.current
        val widthPx = with(density) { width.roundToPx() }
        val fray = with(density) { FrayDepth.toPx() }
        val aged = LocalReaderColors.current.aged
        // Im Dark Mode und auf altem Papier erst wissen, ob es eine Bildseite ist, damit nichts aufblitzt.
        val imagePage = if (darkPages || aged) rememberResult(page, "image") { vm.isImagePage(page) } else false
        // Ansicht "Altes Buch": Ist die erste Seite ein Bild, ist sie der feste Buchdeckel.
        val cover = side == PageSide.RIGHT && page == 0 && imagePage == true
        val bitmap = rememberResult(page, widthPx, crop, aged, darkPages, imagePage, cover) {
            val raw = vm.renderPage(page, widthPx, crop)
            when {
                cover -> withContext(Dispatchers.Default) { HardCover.front(raw, darkPages, density.density) }
                aged && imagePage != null -> withContext(Dispatchers.Default) {
                    AgedPaper.print(
                        raw, seed = page, dark = darkPages, picture = imagePage, fray = fray,
                        unit = density.density, bound = side.boundEdge,
                    )
                }
                else -> raw
            }
        }
        val back = if (cover) rememberInside(bitmap, darkPages) else rememberBack(bitmap, aged && pageTurn == PageTurn.CURL, darkPages)

        Sheet(
            page = page,
            width = width,
            height = height,
            bitmap = bitmap,
            imagePage = imagePage,
            readFraction = if (pageCount > 1) page / (pageCount - 1f) else 0f,
            pageTurn = pageTurn,
            spread = spread,
            side = if (cover) PageSide.COVER else side,
            back = back,
            darkPages = darkPages,
            pageOffset = pageOffset,
            grabRootY = grabRootY,
            scrollable = scrollable,
            vPadding = vPadding,
        )
    }
}

/**
 * Eine neu umbrochene Bildschirmseite (Schriftgröße Groß). Sie füllt den Bildschirm,
 * gescrollt wird nie.
 */
@Composable
internal fun ReflowPageView(
    vm: ReaderViewModel,
    layout: ReflowLayout,
    index: Int,
    pageTurn: PageTurn,
    spread: Boolean,
    darkPages: Boolean,
    pageOffset: () -> Float,
    grabRootY: () -> Float,
    onTap: (x: Float, width: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val side = pageSide(index, spread)
    PageBox(index, pageTurn, spread, side, pageOffset, onTap, modifier) {
        val frame = layout.params.frame
        val density = LocalDensity.current
        val fray = with(density) { FrayDepth.toPx() }
        val screenPage = layout.pages.getOrNull(index)
        val imageOnly = screenPage?.imageOnly == true
        val aged = LocalReaderColors.current.aged
        val cover = side == PageSide.RIGHT && index == 0 && imageOnly
        val bitmap = rememberResult(layout.params, index, aged, darkPages, cover) {
            val raw = vm.renderReflowPage(layout, index)
            when {
                raw == null -> null
                cover -> withContext(Dispatchers.Default) { HardCover.front(raw, darkPages, density.density) }
                aged -> withContext(Dispatchers.Default) {
                    AgedPaper.print(
                        raw, seed = REFLOW_SEED + index, dark = darkPages, picture = imageOnly,
                        fray = fray, unit = density.density, bound = side.boundEdge,
                    )
                }
                else -> raw
            }
        }
        val back = if (cover) rememberInside(bitmap, darkPages) else rememberBack(bitmap, aged && pageTurn == PageTurn.CURL, darkPages)
        // Ganzseitige Bilder (z. B. Cover) liegen auf einem Blatt in ihrer eigenen Größe.
        val image = screenPage?.placements?.singleOrNull()?.takeIf { imageOnly }
        val count = layout.estimatedCount
        Sheet(
            page = index,
            width = with(density) { (image?.dstWidth ?: frame.width.toFloat()).toDp() },
            height = with(density) { (image?.dstHeight ?: frame.height.toFloat()).toDp() },
            bitmap = bitmap,
            imagePage = if (darkPages || aged) imageOnly else false,
            readFraction = if (count > 1) (index / (count - 1f)).coerceAtMost(1f) else 0f,
            pageTurn = pageTurn,
            spread = spread,
            side = if (cover) PageSide.COVER else side,
            back = back,
            darkPages = darkPages,
            pageOffset = pageOffset,
            grabRootY = grabRootY,
            scrollable = false,
            vPadding = PageVPadding,
        )
    }
}

/** Fläche einer Seite im Pager: Blätter-Zonen, Buch-Modus und Abstand zur Kamera-Aussparung. */
@Composable
private fun PageBox(
    page: Int,
    pageTurn: PageTurn,
    spread: Boolean,
    side: PageSide,
    pageOffset: () -> Float,
    onTap: (x: Float, width: Float) -> Unit,
    modifier: Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    val currentOnTap by rememberUpdatedState(onTap)
    val curl = pageTurn == PageTurn.CURL
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .then(if (curl) Modifier.curlTransform(page, spread, pageOffset) else Modifier)
            .pointerInput(Unit) {
                detectTapGestures { offset -> currentOnTap(offset.x, size.width.toFloat()) }
            }
            .windowInsetsPadding(WindowInsets.displayCutout),
        // Im aufgeschlagenen Buch liegt jede Seite am Bundsteg: rechte Seiten links, linke rechts.
        contentAlignment = when (side) {
            PageSide.SINGLE -> Alignment.Center
            PageSide.RIGHT, PageSide.COVER -> Alignment.CenterStart
            PageSide.LEFT -> Alignment.CenterEnd
        },
        content = content,
    )
}

/**
 * Die Seite als Blatt Papier; während des Umblätterns als gebogenes 3D-Blatt.
 *
 * @param imagePage `null`, solange noch unklar ist, ob die Seite ein Bild ist (Dark Mode)
 */
@Composable
private fun Sheet(
    page: Int,
    width: Dp,
    height: Dp,
    bitmap: ImageBitmap?,
    imagePage: Boolean?,
    readFraction: Float,
    pageTurn: PageTurn,
    spread: Boolean,
    side: PageSide,
    back: ImageBitmap?,
    darkPages: Boolean,
    pageOffset: () -> Float,
    grabRootY: () -> Float,
    scrollable: Boolean,
    vPadding: Dp,
) {
    val curl = pageTurn == PageTurn.CURL
    val readerColors = LocalReaderColors.current
    val paperColor = readerColors.paper
    val nightFilter = remember { PageColors.night(DarkReaderColors.paper, DarkReaderColors.ink) }
    val shadowColor = readerColors.shade
    // Nur während die Seite gerade umgeblättert wird, zeichnen wir sie als gebogenes 3D-Blatt.
    val turning by remember(curl, spread, page) {
        derivedStateOf { curl && curlsAfter(page, spread) && pageOffset().let { it > 0f && it < 1f } }
    }
    val colorFilter = when {
        // Altes Papier: Die Seite ist schon auf das Papier gedruckt.
        readerColors.aged -> null
        !darkPages -> PageColors.day
        imagePage == true -> PageColors.dimmedImage
        else -> nightFilter
    }

    val paperTop = remember { FloatArray(1) }
    val screenHeight = LocalView.current.height.toFloat()
    val paper: @Composable () -> Unit = {
        if (turning) {
            Canvas(
                Modifier
                    .size(width, height)
                    .onGloballyPositioned { paperTop[0] = it.positionInRoot().y }
            ) {
                val top = paperTop[0]
                // Bei hohen Seiten ist nur ein Teil sichtbar. Griff und Perspektive
                // beziehen sich auf diesen sichtbaren Teil.
                val visibleTop = top.coerceAtLeast(0f)
                val visibleBottom = (top + size.height).coerceAtMost(screenHeight).coerceAtLeast(visibleTop + 1f)
                val y = grabRootY()
                val grabY = if (y.isNaN()) (visibleTop + visibleBottom) / 2f else y.coerceIn(visibleTop, visibleBottom)
                if (side == PageSide.COVER) {
                    drawHardCover(pageOffset(), bitmap, back, shadowColor, darkPages)
                    return@Canvas
                }
                drawCurledPage(
                    progress = pageOffset(),
                    grab = ((grabY - visibleTop) / (visibleBottom - visibleTop)).coerceIn(0f, 1f),
                    visibleTop = visibleTop - top,
                    visibleBottom = visibleBottom - top,
                    bitmap = bitmap.takeIf { imagePage != null },
                    colorFilter = colorFilter,
                    paperColor = paperColor,
                    shadowColor = shadowColor,
                    backBitmap = back,
                )
            }
        } else Paper(
            paperColor = paperColor,
            darkPaper = darkPages,
            readFraction = readFraction,
            dim = {
                // Die Seite darunter ist dunkler, solange sie noch bedeckt ist.
                val offset = pageOffset()
                if (curl && curlsAfter(page - 1, spread) && offset < 0f && offset > -1f) -offset else 0f
            },
            aged = readerColors.aged,
            side = side,
            modifier = Modifier.size(width, height),
        ) {
            if (bitmap != null && imagePage != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.page_description, page + 1),
                    contentScale = ContentScale.FillBounds,
                    colorFilter = colorFilter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (scrollable) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = vPadding),
            horizontalAlignment = when (side) {
                PageSide.SINGLE -> Alignment.CenterHorizontally
                PageSide.RIGHT, PageSide.COVER -> Alignment.Start
                PageSide.LEFT -> Alignment.End
            },
        ) { paper() }
    } else {
        paper()
    }
}

/**
 * Lädt einen Wert im Hintergrund und liefert ihn nur, solange er zu den aktuellen
 * [keys] gehört. So wird beim Wechsel des Zooms kein veraltetes Bild verzerrt angezeigt.
 */
@Composable
private fun <T> rememberResult(vararg keys: Any?, load: suspend () -> T): T? {
    val keyList = keys.toList()
    val result by produceState<Pair<List<Any?>, T>?>(null, *keys) { value = keyList to load() }
    return result?.takeIf { it.first == keyList }?.second
}

/** Lage der Seite: im aufgeschlagenen Buch liegt Seite 1 (Index 0) rechts. */
internal fun pageSide(page: Int, spread: Boolean): PageSide = when {
    !spread -> PageSide.SINGLE
    page.mod(2) == 0 -> PageSide.RIGHT
    else -> PageSide.LEFT
}

/** Kante am Bundsteg (gebunden, nicht ausgefranst): 3 = links, 1 = rechts, -1 = keine. */
private val PageSide.boundEdge: Int
    get() = when (this) {
        PageSide.SINGLE, PageSide.COVER -> -1
        PageSide.RIGHT -> 3
        PageSide.LEFT -> 1
    }

/** Innenseite des Buchdeckels (Leder und Vorsatzpapier) in der Größe des Deckels. */
@Composable
private fun rememberInside(front: ImageBitmap?, dark: Boolean): ImageBitmap? {
    val density = LocalDensity.current.density
    return if (front != null) {
        rememberResult(front.width, front.height, dark) {
            withContext(Dispatchers.Default) { HardCover.inside(front.width, front.height, dark, density) }
        }
    } else null
}

/** Rückseite des Blatts für das Umblättern (altes Papier, ausgefranst, Schrift scheint durch). */
@Composable
private fun rememberBack(front: ImageBitmap?, enabled: Boolean, dark: Boolean): ImageBitmap? =
    if (enabled && front != null) {
        rememberResult(front, dark) { withContext(Dispatchers.Default) { AgedPaper.back(front, dark) } }
    } else null

/**
 * Wird nach Seite [page] umgeblättert (sonst gleiten die Seiten wie in der Slideshow)?
 *
 * Mit [spread] liest man wie in einem aufgeschlagenen Buch: Seite 1 liegt rechts, Seite 2
 * links, Seite 3 rechts usw. Von der linken zur rechten Seite wandern nur die Augen weiter,
 * die Seiten gleiten. Erst nach einer rechten Seite (gerader Index) wird umgeblättert.
 */
internal fun curlsAfter(page: Int, spread: Boolean): Boolean = !spread || page.mod(2) == 0

/**
 * Buch-Modus: Alle Seiten bleiben an ihrem Platz liegen, statt mit dem Pager zur Seite
 * zu gleiten. Seiten mit kleinerer Nummer liegen oben. Die oberste Seite wird während
 * des Wischens als gebogenes 3D-Blatt gezeichnet (siehe [drawCurledPage]).
 * Bei Doppelseiten ([spread]) gleitet der Übergang von links nach rechts wie im Pager.
 */
private fun Modifier.curlTransform(page: Int, spread: Boolean, pageOffset: () -> Float): Modifier =
    zIndex(-page.toFloat()).graphicsLayer {
        val offset = pageOffset()
        // Übergang, an dem diese Seite gerade beteiligt ist: nach ihr (offset > 0) oder vor ihr.
        val transition = if (offset >= 0f) page else page - 1
        if (curlsAfter(transition, spread)) {
            // Natürliche Position wäre um offset Seitenbreiten verschoben – das heben wir auf.
            translationX = offset * size.width
            // Nur die beiden beteiligten Seiten sind sichtbar.
            alpha = if (offset <= -1f || offset >= 1f) 0f else 1f
        } else {
            translationX = 0f
            alpha = 1f
        }
    }
