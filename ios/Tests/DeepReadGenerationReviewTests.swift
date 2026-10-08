import XCTest
@testable import AmberDeepRead

@MainActor
final class DeepReadGenerationReviewTests: XCTestCase {
    private let overview = #"{"summary":"这是已经核实来源的完整概览，说明事件背景与当前进展，并保留必要的事实边界。"}"#
    private let narrative = #"{"timeline":[{"event":"官方公布了新的进展。"}]}"#
    private let analysis = #"{"analysis":{"core_dispute":"开放条件是什么？"}}"#
    private let blankNarrative = #"{"timeline":[{}],"core_points":[{"point":"  \n"}]}"#
    private let blankAnalysis = #"{"analysis":{"core_dispute":"  ","perspectives":[{"viewpoint":"\n"}],"implications":"\t","quotes":[{"text":" "}]}}"#

    func testPartialRetryKeepsOldCitationsWhenAnEarlierHotTopicBecomesUsable() async throws {
        let hotTopic = IOSDeepReadSource(
            kind: .hotTopic, title: "恢复的热榜报道", content: "榜单标题与排名",
            url: "https://example.org/a", metadata: ["scrape_status": "scrape_failed_keep_content"]
        )
        let second = IOSDeepReadSource(
            kind: .searchResult, title: "报道乙", content: "已核实的乙报道正文",
            url: "https://example.org/b", metadata: ["scrape_status": "ok"]
        )
        let third = IOSDeepReadSource(
            kind: .searchResult, title: "报道丙", content: "已核实的丙报道正文",
            url: "https://example.org/c", metadata: ["scrape_status": "ok"]
        )
        XCTAssertFalse(hotTopic.hasUsableGenerationContent)
        var scrapeCalls = 0
        let enriched = await DeepReadSourceCollector.enrich([hotTopic, second, third], settings: nil, scrape: { input, _ in
            scrapeCalls += 1
            let request = try JSONSerialization.jsonObject(with: Data(input.utf8)) as? [String: Any]
            XCTAssertEqual(request?["url"] as? String, hotTopic.url)
            return #"{"content":"现在可以读取热榜报道的真实正文。"}"#
        })
        XCTAssertEqual(scrapeCalls, 1, "verified old sources are reused; the hot topic can be fetched again")
        XCTAssertTrue(enriched[0].hasUsableGenerationContent)

        var prior = IOSDeepReadOutput()
        prior.summary = "已完成的摘要。"
        prior.corePoints = [.init(point: "乙报道支持的原有判断", sources: [1])]
        prior.sources = [second, third].map { .init(title: $0.title, url: $0.url ?? "") }
        let provider = DeepReadPipelineTests.StageProvider([
            #"{"required_source_ids":[1,2,3]}"#,
            #"{"analysis":{"perspectives":[{"holder":"新增报道","viewpoint":"新增材料补充了事件的后续影响。","sources":[3]}]}}"#
        ])
        let result = await generate(sources: enriched, provider: provider, initialOutput: prior, targetStages: ["深度分析"])
        let output = try decode(result)

        XCTAssertFalse(result.didFail)
        XCTAssertTrue(result.missingSections.isEmpty)
        XCTAssertEqual(output.sources.map(\.url), [second.url!, third.url!, hotTopic.url!])
        let citation = try XCTUnwrap(output.corePoints.first?.sources.first)
        XCTAssertEqual(output.sources[citation - 1].url, second.url, "the old [1] must still cite the same report")
        XCTAssertEqual(output.analysis.perspectives.first?.sources, [3])
        XCTAssertEqual(output.sources[2].url, hotTopic.url, "the recovered report receives a new source number")
        let prompt = try XCTUnwrap(provider.userPrompts.last)
        XCTAssertTrue(prompt.contains("[1] 搜索结果｜报道乙"))
        XCTAssertTrue(prompt.contains("[3] 热榜主题｜恢复的热榜报道"))
    }

    func testBlankNarrativeObjectsRemainAMissingSection() async throws {
        let provider = DeepReadPipelineTests.StageProvider(["{}", overview, blankNarrative, blankNarrative, analysis])
        let result = await generate(provider: provider)

        XCTAssertFalse(result.didFail, "the real overview and analysis remain a partial article")
        XCTAssertEqual(provider.callCount, 5, "a blank narrative consumes its existing in-stage retry")
        XCTAssertEqual(result.missingSections, ["时间轴叙事"])
        let output = try decode(result)
        XCTAssertTrue(output.timeline.isEmpty)
        XCTAssertTrue(output.corePoints.isEmpty)
        XCTAssertEqual(output.analysis.coreDispute, "开放条件是什么？")
    }

    func testWhitespaceAnalysisRemainsAMissingSection() async throws {
        let provider = DeepReadPipelineTests.StageProvider(["{}", overview, narrative, blankAnalysis, blankAnalysis])
        let result = await generate(provider: provider)

        XCTAssertFalse(result.didFail)
        XCTAssertEqual(provider.callCount, 5)
        XCTAssertEqual(result.missingSections, ["深度分析"])
        XCTAssertFalse(try decode(result).analysis.hasContent)
    }

    func testAllPlaceholderStagesFailInsteadOfCompletingAnEmptyArticle() async {
        let provider = DeepReadPipelineTests.StageProvider([
            "{}", "{}", "{}", blankNarrative, blankNarrative, blankAnalysis, blankAnalysis
        ])
        let result = await generate(provider: provider)

        XCTAssertTrue(result.didFail)
        XCTAssertNil(result.structuredJSON)
        XCTAssertEqual(result.missingSections, ["概览", "时间轴叙事", "深度分析"])
        XCTAssertEqual(provider.callCount, 7)
    }

    func testOneRealNarrativeEventAndOneRealAnalysisQuestionAreAccepted() async throws {
        let provider = DeepReadPipelineTests.StageProvider(["{}", overview, narrative, analysis])
        let result = await generate(provider: provider)

        XCTAssertFalse(result.didFail)
        XCTAssertTrue(result.missingSections.isEmpty, "content validity does not impose the prompt's suggested item counts")
        XCTAssertEqual(provider.callCount, 4)
        let output = try decode(result)
        XCTAssertEqual(output.timeline.map(\.event), ["官方公布了新的进展。"])
        XCTAssertEqual(output.analysis.coreDispute, "开放条件是什么？")
    }

    private func generate(
        sources: [IOSDeepReadSource] = [IOSDeepReadSource(kind: .manualText, title: "材料", content: "来源里的真实事件内容。")],
        provider: DeepReadPipelineTests.StageProvider,
        initialOutput: IOSDeepReadOutput? = nil,
        targetStages: Set<String>? = nil
    ) async -> IOSDeepReadDraftGenerator.GenerationResult {
        let model = Model(modelId: "test-model", displayName: "Test", id: UUID(), type: .chat,
                          customHeaders: [], customBodies: [], inputModalities: [], outputModalities: [],
                          abilities: [], tools: [], contextWindowTokens: nil, providerOverwrite: nil)
        let setting = ProviderSetting.OpenAI(
            id: UUID(), enabled: true, name: "Test", models: [model],
            balanceOption: .init(enabled: false, apiPath: "", resultPath: ""), builtIn: false,
            descriptionText: nil, shortDescriptionText: nil, apiKey: "test-key", baseUrl: "https://example.test",
            chatCompletionsPath: "/chat/completions", useResponseApi: false, authMode: .apiKey, brand: .generic
        )
        let task = IOSDeepReadTask(
            id: "generation-review", title: "阅读主题", status: .running, templateId: IOSDeepReadTemplate.magazine.id,
            sources: sources, resultMarkdown: "", failureMessage: nil, createdAt: 0, updatedAt: 0,
            completedAt: nil, retryCount: 0
        )
        return await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: task, providerSetting: setting, model: model, provider: provider,
            initialOutput: initialOutput, targetStages: targetStages
        )
    }

    private func decode(_ result: IOSDeepReadDraftGenerator.GenerationResult) throws -> IOSDeepReadOutput {
        let json = try XCTUnwrap(result.structuredJSON)
        return try JSONDecoder().decode(IOSDeepReadOutput.self, from: Data(json.utf8))
    }
}
