package app.amber.feature.ui.components.richtext

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.jsoup.nodes.Element
import java.io.File

private fun serializeTableCsv(element: Element): String {
    val headers = element.select("thead tr th").map { it.text() }
    val bodyRows = element.select("tbody tr").map { tr ->
        tr.select("td").map { it.text() }
    }
    val columnCount = maxOf(headers.size, bodyRows.maxOfOrNull { it.size } ?: 0)
    if (columnCount == 0) return ""
    return buildList {
        if (headers.isNotEmpty()) add(headers)
        bodyRows.forEach { row ->
            add(List(columnCount) { col -> row.getOrNull(col).orEmpty() })
        }
    }.joinToString("\r\n") { row ->
        row.joinToString(",") { csvEscapeField(it) }
    }
}

private fun csvEscapeField(value: String): String {
    val needsQuoting = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    return if (needsQuoting) "\"" + value.replace("\"", "\"\"") + "\"" else value
}

/** 写入 cache 临时文件 table_<时间戳>.csv 并走系统分享 (与 ExportHooks 同一 FileProvider). */
internal fun shareTableCsv(context: Context, element: Element) {
    val csv = serializeTableCsv(element)
    val file = File(context.cacheDir, "table_${System.currentTimeMillis()}.csv")
    file.writeText(csv)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
