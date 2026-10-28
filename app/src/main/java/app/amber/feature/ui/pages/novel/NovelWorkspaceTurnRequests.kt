package app.amber.feature.ui.pages.novel

import app.amber.ai.provider.Model
import app.amber.core.settings.Settings
import app.amber.feature.novel.workspace.NovelWorkspacePrompts
import app.amber.feature.novel.workspace.NovelWorkspaceRuntime
import app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceSlug
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.io.File
import java.util.Locale

/** Operation prompts share a captured generation context, never mutable ViewModel state. */
internal class NovelWorkspaceTurnRequests(
    private val directory: File,
    private val branch: String,
    private val slug: String,
    private val settings: Settings,
    private val model: Model,
    private val locale: Locale,
    private val epoch: Long,
    private val fallbackError: String,
    private val injection: NovelWorkspaceInjectionFlags,
) {
    fun discussion(
        text: String,
        preparation: NovelWorkspaceSendPreparation,
        acceptedMessageId: String,
        writeProse: Boolean,
    ): NovelWorkspaceRuntime.TurnRequest = request(
        text,
        when {
            preparation.isBlankBook -> NovelWorkspacePrompts.quickStart(genre = "", coreIdea = text, locale = locale)
            writeProse -> NovelWorkspacePrompts.proseDraft(NovelWorkspacePrompts.ProseGranularity.CONTINUATION, locale = locale)
            else -> NovelWorkspacePrompts.discussion(locale = locale)
        },
    ).copy(
        // Quickstart writes several files; the ordinary 16 steps starved it into reads on device.
        maxSteps = if (preparation.isBlankBook) 32 else 16,
        history = novelMarkdownDiscussionHistory(preparation.messages, acceptedMessageId),
    )

    fun rewriteLater(fromOrdinal: Int): NovelWorkspaceRuntime.TurnRequest = request(
        localized(
            "请重写第 $fromOrdinal 章起的受影响章节，使其与前文一致。",
            "Rewrite the affected chapters from chapter $fromOrdinal so they remain consistent with the preceding story.",
        ),
        NovelWorkspacePrompts.rewriteLaterChapters(fromOrdinal, locale = locale),
    ).copy(injection = null)

    fun revision(
        chapter: NovelMarkdownChapterUi,
        body: String,
        polish: Boolean,
        writingPreference: String,
        plan: String?,
    ): NovelWorkspaceRuntime.TurnRequest {
        val ordinal = chapter.ordinal
        val prompt = if (polish) NovelWorkspacePrompts.polishChapter(
            chapterOrdinal = ordinal, chapterPath = chapter.path, chapterBody = body,
            writingPreference = writingPreference, locale = locale,
        ) else NovelWorkspacePrompts.regenerateChapter(
            chapterOrdinal = ordinal, chapterTitle = chapter.title, chapterPath = chapter.path,
            chapterBody = body, plan = plan, writingPreference = writingPreference, locale = locale,
        )
        return request(
            localized(
                if (polish) "请润色第 $ordinal 章「${chapter.title}」，保持故事事实不变，提交整章候选供我确认。" else "请重写第 $ordinal 章「${chapter.title}」，把整章替换稿写回 ${chapter.path}。",
                if (polish) "Polish chapter $ordinal (\"${chapter.title}\"), preserve story facts and submit a complete candidate for my approval." else "Rewrite chapter $ordinal (\"${chapter.title}\") as a complete replacement and write it back to ${chapter.path}.",
            ),
            prompt,
        ).copy(polishChapterPath = chapter.path.takeIf { polish })
    }

    fun character(name: String, sketch: String, target: CharacterTarget): NovelWorkspaceRuntime.TurnRequest = request(
        localized("请提案新角色「$name」：$sketch", "Propose a new character \"$name\": $sketch"),
        NovelWorkspacePrompts.characterProposal(
            characterName = name, sketch = sketch, existingCharacters = target.existing,
            targetPath = target.path, locale = locale,
        ),
    )

    fun chapterPlan(): NovelWorkspaceRuntime.TurnRequest = request(
        localized("请根据前文拟定下一章计划并写入计划文件。", "Draft the next chapter plan from the preceding story and write it to the plan file."),
        NovelWorkspacePrompts.planDraft(locale = locale),
    )

    private fun request(text: String, prompt: String) = NovelWorkspaceRuntime.TurnRequest(
        restoreEpoch = epoch,
        projectDirectory = directory,
        branchId = branch,
        branchSlug = slug,
        userText = text,
        systemPrompt = prompt,
        settings = settings,
        model = model,
        fallbackErrorMessage = fallbackError,
        locale = locale,
        injection = injection,
    )

    private fun localized(chinese: String, english: String): String =
        if (locale.language.equals("zh", ignoreCase = true)) chinese else english

    data class CharacterTarget(val existing: List<String>, val path: String)

    companion object {
        fun characterTarget(directory: File, name: String): CharacterTarget {
            val charactersDir = NovelWorkspacePaths.SETTING_DIR + "/characters"
            val existing = runCatching { NovelWorkspaceStore(directory).list(charactersDir) }.getOrDefault(emptyList())
            val leaf = NovelWorkspaceSlug.reservedPath(
                preferred = NovelWorkspaceSlug.slug(name).ifEmpty { "character" },
                used = existing.map { it.substringAfterLast('/').removeSuffix(".md") }.toMutableSet(),
                fallback = "character",
            )
            return CharacterTarget(existing, "$charactersDir/$leaf.md")
        }
    }
}
