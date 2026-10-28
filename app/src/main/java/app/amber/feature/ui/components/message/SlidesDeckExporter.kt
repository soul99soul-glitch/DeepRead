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
import app.amber.core.ai.generative.VChartSpecValidator

internal suspend fun shareSlidesDeckArchive(
    context: Context,
    title: String?,
    specJson: String,
    coverHtml: String,
    toaster: ToasterState,
) {
    toaster.show(context.getString(R.string.generative_widget_pack_slides))
    val archive = runCatching {
        withContext(Dispatchers.IO) {
            createSlidesDeckArchive(context, title, specJson, coverHtml)
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
                    ?: context.getString(R.string.generative_widget_slides_default_title),
            )
            clipData = ClipData.newUri(context.contentResolver, archive.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            intent,
            context.getString(R.string.generative_widget_share_slides_zip),
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

internal fun createSlidesDeckArchive(
    context: Context,
    title: String?,
    specJson: String,
    coverHtml: String,
): File {
    val now = System.currentTimeMillis()
    val safeTitle = safeArchiveName(title)
    val dir = File(context.cacheDir, "slide-shares").apply { mkdirs() }
    dir.listFiles()
        ?.filter { it.isFile && now - it.lastModified() > 2 * 24 * 60 * 60 * 1000L }
        ?.forEach { it.delete() }
    val archive = File(dir, "AmberAgent_${safeTitle}_$now.zip")
    val normalizedSlides = VChartSpecValidator.normalizeSlidesDeckSpecJson(specJson)
        ?: error(context.getString(R.string.generative_widget_slides_invalid_data))
    val validation = VChartSpecValidator.validateSlidesSpec(normalizedSlides)
    if (!validation.valid) {
        error(
            context.getString(
                R.string.generative_widget_slides_unsafe_data,
                validation.reason.orEmpty(),
            )
        )
    }
    val deckObject = JSONObject(normalizedSlides)
    val prettySlides = deckObject.toString(2)
    val slideCount = deckObject.getJSONArray("slides").length()
    ZipOutputStream(FileOutputStream(archive)).use { zip ->
        zip.writeTextEntry(
            name = "manifest.json",
            text = JSONObject()
                .put("type", "amberagent-slides")
                .put(
                    "title",
                    title?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.generative_widget_slides_default_title),
                )
                .put("slide_count", slideCount)
                .put("created_at_ms", now)
                .toString(2),
        )
        zip.writeTextEntry("slides.json", prettySlides)
        zip.writeTextEntry("theme.json", buildSlidesThemeJson(deckObject))
        zip.writeTextEntry("index.html", buildStandaloneSlidesHtml(context, title, normalizedSlides))
        zip.writeTextEntry(
            "README.md",
            """
            # ${title?.takeIf { it.isNotBlank() } ?: context.getString(R.string.generative_widget_slides_default_title)}

            ${context.getString(R.string.generative_widget_slides_readme_index_html)}
            ${context.getString(R.string.generative_widget_slides_readme_slides_json)}
            ${context.getString(R.string.generative_widget_slides_readme_theme_json)}
            ${context.getString(R.string.generative_widget_slides_readme_cover_svg)}
            ${context.getString(R.string.generative_widget_slides_readme_font_fallback)}
            """.trimIndent(),
        )
        val cover = coverHtml.trim()
        if (cover.startsWith("<svg", ignoreCase = true) && cover.length <= 32_000) {
            zip.writeTextEntry("cover.svg", cover)
        }
    }
    return archive
}

private fun buildSlidesThemeJson(deckObject: JSONObject): String =
    JSONObject()
        .put("style", deckObject.optString("style", "system"))
        .put("accent", deckObject.optString("accent", "#1F5EFF"))
        .put("fontPack", deckObject.optString("fontPack", ""))
        .put("fontEmbedding", "not_included_large_cjk_fonts_use_system_fallback")
        .toString(2)

private fun buildStandaloneSlidesHtml(context: Context, title: String?, specJson: String): String {
    val safeTitle = htmlEscape(
        title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.generative_widget_slides_default_title),
    )
    val slidesLiteral = specJson.toScriptSafeJsonLiteral()
    val sandbox = context.assets.open("generative-libs/slides-sandbox.html")
        .use { it.reader(Charsets.UTF_8).readText() }
    return context.localizeSlidesSandbox(sandbox)
        .replace("<head>", "<head>\n<title>$safeTitle</title>")
        .replace(
            "</body>",
            """
            <script>
            window.__setAmberFontCss && window.__setAmberFontCss("");
            window.__renderSlides && window.__renderSlides($slidesLiteral);
            </script>
            </body>
            """.trimIndent(),
        )
}
