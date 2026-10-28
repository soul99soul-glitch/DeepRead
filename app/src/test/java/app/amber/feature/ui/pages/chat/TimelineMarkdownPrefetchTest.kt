package app.amber.feature.ui.pages.chat

import app.amber.agent.data.model.nativebridge.RegexNativeSwitch
import app.amber.ai.core.MessageRole
import app.amber.ai.ui.UIMessage
import app.amber.ai.ui.UIMessagePart
import app.amber.core.model.AssistantAffectScope
import app.amber.core.model.AssistantRegex
import app.amber.core.model.MessageNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

class TimelineMarkdownPrefetchTest {
    private lateinit var previousConfig: RegexNativeSwitch.Config

    @Before
    fun disableNativeLibraryForHostTests() {
        previousConfig = RegexNativeSwitch.config
        RegexNativeSwitch.config = RegexNativeSwitch.DisabledConfig
    }

    @After
    fun restoreNativeConfig() {
        RegexNativeSwitch.config = previousConfig
    }

    @Test
    fun prefetchUsesAssistantVisualRegexAndStripsUserRouteTags() {
        val assistantRule = rule("SECRET", "VISIBLE", AssistantAffectScope.ASSISTANT)
        val userRule = rule("画", "绘", AssistantAffectScope.USER)
        val assistant = node(
            role = MessageRole.ASSISTANT,
            text = "before SECRET after",
        )
        val user = node(
            role = MessageRole.USER,
            text = "[ROUTE:svg]\n画一只猫",
        )
        assertEquals(
            listOf("before VISIBLE after"),
            assistant.prefetchMarkdownBodies(listOf(assistantRule, userRule)),
        )
        assertEquals(
            listOf("绘一只猫"),
            user.prefetchMarkdownBodies(listOf(assistantRule, userRule)),
        )
        assertFalse(
            assistant.prefetchMarkdownBodies(listOf(assistantRule)).contains("before SECRET after"),
        )
        assertFalse(
            user.prefetchMarkdownBodies(listOf(userRule)).any { it.contains("[ROUTE:") },
        )
    }

    @Test
    fun loadingEarlierHistoryDoesNotSkipNewNodesAtPreviousIndexes() {
        val newer = node(text = "newer body")
        val older = node(text = "older body")
        val requested = HashSet<MarkdownPrefetchKey>()
        val first = collectMarkdownPrefetchBodies(
            messageNodes = listOf(newer),
            regexes = emptyList(),
            messageIndexRange = 0..0,
            requested = requested,
            excludedNodeId = null,
        )
        assertEquals(listOf("newer body"), first)
        val second = collectMarkdownPrefetchBodies(
            messageNodes = listOf(older, newer),
            regexes = emptyList(),
            messageIndexRange = 0..1,
            requested = requested,
            excludedNodeId = null,
        )
        assertEquals(listOf("older body"), second)
        assertTrue(requested.any { it.nodeId == older.id })
        assertEquals(2, requested.size)
    }

    @Test
    fun regenerateWithSameNodeIdRewarmChangedContent() {
        val id = Uuid.random()
        val requested = HashSet<MarkdownPrefetchKey>()
        val first = collectMarkdownPrefetchBodies(
            messageNodes = listOf(node(id = id, text = "version one")),
            regexes = emptyList(),
            messageIndexRange = 0..0,
            requested = requested,
            excludedNodeId = null,
        )
        assertEquals(listOf("version one"), first)
        val second = collectMarkdownPrefetchBodies(
            messageNodes = listOf(node(id = id, text = "version two")),
            regexes = emptyList(),
            messageIndexRange = 0..0,
            requested = requested,
            excludedNodeId = null,
        )
        assertEquals(listOf("version two"), second)
        assertEquals(2, requested.size)
        assertTrue(requested.all { it.nodeId == id })
    }

    @Test
    fun generatingTailIsNotPrefetchedWhileLoadingButOtherNodesAreAndFinalBodyIsPrefetchedOnce() {
        val older = node(text = "older body")
        val tailId = Uuid.random()
        val requested = HashSet<MarkdownPrefetchKey>()
        // Lazy order is newest first: lazy 0 is the generating tail (index 1), lazy 1 is older (index 0).
        val loadingPlan = plan(lazyItemMessageIndexes = listOf(1, 0), timelineLoading = true)
        val streamedTails = listOf("Hel", "Hello wor", "Hello world, streaming")
        streamedTails.forEachIndexed { step, text ->
            val bodies = collectMarkdownPrefetchBodies(
                plan = loadingPlan,
                messageNodes = listOf(older, node(id = tailId, text = text)),
                regexes = emptyList(),
                lazyFrom = 0,
                lazyTo = 1,
                requested = requested,
                excludedNodeId = tailId,
            )
            // The visible older node is prefetched on the first pass only (dedupe).
            assertEquals(
                "step $step: generating tail must not be prefetched",
                if (step == 0) listOf("older body") else emptyList<String>(),
                bodies,
            )
        }
        assertEquals(1, requested.size)
        assertFalse(requested.any { it.nodeId == tailId })

        // Generation ends: the settled body is no longer excluded and is prefetched exactly once.
        val settledText = "Hello world, streaming final"
        val settledPlan = plan(lazyItemMessageIndexes = listOf(1, 0), timelineLoading = false)
        val settled = collectMarkdownPrefetchBodies(
            plan = settledPlan,
            messageNodes = listOf(older, node(id = tailId, text = settledText)),
            regexes = emptyList(),
            lazyFrom = 0,
            lazyTo = 1,
            requested = requested,
            excludedNodeId = null,
        )
        assertEquals(listOf(settledText), settled)
        val again = collectMarkdownPrefetchBodies(
            plan = settledPlan,
            messageNodes = listOf(older, node(id = tailId, text = settledText)),
            regexes = emptyList(),
            lazyFrom = 0,
            lazyTo = 1,
            requested = requested,
            excludedNodeId = null,
        )
        assertEquals(emptyList<String>(), again)
    }

    @Test
    fun excludedNodeIdSkipsOnlyThatNodeInMessageIndexRangeOverload() {
        val older = node(text = "older body")
        val tailId = Uuid.random()
        val requested = HashSet<MarkdownPrefetchKey>()
        val bodies = collectMarkdownPrefetchBodies(
            messageNodes = listOf(older, node(id = tailId, text = "streaming")),
            regexes = emptyList(),
            messageIndexRange = 0..1,
            requested = requested,
            excludedNodeId = tailId,
        )
        assertEquals(listOf("older body"), bodies)
        assertFalse(requested.any { it.nodeId == tailId })
    }

    @Test
    fun generatingPrefetchExclusionIsTheLastNodeOnlyWhileLoading() {
        val older = node(text = "older body")
        val tail = node(text = "streaming")
        val nodes = listOf(older, tail)
        assertEquals(
            tail.id,
            generatingPrefetchExclusion(plan(listOf(1, 0), timelineLoading = true), nodes),
        )
        assertEquals(
            null,
            generatingPrefetchExclusion(plan(listOf(1, 0), timelineLoading = false), nodes),
        )
        assertEquals(
            null,
            generatingPrefetchExclusion(plan(emptyList(), timelineLoading = true), emptyList()),
        )
    }

    private fun plan(
        lazyItemMessageIndexes: List<Int?>,
        timelineLoading: Boolean,
    ) = ChatTimelinePlan(
        entries = emptyList(),
        lazyItemMessageIndexes = lazyItemMessageIndexes,
        lazyIndexByMessageIndex = emptyMap(),
        postSendState = PostSendTimelineState(
            sentUserMessageId = null,
            sentUserMessageIndex = null,
            assistantMessageIndex = null,
            hiddenAssistantMessageIndex = null,
            waitingForAssistantContent = false,
        ),
        timelineLoading = timelineLoading,
    )

    private fun node(
        vararg parts: UIMessagePart = emptyArray(),
        role: MessageRole = MessageRole.ASSISTANT,
        text: String? = null,
        id: Uuid = Uuid.random(),
    ) = MessageNode(
        id = id,
        messages = listOf(
            UIMessage(
                role = role,
                parts = if (text != null) listOf(UIMessagePart.Text(text)) else parts.toList(),
            ),
        ),
    )

    private fun rule(
        find: String,
        replacement: String,
        scope: AssistantAffectScope,
    ) = AssistantRegex(
        id = Uuid.random(),
        findRegex = find,
        replaceString = replacement,
        affectingScope = setOf(scope),
        visualOnly = true,
    )
}
