package dev.wonderdesk.pages

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : WonderActivity() {
    @Composable override fun Content() = PagesApp()
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); incoming.value = intent }
    companion object { val incoming = mutableStateOf<Intent?>(null) }
}

private const val ABOUT = "Pages reads PDFs with smooth scrolling and zoom, remembers where you left off, and lets you bookmark, search, highlight, draw and leave notes on any page. " +
    "It also makes PDFs from photos, camera scans and text, and merges several PDFs into one."

/**
 * Files shared by other apps come with temporary access. Keep permanent access when Android allows it,
 * otherwise keep a private copy so the library can reopen the document later.
 */
fun lastingUri(context: Context, uri: Uri): Uri {
    if (uri.scheme == "file") return uri
    val kept = runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); true }.getOrDefault(false)
    if (kept) return uri
    return runCatching {
        val dir = File(context.filesDir, "library").apply { mkdirs() }
        val name = context.displayName(uri).let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
        val out = File(dir, name)
        context.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
        Uri.fromFile(out)
    }.getOrDefault(uri)
}

@Composable
fun PagesApp() {
    val context = LocalContext.current
    var opened by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var tab by rememberSaveable { mutableStateOf("library") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; opened = uri to context.displayName(uri) }
    }
    val incoming by MainActivity.incoming
    LaunchedEffect(incoming) {
        val i = incoming ?: (context as ComponentActivity).intent
        MainActivity.incoming.value = null; (context as ComponentActivity).intent = Intent()
        val uri = i?.data ?: if (i?.action == Intent.ACTION_SEND) @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri else null
        if (uri != null) { val name = context.displayName(uri); val lasting = withContext(Dispatchers.IO) { lastingUri(context, uri) }; opened = lasting to name }
    }
    val o = opened
    if (o != null) { Reader(o.first, o.second, onClose = { opened = null }); return }
    WonderApp(appName = "Pages", about = ABOUT,
        tabs = listOf(WonderTab("library", "Library", WonderIcons.MenuBook), WonderTab("create", "Create", WonderIcons.NoteAdd)),
        selectedTab = tab, onTab = { tab = it },
        actions = { ToolbarButton(WonderIcons.FolderOpen, "Open PDF", { picker.launch(arrayOf("application/pdf")) }) }) {
        if (tab == "create") CreateTab(onCreated = { uri -> opened = uri to context.displayName(uri) })
        else LibraryTab(onOpen = { opened = Uri.parse(it.uri) to it.name }, onPick = { picker.launch(arrayOf("application/pdf")) })
    }
}

/* -------------------------------------------------------- library ------------------------------------------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryTab(onOpen: (LibraryItem) -> Unit, onPick: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    var items by remember { mutableStateOf(Library.all(context)) }
    var q by rememberSaveable { mutableStateOf("") }
    if (items.isEmpty()) {
        EmptyState("Your bookshelf is empty", "Open a PDF to start reading. Pages remembers your place, bookmarks and notes for every document.",
            action = { WButton("Open a PDF", onPick, icon = WonderIcons.FolderOpen, kind = ButtonKind.Primary) })
        return
    }
    val shown = items.filter { q.isBlank() || it.name.contains(q, true) }
    val reading = if (items.size > 3) shown.filter { it.page in 1 until (it.pages - 1).coerceAtLeast(1) }.take(3) else emptyList()
    Column(Modifier.fillMaxSize()) {
        SearchField(q, { q = it }, "Search your library", Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp))
        LazyVerticalGrid(GridCells.Adaptive(120.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (reading.isNotEmpty() && q.isBlank()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { SectionLabel("Continue reading", Modifier.padding(start = 6.dp)) }
                itemsIndexed(reading, key = { _, it -> "r" + it.uri }) { _, it -> Book(it, onOpen, onRemove = { Library.remove(context, it.uri); items = Library.all(context) }) }
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { SectionLabel("All documents", Modifier.padding(start = 6.dp, top = 6.dp)) }
            }
            itemsIndexed(shown, key = { _, it -> it.uri }) { _, it -> Book(it, onOpen, onRemove = { Library.remove(context, it.uri); items = Library.all(context) }) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Book(it: LibraryItem, onOpen: (LibraryItem) -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val thumb = remember(it.uri, it.opened) { runCatching { android.graphics.BitmapFactory.decodeFile(Library.thumb(context, it.uri).path)?.asImageBitmap() }.getOrNull() }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.combinedClickable(onClick = { onOpen(it) }, onLongClick = { menu = true })) {
        Box(Modifier.fillMaxWidth().aspectRatio(.72f).clip(RoundedCornerShape(4.dp)).background(Color.White).border(1.dp, p.panelBorder, RoundedCornerShape(4.dp))) {
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Icon(WonderIcons.PictureAsPdf, null, Modifier.align(Alignment.Center).size(40.dp), tint = Color(0xFFD63031))
            if (it.pages > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = .1f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(((it.page + 1).toFloat() / it.pages).coerceIn(0f, 1f)).background(p.accent))
            }
        }
        Text(it.name.removeSuffix(".pdf"), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(if (it.pages > 0) "Page ${it.page + 1} of ${it.pages}" else "", style = MaterialTheme.typography.labelSmall, color = p.secondaryText)
        DropdownMenu(menu, { menu = false }, Modifier.background(p.panel)) {
            DropdownMenuItem({ Text("Remove from library") }, { menu = false; onRemove() }, leadingIcon = { Icon(WonderIcons.Delete, null) })
            DropdownMenuItem({ Text("Share") }, { menu = false; context.share(Uri.parse(it.uri), "application/pdf") {} }, leadingIcon = { Icon(WonderIcons.Share, null) })
        }
    }
}

/* --------------------------------------------------------- reader ------------------------------------------- */

private enum class Paper(val label: String) { Normal("Normal"), Sepia("Sepia"), Night("Night") }

private val SEPIA = ColorMatrix(floatArrayOf(0.95f, 0.05f, 0f, 0f, 8f, 0.05f, 0.88f, 0.02f, 0f, 0f, 0.02f, 0.05f, 0.7f, 0f, -12f, 0f, 0f, 0f, 1f, 0f))
private val NIGHT = ColorMatrix(floatArrayOf(-0.85f, 0f, 0f, 0f, 235f, 0f, -0.85f, 0f, 0f, 235f, 0f, 0f, -0.85f, 0f, 235f, 0f, 0f, 0f, 1f, 0f))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Reader(uri: Uri, name: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val scope = rememberCoroutineScope()
    var doc by remember { mutableStateOf<Doc?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var key by remember { mutableStateOf("") }
    val marks = remember { mutableStateMapOf<Int, PageMarks>() }
    val bookmarks = remember { mutableStateListOf<Bookmark>() }
    val list = rememberLazyListState()
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var paper by rememberSaveable { mutableStateOf(runCatching { Paper.valueOf(context.prefs("pages").getString("paper", "Normal")!!) }.getOrDefault(Paper.Normal)) }
    var tool by remember { mutableStateOf<Tool?>(null) }
    var color by remember { mutableIntStateOf(0xFFE5483C.toInt()) }
    val undo = remember { mutableStateListOf<Pair<Int, PageMarks>>() }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<Map<Int, List<RectF>>>(emptyMap()) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var editNote by remember { mutableStateOf<Triple<Int, Int, Note>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val toastRef = remember { mutableStateOf<(String) -> Unit>({}) }
    var chrome by remember { mutableStateOf(true) }
    DisposableEffect(uri) {
        val job = scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Doc(context, uri) to Store.key(context, uri) } }
            r.onSuccess { (d, k) ->
                doc = d; key = k
                val (m, b, page) = withContext(Dispatchers.IO) { Store.load(context, k) }
                marks.putAll(m); bookmarks.addAll(b)
                val start = Library.all(context).find { it.uri == uri.toString() }?.page ?: page
                list.scrollToItem(start.coerceIn(0, d.pageCount - 1))
                Library.put(context, LibraryItem(uri.toString(), name, d.pageCount, start, System.currentTimeMillis()))
                // cover thumbnail
                withContext(Dispatchers.IO) { runCatching { d.render(0, 300).let { bmp -> Library.thumb(context, uri.toString()).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) } } } }
            }.onFailure { e -> error = if (e is SecurityException || e is java.io.FileNotFoundException) "Pages no longer has access to this file. Open it again from Wayfinder or your file manager." else e.message ?: "Couldn't open this PDF" }
        }
        onDispose { job.cancel(); doc?.close() }
    }
    val current by remember { derivedStateOf { list.firstVisibleItemIndex } }
    LaunchedEffect(current, key) {
        val d = doc ?: return@LaunchedEffect
        delay(600)
        Library.put(context, LibraryItem(uri.toString(), name, d.pageCount, current, System.currentTimeMillis()))
        withContext(Dispatchers.IO) { Store.save(context, key, marks.toMap(), bookmarks.toList(), current) }
    }
    fun persist() { val k = key; val m = marks.toMap(); val b = bookmarks.toList(); val c = current; scope.launch(Dispatchers.IO) { Store.save(context, k, m, b, c) } }
    fun change(page: Int, n: PageMarks) { undo.add(page to (marks[page] ?: PageMarks())); marks[page] = n; persist() }
    LaunchedEffect(query, searching) {
        val d = doc ?: return@LaunchedEffect
        if (!searching || query.length < 2) { hits = emptyMap(); return@LaunchedEffect }
        delay(400); hits = d.search(query)
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        val d = doc ?: return@rememberLauncherForActivityResult
        if (out == null) return@rememberLauncherForActivityResult
        busy = "Saving annotated copy…"
        scope.launch {
            val r = runCatching { context.contentResolver.openOutputStream(out, "wt")!!.use { os -> Maker.exportAnnotated(d, marks.toMap(), os) { n -> busy = "Saving page $n of ${d.pageCount}" } } }
            busy = null; toastRef.value(r.fold({ "Saved" }, { it.message ?: "Couldn't save" }))
        }
    }
    val d = doc
    val isBookmarked = bookmarks.any { it.page == current }
    WonderApp(appName = "Pages", about = ABOUT, title = name.removeSuffix(".pdf"), subtitle = d?.let { "Page ${current + 1} of ${it.pageCount}" }, onBack = { if (tool != null) tool = null else if (searching) { searching = false; query = "" } else onClose() }, showChrome = chrome || tool != null || searching,
        actions = {
            ToolbarButton(WonderIcons.Search, "Search", { searching = !searching; if (!searching) query = "" })
            ToolbarButton(if (isBookmarked) WonderIcons.Bookmark else WonderIcons.BookmarkBorder, "Bookmark page", {
                if (isBookmarked) bookmarks.removeAll { it.page == current } else bookmarks.add(Bookmark(current, "Page ${current + 1}", System.currentTimeMillis())); persist()
            }, tint = if (isBookmarked) Color(0xFFF2A516) else null)
            ToolbarButton(WonderIcons.Draw, "Annotate", { tool = if (tool == null) Tool.Pen else null })
        },
        menu = listOf(
            WonderMenuItem("Pages and bookmarks", WonderIcons.GridView) { sheet = "pages" },
            WonderMenuItem("Go to page…", WonderIcons.Pin) { sheet = "goto" },
            WonderMenuItem("Reading mode: ${paper.label}", WonderIcons.DarkMode) { paper = Paper.entries[(paper.ordinal + 1) % 3]; context.prefs("pages").edit().putString("paper", paper.name).apply() },
            WonderMenuItem("Save annotated copy", WonderIcons.Save) { exporter.launch(name.removeSuffix(".pdf") + " (annotated).pdf") },
            WonderMenuItem("Share", WonderIcons.Share) { context.share(uri, "application/pdf") { toastRef.value(it) } },
            WonderMenuItem("Print", WonderIcons.Print) { printPdf(context, uri, name) },
        )) {
        toastRef.value = LocalToast.current
        when {
            error != null -> EmptyState("Can't open this PDF", error!!, icon = WonderIcons.PictureAsPdf)
            d == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = p.accent) }
            else -> Column(Modifier.fillMaxSize()) {
                if (searching) Column(Modifier.background(p.window).padding(12.dp)) {
                    SearchField(query, { query = it }, if (Build.VERSION.SDK_INT >= 35) "Search this document" else "Search needs Android 15 or newer")
                    if (hits.isNotEmpty()) Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        hits.forEach { (pg, r) -> Chip("p. ${pg + 1} (${r.size})", pg == current, { scope.launch { list.animateScrollToItem(pg) } }) }
                    } else if (query.length >= 2) Text("No matches", style = MaterialTheme.typography.bodySmall, color = p.secondaryText, modifier = Modifier.padding(top = 6.dp))
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(if (paper == Paper.Night) Color(0xFF15171A) else Color(0xFF8E959D))
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val ev = awaitPointerEvent()
                                if (ev.changes.size >= 2) {
                                    zoom = (zoom * ev.calculateZoom()).coerceIn(1f, 5f)
                                    val maxPan = size.width * (zoom - 1) / 2
                                    panX = (panX + ev.calculatePan().x).coerceIn(-maxPan, maxPan)
                                    if (zoom == 1f) panX = 0f
                                    ev.changes.forEach { it.consume() }
                                }
                            } while (ev.changes.any { it.pressed })
                        }
                    }) {
                    var boxW by remember { mutableIntStateOf(1) }
                    LazyColumn(Modifier.fillMaxSize().onSizeChanged { boxW = it.width }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = panX; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f) },
                        state = list, userScrollEnabled = tool == null, contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(d.pageCount) { i ->
                            PageView(d, i, boxW, zoom, paper, marks[i], hits[i], tool, color,
                                onStroke = { s -> change(i, (marks[i] ?: PageMarks()).let { it.copy(strokes = it.strokes + s) }) },
                                onErase = { x, y -> val pm = marks[i] ?: return@PageView
                                    val left = pm.strokes.filterNot { s -> s.points.any { (px, py) -> kotlin.math.abs(px - x) < .03f && kotlin.math.abs(py - y) < .02f } }
                                    val leftNotes = pm.notes.filterNot { n -> kotlin.math.abs(n.x - x) < .05f && kotlin.math.abs(n.y - y) < .04f }
                                    if (left.size != pm.strokes.size || leftNotes.size != pm.notes.size) change(i, pm.copy(strokes = left, notes = leftNotes)) },
                                onNote = { x, y -> editNote = Triple(i, -1, Note(x, y, "")) },
                                onOpenNote = { idx, n -> editNote = Triple(i, idx, n) },
                                onTap = { chrome = !chrome })
                        }
                    }
                    if (tool == null) PageIndicator(current, d.pageCount, Modifier.align(Alignment.BottomCenter)) { sheet = "goto" }
                }
                if (tool != null) AnnotateBar(tool!!, color, onTool = { tool = it }, onColor = { color = it }, canUndo = undo.isNotEmpty(),
                    onUndo = { undo.removeLastOrNull()?.let { (pg, old) -> marks[pg] = old; persist() } }, onDone = { tool = null })
            }
        }
    }
    when (sheet) {
        "goto" -> d?.let { doc0 ->
            var v by remember { mutableFloatStateOf(current.toFloat()) }
            WonderDialog(onDismissRequest = { sheet = null }, title = { Text("Go to page") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Page ${v.toInt() + 1} of ${doc0.pageCount}", style = MaterialTheme.typography.titleLarge)
                    Slider(v, { v = it }, valueRange = 0f..(doc0.pageCount - 1).coerceAtLeast(1).toFloat(), colors = SliderDefaults.colors(thumbColor = p.accent, activeTrackColor = p.accent))
                    var t by remember { mutableStateOf("") }
                    Field(t, { t = it.filter(Char::isDigit); t.toIntOrNull()?.let { n -> v = (n - 1).coerceIn(0, doc0.pageCount - 1).toFloat() } }, "Page number", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                }
            }, confirmButton = { TextButton(onClick = { scope.launch { list.scrollToItem(v.toInt()) }; sheet = null }) { Text("Go") } })
        }
        "pages" -> d?.let { doc0 -> PagesSheet(doc0, bookmarks, marks.keys, onPick = { scope.launch { list.scrollToItem(it) }; sheet = null },
            onRename = { b, t -> val i = bookmarks.indexOf(b); if (i >= 0) { bookmarks[i] = b.copy(title = t); persist() } }, onDismiss = { sheet = null }) }
    }
    editNote?.let { (pg, idx, n) ->
        var text by remember { mutableStateOf(n.text) }
        WonderDialog(onDismissRequest = { editNote = null }, title = { Text(if (idx < 0) "Add note" else "Note on page ${pg + 1}") }, text = {
            Field(text, { text = it }, "Note", singleLine = false, minLines = 3)
        }, confirmButton = {
            TextButton(onClick = {
                val pm = marks[pg] ?: PageMarks()
                change(pg, pm.copy(notes = if (idx < 0) pm.notes + n.copy(text = text) else pm.notes.mapIndexed { i, x -> if (i == idx) x.copy(text = text) else x }))
                editNote = null
            }, enabled = text.isNotBlank()) { Text("Save") }
        }, dismissButton = {
            if (idx >= 0) TextButton(onClick = { val pm = marks[pg]!!; change(pg, pm.copy(notes = pm.notes.filterIndexed { i, _ -> i != idx })); editNote = null }) { Text("Delete", color = p.danger) }
            else TextButton(onClick = { editNote = null }) { Text("Cancel") }
        })
    }
    busy?.let { WonderDialog(onDismissRequest = {}, title = { Text(it) }, text = { AquaProgress(null, Modifier.fillMaxWidth()) }, confirmButton = {}) }
}


@Composable
private fun PageIndicator(current: Int, total: Int, modifier: Modifier, onClick: () -> Unit) {
    Text("${current + 1} / $total", modifier.padding(14.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .62f)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        color = Color.White, style = MaterialTheme.typography.labelLarge)
}

@Composable
private fun PageView(doc: Doc, index: Int, boxW: Int, zoom: Float, paper: Paper, marks: PageMarks?, hits: List<RectF>?, tool: Tool?, color: Int,
                     onStroke: (Stroke) -> Unit, onErase: (Float, Float) -> Unit, onNote: (Float, Float) -> Unit, onOpenNote: (Int, Note) -> Unit, onTap: () -> Unit) {
    val (pw, ph) = doc.sizes[index]
    val density = LocalDensity.current
    val renderW = (boxW * zoom.coerceAtMost(3f)).toInt().coerceIn(200, 3000)
    var bmp by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index, renderW / 200) { bmp = withContext(Dispatchers.Default) { runCatching { doc.render(index, renderW) }.getOrNull() } }
    var live by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    val heightDp = with(density) { (boxW.toFloat() * ph / pw).toDp() }
    Box(Modifier.fillMaxWidth().height(heightDp).background(Color.White)
        .pointerInput(tool, color) {
            if (tool == Tool.Pen || tool == Tool.Highlighter || tool == Tool.Eraser) detectDragGestures(
                onDragStart = { o -> if (tool == Tool.Eraser) onErase(o.x / size.width, o.y / size.height) else live = listOf(o.x / size.width to o.y / size.height) },
                onDrag = { ch, _ -> val pt = ch.position.x / size.width to ch.position.y / size.height; if (tool == Tool.Eraser) onErase(pt.first, pt.second) else live = live + pt },
                onDragEnd = { if (tool != Tool.Eraser && live.size > 1) onStroke(Stroke(if (tool == Tool.Highlighter) 0xFFFFE135.toInt().takeIf { color == 0xFFE5483C.toInt() } ?: color else color,
                    if (tool == Tool.Highlighter) 22f else 4f, tool == Tool.Highlighter, live)); live = emptyList() })
        }
        .pointerInput(tool, marks) {
            detectTapGestures(onTap = { o ->
                val x = o.x / size.width; val y = o.y / size.height
                val hit = marks?.notes?.indexOfFirst { kotlin.math.abs(it.x - x) < .05f && kotlin.math.abs(it.y - y) < .04f } ?: -1
                when { hit >= 0 -> onOpenNote(hit, marks!!.notes[hit]); tool == Tool.Note -> onNote(x, y); tool == null -> onTap() }
            })
        }) {
        bmp?.let {
            Image(it.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize(),
                colorFilter = when (paper) { Paper.Sepia -> ColorFilter.colorMatrix(SEPIA); Paper.Night -> ColorFilter.colorMatrix(NIGHT); else -> null })
        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("${index + 1}", color = Color.LightGray, style = MaterialTheme.typography.headlineMedium) }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            hits?.forEach { r -> drawRect(Color(0x66FFB300), Offset(r.left / pw * w, r.top / ph * h), androidx.compose.ui.geometry.Size(r.width() / pw * w, r.height() / ph * h)) }
            fun draw(s: Stroke) {
                val path = Path(); s.points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * w, y * h) else path.lineTo(x * w, y * h) }
                drawPath(path, Color(s.color).copy(alpha = if (s.highlighter) .35f else 1f), style = DrawStroke(s.width * w / 1000f, cap = if (s.highlighter) StrokeCap.Square else StrokeCap.Round, join = StrokeJoin.Round))
            }
            marks?.strokes?.forEach { draw(it) }
            if (live.size > 1) draw(Stroke(if (tool == Tool.Highlighter) 0xFFFFE135.toInt() else color, if (tool == Tool.Highlighter) 22f else 4f, tool == Tool.Highlighter, live))
            marks?.notes?.forEach { n ->
                val x = n.x * w; val y = n.y * h; val s = w / 22f
                drawRoundRect(Color(0xFFFFD43B), Offset(x, y), androidx.compose.ui.geometry.Size(s, s), androidx.compose.ui.geometry.CornerRadius(s / 6))
                drawLine(Color(0xFF8A6D00), Offset(x + s * .2f, y + s * .35f), Offset(x + s * .8f, y + s * .35f), s / 12)
                drawLine(Color(0xFF8A6D00), Offset(x + s * .2f, y + s * .6f), Offset(x + s * .65f, y + s * .6f), s / 12)
            }
        }
    }
}

@Composable
private fun AnnotateBar(tool: Tool, color: Int, onTool: (Tool) -> Unit, onColor: (Int) -> Unit, canUndo: Boolean, onUndo: () -> Unit, onDone: () -> Unit) {
    val p = wonderPalette()
    Column(Modifier.fillMaxWidth().background(p.panel).padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf(Tool.Pen to (WonderIcons.Draw to "Pen"), Tool.Highlighter to (WonderIcons.Brush to "Highlight"), Tool.Note to (WonderIcons.StickyNote2 to "Note"), Tool.Eraser to (WonderIcons.Backspace to "Erase")).forEach { (t, d) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (t == tool) p.accent.copy(alpha = .15f) else Color.Transparent).clickable { onTool(t) }.padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(d.first, null, tint = if (t == tool) p.accent else p.text); Text(d.second, style = MaterialTheme.typography.labelSmall, color = if (t == tool) p.accent else p.text)
                }
            }
            IconButton(onClick = onUndo, enabled = canUndo) { Icon(WonderIcons.Undo, "Undo") }
            TextButton(onClick = onDone) { Text("Done") }
        }
        if (tool == Tool.Pen || tool == Tool.Highlighter) Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(0xFFE5483C, 0xFFFFE135, 0xFF2E9E44, 0xFF1E7FE8, 0xFF8E44AD, 0xFF1B1D21).forEach { c ->
                Box(Modifier.size(30.dp).clip(CircleShape).background(Color(c)).border(if (color == c.toInt()) 3.dp else 1.dp, if (color == c.toInt()) p.accent else p.panelBorder, CircleShape).clickable { onColor(c.toInt()) })
            }
        }
        Text(when (tool) { Tool.Note -> "Tap the page where the note should go"; Tool.Eraser -> "Drag over marks to erase them"; else -> "Draw with one finger. Pinch with two to zoom." },
            Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = p.secondaryText)
    }
}

@Composable
private fun PagesSheet(doc: Doc, bookmarks: List<Bookmark>, annotated: Set<Int>, onPick: (Int) -> Unit, onRename: (Bookmark, String) -> Unit, onDismiss: () -> Unit) {
    val p = wonderPalette()
    var tab by remember { mutableIntStateOf(if (bookmarks.isNotEmpty()) 1 else 0) }
    var renaming by remember { mutableStateOf<Bookmark?>(null) }
    WonderDialog(onDismissRequest = onDismiss, title = { Segmented(listOf("Pages", "Bookmarks (${bookmarks.size})", "Notes (${annotated.size})"), tab) { tab = it } }, text = {
        when (tab) {
            0 -> LazyVerticalGrid(GridCells.Fixed(3), Modifier.height(440.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed((0 until doc.pageCount).toList()) { _, i ->
                    var b by remember { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(i) { b = runCatching { doc.render(i, 220) }.getOrNull() }
                    Column(Modifier.clickable { onPick(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().aspectRatio(doc.sizes[i].first.toFloat() / doc.sizes[i].second).background(Color.White).border(1.dp, p.panelBorder)) {
                            b?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize()) }
                            if (bookmarks.any { it.page == i }) Icon(WonderIcons.Bookmark, null, Modifier.align(Alignment.TopEnd).size(18.dp), tint = Color(0xFFF2A516))
                        }
                        Text("${i + 1}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            1 -> if (bookmarks.isEmpty()) Text("Tap the bookmark button on any page to save it here.") else LazyColumn(Modifier.heightIn(max = 440.dp)) {
                itemsIndexed(bookmarks.sortedBy { it.page }) { _, b -> ListRow(b.title, subtitle = "Page ${b.page + 1}", icon = WonderIcons.Bookmark, tint = Color(0xFFF2A516),
                    trailing = { IconButton(onClick = { renaming = b }) { Icon(WonderIcons.Edit, "Rename") } }, onClick = { onPick(b.page) }) }
            }
            else -> if (annotated.isEmpty()) Text("Pages you draw on or add notes to appear here.") else LazyColumn(Modifier.heightIn(max = 440.dp)) {
                itemsIndexed(annotated.sorted()) { _, pg -> ListRow("Page ${pg + 1}", icon = WonderIcons.StickyNote2, tint = Color(0xFFE5483C), onClick = { onPick(pg) }) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    renaming?.let { b -> InputDialog("Rename bookmark", "Name", initial = b.title, onDismiss = { renaming = null }) { onRename(b, it) } }
}

private fun printPdf(context: Context, uri: Uri, name: String) {
    val pm = context.getSystemService(android.print.PrintManager::class.java)
    pm.print(name, object : android.print.PrintDocumentAdapter() {
        override fun onLayout(o: android.print.PrintAttributes?, n: android.print.PrintAttributes?, c: android.os.CancellationSignal?, cb: LayoutResultCallback, e: android.os.Bundle?) {
            cb.onLayoutFinished(android.print.PrintDocumentInfo.Builder(name).setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
        }
        override fun onWrite(pages: Array<out android.print.PageRange>?, dest: android.os.ParcelFileDescriptor, c: android.os.CancellationSignal?, cb: WriteResultCallback) {
            runCatching { context.contentResolver.openInputStream(uri)!!.use { i -> java.io.FileOutputStream(dest.fileDescriptor).use { i.copyTo(it) } }; cb.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES)) }
                .onFailure { cb.onWriteFailed(it.message) }
        }
    }, null)
}

/* --------------------------------------------------------- create ------------------------------------------- */

@Composable
private fun CreateTab(onCreated: (Uri) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    val toastRef = remember { mutableStateOf<(String) -> Unit>({}) }
    val images = remember { mutableStateListOf<Uri>() }
    val merges = remember { mutableStateListOf<Uri>() }
    var mode by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var camFile by remember { mutableStateOf<File?>(null) }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { if (it.isNotEmpty()) { images.addAll(it); mode = "images" } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> camFile?.let { f -> if (ok) { images.add(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)); mode = "images" } } }
    fun scan() {
        val f = File(context.cacheDir, "scans").apply { mkdirs() }.let { File(it, "scan-${System.currentTimeMillis()}.jpg") }
        camFile = f; camera.launch(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f))
    }
    val pickPdfs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) { merges.addAll(it); mode = "merge" } }
    var pending by remember { mutableStateOf<(suspend (java.io.OutputStream) -> Unit)?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        val job = pending ?: return@rememberLauncherForActivityResult
        if (out == null) return@rememberLauncherForActivityResult
        busy = "Making PDF…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openOutputStream(out, "wt")!!.use { job(it) } } }
            busy = null
            r.onSuccess { images.clear(); merges.clear(); mode = null; title = ""; body = ""; runCatching { context.contentResolver.takePersistableUriPermission(out, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; onCreated(out) }
                .onFailure { toastRef.value(it.message ?: "Couldn't make the PDF") }
        }
    }
    toastRef.value = LocalToast.current
    Page {
        when (mode) {
            "images" -> {
                Panel(title = "${images.size} page${if (images.size == 1) "" else "s"}") {
                    images.forEachIndexed { i, u ->
                        if (i > 0) PanelDivider()
                        ListRow("Page ${i + 1}", subtitle = context.displayName(u), icon = WonderIcons.Image, tint = Color(0xFF2E9E44), trailing = {
                            Row {
                                IconButton(onClick = { if (i > 0) { images.add(i - 1, images.removeAt(i)) } }, enabled = i > 0) { Icon(WonderIcons.KeyboardArrowUp, "Move up") }
                                IconButton(onClick = { images.removeAt(i); if (images.isEmpty()) mode = null }) { Icon(WonderIcons.Close, "Remove") }
                            }
                        })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WButton("Add photos", { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.weight(1f), icon = WonderIcons.AddPhotoAlternate)
                    WButton("Scan page", { scan() }, Modifier.weight(1f), icon = WonderIcons.PhotoCamera)
                }
                WButton("Make PDF", { val list = images.toList(); pending = { os -> Maker.fromImages(context, list, os, fit = true) { n -> scope.launch { busy = "Adding page $n" } } }; saver.launch("Scan ${java.time.LocalDate.now()}.pdf") },
                    Modifier.fillMaxWidth(), icon = WonderIcons.PictureAsPdf, kind = ButtonKind.Primary)
                WButton("Cancel", { images.clear(); mode = null }, kind = ButtonKind.Plain)
            }
            "text" -> {
                Panel { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(title, { title = it }, "Title")
                    Field(body, { body = it }, "Text", singleLine = false, minLines = 12)
                } }
                WButton("Make PDF", { val t = title; val b = body; pending = { os -> Maker.fromText(t, b, os, 12f) }; saver.launch((title.ifBlank { "Document" }) + ".pdf") },
                    Modifier.fillMaxWidth(), icon = WonderIcons.PictureAsPdf, kind = ButtonKind.Primary, enabled = body.isNotBlank() || title.isNotBlank())
                WButton("Cancel", { mode = null }, kind = ButtonKind.Plain)
            }
            "merge" -> {
                Panel(title = "Merge in this order") {
                    merges.forEachIndexed { i, u ->
                        if (i > 0) PanelDivider()
                        ListRow(context.displayName(u), icon = WonderIcons.PictureAsPdf, tint = Color(0xFFD63031), trailing = {
                            Row {
                                IconButton(onClick = { if (i > 0) merges.add(i - 1, merges.removeAt(i)) }, enabled = i > 0) { Icon(WonderIcons.KeyboardArrowUp, "Move up") }
                                IconButton(onClick = { merges.removeAt(i); if (merges.isEmpty()) mode = null }) { Icon(WonderIcons.Close, "Remove") }
                            }
                        })
                    }
                }
                WButton("Add PDFs", { pickPdfs.launch(arrayOf("application/pdf")) }, Modifier.fillMaxWidth(), icon = WonderIcons.Add)
                WButton("Merge", { val list = merges.toList(); pending = { os -> Maker.merge(context, list, os) { n -> scope.launch { busy = "Adding page $n" } } }; saver.launch("Merged.pdf") },
                    Modifier.fillMaxWidth(), icon = WonderIcons.Layers, kind = ButtonKind.Primary, enabled = merges.size >= 2)
                Text("Merged pages are saved as images, so the text in them can't be searched.", style = MaterialTheme.typography.bodySmall, color = p.secondaryText)
            }
            else -> Panel(title = "Make a PDF") {
                ListRow("From photos", subtitle = "Turn pictures into a multi-page PDF", icon = WonderIcons.PhotoLibrary, tint = Color(0xFF2E9E44), chevron = true,
                    onClick = { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                PanelDivider()
                ListRow("Scan a document", subtitle = "Take photos of paper pages with the camera", icon = WonderIcons.PhotoCamera, tint = Color(0xFF1E7FE8), chevron = true, onClick = { scan() })
                PanelDivider()
                ListRow("From text", subtitle = "Type or paste text and save it as a PDF", icon = WonderIcons.TextSnippet, tint = Color(0xFF7A7F87), chevron = true, onClick = { mode = "text" })
                PanelDivider()
                ListRow("Merge PDFs", subtitle = "Combine several PDFs into one", icon = WonderIcons.Layers, tint = Color(0xFFD63031), chevron = true, onClick = { pickPdfs.launch(arrayOf("application/pdf")) })
            }
        }
    }
    busy?.let { WonderDialog(onDismissRequest = {}, title = { Text(it) }, text = { AquaProgress(null, Modifier.fillMaxWidth()) }, confirmButton = {}) }
}
