package dev.wonderdesk.pages

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.wonderdesk.shared.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A PDF found anywhere on the phone. */
data class PhoneDoc(val uri: Uri, val name: String, val folder: String, val size: Long, val modified: Long)

object PhoneDocs {
    fun scan(c: Context): List<PhoneDoc> = runCatching {
        val out = mutableListOf<PhoneDoc>()
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        c.contentResolver.query(files, arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED), "${MediaStore.MediaColumns.MIME_TYPE} = ?", arrayOf("application/pdf"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { cur ->
            while (cur.moveToNext()) out += PhoneDoc(ContentUris.withAppendedId(files, cur.getLong(0)), cur.getString(1) ?: "Document.pdf",
                (cur.getString(2) ?: "").trimEnd('/').ifBlank { "Internal storage" }, cur.getLong(3), cur.getLong(4) * 1000)
        }
        out
    }.getOrDefault(emptyList())
}

/** Every PDF on the phone, newest first, with search, sorting and folders: no need to hunt through a file picker. */
@Composable
fun PhoneTab(onOpen: (Uri, String) -> Unit) {
    val context = LocalContext.current
    val p = wonderPalette()
    val resumed = rememberIsResumed()
    var access by remember { mutableStateOf(SpecialAccess.granted(context, SpecialAccess.Kind.ALL_FILES)) }
    var docs by remember { mutableStateOf<List<PhoneDoc>?>(null) }
    var q by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("recent") }
    var guide by remember { mutableStateOf(false) }
    LaunchedEffect(resumed) {
        if (!resumed) return@LaunchedEffect
        access = SpecialAccess.granted(context, SpecialAccess.Kind.ALL_FILES)
        docs = withContext(Dispatchers.IO) { PhoneDocs.scan(context) }
    }
    val list = remember(docs, q, sort) {
        docs.orEmpty().filter { q.isBlank() || it.name.contains(q, true) || it.folder.contains(q, true) }.let { l ->
            when (sort) { "name" -> l.sortedBy { it.name.lowercase() }; "size" -> l.sortedByDescending { it.size }; "folder" -> l.sortedWith(compareBy({ it.folder.lowercase() }, { it.name.lowercase() })); else -> l }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = rememberTabTopList(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!access) item {
            Panel(footer = "Android only lets Pages see PDFs other apps saved (Downloads, WhatsApp, email…) with this switch. Pages only reads documents, it never changes or uploads them.") {
                ListRow("See every PDF on your phone", subtitle = "Turn on All files access for Pages", icon = WonderIcons.Folder, tint = p.warning, chevron = true, onClick = { guide = true })
            }
        }
        item { SearchField(q, { q = it }, "Search " + (docs?.size ?: 0).let { if (it == 1) "1 document" else "$it documents" } + " by name or folder") }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("recent" to "Recent", "name" to "A to Z", "size" to "Largest", "folder" to "By folder").forEach { (k, l) -> Chip(l, sort == k, { sort = k }) }
            }
        }
        when {
            docs == null -> item { AquaProgress(null, Modifier.fillMaxWidth()) }
            list.isEmpty() -> item { EmptyState(if (q.isBlank()) "No PDFs found" else "No matches", if (q.isBlank()) "PDFs you download or receive show up here by themselves." else "Nothing called “$q”.", icon = WonderIcons.PictureAsPdf) }
            else -> items(list, key = { it.uri.toString() }) { d ->
                Panel {
                    ListRow(d.name.removeSuffix(".pdf").removeSuffix(".PDF"), subtitle = d.folder.substringAfterLast('/') + " · " + formatBytes(d.size) + " · " + relativeTime(d.modified),
                        icon = WonderIcons.PictureAsPdf, tint = Color(0xFFD7372F), chevron = true, onClick = { onOpen(d.uri, d.name) })
                }
            }
        }
    }
    if (guide) AccessGuide(SpecialAccess.Kind.ALL_FILES, "Pages lists every PDF on your phone so you can open any of them in one tap.", onDismiss = { guide = false }, onGranted = { access = true })
}
