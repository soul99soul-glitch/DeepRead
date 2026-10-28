package app.amber.feature.novel.workspace

import app.amber.ai.provider.Model
import app.amber.core.settings.Settings
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteCandidate
import app.amber.feature.novelworkspace.NovelWorkspaceGhostwriteJobs
import app.amber.feature.novelworkspace.NovelWorkspaceInjectionFlags
import app.amber.feature.novelworkspace.NovelWorkspaceLedger
import app.amber.feature.novelworkspace.NovelWorkspaceMarkdown
import app.amber.feature.novelworkspace.NovelWorkspacePaths
import app.amber.feature.novelworkspace.NovelWorkspaceStore
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Generates one candidate (or a legacy host-written chapter), without owning the batch loop. */
internal class NovelWorkspaceChapterGenerator(
    private val runtime: NovelWorkspaceRuntime,
    private val turnLauncher: NovelTurnLauncher,
) {
    /** One chapter-writing turn; version-bound jobs persist a private candidate only. */
    suspend fun ghostwriteOneChapter(
        projectDirectory: File,
        branchId: String,
        branchSlug: String,
        settings: Settings,
        model: Model,
        chapterOrdinal: Int,
        maxSteps: Int = 24,
        injection: NovelWorkspaceInjectionFlags? = null,
        ownerJobId: String? = null,
        ownerExecutionId: String? = null,
        repairInstructions: List<String> = emptyList(),
        locale: Locale = Locale.CHINESE,
        fallbackErrorMessage: String = "Generation failed",
    ): NovelWorkspaceChapterResult {
        val store = NovelWorkspaceStore(projectDirectory)
        val commitIdBeforeTurn = NovelWorkspaceLedger.load(projectDirectory).headOf(branchId)?.id
        var boundJob = ownerJobId?.let { NovelWorkspaceGhostwriteJobs.load(projectDirectory, it) }
        val candidateMode = boundJob?.isVersionBound == true
        val rewrite = repairInstructions.isNotEmpty()
        if (candidateMode) {
            val currentCandidate = boundJob.pendingCandidate
            if (!rewrite && currentCandidate?.chapterOrdinal == chapterOrdinal) {
                return NovelWorkspaceChapterResult(commitId = null, candidate = currentCandidate)
            }
            if (rewrite && currentCandidate == null) {
                return NovelWorkspaceChapterResult(
                    commitId = null,
                    error = ghostwriteLocalized(
                        locale,
                        chinese = "没有可重写的章节候选",
                        english = "There is no chapter candidate to rewrite.",
                    ),
                )
            }
            if (rewrite && currentCandidate != null &&
                currentCandidate.attempt >= NovelWorkspaceGhostwriteCandidate.MAX_REWRITE_ATTEMPTS
            ) {
                return NovelWorkspaceChapterResult(
                    commitId = null,
                    error = ghostwriteLocalized(
                        locale,
                        chinese = "本章已达到最多两次定向重写上限",
                        english = "This chapter has reached the limit of two targeted rewrites.",
                    ),
                )
            }
            boundJob = NovelWorkspaceGhostwriteJobs.beginCandidateTurn(
                projectDirectory = projectDirectory,
                jobId = checkNotNull(ownerJobId),
                executionId = checkNotNull(ownerExecutionId),
                chapterOrdinal = chapterOrdinal,
                rewrite = rewrite,
            ) ?: return NovelWorkspaceChapterResult(
                commitId = null,
                error = ghostwriteLocalized(
                    locale,
                    chinese = "代笔已暂停、取消，或正文与确认计划的版本绑定已变化",
                    english = "The ghostwrite batch was paused or cancelled, or its manuscript/plan binding changed.",
                ),
            )
        }
        val plan = boundJob?.takeIf { it.isVersionBound }?.confirmedPlan
            ?: store.read(NovelWorkspacePaths.branchPrefix(branchSlug) + "/plan/this-chapter.md")
                ?.let { NovelWorkspaceMarkdown.parseFile(it).body }
        val basePrompt = NovelWorkspacePrompts.ghostwriteChapter(
            chapterOrdinal,
            plan,
            locale = locale,
        )
        val writingPrompt = if (rewrite) {
            buildString {
                append(basePrompt)
                boundJob?.pendingCandidate?.let { candidate ->
                    append(ghostwriteLocalized(locale, "\n\n## 待修复候选标题\n", "\n\n## Candidate title to repair\n"))
                    append(candidate.title)
                    append(ghostwriteLocalized(locale, "\n\n## 待修复候选正文\n", "\n\n## Candidate prose to repair\n"))
                    append(candidate.body)
                }
                append(
                    ghostwriteLocalized(
                        locale,
                        chinese = "\n\n## 本次定向修复\n只修复下列已确认问题，保持本章计划、核心事件和未被指出的正文不变：\n" +
                            repairInstructions.joinToString("\n") { "- $it" },
                        english = "\n\n## Targeted repairs\nFix only the confirmed issues below. Keep the chapter plan, core events, and unaffected prose unchanged:\n" +
                            repairInstructions.joinToString("\n") { "- $it" },
                    ),
                )
            }
        } else {
            basePrompt
        }
        // A wedged/trickling provider could otherwise hang a chapter turn for the
        // OkHttp read-timeout window or longer with no feedback anywhere; bound it
        // and surface as a chapter failure (retry/stop logic then applies).
        val turnHandle = turnLauncher.launch(
            NovelWorkspaceRuntime.TurnRequest(
                projectDirectory = projectDirectory,
                branchId = branchId,
                branchSlug = branchSlug,
                userText = ghostwriteLocalized(
                    locale,
                    chinese = "请写第 $chapterOrdinal 章。",
                    english = "Write chapter $chapterOrdinal.",
                ),
                systemPrompt = writingPrompt,
                settings = settings,
                model = model,
                maxSteps = maxSteps,
                readOnlyTools = candidateMode,
                autoApproveCanon = !candidateMode,
                autoCommitMessage = "代笔收录",
                injection = injection,
                ownerJobId = ownerJobId,
                ownerExecutionId = ownerExecutionId,
                ghostwriteChapterOrdinal = chapterOrdinal,
                ghostwritePlanId = boundJob?.takeIf { it.isVersionBound }?.planId,
                ghostwritePlanDigest = boundJob?.takeIf { it.isVersionBound }?.planDigest,
                fallbackErrorMessage = fallbackErrorMessage,
                locale = locale,
            ),
            runtime,
        )
        // One turn emits exactly one terminal event (Completed or Failed); the
        // deltas in between are not needed for an unattended chapter.
        val terminal = try {
            kotlinx.coroutines.withTimeout(CHAPTER_TURN_TIMEOUT_MS) {
                turnHandle.events.first { event ->
                    event is NovelWorkspaceRuntime.TurnEvent.Completed ||
                        event is NovelWorkspaceRuntime.TurnEvent.Failed
                }
            }
        } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
            // The launcher already cancelled the run when withTimeout cancelled
            // the collect; wait for the handler's rollback-on-cancel to settle
            // so the chapter failure never races the restore writes.
            runCatching { turnHandle.awaitTerminal() }
            return NovelWorkspaceChapterResult(
                commitId = null,
                error = ghostwriteLocalized(
                    locale,
                    chinese = "本章生成超时（${CHAPTER_TURN_TIMEOUT_MS / 60_000} 分钟无完成），已中止本轮",
                    english = "Chapter generation timed out (${CHAPTER_TURN_TIMEOUT_MS / 60_000} minutes without completion); this turn was aborted.",
                ),
            )
        }
        val failure = terminal as? NovelWorkspaceRuntime.TurnEvent.Failed
        if (failure != null) return NovelWorkspaceChapterResult(commitId = null, error = failure.message)
        val finalText = (terminal as? NovelWorkspaceRuntime.TurnEvent.Completed)
            ?.finalText
            ?.trim()
            .orEmpty()
        if (candidateMode) {
            if (finalText.length < MIN_HOSTWRITE_CHARS) {
                return NovelWorkspaceChapterResult(
                    commitId = null,
                    error = ghostwriteLocalized(
                        locale,
                        chinese = "本轮未返回可用的完整章节正文",
                        english = "This turn did not return a usable complete chapter.",
                    ),
                )
            }
            val currentJob = checkNotNull(boundJob)
            val previous = currentJob.pendingCandidate
            val now = Instant.now()
            val (title, body) = splitChapterTitle(finalText, chapterOrdinal)
            val candidate = NovelWorkspaceGhostwriteCandidate(
                id = previous?.id ?: "CAND-${UUID.randomUUID().toString().uppercase()}",
                chapterOrdinal = chapterOrdinal,
                planId = currentJob.planId,
                planDigest = currentJob.planDigest,
                attempt = if (rewrite) checkNotNull(previous).attempt + 1 else 0,
                title = title,
                body = body,
                repairInstructions = repairInstructions,
                createdAt = previous?.createdAt ?: now,
                updatedAt = now,
            )
            val recorded = NovelWorkspaceGhostwriteJobs.recordCandidate(
                projectDirectory = projectDirectory,
                jobId = checkNotNull(ownerJobId),
                executionId = checkNotNull(ownerExecutionId),
                candidate = candidate,
            ) ?: return NovelWorkspaceChapterResult(
                commitId = null,
                error = ghostwriteLocalized(
                    locale,
                    chinese = "候选生成完成，但批次版本绑定已变化，未写入正史",
                    english = "The candidate was generated, but the batch binding changed; canon was not modified.",
                ),
            )
            return NovelWorkspaceChapterResult(commitId = null, candidate = recorded.pendingCandidate)
        }
        // A canon commit happened iff the ledger head advanced past where we started.
        val ledgerAfterTurn = NovelWorkspaceLedger.load(projectDirectory)
        val commit = ledgerAfterTurn.headOf(branchId)
        val targetCommitted = chapterOrdinal in NovelWorkspaceLedger.committedChapterOrdinals(
            store,
            ledgerAfterTurn,
            branchSlug,
        )
        if (targetCommitted && commit != null && commit.id != commitIdBeforeTurn) {
            return NovelWorkspaceChapterResult(commitId = commit.id)
        }
        // Host-write fallback (device-proven): many providers narrate the chapter
        // as plain text instead of calling the write tool. A substantial final
        // answer IS the chapter — file it host-side and commit.
        if (finalText.length >= MIN_HOSTWRITE_CHARS) {
            val (title, body) = splitChapterTitle(finalText, chapterOrdinal)
            val filed = try {
                NovelWorkspaceChapterCommits.commitGhostwrittenChapter(
                    projectDirectory = projectDirectory,
                    branchId = branchId,
                    branchSlug = branchSlug,
                    chapterOrdinal = chapterOrdinal,
                    title = title,
                    body = body,
                    ownerJobId = ownerJobId,
                    ownerExecutionId = ownerExecutionId,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return NovelWorkspaceChapterResult(
                    commitId = null,
                    error = ghostwriteOwnerError(
                        error = error,
                        locale = locale,
                        fallbackErrorMessage = fallbackErrorMessage,
                        chinese = "代笔已暂停或取消",
                        english = "The ghostwrite batch is paused or cancelled.",
                    ),
                )
            }
            return NovelWorkspaceChapterResult(commitId = filed.id)
        }
        return NovelWorkspaceChapterResult(commitId = null)
    }

    private val numberedChapterHeading = Regex("""^第\s*[0-9０-９零〇一二三四五六七八九十百千万两壹贰叁肆伍陆柒捌玖拾佰仟]+\s*章""")

    /** Leading "# 标题" / "第N章 标题" line becomes the chapter title; rest is body. */
    private fun splitChapterTitle(text: String, chapterOrdinal: Int): Pair<String, String> {
        val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return "第 $chapterOrdinal 章" to text
        val trimmed = firstLine.trim()
        val heading = trimmed.trimStart('#').trim()
        if (trimmed.startsWith("#") && heading.isNotEmpty()) {
            return heading to text.substringAfter(trimmed).trim()
        }
        if (numberedChapterHeading.containsMatchIn(trimmed)) {
            return trimmed to text.substringAfter(trimmed).trim()
        }
        return "第 $chapterOrdinal 章" to text
    }

}
