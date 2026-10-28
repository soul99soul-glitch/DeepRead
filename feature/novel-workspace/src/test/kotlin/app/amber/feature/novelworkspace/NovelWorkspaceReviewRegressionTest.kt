package app.amber.feature.novelworkspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelWorkspaceReviewRegressionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `planned foreshadowing remains in the brief until explicitly resolved`() {
        val store = NovelWorkspaceStore(temporaryFolder.newFolder())
        for ((title, status, injection) in listOf(
            Triple("黄龙旗", "planned", "smart"),
            Triple("已回收旧伤", "RESOLVED", "smart"),
            Triple("禁用伏笔", "planned", "off"),
        )) {
            store.write(
                "branches/主线/plot/foreshadowing/$title.md",
                NovelWorkspaceMarkdown.render(
                    fields = listOf("kind" to "foreshadowing", "title" to title,
                        "status" to status, "injection" to injection),
                    body = "$title 的约束内容。",
                ),
            )
        }

        val catalog = NovelWorkspaceCatalog.load(store, NovelWorkspaceLedgerStore(), "主线")
        assertFalse(catalog.foreshadowing.single { it.title == "黄龙旗" }.resolved)
        val brief = NovelWorkspaceContextAssembler.assemble(store, "主线")
        assertTrue("the catalog's unresolved planned node must also constrain generation", brief.contains("黄龙旗"))
        assertFalse(brief.contains("已回收旧伤"))
        assertFalse(brief.contains("禁用伏笔"))
    }
}
