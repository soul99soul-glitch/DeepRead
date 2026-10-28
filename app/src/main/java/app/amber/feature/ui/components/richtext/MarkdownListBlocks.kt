package app.amber.feature.ui.components.richtext

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastForEach
import app.amber.feature.ui.components.richtext.tree.MdNode
import app.amber.feature.ui.components.richtext.tree.MdNodeType
import app.amber.feature.ui.components.richtext.tree.textIn

@Composable
internal fun UnorderedListNode(
    node: MdNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    level: Int = 0,
    liveSuffix: String = "",
    liveSuffixSourceOffset: Int = 0,
) {
    val bulletStyle = when (level % 3) {
        0 -> "• "
        1 -> "◦ "
        else -> "▪ "
    }

    Column(
        modifier = modifier
            .fillWidthIf(LocalMarkdownFillWidth.current)
            .padding(start = (level * 8).dp)
    ) {
        var itemIndex = 0
        node.children.fastForEach { child ->
            if (child.type == MdNodeType.ListItem) {
                val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                    children = node.children,
                    child = child,
                    liveSuffix = liveSuffix,
                    liveSuffixSourceOffset = liveSuffixSourceOffset,
                )
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = bulletStyle,
                    onClickCitation = onClickCitation,
                    level = level,
                    itemIndex = itemIndex,
                    liveSuffix = childLiveSuffix.text,
                    liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                )
                itemIndex++
            }
        }
    }
}

@Composable
internal fun OrderedListNode(
    node: MdNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    level: Int = 0,
    liveSuffix: String = "",
    liveSuffixSourceOffset: Int = 0,
) {
    Column(
        modifier = modifier
            .fillWidthIf(LocalMarkdownFillWidth.current)
            .padding(start = (level * 8).dp)
    ) {
        // Parity class [F] — ordered-list start number, shape-agnostic dispatch (same family as
        // BUG #1 / class [C]). The displayed number per item is derived from two different tree
        // shapes:
        //  - JVM (JetBrains): each LIST_ITEM carries its literal LIST_NUMBER marker ("7. ", "8. ",
        //    …) as its first child, mapping to MdNodeType.Unknown. R-E2 / KU-7 keep slicing that
        //    literal to preserve the author's EXACT numbering (so `7. 8. 10.` survives gaps).
        //  - Native (pulldown): the LIST_ITEM has NO literal marker child — its children are the
        //    flat inline run (the native item starts directly at its first inline `Text`).
        //    findChildOfTypeRecursive(Unknown) returns null there, so the OLD `?: "$index. "`
        //    fallback renumbered the list from 1 (`1. 2. 3.` for a list authored `7. 8. 9.`).
        // Fix: when the literal marker is ABSENT (native shape) derive the number from the list's
        // `listStart` accessor — `(listStart ?: 1) + itemIndex` — which both trees decode to the
        // first item's number (listStart == 7L on sample 14, both trees; NativeMdTreeTest
        // orderedListStartIsSeven / JvmMdTree derives the same). This matches JVM's first-item
        // number and increments per item, and for a `start=1` or nested ordered list listStart is 1
        // so it yields `1. 2. 3.` exactly as before. JVM is BYTE-IDENTICAL: when the Unknown marker
        // is present (always, on the JVM tree) the literal slice still wins, so the computed branch
        // is native-only by construction.
        val listStart = node.listStart ?: 1L
        var index = 1
        node.children.fastForEach { child ->
            if (child.type == MdNodeType.ListItem) {
                val numberText =
                    child.findChildOfTypeRecursive(MdNodeType.Unknown)?.textIn(content)
                        ?: "${listStart + (index - 1)}. "
                val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                    children = node.children,
                    child = child,
                    liveSuffix = liveSuffix,
                    liveSuffixSourceOffset = liveSuffixSourceOffset,
                )
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = numberText,
                    onClickCitation = onClickCitation,
                    level = level,
                    itemIndex = index - 1,
                    liveSuffix = childLiveSuffix.text,
                    liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                )
                index++
            }
        }
    }
}

/**
 * Render-time view of a list item's FLAT inline content as a single inline group (parity class
 * [C]). The native tree gives a tight list item flat inline children (no PARAGRAPH wrapper, unlike
 * JVM); to render them as ONE grouped Text the way the JVM Paragraph-wrapper child does, [Paragraph]
 * walks `node.children`, so we hand it a node whose children ARE the inline run.
 *
 * This is renderer-side dispatch, NOT tree fakery: [inlineChildren] are the REAL parsed native
 * nodes (a subset of [delegate]'s children with the nested lists removed by `separateContentAndLists`).
 * Everything except the structural fields [Paragraph] reads is delegated to the real list-item node:
 *  - [type] is pinned to [MdNodeType.Paragraph] so Paragraph's `else`-arm walkers and the
 *    streaming-suffix target check classify it as a grouped inline block (it is one).
 *  - [children] / [contentChildren] expose only the inline run (no nested lists).
 *  - [findChildOfTypeRecursive] searches ONLY the inline run, so Paragraph's image/inline-math
 *    pre-scans don't reach into a sibling nested list.
 *  - [nextSibling] is null: the group is the item's leading content; its bottom-padding cue must
 *    not depend on the list item's own sibling (a following list item).
 * All other typed accessors delegate to [delegate]; none are read for a Paragraph-typed node.
 */
private class InlineRunMdNode(
    private val delegate: MdNode,
    private val inlineChildren: List<MdNode>,
) : MdNode by delegate {
    override val type: MdNodeType get() = MdNodeType.Paragraph
    override val children: List<MdNode> get() = inlineChildren
    override val contentChildren: List<MdNode> get() = inlineChildren
    override val parent: MdNode? get() = delegate
    override fun nextSibling(): MdNode? = null

    override fun findChildOfTypeRecursive(vararg types: MdNodeType): MdNode? {
        if (MdNodeType.Paragraph in types) return this
        inlineChildren.fastForEach { child ->
            child.findChildOfTypeRecursive(*types)?.let { return it }
        }
        return null
    }
}

@Composable
internal fun ListItemNode(
    node: MdNode,
    content: String,
    bulletText: String,
    onClickCitation: (String) -> Unit = {},
    level: Int,
    itemIndex: Int,
    liveSuffix: String = "",
    liveSuffixSourceOffset: Int = 0,
) {
    Column(
        modifier = Modifier.fillWidthIf(LocalMarkdownFillWidth.current)
    ) {
        // 分离列表项的直接内容和嵌套列表
        val (directContent, nestedLists) = separateContentAndLists(node)
        // directContent 渲染处理
        if (directContent.isNotEmpty()) {
            StreamingBlockReveal(
                node = node,
                enabled = itemIndex < STREAMING_LIST_ITEM_STAGGER_LIMIT,
                delayMillis = itemIndex * STREAMING_LIST_ITEM_STAGGER_MS,
                durationMillis = STREAMING_LIST_ITEM_REVEAL_MS,
            ) { revealModifier ->
                Row(
                    modifier = revealModifier.fillWidthIf(LocalMarkdownFillWidth.current)
                ) {
                    Text(
                        text = bulletText,
                        modifier = Modifier.alignByBaseline(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Shape-agnostic list-item inline-content dispatch (parity class [C], same
                        // structural family as the heading BUG #1 fix). A TIGHT list item carries its
                        // inline content in two different tree shapes:
                        //  - JVM (JetBrains): LIST_ITEM children = [`- ` marker Unknown, PARAGRAPH
                        //    wrapper (→ MdNodeType.Paragraph) holding the inline run, EOL/indent
                        //    Unknowns, nested LIST]. The inline content is GROUPED inside that one
                        //    Paragraph child, which renders as a single Text run.
                        //  - Native (pulldown): NO Paragraph wrapper — the LIST_ITEM's inline content
                        //    is FLAT inline children (Text/InlineCode/Strong/… directly under the item;
                        //    see MarkdownTreeParityTest TREEDIAG). The old per-child loop rendered each
                        //    flat node as its OWN Text composable, splitting `Call ` / `code` / ` to…`
                        //    into three runs instead of one (parity divergence [C]).
                        // Fix: if directContent contains a Paragraph wrapper (JVM), render per-child as
                        // before — the Paragraph groups, the marker/whitespace Unknowns render nothing.
                        // Otherwise (native flat inline) group the whole directContent run into ONE
                        // Paragraph render so it collapses to a single grouped Text exactly like JVM.
                        // JVM byte-identical: the JVM branch is the unchanged original loop.
                        val hasParagraphWrapper =
                            directContent.fastFirstOrNull { it.type == MdNodeType.Paragraph } != null
                        if (hasParagraphWrapper) {
                            directContent.fastForEach { contentChild ->
                                val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                                    children = node.children,
                                    child = contentChild,
                                    liveSuffix = liveSuffix,
                                    liveSuffixSourceOffset = liveSuffixSourceOffset,
                                )
                                MarkdownNode(
                                    node = contentChild,
                                    content = content,
                                    modifier = Modifier.fillWidthIf(LocalMarkdownFillWidth.current),
                                    onClickCitation = onClickCitation,
                                    listLevel = level,
                                    liveSuffix = childLiveSuffix.text,
                                    liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
                                )
                            }
                        } else {
                            // Edge shape: the nested-FIRST list item `- \n  - nested` has, on the JVM
                            // tree, a LIST_ITEM whose directContent is ONLY marker/whitespace Unknown
                            // tokens (no Paragraph wrapper, because the item's leading line carries no
                            // inline text). Grouping those raw Unknown tokens through InlineRunMdNode
                            // → Paragraph would append the literal `-` marker (a flag-OFF render
                            // regression). The OLD per-child loop fed each such Unknown token to
                            // MarkdownNode, which has NO Unknown arm and so hit the generic `else`
                            // arm — that walks `node.children`, and an Unknown marker LEAF has none,
                            // so it rendered NOTHING (verified: no Unknown arm in the MarkdownNode
                            // `when`; the else arm at the bottom is the only catch-all). Replicate
                            // exactly: build the inline run from the NON-Unknown children only, and if
                            // that set is empty, render nothing. NOTE: native list-item trees never
                            // produce Unknown children (pulldown emits flat REAL inline nodes), so this
                            // filter is a JVM-only guard by construction and is a no-op for the native
                            // shape it was added to support.
                            //
                            // Parity class [G] — task-list `[x]` marker text. On the native (pulldown)
                            // tree a task item's children are FLAT inline, with the checkbox emitted as
                            // a CHILDLESS TaskListMarker leaf whose text is `[x]`/`[ ]` (native item =
                            // [TaskListMarker «[x]», Text «Increment », …]). The old grouping fed that
                            // TaskListMarker into InlineRunMdNode →
                            // Paragraph, where its childless leaf hit the generic leaf arm and appended
                            // the LITERAL `[x]` into the text run (`text=[x]Increment…`). The JVM tree
                            // instead relocates the marker into a TaskListMarker node OUTSIDE the
                            // Paragraph wrapper and renders it as a checkbox composable (the per-child
                            // loop above dispatches it to MarkdownNode's TaskListMarker arm; the text
                            // wrapper excludes `[x]`). Fix shape-agnostically: render any TaskListMarker
                            // child as the checkbox via MarkdownNode (exactly as the JVM per-child loop
                            // does), and EXCLUDE it from the grouped inline run so the `[x]` text never
                            // reaches Paragraph. The checkbox carries no contentDescription, so the
                            // remaining grouped text matches the JVM render. JVM is byte-identical: its
                            // task items have a Paragraph wrapper and take the per-child branch above, so
                            // this branch is native-only for task items by construction.
                            directContent.fastForEach { contentChild ->
                                if (contentChild.type == MdNodeType.TaskListMarker) {
                                    MarkdownNode(
                                        node = contentChild,
                                        content = content,
                                        modifier = Modifier.fillWidthIf(LocalMarkdownFillWidth.current),
                                        onClickCitation = onClickCitation,
                                        listLevel = level,
                                    )
                                }
                            }
                            val inlineRun = directContent.filter {
                                it.type != MdNodeType.Unknown && it.type != MdNodeType.TaskListMarker
                            }
                            if (inlineRun.isNotEmpty()) {
                                // Attribute the live suffix to this grouped inline run iff the item's
                                // last renderable child lives inside directContent (i.e. there's no
                                // trailing nested list that would own the suffix instead) — mirrors the
                                // per-child attribution the JVM loop performs against node.children.
                                val groupSuffix = inlineRun.lastOrNull()?.let { last ->
                                    streamingLiveSuffixForLastRenderableChild(
                                        children = node.children,
                                        child = last,
                                        liveSuffix = liveSuffix,
                                        liveSuffixSourceOffset = liveSuffixSourceOffset,
                                    )
                                } ?: EMPTY_STREAMING_LIVE_SUFFIX
                                Paragraph(
                                    node = InlineRunMdNode(node, inlineRun),
                                    content = content,
                                    modifier = Modifier.fillWidthIf(LocalMarkdownFillWidth.current),
                                    onClickCitation = onClickCitation,
                                    liveSuffix = groupSuffix.text,
                                    liveSuffixSourceOffset = groupSuffix.sourceOffset,
                                )
                            }
                        }
                    }
                }
            }
        }
        // nestedLists 渲染处理
        nestedLists.fastForEach { nestedList ->
            val childLiveSuffix = streamingLiveSuffixForLastRenderableChild(
                children = node.children,
                child = nestedList,
                liveSuffix = liveSuffix,
                liveSuffixSourceOffset = liveSuffixSourceOffset,
            )
            MarkdownNode(
                node = nestedList,
                content = content,
                onClickCitation = onClickCitation,
                listLevel = level + 1, // 增加层级
                liveSuffix = childLiveSuffix.text,
                liveSuffixSourceOffset = childLiveSuffix.sourceOffset,
            )
        }
    }
}

// 分离列表项的直接内容和嵌套列表
private fun separateContentAndLists(listItemNode: MdNode): Pair<List<MdNode>, List<MdNode>> {
    val directContent = mutableListOf<MdNode>()
    val nestedLists = mutableListOf<MdNode>()
    listItemNode.children.fastForEach { child ->
        when (child.type) {
            MdNodeType.ListUnordered, MdNodeType.ListOrdered -> {
                nestedLists.add(child)
            }

            else -> {
                directContent.add(child)
            }
        }
    }
    return directContent to nestedLists
}
