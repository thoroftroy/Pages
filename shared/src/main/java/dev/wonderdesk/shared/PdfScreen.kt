package dev.wonderdesk.shared

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

fun pdfPageIndex(value: String, total: Int): Int {
    require(total > 0) { "Open a document first" }
    val number = value.trim().toIntOrNull() ?: throw IllegalArgumentException("Enter a page number")
    require(number in 1..total) { "Enter a page from 1 to $total" }
    return number - 1
}

@Composable
fun PdfScreen() {
    val context = LocalContext.current
    val op = rememberOperation()
    var uriText by rememberSaveable { mutableStateOf((context as ComponentActivity).intent.data?.toString()) }
    val uri = uriText?.let(Uri::parse)
    var page by rememberSaveable { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var jump by remember { mutableStateOf(false) }
    var requestedPage by rememberSaveable { mutableStateOf("") }
    var pageError by remember { mutableStateOf("") }
    val local = remember { File(context.cacheDir, "pages-current.pdf") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { value ->
        if (value != null) { context.keepRead(value); uriText = value.toString(); page = 0 }
    }
    LaunchedEffect(uriText) {
        total = 0; bitmap = null
        if (uri != null) {
            try {
                val count = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        local.outputStream().use { output ->
                            val buffer = ByteArray(32768); var bytes = 0L
                            while (true) { val n = input.read(buffer); if (n < 0) break; bytes += n; require(bytes <= 64L * 1024 * 1024) { "PDF exceeds the 64 MiB limit" }; output.write(buffer, 0, n) }
                        }
                    }
                    PdfRenderer(ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
                }
                total = count; page = page.coerceIn(0, (count - 1).coerceAtLeast(0)); op.notice("PDF opened: $count pages")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { op.notice("Cannot open PDF: ${e.message}. Password-protected and damaged PDFs are not supported.") }
        }
    }
    LaunchedEffect(page, total) {
        if (total > 0) {
            try {
                bitmap = withContext(Dispatchers.IO) {
                    PdfRenderer(ParcelFileDescriptor.open(local, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                        renderer.openPage(page).use { pdfPage ->
                            val scale = 1600.0 / maxOf(pdfPage.width, pdfPage.height)
                            Bitmap.createBitmap(maxOf(1, (pdfPage.width * scale).toInt()), maxOf(1, (pdfPage.height * scale).toInt()), Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(android.graphics.Color.WHITE)
                                pdfPage.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
                zoom = 1f; offset = Offset.Zero
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { op.notice("Page could not be rendered: ${e.message}") }
        }
    }
    DisposableEffect(Unit) { onDispose { local.delete() } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AquaButton(onClick = { picker.launch(arrayOf("application/pdf")) }) { Text("Open PDF") }
            AquaSecondaryButton(onClick = { uri?.let { context.share(it, "application/pdf", op::notice) } }, enabled = uri != null) { Text("Share") }
        }
        OperationStatus(op)
        Text("Pinch to zoom; drag to pan. PDFs are read-only. No annotations or text search yet.", style = MaterialTheme.typography.bodySmall)
        Box(Modifier.weight(1f).fillMaxWidth().pointerInput(uriText, page) { detectTransformGestures { _, pan, scale, _ -> zoom = (zoom * scale).coerceIn(1f, 5f); offset = if (zoom == 1f) Offset.Zero else offset + pan } }) {
            bitmap?.let { Image(it.asImageBitmap(), "PDF page ${page + 1}", Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AquaSecondaryButton(onClick = { page-- }, enabled = page > 0) { Text("Previous") }
            Text(if (total > 0) "${page + 1} / $total" else "No document", Modifier.padding(top = 14.dp))
            AquaSecondaryButton(onClick = { page++ }, enabled = page + 1 < total) { Text("Next") }
        }
        TextButton(onClick = { requestedPage = (page + 1).toString(); pageError = ""; jump = true }, enabled = total > 0) { Text("Go to page…") }
    }
    if (jump) AlertDialog(onDismissRequest = { jump = false }, title = { Text("Go to page") }, text = {
        Column {
            OutlinedTextField(requestedPage, { requestedPage = it.take(10); pageError = "" }, label = { Text("Page number (1–$total)") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), isError = pageError.isNotEmpty())
            if (pageError.isNotEmpty()) Text(pageError, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(onClick = {
            runCatching { pdfPageIndex(requestedPage, total) }.onSuccess { page = it; jump = false }.onFailure { pageError = it.message ?: "Invalid page" }
        }) { Text("Go") }
    }, dismissButton = { TextButton(onClick = { jump = false }) { Text("Cancel") } })
}
