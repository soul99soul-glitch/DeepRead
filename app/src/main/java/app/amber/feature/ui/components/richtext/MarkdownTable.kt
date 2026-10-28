package app.amber.feature.ui.components.richtext

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.amber.feature.ui.components.table.DataTable
import app.amber.feature.ui.utils.amberTraceMeasure
import app.amber.feature.ui.components.richtext.tree.JvmMdNode
import app.amber.feature.ui.components.richtext.tree.MdNode
import app.amber.feature.ui.components.richtext.tree.MdNodeType
import app.amber.feature.ui.components.richtext.tree.textIn

private val TABLE_CELL_MARKDOWN_HINT_REGEX = Regex(
    """[`*_~\[\]()!#$<>\\]|https?://|(?:[A-Za-z0-9-]+\.)+(?:com|net|org|io|ai|cn|co|dev|app|me|info|xyz|news)|\n"""
)

@Composable
internal fun TableNode(
    node: MdNode,
    content: String,
    modifier: Modifier = Modifier,
    liveSuffix: String = "",
    liveSuffixSourceOffset: Int = 0,
) {
    val settledTableData = remember(node, content) {
        traceMarkdown("Amber Markdown table extract") {
            extractMarkdownTableData(node = node, content = content)
        }
    } ?: return
    val sourceOffsetBase = LocalMarkdownSourceOffsetBase.current
    val tableData = remember(node, content, sourceOffsetBase, liveSuffix, liveSuffixSourceOffset) {
        traceMarkdown("Amber Markdown table streaming extract") {
            extractStreamingMarkdownTableData(
                node = node,
                content = content,
                settledData = settledTableData,
                sourceOffsetBase = sourceOffsetBase,
                liveSuffix = liveSuffix,
                liveSuffixSourceOffset = liveSuffixSourceOffset,
            )
        }
    }
    val tableMotionKey = remember(node.type, sourceOffsetBase, node.startOffset) {
        streamingMarkdownMotionKey(
            type = node.type,
            sourceOffsetBase = sourceOffsetBase,
            nodeStartOffset = node.startOffset,
        )
    }
    val firstLiveRowIndex = if (liveSuffix.isNotEmpty()) {
        settledTableData.rows.size
    } else {
        0
    }
    val tableCellRevealEnabled =
        tableData.rows.size * tableData.columnCount <= STREAMING_TABLE_CELL_REVEAL_MAX_CELLS

    // 创建表头composable列表
    val headers = List(tableData.columnCount) { columnIndex ->
        @Composable {
            TableCellContent(tableData.headers.getOrElse(columnIndex) { "" })
        }
    }

    // 创建行数据composable列表
    val rowComposables = tableData.rows.map { rowData ->
        List(tableData.columnCount) { columnIndex ->
            @Composable {
                TableCellContent(rowData.getOrElse(columnIndex) { "" })
            }
        }
    }

    TraceMarkdownComposable("Amber DataTable render") {
        StreamingBlockReveal(
            node = node,
            modifier = modifier
                .padding(vertical = 8.dp)
                .amberTraceMeasure("Amber DataTable measure"),
        ) { revealModifier ->
            DataTable(
                headers = headers,
                rows = rowComposables,
                modifier = revealModifier,
                columnMinWidths = List(tableData.columnCount) { 80.dp },
                columnMaxWidths = List(tableData.columnCount) { 200.dp },
                bodyCellModifier = { rowIndex, columnIndex ->
                    streamingRevealModifier(
                        key = streamingTableCellMotionKey(
                            tableKey = tableMotionKey,
                            rowIndex = rowIndex,
                            columnIndex = columnIndex,
                            header = false,
                        ),
                        enabled = tableCellRevealEnabled &&
                            (liveSuffix.isEmpty() || rowIndex >= firstLiveRowIndex),
                        durationMillis = STREAMING_TABLE_CELL_REVEAL_MS,
                        startAlpha = STREAMING_TABLE_CELL_REVEAL_START_ALPHA,
                    )
                },
            )
        }
    }
}

@Composable
private fun TableCellContent(content: String) {
    if (TABLE_CELL_MARKDOWN_HINT_REGEX.containsMatchIn(content)) {
        MarkdownBlock(content = content, fillWidth = false)
    } else {
        Text(
            text = content,
            modifier = Modifier.padding(start = 4.dp),
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = LocalTextStyle.current,
        )
    }
}

internal data class MarkdownTableData(
    val columnCount: Int,
    val headers: List<String>,
    val rows: List<List<String>>,
)

internal fun extractStreamingMarkdownTableData(
    node: MdNode,
    content: String,
    settledData: MarkdownTableData,
    sourceOffsetBase: Int,
    liveSuffix: String,
    liveSuffixSourceOffset: Int,
): MarkdownTableData {
    if (liveSuffix.isEmpty()) return settledData

    val absoluteTableEnd = sourceOffsetBase + node.endOffset
    if (liveSuffixSourceOffset != absoluteTableEnd) return settledData

    val tableText = node.textIn(content) + liveSuffix
    // Re-parse the appended table text on the JVM parser and wrap the table node in JvmMdNode
    // so extractMarkdownTableData stays MdNode-typed (the only place a JetBrains parse leaks
    // here, mirroring the parse-funnel wrapping at parsePreprocessedMarkdownUncached).
    //
    // THIRD funnel site — stays JVM-only by design (TD.Rust.1a Task 13, not in scope): this
    // re-parses a tiny appended table fragment for live streaming, not a full document. The
    // `markdownAst` native switch is intentionally NOT applied here; the fragment is JvmMdNode
    // regardless of the flag. Switching it would add JNI cost per streaming tick for no parity
    // benefit (the settled table already went through the flag-gated main funnel).
    val streamingTable = JvmMdNode(markdownJvmParser.buildMarkdownTreeFromString(tableText), tableText, null)
        .children
        .firstOrNull { it.type == MdNodeType.Table }
        ?: return settledData

    return extractMarkdownTableData(node = streamingTable, content = tableText) ?: settledData
}

internal fun extractMarkdownTableData(node: MdNode, content: String): MarkdownTableData? {
    val headerNode = node.children.find { it.type == MdNodeType.TableHead }
    val rowNodes = node.children.filter { it.type == MdNodeType.TableRow }
    val columnCount = headerNode?.children?.count { it.type == MdNodeType.TableCell } ?: 0
    if (columnCount == 0) return null

    val headerCells = headerNode?.children
        ?.filter { it.type == MdNodeType.TableCell }
        ?.map { it.textIn(content).trim() }
        ?: emptyList()
    val rows = rowNodes.map { rowNode ->
        rowNode.children
            .filter { it.type == MdNodeType.TableCell }
            .map { it.textIn(content).trim() }
    }
    return MarkdownTableData(
        columnCount = columnCount,
        headers = headerCells,
        rows = rows,
    )
}
