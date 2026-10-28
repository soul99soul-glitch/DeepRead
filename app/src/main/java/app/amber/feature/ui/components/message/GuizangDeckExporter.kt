package app.amber.feature.ui.components.message

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.amber.agent.R
import com.dokar.sonner.ToasterState
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream
import app.amber.core.ai.generative.GuizangHtmlDeckValidator

internal suspend fun shareGuizangDeckArchive(
    context: Context,
    title: String?,
    specJson: String,
    coverHtml: String,
    toaster: ToasterState,
) {
    toaster.show(context.getString(R.string.generative_widget_pack_full_html))
    val archive = runCatching {
        withContext(Dispatchers.IO) {
            createGuizangDeckArchive(context, title, specJson, coverHtml)
        }
    }.getOrElse { error ->
        toaster.show(
            error.message ?: context.getString(R.string.generative_widget_pack_failed),
            type = ToastType.Error,
        )
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, archive.name)
            putExtra(
                Intent.EXTRA_SUBJECT,
                title?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.generative_widget_core_full_html_deck_title),
            )
            clipData = ClipData.newUri(context.contentResolver, archive.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            intent,
            context.getString(R.string.generative_widget_share_full_html_zip),
        )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }.onSuccess {
        toaster.show(
            context.getString(R.string.generative_widget_share_menu_opened),
            type = ToastType.Success,
        )
    }.onFailure { error ->
        toaster.show(
            error.message ?: context.getString(R.string.generative_widget_share_menu_failed),
            type = ToastType.Error,
        )
    }
}

internal fun createGuizangDeckArchive(
    context: Context,
    title: String?,
    specJson: String,
    coverHtml: String,
): File {
    val now = System.currentTimeMillis()
    val safeTitle = safeArchiveName(title)
    val dir = File(context.cacheDir, "full-html-shares").apply { mkdirs() }
    dir.listFiles()
        ?.filter { it.isFile && now - it.lastModified() > 2 * 24 * 60 * 60 * 1000L }
        ?.forEach { it.delete() }
    val archive = File(dir, "AmberAgent_${safeTitle}_full_html_$now.zip")
    val deck = GuizangHtmlDeckValidator.normalizeSpecJson(specJson)
        ?: error(context.getString(R.string.generative_widget_full_html_invalid_data))
    val validation = GuizangHtmlDeckValidator.validateDeck(deck)
    if (!validation.valid) {
        error(
            context.getString(
                R.string.generative_widget_full_html_unsafe_data,
                validation.reason.orEmpty(),
            )
        )
    }
    val exportHtml = GuizangHtmlDeckValidator.rewriteRuntimeUrlsForArchive(deck.html)
    ZipOutputStream(FileOutputStream(archive)).use { zip ->
        zip.writeTextEntry(
            name = "manifest.json",
            text = JSONObject()
                .put("type", "amberagent-full-html")
                .put(
                    "title",
                    title?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.generative_widget_core_full_html_deck_title),
                )
                .put("source", deck.source ?: GuizangHtmlDeckValidator.RENDERER)
                .put("allow_remote_images", deck.allowRemoteImages)
                .put("allow_remote_fonts", deck.allowRemoteFonts)
                .put("created_at_ms", now)
                .toString(2),
        )
        zip.writeTextEntry("index.html", exportHtml)
        zip.writeAssetEntry(context, "assets/motion.min.js", GuizangHtmlDeckValidator.MOTION_ASSET_PATH)
        zip.writeAssetEntry(context, "assets/lucide.min.js", GuizangHtmlDeckValidator.LUCIDE_ASSET_PATH)
        val usesThree = exportHtml.contains("assets/three.min.js")
        if (usesThree) {
            zip.writeAssetEntry(context, "assets/three.min.js", GuizangHtmlDeckValidator.THREE_ASSET_PATH)
        }
        zip.writeTextEntry(
            "README.md",
            """
            # ${title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.generative_widget_core_full_html_deck_title)}

            ${context.getString(R.string.generative_widget_full_html_readme_index_html)}
            ${context.getString(R.string.generative_widget_full_html_readme_motion_js)}
            ${context.getString(R.string.generative_widget_full_html_readme_lucide_js)}
            ${if (usesThree) context.getString(R.string.generative_widget_full_html_readme_three_js) else ""}
            ${context.getString(R.string.generative_widget_full_html_readme_cover_svg)}
            ${context.getString(R.string.generative_widget_full_html_readme_remote_assets)}
            """.trimIndent(),
        )
        val cover = coverHtml.trim()
        if (cover.startsWith("<svg", ignoreCase = true) && cover.length <= 32_000) {
            zip.writeTextEntry("cover.svg", cover)
        }
    }
    return archive
}
