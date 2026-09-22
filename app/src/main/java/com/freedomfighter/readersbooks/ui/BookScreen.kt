package com.freedomfighter.readersbooks.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freedomfighter.readersbooks.App
import com.freedomfighter.readersbooks.R
import com.freedomfighter.readersbooks.books.Block
import com.freedomfighter.readersbooks.books.Book
import com.freedomfighter.readersbooks.books.Entry
import com.freedomfighter.readersbooks.books.ImageStore
import com.freedomfighter.readersbooks.books.Magazine
import com.freedomfighter.readersbooks.books.ParaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Default reading size follows the screen: about one twentieth of its width, clamped. */
@Composable
fun defaultReaderSp(): Int {
    val w = LocalConfiguration.current.smallestScreenWidthDp
    return (w / 20).coerceIn(17, 24)
}

/**
 * A book from the shelf: a paginated plain-text reader. Tap right half = next page, left
 * half = previous, long press = menu. Back returns to the shelf.
 */
@Composable
fun BookScreen(nav: Nav, app: App, id: String) {
    val books by app.library.books.collectAsState()
    val entry = books.firstOrNull { it.id == id }
    var menu by remember { mutableStateOf(false) }
    BackHandler { nav.pop() }
    if (entry == null) { nav.pop(); return }
    Page {
        Reader(app, entry, onMenu = { menu = true })
        if (menu) {
            val settings by app.prefs.settings.collectAsState()
            val current = if (settings.readerSp > 0) settings.readerSp else defaultReaderSp()
            val dark = LocalColors.current.isDark
            TextMenu(
                title = entry.title,
                items = buildList {
                    if (entry.magazine) add(MenuItem(stringResource(R.string.contents)) { nav.home(); nav.push(Screen.Chapters(id)) })
                    else add(MenuItem(stringResource(R.string.chapters)) { nav.push(Screen.Chapters(id)) })
                    add(MenuItem(stringResource(R.string.larger_text), "$current → ${(current + 2).coerceAtMost(40)}") { app.prefs.setReaderSp((current + 2).coerceAtMost(40)) })
                    add(MenuItem(stringResource(R.string.smaller_text), "$current → ${(current - 2).coerceAtLeast(12)}") { app.prefs.setReaderSp((current - 2).coerceAtLeast(12)) })
                    add(MenuItem(stringResource(R.string.remove)) { app.library.remove(id); nav.pop() })
                },
                footer = listOf(
                    MenuItem(stringResource(R.string.shelf)) { nav.pop() },
                    MenuItem(if (dark) stringResource(R.string.theme_light) else stringResource(R.string.theme_dark)) { app.prefs.toggleTheme(dark) }
                ),
                onDismiss = { menu = false }
            )
        }
    }
}

/**
 * One chapter laid out at the current width/size: pages made of whole text lines and of
 * pictures. Text goes through android.text.StaticLayout, the same engine used by working
 * open-source readers: getLineTop/getLineBottom describe exactly what StaticLayout.draw paints,
 * so a page holds only whole lines and nothing is clipped or repeated. A picture is a box
 * scaled to the text width (never taller than the page) that moves to the next page whole.
 */
private sealed class Piece(val y: Int, val charBase: Int) {
    class Lines(y: Int, charBase: Int, val layout: StaticLayout, val first: Int, val last: Int) : Piece(y, charBase) {
        val top: Int get() = layout.getLineTop(first)
        val height: Int get() = layout.getLineBottom(last) - top
        val startChar: Int get() = charBase + layout.getLineStart(first)
    }
    class Picture(y: Int, charBase: Int, val source: String, val width: Int, val height: Int) : Piece(y, charBase)
}

private class PageDef(val pieces: List<Piece>) {
    val startChar: Int = pieces.firstOrNull()?.let { if (it is Piece.Lines) it.startChar else it.charBase } ?: 0
}

private class ChapterLayout(val pages: List<PageDef>) {
    val pageCount: Int get() = pages.size
    fun pageStartChar(page: Int): Int = pages[page].startChar
    fun pageFor(charOffset: Int): Int {
        var p = 0
        for (i in pages.indices) if (pages[i].startChar <= charOffset) p = i else break
        return p
    }
}

private fun buildLayout(text: CharSequence, paint: TextPaint, widthPx: Int, spacingMul: Float): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setLineSpacing(0f, spacingMul)
        .setIncludePad(false)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
        .build()

private fun paginate(ch: Book.Chapter, paint: TextPaint, dimArgb: Int, widthPx: Int, pageHeight: Int, images: ImageStore): ChapterLayout {
    val pages = ArrayList<PageDef>()
    var pieces = ArrayList<Piece>()
    var y = 0
    var charBase = 0
    val gap = (paint.textSize * 0.8f).toInt()
    fun newPage() { if (pieces.isNotEmpty()) { pages += PageDef(pieces); pieces = ArrayList() }; y = 0 }
    for (block in ch.blocks) when (block) {
        is Block.Text -> {
            val layout = buildLayout(blockText(block, dimArgb), paint, widthPx, 1.45f)
            if (y > 0) y += gap
            var line = 0
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                var l = line
                // A line belongs to the page only if its whole box fits.
                while (l < layout.lineCount && layout.getLineBottom(l) - top <= pageHeight - y) l++
                if (l == line) {
                    if (y > 0) { newPage(); continue } // try again at the top of a fresh page
                    l++ // a single line taller than the page
                }
                pieces += Piece.Lines(y, charBase, layout, line, l - 1)
                y += layout.getLineBottom(l - 1) - top
                line = l
                if (line < layout.lineCount) newPage()
            }
            charBase += block.length
        }
        is Block.Image -> {
            val size = images.size(block.source)
            if (size != null && size.width >= 48 && size.height >= 48) {
                var w = widthPx
                var h = (w.toLong() * size.height / size.width).toInt()
                if (h > pageHeight) { h = pageHeight; w = (h.toLong() * size.width / size.height).toInt() }
                if (y > 0) y += gap
                if (y + h > pageHeight) newPage()
                pieces += Piece.Picture(y, charBase, block.source, w, h)
                y += h
            }
            charBase += 1
        }
    }
    newPage()
    if (pages.isEmpty()) pages += PageDef(emptyList())
    return ChapterLayout(pages)
}

/**
 * One single string for a run of paragraphs. Multi-paragraph layouts report line tops and
 * bottoms that do not match what is drawn once a line height is set (paragraph boundaries
 * trim differently), which clipped the last line of a page and repeated it on the next.
 * Indentation is therefore made of em spaces instead of a paragraph style; headings, leads and
 * small print are spans on the same string.
 */
private fun blockText(block: Block.Text, dimArgb: Int): CharSequence {
    val sb = SpannableStringBuilder()
    block.paragraphs.forEachIndexed { i, p ->
        if (i > 0) sb.append("\n")
        val start = sb.length
        val indent = i > 0 && p.kind == ParaKind.NORMAL && block.paragraphs[i - 1].kind == ParaKind.NORMAL
        if (indent) sb.append("\u2003\u2003")
        sb.append(if (indent) p.text.replace("\n", "\n\u2003\u2003") else p.text)
        val end = sb.length
        when (p.kind) {
            ParaKind.HEADING -> { sb.setSpan(RelativeSizeSpan(1.3f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            ParaKind.LEAD -> sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ParaKind.SMALL -> { sb.setSpan(RelativeSizeSpan(0.8f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); sb.setSpan(ForegroundColorSpan(dimArgb), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            ParaKind.NORMAL -> Unit
        }
    }
    return sb
}

@Composable
private fun Reader(app: App, entry: Entry, onMenu: () -> Unit) {
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val settings by app.prefs.settings.collectAsState()
    val tick = rememberTick()
    val readerSp = if (settings.readerSp > 0) settings.readerSp else defaultReaderSp()

    val book by produceState<Result<Book>?>(null, entry.fileName) {
        value = withContext(Dispatchers.IO) { runCatching { app.library.book(entry) } }
    }
    val loaded = book
    if (loaded == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            T(stringResource(R.string.reader_loading), color = colors.dim, align = TextAlign.Center)
        }
        return
    }
    val b = loaded.getOrNull()
    if (b == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            T(stringResource(R.string.reader_unsupported), Modifier.padding(rowPadH), align = TextAlign.Center)
        }
        return
    }

    // Keep the screen on while a page is open: a slow reader must never be locked out mid-page.
    // Released when leaving the book, and after 15 minutes without a page turn.
    var lastTurn by remember { mutableStateOf(System.currentTimeMillis()) }
    if (settings.keepScreenOn) KeepScreenOn(lastTurn)

    var chapter by remember(entry.fileName) { mutableStateOf(entry.chapter.coerceIn(0, b.chapters.size - 1)) }
    // The character we want at the top of the page; re-resolved whenever the layout changes.
    var wantedChar by remember(entry.fileName) { mutableStateOf(entry.charOffset) }
    var page by remember(entry.fileName) { mutableStateOf(0) }

    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hPad = 24.dp
        val topPad = 20.dp
        val bottomPad = 20.dp
        val statusBar = WindowInsets.statusBars.asPaddingValuesTop()
        val navBar = WindowInsets.navigationBars.asPaddingValuesBottom()
        val headerHeight = 22.dp
        val footerHeight = 22.dp
        val widthPx = with(density) { (maxWidth - hPad * 2).roundToPx() }
        // The page is paginated against the text area's *measured* height; the estimate below only
        // serves the first frame. Any drift (hidden status bar, rounding) would otherwise clip the
        // last line and repeat it on the next page.
        val estimatedHeightPx = with(density) {
            (maxHeight - statusBar - navBar - topPad - bottomPad - headerHeight - footerHeight).toPx()
        }
        var canvasHeight by remember { mutableStateOf(0) }
        val pageHeightPx = if (canvasHeight > 0) canvasHeight.toFloat() else estimatedHeightPx
        val fgArgb = colors.fg.toArgb()
        val dimArgb = colors.dim.toArgb()
        val images = remember(entry.fileName) { app.library.images(entry) }
        val bitmapPaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
        val paint = remember(readerSp, typo.family, typo.weight, fgArgb, density) {
            TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = fgArgb
                textSize = with(density) { readerSp.sp.toPx() }
                typeface = when (typo.family) {
                    FontFamily.Serif -> Typeface.SERIF
                    FontFamily.Monospace -> Typeface.MONOSPACE
                    else -> if (typo.weight == FontWeight.Light) Typeface.create("sans-serif-light", Typeface.NORMAL) else Typeface.SANS_SERIF
                }
            }
        }
        val ch = b.chapters[chapter]
        val layout = remember(ch, widthPx, paint, dimArgb, pageHeightPx) {
            paginate(ch, paint, dimArgb, widthPx, pageHeightPx.toInt(), images)
        }
        // Resolve the wanted character into a page whenever the layout (size, width) changes.
        LaunchedEffect(layout) { page = layout.pageFor(wantedChar).coerceIn(0, layout.pageCount - 1) }
        LaunchedEffect(chapter, page, layout) {
            if (page in 0 until layout.pageCount) {
                val pct = ((b.charsBefore(chapter) + layout.pageStartChar(page)).toFloat() / b.totalLength.coerceAtLeast(1) * 100).toInt()
                app.library.savePosition(entry.id, chapter, layout.pageStartChar(page), pct)
            }
        }

        fun goTo(c: Int, charOffset: Int) { chapter = c; wantedChar = charOffset }
        fun next() {
            lastTurn = System.currentTimeMillis()
            if (page + 1 < layout.pageCount) { page++; wantedChar = layout.pageStartChar(page) }
            else if (chapter + 1 < b.chapters.size) goTo(chapter + 1, 0)
        }
        fun prev() {
            lastTurn = System.currentTimeMillis()
            if (page > 0) { page--; wantedChar = layout.pageStartChar(page) }
            else if (chapter > 0) goTo(chapter - 1, Int.MAX_VALUE)
        }

        val safePage = page.coerceIn(0, layout.pageCount - 1)
        val pageDef = layout.pages[safePage]
        // Pictures of this page, decoded off the main thread; the canvas draws what has arrived.
        val bitmaps by produceState<Map<String, Bitmap>>(emptyMap(), pageDef) {
            val wanted = pageDef.pieces.filterIsInstance<Piece.Picture>()
            if (wanted.isNotEmpty()) value = withContext(Dispatchers.IO) {
                wanted.mapNotNull { p -> images.bitmap(p.source, p.width)?.let { p.source to it } }.toMap()
            }
        }
        val progress = ((b.charsBefore(chapter) + layout.pageStartChar(safePage)).toFloat() / b.totalLength.coerceAtLeast(1) * 100).toInt()

        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(layout, chapter) {
                    detectTapGestures(
                        onTap = { pos -> if (pos.x > size.width / 2) next() else prev() },
                        onLongPress = { tick(); onMenu() }
                    )
                }
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = hPad)
        ) {
            Row(Modifier.fillMaxWidth().padding(top = topPad).height(headerHeight)) {
                Small(b.title, Modifier.weight(1f), maxLines = 1, align = TextAlign.Start)
            }
            Canvas(Modifier.weight(1f).fillMaxWidth().onSizeChanged { canvasHeight = it.height }) {
                drawIntoCanvas { c ->
                    val n = c.nativeCanvas
                    for (piece in pageDef.pieces) when (piece) {
                        is Piece.Lines -> {
                            // Clip to the bottom of this piece's last whole line, not to the canvas:
                            // the layout paints every line that intersects the clip, so clipping to
                            // the canvas would show a sliver of the next line under the last one.
                            n.save()
                            n.clipRect(0f, piece.y.toFloat(), size.width, (piece.y + piece.height).toFloat().coerceAtMost(size.height))
                            n.translate(0f, (piece.y - piece.top).toFloat())
                            piece.layout.draw(n)
                            n.restore()
                        }
                        is Piece.Picture -> {
                            val bmp = bitmaps[piece.source] ?: continue
                            val left = ((size.width - piece.width) / 2).toInt()
                            n.drawBitmap(bmp, null, Rect(left, piece.y, left + piece.width, piece.y + piece.height), bitmapPaint)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(footerHeight).padding(bottom = 0.dp), verticalAlignment = Alignment.Bottom) {
                // A bare number means the format had no chapter heading: not worth a label.
                Small(if (ch.title.substringBefore(" · ").toIntOrNull() == null) ch.title else "", Modifier.weight(1f), maxLines = 1, align = TextAlign.Start)
                Small("${safePage + 1}/${layout.pageCount} · $progress%", maxLines = 1, align = TextAlign.End)
            }
            VSpace(bottomPad)
        }
    }
}

@Composable
private fun WindowInsets.asPaddingValuesTop(): androidx.compose.ui.unit.Dp =
    with(LocalDensity.current) { getTop(this).toDp() }

@Composable
private fun WindowInsets.asPaddingValuesBottom(): androidx.compose.ui.unit.Dp =
    with(LocalDensity.current) { getBottom(this).toDp() }

@Composable
private fun KeepScreenOn(lastTurn: Long) {
    val view = androidx.compose.ui.platform.LocalView.current
    val window = (view.context.findActivity())?.window
    androidx.compose.runtime.DisposableEffect(window) {
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(lastTurn, window) {
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        kotlinx.coroutines.delay(15 * 60_000L)
        window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

/** Table of contents: tap a chapter to jump there. A magazine lists its articles by section. */
@Composable
fun BookChaptersScreen(nav: Nav, app: App, id: String) {
    val books by app.library.books.collectAsState()
    val entry = books.firstOrNull { it.id == id }
    BackHandler { nav.pop() }
    if (entry == null) { nav.pop(); return }
    val book by produceState<Book?>(null, entry.fileName) { value = withContext(Dispatchers.IO) { runCatching { app.library.book(entry) }.getOrNull() } }
    val listState = rememberLazyListState()
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(entry.title, onBack = { nav.pop() })
            val magazine = book?.magazine
            if (magazine != null) {
                MagazineContents(magazine, entry, app, listState) { article ->
                    app.library.savePosition(entry.id, article.chapter, 0, entry.progress)
                    nav.push(Screen.Book(id))
                }
                return@Column
            }
            val chapters = book?.chapters ?: emptyList()
            val entries = chapters.withIndex().filter { (i, ch) -> i == 0 || !ch.title.startsWith(chapters[i - 1].title.substringBefore(" · ") + " · ") }
            val currentEntry = entries.lastOrNull { it.index <= entry.chapter }?.index
            LazyColumn(state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                items(entries, key = { it.index }) { (i, ch) ->
                    TextRow(ch.title, inverted = i == currentEntry, size = LocalTypo.current.title) {
                        app.library.savePosition(entry.id, i, 0, entry.progress)
                        nav.home(); nav.push(Screen.Book(id))
                    }
                }
            }
        }
    }
}

/** Articles under their section names, each with its author and cover picture. */
@Composable
private fun MagazineContents(magazine: Magazine, entry: Entry, app: App, listState: androidx.compose.foundation.lazy.LazyListState, onOpen: (Magazine.Article) -> Unit) {
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val images = remember(entry.fileName) { app.library.images(entry) }
    val current = magazine.articles.lastOrNull { it.chapter <= entry.chapter && entry.opened > 0L }
    LazyColumn(state = listState, contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
        magazine.articles.forEachIndexed { i, a ->
            if (a.category.isNotEmpty() && (i == 0 || a.category != magazine.articles[i - 1].category)) {
                item(key = "s$i") {
                    Small(a.category.uppercase(), Modifier.padding(start = rowPadH, end = rowPadH, top = rowPadV * 0.9f, bottom = 2.dp), maxLines = 1, align = TextAlign.Start)
                }
            }
            item(key = "a$i") {
                val inverted = a === current
                val fg = if (inverted) colors.bg else colors.fg
                val dim = if (inverted) colors.bg.copy(alpha = 0.6f) else colors.dim
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (inverted) colors.fg else androidx.compose.ui.graphics.Color.Transparent)
                        .noRippleClickable { onOpen(a) }
                        .padding(horizontal = rowPadH, vertical = rowPadV * 0.6f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        T(a.title, size = typo.title, color = fg, maxLines = 3, align = TextAlign.Start)
                        if (a.author != null) Small(a.author, color = dim, maxLines = 1, align = TextAlign.Start)
                    }
                    if (a.image != null) {
                        Spacer(Modifier.width(14.dp))
                        Thumb(images, a.image)
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumb(images: ImageStore, source: String) {
    val px = with(LocalDensity.current) { 72.dp.roundToPx() }
    val bmp by produceState<Bitmap?>(null, source) { value = withContext(Dispatchers.IO) { images.bitmap(source, px * 2) } }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), contentDescription = null, Modifier.size(72.dp), contentScale = ContentScale.Crop)
    else Box(Modifier.size(72.dp))
}
