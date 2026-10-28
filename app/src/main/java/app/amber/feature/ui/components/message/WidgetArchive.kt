package app.amber.feature.ui.components.message

import android.content.Context
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun ZipOutputStream.writeTextEntry(name: String, text: String) {
    putNextEntry(ZipEntry(name))
    write(text.toByteArray(Charsets.UTF_8))
    closeEntry()
}

internal fun ZipOutputStream.writeAssetEntry(context: Context, name: String, assetPath: String) {
    putNextEntry(ZipEntry(name))
    context.assets.open(assetPath).use { input -> input.copyTo(this) }
    closeEntry()
}

internal fun safeArchiveName(value: String?): String =
    (value?.takeIf { it.isNotBlank() } ?: "slides")
        .replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
        .take(40)
        .ifBlank { "slides" }
