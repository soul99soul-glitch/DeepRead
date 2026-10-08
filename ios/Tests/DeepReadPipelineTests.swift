import XCTest
@testable import AmberDeepRead

/// Exercises the standalone provider bridge through the shared synthesis pipeline.
@MainActor
final class DeepReadPipelineTests: XCTestCase {
    func testProviderJSONDecodesEscapesAndUnicode() throws {
        let cases = [
            (#"{"content":"{\"title\":\"hello\"}"}"#, #"{"title":"hello"}"#),
            (#"{"content":"a\nb\t\r\b\f\\\/"}"#, "a\nb\t\r\u{08}\u{0C}\\/"),
            (#"{"content":"\u4e2d"}"#, "中"),
            (#"{"content":"\uD83D\uDE00"}"#, "😀")
        ]
        for (input, expected) in cases {
            let decoded = try XCTUnwrap(JSONValue.parse(input), input)
            XCTAssertEqual(decoded["content"]?.stringValue, expected, input)
        }
    }

    func testProviderJSONPreservesValueTypesAndRejectsInvalidInput() {
        let value = JSONValue.array([.null, .bool(true), .bool(false), .number(1), .number(0),
                                    .number(-2.5), .string("正文\n\"引用\""), .object(["items": .array([])])])
        XCTAssertEqual(JSONValue.parse(value.jsonString), value)
        for invalid in [#"{"content":"unfinished}"#, "{} trailing", "[1 2]", #"{"a":1 "b":2}"#] {
            XCTAssertNil(JSONValue.parse(invalid), invalid)
        }
    }

    func testAllProviderResponseParsersPreserveStructuredText() throws {
        let expected = #"{"title":"中文😀"}"# + "\n下一行"
        let chat = try XCTUnwrap(JSONValue.parse(
            #"{"choices":[{"message":{"role":"assistant","content":"{\"title\":\"中文\uD83D\uDE00\"}\n下一行"},"finish_reason":"stop"}]}"#
        ))
        let responses = try XCTUnwrap(JSONValue.parse(
            #"{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{\"title\":\"中文\uD83D\uDE00\"}\n下一行"}]}]}"#
        ))
        let claude = try XCTUnwrap(JSONValue.parse(
            #"{"content":[{"type":"text","text":"{\"title\":\"中文\uD83D\uDE00\"}\n下一行"}]}"#
        ))
        let openAI = DeepReadOpenAIProvider()
        XCTAssertEqual(try openAI.parseChatCompletionChunk(chat).choices.first?.message?.toText(), expected)
        XCTAssertEqual(openAI.parseResponseOutput(responses).choices.first?.message?.toText(), expected)
        XCTAssertEqual(DeepReadClaudeProvider().parseMessage(try XCTUnwrap(claude["content"]?.arrayValue)).toText(), expected)
    }

    func testSynthesisRunnerRecognizesOutputLimitReasonsAndKeepsPartialText() async {
        for reason in ["length", "max_tokens", "max_output_tokens", "stop"] {
            let result = await IOSDeepReadSynthesisRunner.run(
                provider: StageProvider(["已有正文"], finishReasons: [1: reason]),
                providerSetting: makeProviderSetting(),
                messages: [.user(prompt: "生成文章")],
                params: TextGenerationParams(model: makeDeepReadModel())
            )
            XCTAssertEqual(result.hitOutputLimit, reason != "stop", reason)
            XCTAssertEqual(result.providerFailureMessage, reason == "stop" ? nil : "模型输出达到长度上限。", reason)
            XCTAssertEqual(result.messages.last?.toText(), "已有正文", reason)
        }
    }

    private func makeDeepReadModel(_ modelId: String = "test-model") -> Model {
        Model(
            modelId: modelId, displayName: modelId, id: UUID(),
            type: .chat, customHeaders: [], customBodies: [], inputModalities: [],
            outputModalities: [], abilities: [], tools: Set<BuiltInTools>(),
            contextWindowTokens: nil, providerOverwrite: nil
        )
    }

    private func makeProviderSetting(model: Model? = nil) -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: UUID(),
            enabled: true,
            name: "deepread-test",
            models: model.map { [$0] } ?? [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "sk-test",
            baseUrl: "https://example.test",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
    }

    /// A plan reply for the planning call (call #1 of every run).
    private let planReply = #"{"overview_angle":"从产品与生态角度解读","narrative_slots":["背景与触发","关键进展","后续观察"],"analysis_questions":["核心矛盾是什么","影响哪些群体"],"stakeholders":["用户","开发者","监管机构"],"risk_or_uncertainty":["未印证的事实需降格表达"],"required_source_ids":[1,2]}"#

    /// A gate-passing (≥24 chars) overview summary.
    private let goodSummaryReply = #"{"topic_type":"product","bottom_line":"AmberAgent 把聊天、工具与深度阅读放进一个应用。","summary":"两个来源共同描述了一个支持聊天、工具与深度阅读的 iOS 应用产品。"}"#

    private func makeTask() -> IOSDeepReadTask {
        makeTask(sources: [
            IOSDeepReadSource(kind: .manualText, title: "Source A", content: "AmberAgent is an iOS app with chat and tools."),
            IOSDeepReadSource(kind: .manualText, title: "Source B", content: "It supports deep reading and subagents.")
        ])
    }

    private func makeTask(sources: [IOSDeepReadSource]) -> IOSDeepReadTask {
        IOSDeepReadTask(
            id: "test-task",
            title: "Test Deep Read",
            status: .running,
            templateId: IOSDeepReadTemplate.analysis.id,
            sources: sources,
            resultMarkdown: "",
            failureMessage: nil,
            createdAt: 1,
            updatedAt: 1,
            completedAt: nil,
            retryCount: 0
        )
    }

    /// A scripted provider that returns one canned reply per call and records
    /// every call so we can assert the stage loop ran 1 plan + 3 stage calls.
    /// `throwAtCalls` makes the given 1-based call indexes throw (transient-
    /// failure simulation for the in-stage retry).
    final class StageProvider: IOSAgentTextProvider, @unchecked Sendable {
        private let replies: [String]
        private let throwAtCalls: Set<Int>
        private let finishReasons: [Int: String]
        private(set) var receivedParams: [TextGenerationParams] = []
        private(set) var callCount = 0
        var onCall: ((Int) throws -> Void)?
        private(set) var userPrompts: [String] = []
        init(_ replies: [String], throwAtCalls: Set<Int> = [], finishReasons: [Int: String] = [:]) {
            self.replies = replies
            self.throwAtCalls = throwAtCalls
            self.finishReasons = finishReasons
        }

        func generateText(
            providerSetting: ProviderSetting,
            messages: [UIMessage],
            params: TextGenerationParams
        ) async throws -> MessageChunk {
            callCount += 1
            try onCall?(callCount)
            receivedParams.append(params)
            if throwAtCalls.contains(callCount) {
                throw NSError(domain: "deepread-test", code: 1, userInfo: [NSLocalizedDescriptionKey: "transient failure"])
            }
            if let user = messages.last(where: { $0.role == MessageRole.user }) {
                userPrompts.append(user.toText())
            }
            let reply = replies[(callCount - 1) % replies.count]
            let message = UIMessage(
                id: UUID(),
                role: MessageRole.assistant,
                parts: [UIMessagePart.Text(text: reply)]
            )
            return MessageChunk(
                id: "chunk-\(callCount)",
                model: "test",
                choices: [UIMessageChoice(index: 0, delta: nil, message: message, finishReason: finishReasons[callCount] ?? "stop")],
                usage: nil
            )
        }
    }


    private let timelineReply = #"{"timeline":[{"date":"今天","event":"事件发生并逐步展开。","is_highlight":true,"why":"首次公开"}],"core_points":[{"point":"能力分层","sources":[2,9]}]}"#
    private let analysisReply = #"{"analysis":{"core_dispute":"是否已到产品化拐点？","perspectives":[{"viewpoint":"还早","holder":"观察者","# +
        #""interest":"看到真实用例","quote":"这只是开始。","quote_by":"观察者","sources":[1]}]},"# +
        #""impacts":[{"target":"开发者","horizon":"short","effect":"需要更多验证"}],"watch":["下一版是否开放插件"],"# +
        #""uncertainties":[{"claim":"上线时间未定","status":"pending_official"},"旧式字符串"]}"#

    func testPipelineRunsPlanAndThreeJSONStagesAndAssemblesStructured() async throws {
        let provider = StageProvider([
            planReply,
            goodSummaryReply,
            timelineReply,
            analysisReply
        ])
        let model = Model(
            modelId: "configured-model", displayName: "Configured", id: UUID(),
            type: .chat, customHeaders: [CustomHeader(name: "X-Model", value: "deep-read")],
            customBodies: [CustomBody(key: "reasoning_effort", value: .string("low"))],
            inputModalities: [.text], outputModalities: [.text], abilities: [.reasoning],
            tools: Set<BuiltInTools>(), contextWindowTokens: 272_000, providerOverwrite: nil
        )
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            model: model,
            provider: provider
        )

        // 1 planning call + 3 synthesis calls (overview/narrative/analysis).
        XCTAssertEqual(provider.callCount, 4)
        XCTAssertFalse(result.didFail)
        XCTAssertTrue(result.missingSections.isEmpty, "no stage should be missing: \(result.missingSections)")

        for params in provider.receivedParams {
            XCTAssertEqual(params.model.id, model.id)
            XCTAssertEqual(params.model.abilities, model.abilities)
            XCTAssertEqual(params.customHeaders, model.customHeaders)
            XCTAssertEqual(params.customBody, model.customBodies)
            XCTAssertNil(params.maxTokens, "Use the provider/model output budget instead of a hard-coded 3500-token cap")
            // GPT-5 / Kimi 等只接受默认温度，固定 0.3 会被 400 拒绝；与聊天一致交给服务商默认。
            XCTAssertNil(params.temperature, "Deep read must not pin a sampling temperature")
        }

        // The merged structured output carries every stage's fields.
        let json = try XCTUnwrap(result.structuredJSON)
        let output = try JSONDecoder().decode(IOSDeepReadOutput.self, from: Data(json.utf8))
        XCTAssertEqual(output.topicType, "product")
        XCTAssertEqual(output.bottomLine, "AmberAgent 把聊天、工具与深度阅读放进一个应用。")
        XCTAssertTrue(output.summary.count >= 24)
        XCTAssertEqual(output.timeline.first?.why, "首次公开")
        XCTAssertEqual(output.corePoints.first?.sources, [2, 9])
        XCTAssertEqual(output.analysis.coreDispute, "是否已到产品化拐点？")
        XCTAssertEqual(output.analysis.perspectives.first?.interest, "看到真实用例")
        XCTAssertEqual(output.analysis.perspectives.first?.quoteBy, "观察者")
        XCTAssertEqual(output.impacts, [IOSDeepReadImpact(target: "开发者", horizon: "short", effect: "需要更多验证")])
        XCTAssertEqual(output.watch, ["下一版是否开放插件"])
        XCTAssertEqual(output.uncertainties, [IOSDeepReadUncertainty(claim: "上线时间未定", status: "pending_official"),
                                              IOSDeepReadUncertainty(claim: "旧式字符串")])
        // The numbered source list is filled locally, in the stage blocks' numbering.
        XCTAssertEqual(output.sources.map(\.title), ["Source A", "Source B"])

        // The serialized markdown (for share / fallback) carries the section headings.
        XCTAssertTrue(result.markdown.contains("**AmberAgent 把聊天、工具与深度阅读放进一个应用。**"))
        XCTAssertTrue(result.markdown.contains("## 摘要"))
        XCTAssertTrue(result.markdown.contains("## 关键判断\n- **能力分层** [2][9]"))
        XCTAssertTrue(result.markdown.contains("## 时间轴"))
        XCTAssertTrue(result.markdown.contains("## 各方立场"))
        XCTAssertTrue(result.markdown.contains("## 影响与走向\n- **开发者**（短期）：需要更多验证"))
        XCTAssertTrue(result.markdown.contains("- 【待官方确认】上线时间未定"))
        XCTAssertTrue(result.markdown.contains("## 来源\n- [1] Source A"))
    }

    func testLaterStagesSeededWithEarlierStructuredJSON() async {
        // Each stage's prompt must carry the merged prior-stage JSON.
        let provider = StageProvider([
            planReply,
            #"{"summary":"这是一个足够长的概览摘要，用来通过门闩并传递给后续段落。"}"#,
            #"{"core_points":[{"point":"叙事要点"}]}"#,
            #"{"analysis":{"core_dispute":"分析分歧"}}"#
        ])
        _ = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            model: makeDeepReadModel("test-model"),
            provider: provider
        )
        XCTAssertEqual(provider.userPrompts.count, 4)
        // Prompt 0 is the planning call; stage 1 is prompt 1.
        XCTAssertTrue(provider.userPrompts[0].contains("结构规划"))
        // Stage 2 prompt references the overview summary (merged JSON).
        XCTAssertTrue(provider.userPrompts[2].contains("足够长的概览摘要"))
        // Stage 3 references the narrative core point.
        XCTAssertTrue(provider.userPrompts[3].contains("叙事要点"))
    }


    func testTruncatedJSONIsRepairedWithoutRetry() async throws {
        // Narrative output is cut mid-array: repair balances it, the stage is
        // accepted, and no retry call is spent.
        let provider = StageProvider([
            planReply,
            goodSummaryReply,
            #"{"timeline":[{"date":"今天","event":"事件发生"}"#,
            analysisReply
        ], finishReasons: [3: "length"])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            model: makeDeepReadModel("test-model"),
            provider: provider
        )
        XCTAssertEqual(provider.callCount, 4)
        XCTAssertFalse(result.didFail)
        XCTAssertTrue(result.missingSections.isEmpty)
        let json = try XCTUnwrap(result.structuredJSON)
        let output = try JSONDecoder().decode(IOSDeepReadOutput.self, from: Data(json.utf8))
        XCTAssertEqual(output.timeline.first?.event, "事件发生")
    }

    func testPersistentlyFailingStageIsReportedNotSilentlyDropped() async {
        // Analysis returns prose on both attempts (calls 4 and 5); the run still
        // completes with the other sections and reports the missing one.
        let provider = StageProvider([
            planReply,
            goodSummaryReply,
            timelineReply,
            "分析部分我想写一段长文……",
            "分析部分我想写一段长文……"
        ])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            model: makeDeepReadModel("test-model"),
            provider: provider
        )
        XCTAssertEqual(provider.callCount, 5) // analysis consumed its retry
        XCTAssertFalse(result.didFail, "partial content is a completed draft")
        XCTAssertEqual(result.missingSections, ["深度分析"])
        XCTAssertTrue(result.markdown.contains("## 时间轴"))
        XCTAssertTrue(result.markdown.contains("## 来源"))
        XCTAssertFalse(result.markdown.contains("## 各方立场"))
    }

    // 全部调用失败且报错是未归类的英文时，失败原因要带上服务商原话，不能只剩「操作失败」。
    func testAllStagesThrowingSurfacesRawProviderError() async {
        final class RejectingProvider: IOSAgentTextProvider, @unchecked Sendable {
            func generateText(
                providerSetting: ProviderSetting,
                messages: [UIMessage],
                params: TextGenerationParams
            ) async throws -> MessageChunk {
                throw NSError(domain: "deepread-test", code: 400, userInfo: [
                    NSLocalizedDescriptionKey: "Unsupported value: 'temperature' does not support 0.3 with this model."
                ])
            }
        }
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            model: makeDeepReadModel("test-model"),
            provider: RejectingProvider()
        )
        XCTAssertTrue(result.didFail)
        XCTAssertTrue(
            result.failureReason.contains("does not support 0.3"),
            "raw provider error must survive: \(result.failureReason)"
        )
        // 与启动方一致再过一次清洗，原话仍须保留。
        XCTAssertTrue(IOSDeepReadUserFacingText.sanitize(result.failureReason).contains("does not support 0.3"))
    }


    private func priorOutput() -> IOSDeepReadOutput {
        var output = IOSDeepReadOutput()
        output.summary = "这是一个已有的概览摘要，长度足够通过任何门闩。"
        output.timeline = [IOSDeepReadTimelineEvent(date: "今天", event: "既有事件")]
        output.extendedReading = [IOSDeepReadLink(title: "既有链接", url: "https://example.com")]
        return output
    }

    func testTargetedRetryRegeneratesOnlyTargetedStage() async throws {
        let provider = StageProvider([
            planReply,
            analysisReply
        ])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            model: makeDeepReadModel("test-model"),
            provider: provider,
            initialOutput: priorOutput(),
            targetStages: ["深度分析"]
        )
        // Plan + the single targeted stage; the other sections are untouched.
        XCTAssertEqual(provider.callCount, 2)
        XCTAssertFalse(result.didFail)
        XCTAssertTrue(result.missingSections.isEmpty)
        let json = try XCTUnwrap(result.structuredJSON)
        let output = try JSONDecoder().decode(IOSDeepReadOutput.self, from: Data(json.utf8))
        XCTAssertEqual(output.timeline.first?.event, "既有事件")
        XCTAssertEqual(output.analysis.coreDispute, "是否已到产品化拐点？")
    }

    func testTargetedRetryFailureKeepsPriorSectionsAndReportsMissing() async {
        let provider = StageProvider([
            planReply,
            "分析部分我想写一段长文……",
            "分析部分我想写一段长文……"
        ])
        let result = await IOSDeepReadDraftGenerator.generateViaLLMResult(
            task: makeTask(),
            providerSetting: makeProviderSetting(),
            model: makeDeepReadModel("test-model"),
            provider: provider,
            initialOutput: priorOutput(),
            targetStages: ["深度分析"]
        )
        XCTAssertEqual(provider.callCount, 3)
        XCTAssertFalse(result.didFail, "prior sections keep the run completed")
        XCTAssertEqual(result.missingSections, ["深度分析"])
        XCTAssertTrue(result.markdown.contains("## 时间轴"))
    }

}
