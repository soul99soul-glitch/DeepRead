package app.amber.feature.board.hotlist.deepread

import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.Locale

internal object DeepReadMagazinePrompt {
    fun buildPrompt(
        topicTitle: String,
        stages: List<DeepReadGenerationStage>,
        existingOutput: DeepReadOutput,
        seedUrl: String?,
        scrapeWebAvailable: Boolean,
        evidencePack: DeepReadEvidencePack,
        articlePlan: DeepReadArticlePlan,
        stageEvidence: List<DeepReadEvidenceCard>,
        targetStage: DeepReadGenerationStage,
        stageTimeoutMs: Long,
        playbookMarkdown: String,
        coverageReport: DeepReadCoverageReport? = null,
        locale: Locale,
    ): String = buildString {
        val chinese = locale.isDeepReadChineseLocale()
        appendLine(if (chinese) "今天日期：${LocalDate.now()}" else "Today: ${LocalDate.now()}")
        appendLine(if (chinese) "话题标题：$topicTitle" else "Topic title: $topicTitle")
        appendLine(
            if (chinese) {
                "目标段落：${stages.joinToString(" → ") { it.deepReadLocalizedLabel(locale) }}"
            } else {
                "Target sections: ${stages.joinToString(" -> ") { it.deepReadLocalizedLabel(locale) }}"
            }
        )
        appendLine()
        appendLine(if (chinese) "## Deep Read Playbook（本地规则，只读）" else "## Deep Read Playbook (local, read-only rules)")
        appendLine(playbookMarkdown.take(PLAYBOOK_PROMPT_LIMIT))
        seedUrl?.takeIf { it.isNotBlank() }?.let { url ->
            appendLine(if (chinese) "用户指定来源 URL：$url" else "User-specified source URL: $url")
            appendLine(
                if (chinese) {
                    "该 URL 已在预抓阶段尝试读取，优先使用预抓正文；如下面预抓正文为空再 search_web/scrape_web 补充。"
                } else {
                    "This URL was attempted during prefetch. Prefer its prefetched text; use search_web/scrape_web only when that text is empty."
                }
            )
        }
        appendLine()
        appendArticlePlan(articlePlan, locale)
        appendLine()
        coverageReport?.let { report ->
            appendLine(if (chinese) "## 本轮补漏目标" else "## Coverage gaps for this pass")
            appendLine(report.promptSummary(locale))
            appendLine(
                if (chinese) {
                    "只补上述缺项，不要重写整篇。已有段落可保留，只更新目标段落中缺失的事实、立场、影响或来源。"
                } else {
                    "Address only those gaps; do not rewrite the whole article. Keep existing sections and update only missing facts, positions, impacts, or sources in the target section."
                }
            )
            appendLine()
        }
        appendEvidenceCards(
            cards = stageEvidence,
            title = if (chinese) {
                "本段证据包（全局共 ${evidencePack.cards.size} 条，本轮只给最相关 ${stageEvidence.size} 条）"
            } else {
                "Evidence pack for this section (${evidencePack.cards.size} total; ${stageEvidence.size} most relevant in this pass)"
            },
            excerptLimit = targetStage.promptExcerptLimit(),
            locale = locale,
        )
        appendLine()
        appendArticleContext(existingOutput, locale)
        appendLine()
        appendDeadlineGuidance(targetStage, stageTimeoutMs, locale)
        appendLine()
        appendLine(if (chinese) "## 研究顺序" else "## Research order")
        if (chinese) {
            appendLine("1. 本地 harness 已经完成来源扩展、去重、分桶和结构规划。你只处理当前小目标。")
            appendLine("2. 仅在以下情况补充 search_web：")
            appendLine("   - 关键事实（发布时间、价格、官方表态、版本号等）在预抓来源中找不到或互相矛盾")
            appendLine("   - 用户指定 URL 的预抓正文为空，需要别的 query 命中该话题")
            appendLine("   - 你需要反面证据时（例如「辟谣」「不实」「未确认」）")
        } else {
            appendLine("1. The local harness has already expanded, deduplicated, bucketed, and planned the sources. Handle only the current target.")
            appendLine("2. Supplement with search_web only in these cases:")
            appendLine("   - Key facts (publication time, price, official statement, version, and so on) are missing or contradictory in the prefetched sources")
            appendLine("   - The prefetched body for the user-specified URL is empty and another query is needed to hit the topic")
            appendLine("   - You need counter-evidence, such as a denial, a false claim, or an unconfirmed claim")
        }
        if (scrapeWebAvailable) {
            appendLine(
                if (chinese) {
                    "3. 仅在确认某个 URL 比预抓正文更详细时再 scrape_web；不要把预抓已有正文重抓一次。"
                } else {
                    "3. Use scrape_web only after confirming that a URL has more detail than its prefetched text; do not scrape the same prefetched body again."
                }
            )
        } else {
            appendLine(
                if (chinese) {
                    "3. 当前未暴露 scrape_web；只能基于预抓正文 + 必要时的 search_web 摘要写入。"
                } else {
                    "3. scrape_web is not exposed in this pass; write only from prefetched text plus search_web snippets when necessary."
                }
            )
        }
        appendLine(
            if (chinese) {
                "4. 本轮只生成目标段落，不要改写或重写未列入目标的段落。"
            } else {
                "4. Generate only the target section in this pass; do not edit or rewrite sections outside the target."
            }
        )
        appendLine(if (chinese) "5. 完成研究后立即调用对应 writer tool：" else "5. After research, immediately call the corresponding writer tool:")
        stages.forEach { stage -> appendLine("   - ${stage.deepReadWriterToolName()}: ${stage.deepReadLocalizedLabel(locale)}") }
        appendLine(
            if (chinese) {
                "6. 本轮只暴露目标段 writer tool；如果你输出自由文本但没调工具，系统会尝试把自由文本转换成基础稿。"
            } else {
                "6. Only the target-section writer tool is exposed in this pass; if you return free text without calling it, the system may convert that text into a basic draft."
            }
        )
        appendLine(
            if (chinese) {
                "7. 图片只能从 image_candidates 中选择；本轮如果没有视觉 writer，就在目标段 references/links 中保留可用来源。"
            } else {
                "7. Select images only from image_candidates; when no visual writer is available, keep usable sources in the target section's references/links."
            }
        )
        appendLine(
            if (chinese) {
                "8. 全部 writer 完成后，直接调用 deep_read_finish。"
            } else {
                "8. After all writers are complete, call deep_read_finish directly."
            }
        )
        appendLine()
        appendLine(if (chinese) "## 段落要求" else "## Section requirements")
        if (DeepReadGenerationStage.OVERVIEW in stages) {
            appendLine(
                if (chinese) {
                    "- 概览：约 120-250 字中文杂志导语，说明事件是什么、为什么值得读、哪些事实已核查；完整句子优先，略超可以接受。"
                } else {
                    "- Overview: a 120-250-word magazine-style lead explaining what happened, why it matters, and which facts are verified; prefer complete sentences."
                }
            )
        }
        if (DeepReadGenerationStage.NARRATIVE in stages) {
            appendLine(
                if (chinese) {
                    "- 时间轴叙事：事件型写 timeline；观点/产品/人物型可写 core_points，但要有故事性和演化脉络。"
                } else {
                    "- Narrative: use timeline for events; opinion/product/person topics may use core_points, but preserve a clear story and evolution."
                }
            )
        }
        if (DeepReadGenerationStage.ANALYSIS in stages) {
            appendLine(
                if (chinese) {
                    "- 深度分析：围绕核心分歧、各方立场、影响分析；这一段需要充分 reasoning，但不要输出 reasoning 给 UI。"
                } else {
                    "- Analysis: cover the core dispute, stakeholder positions, and implications; reason thoroughly but do not expose reasoning to the UI."
                }
            )
        }
        if (DeepReadGenerationStage.EXTENDED_READING in stages) {
            appendLine(if (chinese) "- 扩展阅读：只放真实来源链接和真实图片资产。" else "- Extended reading: include only real source links and real image assets.")
        }
        appendLine(
            if (chinese) {
                "- 视觉：头图必须来自候选池且 confidence=hero；inline 候选只能作为正文图。不得提交任意 URL、站点 logo、favicon、媒体图标或头像。"
            } else {
                "- Visuals: the hero image must come from the candidate pool with confidence=hero; inline candidates are for body images only. Never submit arbitrary URLs, site logos, favicons, media icons, or avatars."
            }
        )
        appendLine(
            if (chinese) {
                "- 图解：只提交 3-6 个短节点的 diagram spec，节点 label 控制在约 30 字内；流程/因果可保留少量关键跨节点关系，但避免网状交叉。禁止 raw SVG/HTML/JS/外链资源。图解不参与段落完成状态，不需要就隐藏。"
            } else {
                "- Diagrams: submit only a 3-6-node diagram spec with labels around 30 characters; flow/causal diagrams may keep a few important cross-node relations, but avoid tangled networks. No raw SVG/HTML/JS or external resources. Diagrams do not determine section completion and may be omitted."
            }
        )
        appendLine()
        appendLine(
            if (chinese) {
                "- 用户可见的 summary、timeline/core_points、analysis、extended_reading、references 和图片说明一律使用中文；URL、专有名词及必要原文保持不变。"
            } else {
                "- Write all user-visible summary, timeline/core_points, analysis, extended_reading, references, and image captions in English; keep URLs, proper nouns, and necessary original names unchanged."
            }
        )
        appendLine(if (chinese) "正文输出不会被 UI 消费。不要输出完整 JSON，不要写 Markdown 长文作为最终答案。" else "The UI does not consume free-form text. Do not return the full JSON or a long Markdown article as the final answer.")
    }

    private fun StringBuilder.appendDeadlineGuidance(
        stage: DeepReadGenerationStage,
        stageTimeoutMs: Long,
        locale: Locale,
    ) {
        val chinese = locale.isDeepReadChineseLocale()
        val stageSeconds = stageTimeoutMs.toWholeSeconds()
        appendLine(if (chinese) "## 时间预算（硬约束）" else "## Time budget (hard constraint)")
        appendLine(if (chinese) "- 本段运行预算约 ${stageSeconds} 秒。" else "- This section has a runtime budget of about ${stageSeconds} seconds.")
        appendLine(
            if (chinese) {
                "- 你必须把第一优先级放在调用 ${stage.deepReadWriterToolName()}；不要先输出长文、完整 JSON 或 Markdown 草稿。"
            } else {
                "- Prioritize calling ${stage.deepReadWriterToolName()} above all else; do not output a long article, complete JSON, or Markdown draft first."
            }
        )
        appendLine(
            if (chinese) {
                "- 预抓证据包是主材料。除非关键事实缺失或互相矛盾，不要连续 search_web / scrape_web。"
            } else {
                "- The prefetched evidence pack is the primary material. Do not chain search_web / scrape_web calls unless key facts are missing or contradictory."
            }
        )
        appendLine(
            if (chinese) {
                "- 如果证据不够完整，先基于现有证据写保守版本；不要为了补全而耗尽本段预算。"
            } else {
                "- If the evidence is incomplete, write a cautious version from what is available; do not exhaust this section's budget trying to fill every gap."
            }
        )
        if (stage == DeepReadGenerationStage.EXTENDED_READING) {
            appendLine(
                if (chinese) {
                    "- 扩展阅读不是长文写作：从本段证据包挑选 4-8 条真实来源链接，必要时带 image_assets，然后立即调用 writer tool。"
                } else {
                    "- Extended reading is not long-form writing: choose 4-8 real source links from this section's evidence pack, add image_assets when needed, then call the writer tool immediately."
                }
            )
        }
    }

    private fun StringBuilder.appendArticlePlan(plan: DeepReadArticlePlan, locale: Locale) {
        appendLine(if (locale.isDeepReadChineseLocale()) "## Article Plan（本地 harness 规划，只读）" else "## Article Plan (local harness plan, read-only)")
        appendLine("- angle: ${plan.overviewAngle}")
        if (plan.narrativeSlots.isNotEmpty()) {
            appendLine("- narrative_slots: ${plan.narrativeSlots.joinToString(" / ")}")
        }
        if (plan.analysisQuestions.isNotEmpty()) {
            appendLine("- analysis_questions:")
            plan.analysisQuestions.forEach { question -> appendLine("  - $question") }
        }
        if (plan.stakeholders.isNotEmpty()) {
            appendLine("- stakeholders: ${plan.stakeholders.joinToString(" / ")}")
        }
        if (plan.riskOrUncertainty.isNotEmpty()) {
            appendLine("- risk_or_uncertainty:")
            plan.riskOrUncertainty.forEach { risk -> appendLine("  - $risk") }
        }
        appendLine("- required_source_ids: ${plan.requiredSourceIds.joinToString(", ")}")
    }

    private fun StringBuilder.appendEvidenceCards(
        cards: List<DeepReadEvidenceCard>,
        title: String = "Evidence Pack",
        excerptLimit: Int = PROMPT_SOURCE_EXCERPT_LIMIT,
        locale: Locale,
    ) {
        appendLine("## $title")
        if (cards.isEmpty()) {
            appendLine(
                if (locale.isDeepReadChineseLocale()) {
                    "- 本段没有可用证据（理论上不会到这里）。"
                } else {
                    "- No usable evidence is available for this section (this should not normally occur)."
                }
            )
            return
        }
        cards.forEach { card ->
            val source = card.source
            appendLine("### ${card.sourceId}. ${card.title}")
            if (source.url.isNotBlank()) appendLine("- url: ${source.url}")
            appendLine("- source: ${source.source ?: "-"}")
            appendLine("- tags: ${card.topicTags.joinToString { it.label }}")
            appendLine("- credibility: ${card.credibilityHint}; freshness: ${card.freshnessHint}")
            card.claimSummary.takeIf { it.isNotBlank() }?.let { appendLine("- claim_summary: $it") }
            source.publishedAt?.takeIf { it.isNotBlank() }?.let { appendLine("- published_at: $it") }
            val candidates = source.imageCandidates
                .filter { it.confidence != IMAGE_CONFIDENCE_REJECT }
                .take(4)
            if (candidates.isNotEmpty()) {
                appendLine("- image_candidates:")
                candidates.forEach { candidate ->
                    val risks = candidate.riskFlags.takeIf { it.isNotEmpty() }?.joinToString("|") ?: "-"
                    appendLine(
                        "  - ${candidate.confidence} score=${candidate.score} kind=${candidate.candidateKind} " +
                            "risk=$risks url=${candidate.imageUrl} alt=${candidate.alt.orEmpty().take(80)}"
                    )
                }
            }
            val excerpt = card.evidenceExcerpt.take(excerptLimit).replace("\n", " ").trim()
            if (excerpt.isNotBlank()) appendLine("- evidence_excerpt: $excerpt")
            appendLine()
        }
    }

    fun buildWriterReminder(
        stages: List<DeepReadGenerationStage>,
        pass: Int,
        locale: Locale,
    ): String = buildString {
        if (locale.isDeepReadChineseLocale()) {
            appendLine("Supervisor reminder #${pass + 1}: 上一轮没有任何 deep_read_write_* 写入。")
            appendLine("时间提醒：现在请直接调用 writer tool。")
            appendLine("UI 不会消费你的自由文本。请立刻继续研究缺口，然后调用以下 writer tool 中至少一个：")
            stages.forEach { stage -> appendLine("- ${stage.deepReadWriterToolName()} for ${stage.deepReadLocalizedLabel(locale)}") }
            appendLine("如果来源不足，只把当前段落写为 FAILED 的决定留给系统；不要用自由文本交差。")
        } else {
            appendLine("Supervisor reminder #${pass + 1}: the previous pass made no deep_read_write_* call.")
            appendLine("Time reminder: call the writer tool directly now.")
            appendLine("The UI does not consume free-form text. Continue researching the gap, then call at least one of these writer tools:")
            stages.forEach { stage -> appendLine("- ${stage.deepReadWriterToolName()} for ${stage.deepReadLocalizedLabel(locale)}") }
            appendLine("If evidence is insufficient, let the system mark this section FAILED; do not substitute free-form text.")
        }
    }

    private fun StringBuilder.appendArticleContext(output: DeepReadOutput, locale: Locale) {
        val current = output.withInferredSectionStates()
        appendLine(if (locale.isDeepReadChineseLocale()) "## 当前稿件正文（补段时必须覆盖）" else "## Current article content (must be covered when supplementing a section)")
        appendLine(
            "section_states: " + DeepReadGenerationStage.entries.joinToString(", ") {
                "${it.name.lowercase()}=${current.statusOf(it).name.lowercase()}"
            }
        )
        if (!current.hasAnyReadySection() && current.summary.isBlank()) {
            appendLine(if (locale.isDeepReadChineseLocale()) "- 暂无已写入段落。" else "- No sections have been written yet.")
            return
        }
        if (current.summary.isNotBlank()) {
            appendLine("overview.summary: ${current.summary}")
        }
        if (current.keyEntities.isNotEmpty()) {
            appendLine("overview.key_entities: ${current.keyEntities.take(12).joinToString(" / ")}")
        }
        current.heroImageUrl?.takeIf { it.isNotBlank() }?.let { imageUrl ->
            appendLine("visual.hero: $imageUrl")
            appendLine("visual.hero_confidence: ${current.heroImageConfidence.orEmpty()}")
            current.heroCaption?.takeIf { it.isNotBlank() }?.let { caption ->
                appendLine("visual.hero_caption: $caption")
            }
            current.visualDiagnostics?.heroSelection?.let { selection ->
                appendLine("visual.hero_reason: ${selection.reason.take(320)}")
                appendLine("visual.hero_risks: ${selection.riskFlags.take(8).joinToString(" / ")}")
            }
        }
        current.imageAssets.take(8).takeIf { it.isNotEmpty() }?.let { assets ->
            appendLine("visual.inline_assets:")
            assets.forEach { asset ->
                appendLine(
                    "- ${asset.confidence.orEmpty()} score=${asset.score ?: 0} " +
                        "${asset.url} ${asset.caption.orEmpty()}"
                )
            }
        }
        current.timeline.orEmpty().take(8).takeIf { it.isNotEmpty() }?.let { timeline ->
            appendLine("narrative.timeline:")
            timeline.forEachIndexed { index, event ->
                appendLine("- ${index + 1}. ${event.date}: ${event.event}")
                if (!event.imageUrl.isNullOrBlank() || !event.imageCaption.isNullOrBlank()) {
                    appendLine(
                        "  image: ${event.imageUrl.orEmpty()} " +
                            event.imageCaption.orEmpty()
                    )
                }
            }
        }
        current.corePoints.orEmpty().take(8).takeIf { it.isNotEmpty() }?.let { points ->
            appendLine("narrative.core_points:")
            points.forEachIndexed { index, point ->
                appendLine("- ${index + 1}. ${point.point} ${point.supporting.orEmpty()}")
                if (!point.imageUrl.isNullOrBlank() || !point.imageCaption.isNullOrBlank()) {
                    appendLine(
                        "  image: ${point.imageUrl.orEmpty()} " +
                            point.imageCaption.orEmpty()
                    )
                }
            }
        }
        if (
            !current.analysis.coreDispute.isNullOrBlank() ||
            current.analysis.perspectives.isNotEmpty() ||
            !current.analysis.implications.isNullOrBlank() ||
            current.analysis.quotes.isNotEmpty()
        ) {
            appendLine("analysis:")
            current.analysis.coreDispute?.takeIf { it.isNotBlank() }?.let {
                appendLine("- core_dispute: $it")
            }
            current.analysis.perspectives.take(8).forEach { perspective ->
                appendLine("- perspective(${perspective.holder.orEmpty()}): ${perspective.viewpoint}")
            }
            current.analysis.implications?.takeIf { it.isNotBlank() }?.let {
                appendLine("- implications: $it")
            }
            current.analysis.quotes.take(6).forEach { quote ->
                appendLine("- quote(${quote.attribution.orEmpty()}): ${quote.text}")
            }
        }
        current.diagram?.takeIf { it.nodes.size >= 2 }?.let { diagram ->
            appendLine("diagram:")
            appendLine("- type: ${diagram.type}")
            appendLine("- title: ${diagram.title}")
            diagram.reason?.takeIf { it.isNotBlank() }?.let { appendLine("- reason: $it") }
            appendLine("- nodes:")
            diagram.nodes.take(8).forEach { node ->
                val group = node.group?.takeIf { it.isNotBlank() }?.let { " group=$it" }.orEmpty()
                appendLine(
                    "  - ${node.id}$group: ${node.label} " +
                        node.note.orEmpty()
                )
            }
            if (diagram.edges.isNotEmpty()) {
                appendLine("- edges:")
                diagram.edges.take(12).forEach { edge ->
                    appendLine("  - ${edge.from} -> ${edge.to}: ${edge.label.orEmpty()}")
                }
            }
            diagram.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                appendLine("- caption: $caption")
            }
        }
        current.extendedReading.take(10).takeIf { it.isNotEmpty() }?.let { links ->
            appendLine("extended_reading:")
            links.forEach { link ->
                appendLine("- ${link.title} | ${link.source.orEmpty()} | ${link.url}")
            }
        }
        current.references.take(12).takeIf { it.isNotEmpty() }?.let { links ->
            appendLine("references:")
            links.forEach { link ->
                appendLine("- ${link.title} | ${link.source.orEmpty()} | ${link.url}")
            }
        }
    }

    private const val PROMPT_SOURCE_LIMIT = 12
    private const val PROMPT_SOURCE_EXCERPT_LIMIT = 2_000
    private const val PLAYBOOK_PROMPT_LIMIT = 12_000
    private fun Long.toWholeSeconds(): Long = (this / 1_000L).coerceAtLeast(1L)
}

internal fun Locale.isDeepReadChineseLocale(): Boolean = language.equals("zh", ignoreCase = true)

internal fun DeepReadGenerationStage.deepReadLocalizedLabel(locale: Locale): String = when (this) {
    DeepReadGenerationStage.OVERVIEW -> if (locale.isDeepReadChineseLocale()) "概览" else "Overview"
    DeepReadGenerationStage.NARRATIVE -> if (locale.isDeepReadChineseLocale()) "时间轴叙事" else "Narrative"
    DeepReadGenerationStage.ANALYSIS -> if (locale.isDeepReadChineseLocale()) "深度分析" else "Analysis"
    DeepReadGenerationStage.EXTENDED_READING -> if (locale.isDeepReadChineseLocale()) "扩展阅读" else "Extended reading"
}

internal fun DeepReadGenerationStage.deepReadWriterToolName(): String = when (this) {
    DeepReadGenerationStage.OVERVIEW -> "deep_read_write_overview"
    DeepReadGenerationStage.NARRATIVE -> "deep_read_write_narrative"
    DeepReadGenerationStage.ANALYSIS -> "deep_read_write_analysis"
    DeepReadGenerationStage.EXTENDED_READING -> "deep_read_write_extended_reading"
}

private fun DeepReadGenerationStage.promptExcerptLimit(): Int = when (this) {
    DeepReadGenerationStage.OVERVIEW -> 1_000
    DeepReadGenerationStage.NARRATIVE -> 1_400
    DeepReadGenerationStage.ANALYSIS -> 1_400
    DeepReadGenerationStage.EXTENDED_READING -> 700
}
