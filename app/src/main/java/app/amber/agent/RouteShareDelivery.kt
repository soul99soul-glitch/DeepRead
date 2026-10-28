package app.amber.agent

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** Delivery state is saved by RouteActivity; this effect only decodes and appends. */
@Composable
internal fun RouteShareDelivery(
    currentIntent: Intent?,
    isConsumed: () -> Boolean,
    onDelivery: (Screen.ShareHandler) -> Unit,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(currentIntent) {
        if (isConsumed()) return@LaunchedEffect
        val intent = currentIntent ?: return@LaunchedEffect
        if (!isShareDeliveryAction(intent.action)) return@LaunchedEffect
        shareScreenFromIntent(intent)?.let(onDelivery)
        // VIEW without a content/file target (including OAuth) is still consumed.
        onConsumed()
    }
}

internal fun shareScreenFromIntent(intent: Intent): Screen.ShareHandler? {
    if (!isShareDeliveryAction(intent.action)) return null
    val streams = when (intent.action) {
        Intent.ACTION_SEND -> intent.extractSingleStreamUri()
        Intent.ACTION_SEND_MULTIPLE -> intent.extractMultipleStreamUris()
        Intent.ACTION_VIEW -> intent.data
            ?.takeIf { it.scheme == "content" || it.scheme == "file" }
            ?.let { listOf(it.toString()) }
            .orEmpty()
        else -> emptyList()
    }
    if (intent.action == Intent.ACTION_VIEW && streams.isEmpty()) return null
    return Screen.ShareHandler(
        text = intent.extractSharedText(),
        streamUris = streams,
        sourceAction = intent.action,
        declaredMimeType = intent.type,
    )
}

private fun isShareDeliveryAction(action: String?): Boolean = when (action) {
    Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW, Intent.ACTION_PROCESS_TEXT -> true
    else -> false
}

private fun Intent.extractSharedText(): String {
    val text = getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        ?: getStringExtra(Intent.EXTRA_HTML_TEXT)
        ?: getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        ?: ""
    val subject = getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
    return listOf(subject, text)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString("\n\n")
}

@Suppress("DEPRECATION")
private fun Intent.extractSingleStreamUri(): List<String> =
    getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        ?.let { listOf(it.toString()) }
        .orEmpty()

@Suppress("DEPRECATION")
private fun Intent.extractMultipleStreamUris(): List<String> =
    getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        ?.map { it.toString() }
        .orEmpty()
