import XCTest
@testable import AmberDeepRead

final class DeepReadProviderReviewTests: XCTestCase {
    func testOSeriesChatRequestsUseCompletionTokenLimit() {
        let provider = ProviderSetting.OpenAI()
        for modelID in [
            "o1", "o1-mini", "o1-preview", "o1-2024-12-17",
            "o3", "o3-mini", "o3-2025-04-16",
            "o4-mini", "o4-mini-2025-04-16", "openai/o3"
        ] {
            let body = DeepReadOpenAIProvider().buildChatCompletionRequest(
                provider,
                messages: [.user(prompt: "生成模板草稿")],
                params: TextGenerationParams(model: Model(modelId: modelID), maxTokens: 2_800)
            )
            XCTAssertEqual(body["max_completion_tokens"], .number(2_800), modelID)
            XCTAssertNil(body["max_tokens"], modelID)
            XCTAssertNil(body["reasoning_effort"], modelID)
        }
    }

    func testOrdinaryCompatibleChatRequestsKeepMaxTokens() {
        for modelID in ["gpt-4o-mini", "deepseek-chat", "Qwen/Qwen3-32B", "ocean-model"] {
            let body = DeepReadOpenAIProvider().buildChatCompletionRequest(
                ProviderSetting.OpenAI(baseUrl: "https://example.test/v1"),
                messages: [.user(prompt: "翻译标题")],
                params: TextGenerationParams(model: Model(modelId: modelID), maxTokens: 4_000)
            )
            XCTAssertEqual(body["max_tokens"], .number(4_000), modelID)
            XCTAssertNil(body["max_completion_tokens"], modelID)
        }
    }

    func testMiMoChatRequestsKeepCompletionTokenLimit() {
        let body = DeepReadOpenAIProvider().buildChatCompletionRequest(
            ProviderSetting.OpenAI(baseUrl: "https://api.xiaomimimo.com/v1"),
            messages: [.user(prompt: "翻译标题")],
            params: TextGenerationParams(model: Model(modelId: "mimo-v2-flash"), maxTokens: 4_000)
        )
        XCTAssertEqual(body["max_completion_tokens"], .number(4_000))
        XCTAssertNil(body["max_tokens"])
    }

    func testCustomBodyStillOverridesTheChosenTokenLimit() {
        for (modelID, field) in [("o3", "max_completion_tokens"), ("gpt-4o-mini", "max_tokens")] {
            let body = DeepReadOpenAIProvider().buildChatCompletionRequest(
                ProviderSetting.OpenAI(),
                messages: [.user(prompt: "生成模板草稿")],
                params: TextGenerationParams(
                    model: Model(modelId: modelID),
                    maxTokens: 2_800,
                    customBody: [CustomBody(key: field, value: .number(3_000))]
                )
            )
            XCTAssertEqual(body[field], .number(3_000), modelID)
            XCTAssertNil(body[field == "max_tokens" ? "max_completion_tokens" : "max_tokens"], modelID)
        }
    }
}
