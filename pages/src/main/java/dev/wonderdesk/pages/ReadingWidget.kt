package dev.wonderdesk.pages

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.wonderdesk.shared.*

/** Continue reading: the document you last opened (or one you pick), with the page you're on. */
class ReadingWidget : AppWidgetProvider() {
    companion object {
        val IDS = CardIds(R.layout.widget_card, R.layout.widget_card_small, R.id.w_root, R.id.w_img, R.id.w_label, R.id.w_title, R.id.w_text, R.id.w_bar, R.id.w_btn,
            Triple(R.drawable.wd_widget_bg, R.drawable.wd_widget_bg_dark, R.drawable.wd_widget_bg_clear))
        fun build(c: Context, id: Int): android.widget.RemoteViews {
            val s = WidgetPrefs(c, id)
            val look = runCatching { WidgetLook.valueOf(s.str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)
            val all = Library.all(c)
            val pinned = s.str("doc", "")
            val item = all.find { it.uri == pinned } ?: all.maxByOrNull { it.opened }
            val home = widgetOpen(c, MainActivity::class.java, 8300 + id % 100)
            if (item == null) return cardWidget(c, IDS, look, Card("Pages", "Nothing to read yet", "Open a PDF and it shows here", open = home))
            val thumb = if (s.bool("thumb", true)) runCatching { android.graphics.BitmapFactory.decodeFile(Library.thumb(c, item.uri).path) }.getOrNull() else null
            val open = widgetOpen(c, MainActivity::class.java, 8400 + id % 100) { it.setAction(Intent.ACTION_VIEW).setData(Uri.parse(item.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val pct = if (item.pages > 0) ((item.page + 1) * 100 / item.pages) else null
            return cardWidget(c, IDS, look, Card(if (pinned.isNotEmpty()) "Reading" else "Continue reading", item.name.substringBeforeLast('.'),
                if (item.pages > 0) "Page ${item.page + 1} of ${item.pages}" else "", progress = pct, button = "Read", image = thumb, open = open, action = open))
        }
    }
    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        val r = goAsync()
        Thread { ids.forEach { runCatching { mgr.updateAppWidget(it, build(c, it)) } }; r.finish() }.start()
    }
    override fun onDeleted(c: Context, ids: IntArray) { ids.forEach { WidgetPrefs(c, it).clear() } }
}

class ReadingWidgetSetup : WidgetSetupActivity() {
    override fun redraw() { val m = AppWidgetManager.getInstance(this); Thread { runCatching { m.updateAppWidget(widgetId, ReadingWidget.build(this, widgetId)) } }.start() }
    @Composable override fun Content() {
        val c = LocalContext.current
        val s = remember { WidgetPrefs(c, widgetId) }
        val docs = remember { Library.all(c).sortedByDescending { it.opened }.take(20) }
        var look by remember { mutableStateOf(runCatching { WidgetLook.valueOf(s.str("look", "GLASS")) }.getOrDefault(WidgetLook.GLASS)) }
        var thumb by remember { mutableStateOf(s.bool("thumb", true)) }
        var doc by remember { mutableStateOf(s.str("doc", "")) }
        WidgetSetup("Pages", "Reading widget", onCancel = { finish() }, onSave = { s.put("look" to look.name, "thumb" to thumb, "doc" to doc); save() }) {
            Panel(title = "Document") {
                ListRow("Whatever I read last", selected = doc.isEmpty(), onClick = { doc = "" })
                docs.forEach { d -> PanelDivider(); ListRow(d.name, subtitle = if (d.pages > 0) "Page ${d.page + 1} of ${d.pages}" else null, selected = doc == d.uri, onClick = { doc = d.uri }) }
            }
            Panel { SwitchRow("Show the first page", thumb, { thumb = it }, icon = WonderIcons.Image) }
            WidgetLookPicker(look) { look = it }
        }
    }
}
