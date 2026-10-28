package app.amber.feature.board.hotlist.deepread

import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.OVERVIEW_SUMMARY_STORAGE_MAX_CHARS
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.MAX_DIAGRAM_NODES
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.OVERVIEW_SUMMARY_MIN_CHARS
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.WriterFeedback
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.appendWriterFeedback
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.stringProp
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.stringArrayProp
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.readingLinksProp
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.timelineProp
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.corePointsProp
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.perspectivesProp
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.quotesProp
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.imageAssetsProp
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.diagramNodesProp
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.diagramEdgesProp
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.objectOrEmpty
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.string
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.stringList
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.url
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.objectList
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.readingLinks
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.timeline
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.corePoints
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.perspectives
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.quotes
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.imageAssets
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.diagram
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.diagramDropReason
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.cleanText
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.safeTake
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.mergeStrings
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.mergeReadingLinks
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.mergeImageAssets
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.toReadingLinks
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.selectionReason
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.hasOverviewContent
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.hasNarrativeContent
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.hasAnalysisContent
import app.amber.feature.board.hotlist.deepread.DeepReadWriterContent.hasExtendedReadingContent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.amber.ai.core.InputSchema
import app.amber.ai.core.Tool
import app.amber.ai.ui.UIMessagePart
import app.amber.feature.board.hotlist.HotListRepository
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class DeepReadSectionWriterTools(
    private val repository: HotListRepository,
    private val topicId: String,
    private val topicTitle: String,
    private val imageCandidates: List<DeepReadImageCandidate> = emptyList(),
    private val allowTitleFallback: Boolean = true,
    private val ttlDays: Int = HotListRepository.DEFAULT_TTL_DAYS,
) {
    private val _writeCount = AtomicInteger(0)
    private val _requiredWriteCount = AtomicInteger(0)
    private val writeMutex = Mutex()
    val writeCount: Int get() = _writeCount.get()
    val requiredWriteCount: Int get() = _requiredWriteCount.get()

    fun tools(
        stages: Set<DeepReadGenerationStage>? = null,
        locale: Locale = Locale.CHINESE,
    ): List<Tool> = buildList {
        if (stages == null || DeepReadGenerationStage.OVERVIEW in stages) add(overviewTool(locale))
        if (stages == null || DeepReadGenerationStage.NARRATIVE in stages) add(narrativeTool(locale))
        if (stages == null || DeepReadGenerationStage.ANALYSIS in stages) add(analysisTool(locale))
        if (stages == null || DeepReadGenerationStage.EXTENDED_READING in stages) add(extendedReadingTool(locale))
        add(visualsTool(locale))
        add(diagramTool(locale))
        add(finishTool())
    }

    suspend fun markPhase(phase: DeepReadGenerationPhase): DeepReadOutput =
        update { current ->
            current.copy(generationPhase = phase)
        }

    suspend fun markRunning(stages: Collection<DeepReadGenerationStage>): DeepReadOutput =
        update { current ->
            stages.fold(
                current.copy(
                    generationPhase = DeepReadGenerationPhase.WRITING,
                    verificationState = DeepReadSectionState(),
                )
            ) { output, stage ->
                if (output.statusOf(stage) == DeepReadSectionStatus.READY) {
                    output
                } else {
                    output.withSectionStatus(stage, DeepReadSectionStatus.RUNNING)
                }
            }
        }

    suspend fun markFailed(stage: DeepReadGenerationStage, message: String): DeepReadOutput =
        update { current ->
            if (current.statusOf(stage) == DeepReadSectionStatus.READY) {
                current
            } else {
                current.withSectionStatus(stage, DeepReadSectionStatus.FAILED, message.safeTake(220))
            }
        }

    /**
     * Links-only fallback: merges real source links into references / extended_reading but
     * does NOT synthesize placeholder body text and does NOT mark the section READY.
     *
     * Previously this produced占位正文 ("围绕xxx，当前来源已提供可继续阅读的基础事实…") and
     * stamped READY+BASIC, which disguised a failed section as a thin success — directly
     * contradicting the playbook's ban on占位话术. Body fallback is abolished (spec A1):
     * failed sections now surface as FAILED. Real source links are still worth keeping
     * (they are genuine, not伪装), so we preserve them here. The caller (RunManager)
     * then calls markFailed(stage, ...) — this function never changes section status.
     */
    suspend fun writeFallbackSection(
        stage: DeepReadGenerationStage,
        assistantText: String,
        sources: List<DeepReadSource>,
        allowReadyRewrite: Boolean = false,
    ): DeepReadOutput =
        update { current ->
            if (!allowReadyRewrite && current.statusOf(stage) == DeepReadSectionStatus.READY) return@update current
            val links = sources.toReadingLinks()
            // Merge real source links for every stage — they are genuine, and the failing
            // section should not discard them. EXTENDED_READING additionally feeds extendedReading.
            val withExtended = if (stage == DeepReadGenerationStage.EXTENDED_READING) {
                mergeReadingLinks(current.extendedReading, links, limit = 10)
            } else {
                current.extendedReading
            }
            current.copy(
                extendedReading = withExtended,
                references = mergeReadingLinks(current.references, links, limit = 12),
            )
        }

    suspend fun currentOutput(): DeepReadOutput =
        repository.getFreshDeepRead(
            topicId = topicId,
            title = topicTitle.takeIf { allowTitleFallback },
        )
            ?.withInferredSectionStates()
            ?: DeepReadOutput()

    private fun overviewTool(locale: Locale) = Tool(
        name = "deep_read_write_overview",
        description = "Internal Deep Read writer. Write the source-backed overview section after using search_web/scrape_web. UI only renders content written through this tool.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("topic_type", stringProp("event/opinion/product/person."))
                    put("summary", stringProp(if (locale.isChineseLocale()) {
                        "Chinese editorial overview, around 120-250 Chinese characters; keep sentences complete."
                    } else {
                        "English editorial overview, around 120-250 characters; keep sentences complete."
                    }))
                    put("key_entities", stringArrayProp("Key people, companies, products, places, or institutions."))
                    put("references", readingLinksProp("Sources used in this section.", locale))
                },
                required = listOf("summary"),
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val summary = obj.string("summary")?.cleanText(OVERVIEW_SUMMARY_STORAGE_MAX_CHARS)
            val output = update { current ->
                val references = obj.readingLinks("references")
                val next = current.copy(
                    topicType = obj.string("topic_type")?.safeTake(32) ?: current.topicType,
                    summary = summary ?: current.summary,
                    keyEntities = mergeStrings(current.keyEntities, obj.stringList("key_entities"), limit = 12),
                    references = mergeReadingLinks(current.references, references, limit = 12),
                )
                if (!next.hasOverviewContent()) return@update current
                markRequiredWrite()
                next
                    .withSectionStatus(DeepReadGenerationStage.OVERVIEW, DeepReadSectionStatus.READY)
                    .withSectionQuality(DeepReadGenerationStage.OVERVIEW, DeepReadSectionQuality.STANDARD)
            }
            if (output.statusOf(DeepReadGenerationStage.OVERVIEW) == DeepReadSectionStatus.READY) {
                ok(
                    section = "overview",
                    output = output,
                    feedback = WriterFeedback(
                        accepted = mapOf(
                            "summary_chars" to output.summary.trim().length,
                            "key_entities" to output.keyEntities.size,
                            "references" to output.references.size,
                        )
                    ),
                )
            } else {
                val summaryChars = summary.orEmpty().trim().length
                val required = if (summary.isNullOrBlank()) {
                    "summary missing"
                } else {
                    "summary too short: $summaryChars/$OVERVIEW_SUMMARY_MIN_CHARS"
                }
                missing(
                    section = "overview",
                    required = required,
                    feedback = WriterFeedback(
                        dropped = mapOf("summary" to 1),
                        dropReasons = listOf(required),
                    ),
                )
            }
        },
    )

    private fun narrativeTool(locale: Locale) = Tool(
        name = "deep_read_write_narrative",
        description = "Internal Deep Read writer. Write timeline/story narrative data. Use timeline for events, core_points for opinion/product/person topics.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("timeline", timelineProp(locale))
                    put("core_points", corePointsProp(locale))
                    put("references", readingLinksProp("Sources used in this section.", locale))
                },
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val timeline = obj.timeline()
            val corePoints = obj.corePoints()
            val references = obj.readingLinks("references")
            val rawTimelineCount = obj.objectList("timeline").size
            val rawCorePointCount = obj.objectList("core_points").size
            val output = update { current ->
                val next = current.copy(
                    timeline = timeline.takeIf { it.isNotEmpty() } ?: current.timeline,
                    corePoints = corePoints.takeIf { it.isNotEmpty() } ?: current.corePoints,
                    references = mergeReadingLinks(current.references, references, limit = 12),
                )
                if (!next.hasNarrativeContent()) return@update current
                markRequiredWrite()
                next
                    .withSectionStatus(DeepReadGenerationStage.NARRATIVE, DeepReadSectionStatus.READY)
                    .withSectionQuality(DeepReadGenerationStage.NARRATIVE, DeepReadSectionQuality.STANDARD)
            }
            val feedback = WriterFeedback(
                accepted = mapOf(
                    "timeline" to timeline.size,
                    "core_points" to corePoints.size,
                    "references" to references.size,
                ),
                dropped = mapOf(
                    "timeline" to (rawTimelineCount - timeline.size).coerceAtLeast(0),
                    "core_points" to (rawCorePointCount - corePoints.size).coerceAtLeast(0),
                ).filterValues { it > 0 },
                dropReasons = buildList {
                    if (rawTimelineCount > timeline.size) add("timeline dropped: missing event or truncated limit=8")
                    if (rawCorePointCount > corePoints.size) add("core_points dropped: missing point or truncated limit=8")
                },
            )
            if (output.statusOf(DeepReadGenerationStage.NARRATIVE) == DeepReadSectionStatus.READY) {
                ok("narrative", output, feedback)
            } else {
                missing("narrative", "timeline or core_points", feedback)
            }
        },
    )

    private fun analysisTool(locale: Locale) = Tool(
        name = "deep_read_write_analysis",
        description = "Internal Deep Read writer. Write the deep analysis section after reasoning over source-backed evidence.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("core_dispute", stringProp("Central tension or core dispute."))
                    put("perspectives", perspectivesProp(locale))
                    put("implications", stringProp(if (locale.isChineseLocale()) "Impact analysis in Chinese." else "Impact analysis in English."))
                    put("quotes", quotesProp())
                    put("references", readingLinksProp("Sources used in this section.", locale))
                },
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val output = update { current ->
                val next = current.copy(
                    analysis = DeepAnalysis(
                        coreDispute = obj.string("core_dispute")?.cleanText(1_000) ?: current.analysis.coreDispute,
                        perspectives = obj.perspectives().takeIf { it.isNotEmpty() } ?: current.analysis.perspectives,
                        implications = obj.string("implications")?.cleanText(1_600) ?: current.analysis.implications,
                        quotes = obj.quotes().takeIf { it.isNotEmpty() } ?: current.analysis.quotes,
                    ),
                    references = mergeReadingLinks(current.references, obj.readingLinks("references"), limit = 12),
                )
                if (!next.hasAnalysisContent()) return@update current
                markRequiredWrite()
                next
                    .withSectionStatus(DeepReadGenerationStage.ANALYSIS, DeepReadSectionStatus.READY)
                    .withSectionQuality(DeepReadGenerationStage.ANALYSIS, DeepReadSectionQuality.STANDARD)
            }
            if (output.statusOf(DeepReadGenerationStage.ANALYSIS) == DeepReadSectionStatus.READY) {
                ok("analysis", output)
            } else {
                missing("analysis", "core_dispute, perspectives, implications, or quotes")
            }
        },
    )

    private fun extendedReadingTool(locale: Locale) = Tool(
        name = "deep_read_write_extended_reading",
        description = "Internal Deep Read writer. Write source-backed extended reading links and optional real image assets.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("links", readingLinksProp("Recommended source links.", locale))
                    put("image_assets", imageAssetsProp(locale))
                },
                required = listOf("links"),
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val output = update { current ->
                val links = obj.readingLinks("links")
                    .ifEmpty { obj.readingLinks("extended_reading") }
                val next = current.copy(
                    extendedReading = mergeReadingLinks(current.extendedReading, links, limit = 10),
                    references = mergeReadingLinks(current.references, links, limit = 12),
                    imageAssets = mergeImageAssets(
                        current.imageAssets,
                        obj.imageAssets().mapNotNull { it.withCandidateEvidence() },
                        limit = 8,
                    ),
                )
                if (!next.hasExtendedReadingContent()) return@update current
                markRequiredWrite()
                next
                    .withSectionStatus(DeepReadGenerationStage.EXTENDED_READING, DeepReadSectionStatus.READY)
                    .withSectionQuality(DeepReadGenerationStage.EXTENDED_READING, DeepReadSectionQuality.STANDARD)
            }
            if (output.statusOf(DeepReadGenerationStage.EXTENDED_READING) == DeepReadSectionStatus.READY) {
                ok("extended_reading", output)
            } else {
                missing("extended_reading", "links")
            }
        },
    )

    private fun visualsTool(locale: Locale) = Tool(
        name = "deep_read_write_visuals",
        description = "Internal Deep Read visual selector. Select hero/inline images only from the pre-fetched candidate pool; never submit arbitrary URLs.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("hero_image_url", stringProp("Optional candidate image URL. Must have hero confidence in the candidate pool."))
                    put("hero_caption", stringProp(if (locale.isChineseLocale()) "Chinese caption for selected hero image." else "English caption for selected hero image."))
                    put("hero_reason", stringProp("Why this candidate matches the title and is not a logo/icon."))
                    put("image_assets", imageAssetsProp(locale))
                },
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val output = update { current ->
                val heroUrl = obj.url("hero_image_url")?.takeIf { isHeroCandidate(it) }
                val incomingAssets = obj.imageAssets()
                    .mapNotNull { asset -> asset.withCandidateEvidence() }
                val heroCandidate = heroUrl?.let { candidateForUrl(it) }
                val heroReason = obj.string("hero_reason")?.cleanText(240)
                    ?: heroCandidate?.selectionReason(topicTitle)
                val heroAsset = heroCandidate?.toImageAsset(
                    caption = obj.string("hero_caption")?.cleanText(180),
                    reason = heroReason,
                )
                val next = current.copy(
                    heroImageUrl = heroUrl ?: current.heroImageUrl,
                    heroCaption = obj.string("hero_caption")?.cleanText(180)?.takeIf { heroUrl != null } ?: current.heroCaption,
                    heroImageConfidence = if (heroUrl != null) IMAGE_CONFIDENCE_HERO else current.heroImageConfidence,
                    imageAssets = mergeImageAssets(current.imageAssets, listOfNotNull(heroAsset) + incomingAssets, limit = 8),
                    visualDiagnostics = buildVisualDiagnostics(
                        previous = current.visualDiagnostics,
                        heroCandidate = heroCandidate,
                        heroReason = heroReason,
                        inlineAssets = incomingAssets,
                    ),
                )
                if (heroUrl == null && incomingAssets.isEmpty()) return@update current
                markVisibleWrite()
                next
            }
            ok("visuals", output)
        },
    )

    private fun diagramTool(locale: Locale) = Tool(
        name = "deep_read_write_diagram",
        description = "Internal Deep Read diagram writer. Submit only a compact structured diagram spec; raw SVG/HTML/JS/external resources are forbidden. Use 3-6 short nodes; flow/causal diagrams may keep a few key cross-node relations but must avoid dense webs.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("type", stringProp("causal_chain/process_flow/stakeholder_map/system_structure/comparison_matrix."))
                    put("title", stringProp(if (locale.isChineseLocale()) "Short Chinese title." else "Short English title."))
                    put("reason", stringProp("Why this topic benefits from a diagram."))
                    put("nodes", diagramNodesProp(locale))
                    put("edges", diagramEdgesProp(locale))
                    put("caption", stringProp(if (locale.isChineseLocale()) "Optional Chinese caption." else "Optional English caption."))
                },
                required = listOf("type", "title", "nodes"),
            )
        },
        allowsAutoApproval = true,
        execute = { input ->
            val obj = input.objectOrEmpty()
            val diagram = obj.diagram()
            val rawNodeCount = obj.objectList("nodes").size
            val rawEdgeCount = obj.objectList("edges").size
            val output = update { current ->
                if (diagram == null) return@update current
                markVisibleWrite()
                current.copy(diagram = diagram)
            }
            val feedback = WriterFeedback(
                accepted = mapOf(
                    "nodes" to diagram?.nodes.orEmpty().size,
                    "edges" to diagram?.edges.orEmpty().size,
                ),
                dropped = mapOf(
                    "nodes" to (rawNodeCount - diagram?.nodes.orEmpty().size).coerceAtLeast(0),
                    "edges" to (rawEdgeCount - diagram?.edges.orEmpty().size).coerceAtLeast(0),
                ).filterValues { it > 0 },
                dropReasons = buildList {
                    if (diagram == null) add(obj.diagramDropReason())
                    if (rawNodeCount > diagram?.nodes.orEmpty().size) {
                        add("nodes dropped: missing id/label, duplicate id, or truncated limit=$MAX_DIAGRAM_NODES")
                    }
                    if (rawEdgeCount > diagram?.edges.orEmpty().size) {
                        add("edges dropped: unknown node, self edge, duplicate edge, or truncated limit")
                    }
                }.filter { it.isNotBlank() },
            )
            if (diagram != null) {
                ok("diagram", output, feedback)
            } else {
                missing("diagram", obj.diagramDropReason(), feedback)
            }
        },
    )

    private fun finishTool() = Tool(
        name = "deep_read_finish",
        description = "Internal Deep Read writer. Call after every section writer reports ready. Returns missing sections if any remain.",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}) },
        allowsAutoApproval = true,
        execute = {
            val output = update { current ->
                current.copy(
                    generationPhase = if (current.sectionsReady()) {
                        DeepReadGenerationPhase.COMPLETE
                    } else {
                        current.generationPhase
                    },
                    generationComplete = current.sectionsReady(),
                )
            }
            val missing = DeepReadGenerationStage.entries.filter { output.statusOf(it) != DeepReadSectionStatus.READY }
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("status", if (missing.isEmpty()) "complete" else "missing_sections")
                        put("missing", buildJsonArray { missing.forEach { add(JsonPrimitive(it.name.lowercase())) } })
                    }.toString()
                )
            )
        },
    )

    private fun isHeroCandidate(url: String): Boolean =
        candidateForUrl(url)?.confidence == IMAGE_CONFIDENCE_HERO

    private fun markRequiredWrite() {
        _writeCount.incrementAndGet()
        _requiredWriteCount.incrementAndGet()
    }

    private fun markVisibleWrite() {
        _writeCount.incrementAndGet()
    }

    private fun candidateForUrl(url: String): DeepReadImageCandidate? =
        imageCandidates.firstOrNull { it.imageUrl == url }

    private fun DeepReadImageAsset.withCandidateEvidence(): DeepReadImageAsset? {
        val candidate = candidateForUrl(url) ?: return null
        if (candidate.confidence == IMAGE_CONFIDENCE_REJECT) return null
        return copy(
            source = source ?: candidate.sourceService,
            confidence = candidate.confidence,
            score = candidate.score,
            qualityHint = qualityHint ?: candidate.confidence,
            selectionReason = selectionReason ?: candidate.selectionReason(topicTitle),
        )
    }

    private fun DeepReadImageCandidate.toImageAsset(
        caption: String? = null,
        reason: String? = null,
    ): DeepReadImageAsset =
        DeepReadImageAsset(
            url = imageUrl,
            caption = caption,
            source = sourceService,
            qualityHint = confidence,
            confidence = confidence,
            score = score,
            selectionReason = reason ?: selectionReason(topicTitle),
        )

    private fun buildVisualDiagnostics(
        previous: DeepReadVisualDiagnostics?,
        heroCandidate: DeepReadImageCandidate?,
        heroReason: String?,
        inlineAssets: List<DeepReadImageAsset>,
    ): DeepReadVisualDiagnostics =
        DeepReadVisualDiagnostics(
            candidateCount = imageCandidates.size.coerceAtLeast(previous?.candidateCount ?: 0),
            heroSelection = heroCandidate?.let {
                DeepReadImageSelection(
                    imageUrl = it.imageUrl,
                    confidence = it.confidence,
                    score = it.score,
                    reason = heroReason ?: it.selectionReason(topicTitle),
                    riskFlags = it.riskFlags,
                )
            } ?: previous?.heroSelection,
            inlineSelections = (previous?.inlineSelections.orEmpty() + inlineAssets.mapNotNull { asset ->
                candidateForUrl(asset.url)?.let { candidate ->
                    DeepReadImageSelection(
                        imageUrl = candidate.imageUrl,
                        confidence = candidate.confidence,
                        score = candidate.score,
                        reason = asset.selectionReason ?: candidate.selectionReason(topicTitle),
                        riskFlags = candidate.riskFlags,
                    )
                }
            }).distinctBy { it.imageUrl }.take(6),
            rejectedImages = (previous?.rejectedImages.orEmpty() + imageCandidates
                .filter { it.confidence == IMAGE_CONFIDENCE_REJECT }
                .sortedByDescending { it.score }
                .take(6)
                .map {
                    DeepReadImageSelection(
                        imageUrl = it.imageUrl,
                        confidence = it.confidence,
                        score = it.score,
                        reason = it.selectionReason(topicTitle),
                        riskFlags = it.riskFlags,
                    )
                }).distinctBy { it.imageUrl }.take(6),
        )

    private suspend fun update(transform: (DeepReadOutput) -> DeepReadOutput): DeepReadOutput = writeMutex.withLock {
        val current = currentOutput()
        val next = transform(current)
        repository.saveDeepRead(topicId, topicTitle, next, ttlDays = ttlDays)
        next
    }

    private fun ok(
        section: String,
        output: DeepReadOutput,
        feedback: WriterFeedback = WriterFeedback(),
    ): List<UIMessagePart> =
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", "ok")
                    put("section", section)
                    put("generation_complete", output.isComplete())
                    appendWriterFeedback(feedback)
                }.toString()
            )
        )

    private fun missing(
        section: String,
        required: String,
        feedback: WriterFeedback = WriterFeedback(),
    ): List<UIMessagePart> =
        listOf(
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", "missing_required_content")
                    put("section", section)
                    put("required", required)
                    appendWriterFeedback(feedback)
                }.toString()
            )
        )
}

private fun Locale.isChineseLocale(): Boolean = language.equals("zh", ignoreCase = true)
