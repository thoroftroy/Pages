package dev.wonderdesk.pages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.security.MessageDigest

/** One open PDF. PdfRenderer allows one page open at a time, so every call goes through [lock]. */
class Doc(context: Context, val uri: Uri) {
    private val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Couldn't open that file")
    private val renderer: PdfRenderer = try { PdfRenderer(pfd) } catch (e: SecurityException) { pfd.close(); throw IllegalStateException("This PDF is password protected. Pages can't open locked PDFs yet.") }
        catch (e: Exception) { pfd.close(); throw IllegalStateException("This file isn't a readable PDF") }
    private val lock = Mutex()
    val pageCount = renderer.pageCount
    val sizes: List<Pair<Int, Int>> = (0 until pageCount).map { i -> renderer.openPage(i).use { it.width to it.height } }
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }

    suspend fun render(page: Int, widthPx: Int): Bitmap = lock.withLock {
        val key = "$page@$widthPx"
        cache.get(key)?.let { return it }
        renderer.openPage(page).use { p ->
            val h = (widthPx.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(1), h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            cache.put(key, bmp); bmp
        }
    }

    /** Text search (Android 15+). Returns page -> list of match boxes in page points. */
    suspend fun search(query: String): Map<Int, List<RectF>> = lock.withLock {
        if (Build.VERSION.SDK_INT < 35 || query.isBlank()) return emptyMap()
        val out = LinkedHashMap<Int, List<RectF>>()
        for (i in 0 until pageCount) renderer.openPage(i).use { p ->
            val m = runCatching { p.searchText(query) }.getOrDefault(emptyList())
            if (m.isNotEmpty()) out[i] = m.flatMap { it.bounds }
        }
        out
    }

    suspend fun pageText(page: Int): String = lock.withLock {
        if (Build.VERSION.SDK_INT < 35) return ""
        renderer.openPage(page).use { p -> runCatching { p.textContents.joinToString("\n") { it.text } }.getOrDefault("") }
    }

    fun close() { runCatching { renderer.close() }; runCatching { pfd.close() } }
}

/* -------------------------------------------------- annotations ---------------------------------------------- */

enum class Tool { Pen, Highlighter, Note, Eraser }

/** Coordinates are fractions of the page (0..1) so they survive any zoom. */
data class Stroke(val color: Int, val width: Float, val highlighter: Boolean, val points: List<Pair<Float, Float>>)
data class Note(val x: Float, val y: Float, val text: String)
data class PageMarks(val strokes: List<Stroke> = emptyList(), val notes: List<Note> = emptyList())
data class Bookmark(val page: Int, val title: String, val at: Long)

object Store {
    fun key(context: Context, uri: Uri): String = runCatching {
        val md = MessageDigest.getInstance("SHA-1")
        context.contentResolver.openInputStream(uri)!!.use { i -> val b = ByteArray(65536); var total = 0; while (total < 2_000_000) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n); total += n } }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault(uri.toString().hashCode().toString())

    private fun file(c: Context, key: String) = java.io.File(c.filesDir, "docs").apply { mkdirs() }.let { java.io.File(it, "$key.json") }

    fun load(c: Context, key: String): Triple<Map<Int, PageMarks>, List<Bookmark>, Int> = runCatching {
        val o = JSONObject(file(c, key).readText())
        val marks = HashMap<Int, PageMarks>()
        o.optJSONObject("marks")?.let { m -> m.keys().forEach { k ->
            val pm = m.getJSONObject(k)
            val strokes = pm.optJSONArray("s")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { s ->
                val pts = s.getJSONArray("p"); Stroke(s.getInt("c"), s.getDouble("w").toFloat(), s.optBoolean("h"), (0 until pts.length() / 2).map { pts.getDouble(it * 2).toFloat() to pts.getDouble(it * 2 + 1).toFloat() })
            } } }.orEmpty()
            val notes = pm.optJSONArray("n")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { n -> Note(n.getDouble("x").toFloat(), n.getDouble("y").toFloat(), n.getString("t")) } } }.orEmpty()
            marks[k.toInt()] = PageMarks(strokes, notes)
        } }
        val bm = o.optJSONArray("bookmarks")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { b -> Bookmark(b.getInt("page"), b.getString("title"), b.getLong("at")) } } }.orEmpty()
        Triple(marks, bm, o.optInt("page"))
    }.getOrDefault(Triple(emptyMap(), emptyList(), 0))

    fun save(c: Context, key: String, marks: Map<Int, PageMarks>, bookmarks: List<Bookmark>, page: Int) {
        val m = JSONObject()
        marks.forEach { (p, pm) ->
            if (pm.strokes.isEmpty() && pm.notes.isEmpty()) return@forEach
            m.put(p.toString(), JSONObject()
                .put("s", JSONArray(pm.strokes.map { s -> JSONObject().put("c", s.color).put("w", s.width.toDouble()).put("h", s.highlighter).put("p", JSONArray(s.points.flatMap { listOf(it.first.toDouble(), it.second.toDouble()) })) }))
                .put("n", JSONArray(pm.notes.map { n -> JSONObject().put("x", n.x.toDouble()).put("y", n.y.toDouble()).put("t", n.text) })))
        }
        file(c, key).writeText(JSONObject().put("marks", m).put("page", page).put("bookmarks", JSONArray(bookmarks.map { JSONObject().put("page", it.page).put("title", it.title).put("at", it.at) })).toString())
    }
}

data class LibraryItem(val uri: String, val name: String, val pages: Int, val page: Int, val opened: Long, val favorite: Boolean = false)

object Library {
    private fun prefs(c: Context) = c.getSharedPreferences("pages", Context.MODE_PRIVATE)
    fun all(c: Context): List<LibraryItem> = runCatching { JSONArray(prefs(c).getString("library", "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it).let { o ->
        LibraryItem(o.getString("uri"), o.getString("name"), o.optInt("pages"), o.optInt("page"), o.optLong("opened"), o.optBoolean("fav")) } } } }.getOrDefault(emptyList())
    fun put(c: Context, item: LibraryItem) = save(c, listOf(item) + all(c).filter { it.uri != item.uri })
    fun remove(c: Context, uri: String) = save(c, all(c).filter { it.uri != uri })
    private fun save(c: Context, list: List<LibraryItem>) = prefs(c).edit().putString("library", JSONArray(list.take(100).map {
        JSONObject().put("uri", it.uri).put("name", it.name).put("pages", it.pages).put("page", it.page).put("opened", it.opened).put("fav", it.favorite) }).toString()).apply()
    fun thumb(c: Context, uri: String) = java.io.File(c.filesDir, "thumbs").apply { mkdirs() }.let { java.io.File(it, "${uri.hashCode()}.png") }
}

/* ----------------------------------------------------- making PDFs ------------------------------------------- */

object Maker {
    private const val A4_W = 595; private const val A4_H = 842

    fun drawMarks(canvas: Canvas, marks: PageMarks?, w: Float, h: Float) {
        marks ?: return
        marks.strokes.forEach { s ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.color; strokeWidth = s.width * w / 1000f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                if (s.highlighter) { alpha = 90; strokeCap = Paint.Cap.SQUARE } }
            val path = Path(); s.points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * w, y * h) else path.lineTo(x * w, y * h) }
            canvas.drawPath(path, p)
        }
        marks.notes.forEach { n ->
            val bg = Paint().apply { color = 0xFFFFE680.toInt() }
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = w / 50f }
            val layout = StaticLayout.Builder.obtain(n.text, 0, n.text.length, tp, (w / 3).toInt()).build()
            val x = n.x * w; val y = n.y * h
            canvas.drawRect(x, y, x + layout.width + 16, y + layout.height + 16, bg)
            canvas.save(); canvas.translate(x + 8, y + 8); layout.draw(canvas); canvas.restore()
        }
    }

    /** A new PDF of every page with annotations drawn in. */
    suspend fun exportAnnotated(doc: Doc, marks: Map<Int, PageMarks>, out: OutputStream, onPage: (Int) -> Unit) {
        val pdf = PdfDocument()
        try {
            for (i in 0 until doc.pageCount) {
                val (pw, ph) = doc.sizes[i]
                val bmp = doc.render(i, (pw * 2).coerceAtMost(2400))
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                drawMarks(page.canvas, marks[i], pw.toFloat(), ph.toFloat())
                pdf.finishPage(page); onPage(i + 1)
            }
            pdf.writeTo(out)
        } finally { pdf.close() }
    }

    fun fromImages(c: Context, images: List<Uri>, out: OutputStream, fit: Boolean, onPage: (Int) -> Unit) {
        val pdf = PdfDocument()
        try {
            images.forEachIndexed { i, u ->
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                c.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, o) }
                var s = 1; while (o.outWidth / s > 2400 || o.outHeight / s > 2400) s *= 2
                val bmp = c.contentResolver.openInputStream(u)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) } ?: return@forEachIndexed
                val rotated = rotateByExif(c, u, bmp)
                val (pw, ph) = if (fit) A4_W to A4_H else rotated.width * 72 / 150 to rotated.height * 72 / 150
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val scale = minOf(pw.toFloat() / rotated.width, ph.toFloat() / rotated.height)
                val dw = rotated.width * scale; val dh = rotated.height * scale
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawBitmap(rotated, null, RectF((pw - dw) / 2, (ph - dh) / 2, (pw + dw) / 2, (ph + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(page); onPage(i + 1)
            }
            pdf.writeTo(out)
        } finally { pdf.close() }
    }

    private fun rotateByExif(c: Context, u: Uri, bmp: Bitmap): Bitmap = runCatching {
        val deg = c.contentResolver.openInputStream(u)!!.use { androidx.exifinterface.media.ExifInterface(it).rotationDegrees }
        if (deg == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(deg.toFloat()) }, true)
    }.getOrDefault(bmp)

    fun fromText(title: String, body: String, out: OutputStream, fontSize: Float) {
        val pdf = PdfDocument()
        val margin = 56f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = fontSize }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = fontSize * 1.8f; isFakeBoldText = true }
        val width = (A4_W - margin * 2).toInt()
        val all = StaticLayout.Builder.obtain(body, 0, body.length, paint, width).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0f, 1.25f).build()
        var line = 0; var pageNo = 1
        try {
            while (line < all.lineCount || pageNo == 1) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(A4_W, A4_H, pageNo).create())
                val c = page.canvas
                var top = margin
                if (pageNo == 1 && title.isNotBlank()) {
                    val t = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, width).build()
                    c.save(); c.translate(margin, top); t.draw(c); c.restore(); top += t.height + fontSize
                }
                val avail = A4_H - margin - top
                val startY = all.getLineTop(line)
                var end = line
                while (end < all.lineCount && all.getLineBottom(end) - startY <= avail) end++
                if (end == line && line < all.lineCount) end = line + 1
                c.save(); c.translate(margin, top - startY); c.clipRect(0f, startY.toFloat(), width.toFloat(), (if (end > 0) all.getLineBottom(end - 1) else startY).toFloat()); all.draw(c); c.restore()
                val foot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 9f; textAlign = Paint.Align.CENTER }
                c.drawText("$pageNo", A4_W / 2f, A4_H - 24f, foot)
                pdf.finishPage(page)
                line = end; pageNo++
                if (all.lineCount == 0) break
            }
            pdf.writeTo(out)
        } finally { pdf.close() }
    }

    suspend fun merge(c: Context, docs: List<Uri>, out: OutputStream, onPage: (Int) -> Unit) {
        val pdf = PdfDocument()
        var n = 0
        try {
            docs.forEach { u ->
                val d = Doc(c, u)
                try {
                    for (i in 0 until d.pageCount) {
                        val (pw, ph) = d.sizes[i]
                        val bmp = d.render(i, (pw * 2).coerceAtMost(2400))
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, ++n).create())
                        page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, pw.toFloat(), ph.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                        pdf.finishPage(page); onPage(n)
                    }
                } finally { d.close() }
            }
            pdf.writeTo(out)
        } finally { pdf.close() }
    }
}
