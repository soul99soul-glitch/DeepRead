import Foundation
import Observation

@Observable @MainActor
final class DeepReadSettingsStore {
    var models: [DeepReadModelConfiguration] = []
    var selectedModelID: UUID?
    var searchServices: [DeepReadSearchConfiguration] = [DeepReadSearchConfiguration()]
    var selectedSearchID: UUID?
    var resultSize = 10
    var searchBuiltinDuckDuckGoEnabled = true
    var searchBuiltinBingEnabled = true
    var searchBuiltinJinaEnabled = true
    var searchBuiltinWikipediaEnabled = true
    var searchBuiltinHackerNewsEnabled = true
    var searchGoogleWebViewFallbackEnabled = true
    var fontScale = 1.0
    var fontMode = "serif"
    var templateId = DeepReadSynthesisTemplate.auto.id
    private(set) var errorMessage: String?
    private(set) var searchSettings: Settings
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let credentials: any DeepReadCredentialStorage
    @ObservationIgnored private var credentialLoadFailed = false
    @ObservationIgnored private var credentialRevision = UUID().uuidString
    static let persistenceKey = "deepread.settings.v1"

    init(defaults: UserDefaults = UserDefaults(suiteName: "app.amber.deepread.settings")!,
         credentials: any DeepReadCredentialStorage = DeepReadKeychainStorage()) {
        self.defaults = defaults
        self.credentials = credentials
        searchSettings = Settings()
        if let data = defaults.data(forKey: Self.persistenceKey) {
            do { apply(try JSONDecoder().decode(Persisted.self, from: data)) }
            catch { errorMessage = "设置读取失败：\(error.localizedDescription)" }
        }
        if selectedSearchID == nil { selectedSearchID = searchServices.first?.id }
        reloadCredentials()
        searchSettings = buildSettings()
    }

    var resolvedModel: (model: Model, provider: ProviderSetting)? {
        guard let selectedModelID else { return nil }
        let provider = searchSettings.providers.first {
            $0.id.uuidString.lowercased() == selectedModelID.uuidString.lowercased()
        }
        guard let provider, provider.enabled, let model = provider.models.first else { return nil }
        if let openAI = provider as? ProviderSetting.OpenAI, openAI.apiKey.isEmpty { return nil }
        if let claude = provider as? ProviderSetting.Claude, claude.apiKey.isEmpty { return nil }
        return (model, provider)
    }

    func reloadCredentials() {
        let retrying = credentialLoadFailed
        credentialLoadFailed = false
        do {
            var loadedModels = models
            var loadedSearch = searchServices
            for index in loadedModels.indices {
                loadedModels[index].apiKey = try credentials.read(account: "\(credentialRevision).model.\(loadedModels[index].id)") ?? ""
            }
            for index in loadedSearch.indices {
                let id = loadedSearch[index].id
                loadedSearch[index].apiKey = try credentials.read(account: "\(credentialRevision).search.\(id)") ?? ""
                loadedSearch[index].password = try credentials.read(account: "\(credentialRevision).search.password.\(id)") ?? ""
            }
            if loadedModels != models { models = loadedModels }
            if loadedSearch != searchServices { searchServices = loadedSearch }
            // A successful retry must also refresh what generation reads, not just hide the error.
            if retrying {
                searchSettings = buildSettings()
                errorMessage = nil
            }
        } catch {
            credentialLoadFailed = true
            errorMessage = "凭据读取失败，请重试读取后再保存：\(error.localizedDescription)"
        }
    }

    @discardableResult func save() -> Bool {
        guard !credentialLoadFailed else { return false }
        let nextRevision = UUID().uuidString
        var stagedAccounts: [String] = []
        do {
            for model in models {
                guard let url = URL(string: model.baseURL), url.scheme == "https" || url.scheme == "http",
                      url.host != nil, !model.modelID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                    throw ConfigurationError.invalidModel(model.name)
                }
            }
            guard (1...50).contains(resultSize), (0.7...1.8).contains(fontScale) else {
                throw ConfigurationError.invalidRange
            }
            let snapshot = buildSettings()
            var saved = Persisted(store: self)
            saved.credentialRevision = nextRevision
            let persisted = try JSONEncoder().encode(saved)
            let previous = defaults.data(forKey: Self.persistenceKey).flatMap {
                try? JSONDecoder().decode(Persisted.self, from: $0)
            }
            // Stage credentials under a new revision. A failed multi-key write must
            // not change the credentials used by the previously saved configuration.
            for model in models {
                let account = "\(nextRevision).model.\(model.id)"
                try credentials.write(model.apiKey, account: account)
                stagedAccounts.append(account)
            }
            for service in searchServices {
                for (name, value) in [("search", service.apiKey), ("search.password", service.password)] {
                    let account = "\(nextRevision).\(name).\(service.id)"
                    try credentials.write(value, account: account)
                    stagedAccounts.append(account)
                }
            }
            defaults.set(persisted, forKey: Self.persistenceKey)
            credentialRevision = nextRevision
            searchSettings = snapshot
            errorMessage = nil
            if let previous {
                for account in previous.credentialAccounts {
                    do { try credentials.write("", account: account) }
                    catch { errorMessage = "设置已保存，但清理旧凭据失败：\(error.localizedDescription)" }
                }
            }
            return true
        } catch {
            errorMessage = "设置未保存：\(error.localizedDescription)"
            for account in stagedAccounts {
                do { try credentials.write("", account: account) }
                catch { errorMessage = "设置未保存，且清理临时凭据失败：\(error.localizedDescription)" }
            }
            return false
        }
    }

    /// 直接构造纯 Swift `Settings`（原实现序列化成 kotlinx JSON 再桥接解码，
    /// 纯 Swift 化后不再需要往返）。字段语义与原 JSON 键一一对应。
    private func buildSettings() -> Settings {
        let providers: [ProviderSetting] = models.map { model in
            let modelConfig = Model(
                modelId: model.modelID,
                displayName: model.modelID,
                id: model.id,
                type: .chat
            )
            if model.protocolType == .openAI {
                return ProviderSetting.OpenAI(
                    id: model.id, enabled: model.enabled, name: model.name,
                    models: [modelConfig], apiKey: model.apiKey,
                    baseUrl: model.baseURL,
                    chatCompletionsPath: model.chatCompletionsPath,
                    useResponseApi: model.useResponsesAPI
                )
            }
            return ProviderSetting.Claude(
                id: model.id, enabled: model.enabled, name: model.name,
                models: [modelConfig], apiKey: model.apiKey,
                baseUrl: model.baseURL, promptCaching: model.promptCaching
            )
        }
        let services = searchServices.map(makeServiceOptions)
        return Settings(
            providers: providers,
            searchServices: services,
            searchCommonOptions: SearchCommonOptions(resultSize: resultSize),
            searchServiceSelected: searchServices.firstIndex { $0.id == selectedSearchID } ?? 0,
            searchEnabledServiceIds: searchServices.filter(\.enabled).map(\.id),
            searchBuiltinDuckDuckGoEnabled: searchBuiltinDuckDuckGoEnabled,
            searchBuiltinBingEnabled: searchBuiltinBingEnabled,
            searchBuiltinJinaEnabled: searchBuiltinJinaEnabled,
            searchBuiltinWikipediaEnabled: searchBuiltinWikipediaEnabled,
            searchBuiltinHackerNewsEnabled: searchBuiltinHackerNewsEnabled,
            searchGoogleWebViewFallbackEnabled: searchGoogleWebViewFallbackEnabled
        )
    }

    /// 设置页按 kind 以 `fields` 字典保存可编辑字段；缺失/空键回落 Kotlin
    /// 反序列化时的默认值（与原 JSON 桥语义一致）。
    private func makeServiceOptions(_ service: DeepReadSearchConfiguration) -> SearchServiceOptions {
        func field(_ key: String, default fallback: String) -> String {
            let value = service.fields[key] ?? ""
            return value.isEmpty ? fallback : value
        }
        switch service.kind {
        case .bingLocal:
            return SearchServiceOptions.BingLocalOptions(id: service.id)
        case .zhipu:
            return SearchServiceOptions.ZhipuOptions(id: service.id, apiKey: service.apiKey)
        case .tavily:
            return SearchServiceOptions.TavilyOptions(id: service.id, apiKey: service.apiKey, depth: field("depth", default: "advanced"))
        case .exa:
            return SearchServiceOptions.ExaOptions(id: service.id, apiKey: service.apiKey)
        case .searxng:
            return SearchServiceOptions.SearXNGOptions(
                id: service.id,
                url: field("url", default: ""),
                engines: field("engines", default: ""),
                language: field("language", default: ""),
                username: field("username", default: ""),
                password: service.password
            )
        case .linkup:
            return SearchServiceOptions.LinkUpOptions(id: service.id, apiKey: service.apiKey, depth: field("depth", default: "standard"))
        case .brave:
            return SearchServiceOptions.BraveOptions(id: service.id, apiKey: service.apiKey)
        case .serper:
            return SearchServiceOptions.SerperOptions(id: service.id, apiKey: service.apiKey)
        case .serpapi:
            return SearchServiceOptions.SerpApiOptions(id: service.id, apiKey: service.apiKey)
        case .metaso:
            return SearchServiceOptions.MetasoOptions(id: service.id, apiKey: service.apiKey)
        case .ollama:
            return SearchServiceOptions.OllamaOptions(id: service.id, apiKey: service.apiKey)
        case .perplexity:
            return SearchServiceOptions.PerplexityOptions(
                id: service.id,
                apiKey: service.apiKey,
                maxTokens: service.fields["maxTokens"].flatMap(Int.init),
                maxTokensPerPage: service.fields["maxTokensPerPage"].flatMap(Int.init)
            )
        case .firecrawl:
            return SearchServiceOptions.FirecrawlOptions(id: service.id, apiKey: service.apiKey)
        case .jina:
            return SearchServiceOptions.JinaOptions(
                id: service.id,
                apiKey: service.apiKey,
                searchUrl: field("searchUrl", default: "https://s.jina.ai/"),
                scrapeUrl: field("scrapeUrl", default: "https://r.jina.ai/")
            )
        case .bocha:
            return SearchServiceOptions.BochaOptions(
                id: service.id,
                apiKey: service.apiKey,
                summary: service.fields["summary"].map { $0 == "true" } ?? true
            )
        case .amberAgent:
            return SearchServiceOptions.AmberAgentSearchOptions(id: service.id, apiKey: service.apiKey, depth: field("depth", default: "standard"))
        case .grok:
            return SearchServiceOptions.GrokOptions(
                id: service.id,
                apiKey: service.apiKey,
                model: field("model", default: "grok-4-1-fast-non-reasoning"),
                customUrl: field("customUrl", default: "https://api.x.ai/v1/responses"),
                systemPrompt: field(
                    "systemPrompt",
                    default: "You are a helpful search assistant. Search the web to find accurate and up-to-date information for the user's query. Provide a comprehensive answer with citations."
                )
            )
        }
    }

    private struct Persisted: Codable {
        var models: [DeepReadModelConfiguration]
        var selectedModelID: UUID?
        var searchServices: [DeepReadSearchConfiguration]
        var selectedSearchID: UUID?
        var resultSize: Int
        var duckDuckGo: Bool, bing: Bool, jina: Bool, wikipedia: Bool, hackerNews: Bool, google: Bool
        var fontScale: Double, fontMode: String, templateId: String
        var credentialRevision: String
        var credentialAccounts: [String] {
            models.map { "\(credentialRevision).model.\($0.id)" } + searchServices.flatMap {
                ["\(credentialRevision).search.\($0.id)", "\(credentialRevision).search.password.\($0.id)"]
            }
        }
        @MainActor init(store: DeepReadSettingsStore) {
            models = store.models; selectedModelID = store.selectedModelID
            searchServices = store.searchServices; selectedSearchID = store.selectedSearchID
            resultSize = store.resultSize
            duckDuckGo = store.searchBuiltinDuckDuckGoEnabled; bing = store.searchBuiltinBingEnabled
            jina = store.searchBuiltinJinaEnabled; wikipedia = store.searchBuiltinWikipediaEnabled
            hackerNews = store.searchBuiltinHackerNewsEnabled; google = store.searchGoogleWebViewFallbackEnabled
            fontScale = store.fontScale; fontMode = store.fontMode; templateId = store.templateId
            credentialRevision = store.credentialRevision
        }
    }

    private func apply(_ saved: Persisted) {
        models = saved.models; selectedModelID = saved.selectedModelID
        searchServices = saved.searchServices; selectedSearchID = saved.selectedSearchID
        resultSize = saved.resultSize
        searchBuiltinDuckDuckGoEnabled = saved.duckDuckGo; searchBuiltinBingEnabled = saved.bing
        searchBuiltinJinaEnabled = saved.jina; searchBuiltinWikipediaEnabled = saved.wikipedia
        searchBuiltinHackerNewsEnabled = saved.hackerNews; searchGoogleWebViewFallbackEnabled = saved.google
        fontScale = saved.fontScale; fontMode = saved.fontMode; templateId = saved.templateId
        credentialRevision = saved.credentialRevision
    }

    private enum ConfigurationError: LocalizedError {
        case invalidModel(String), invalidRange
        var errorDescription: String? {
            switch self {
            case .invalidModel(let name): "请检查 \(name) 的服务地址和模型 ID。"
            case .invalidRange: "搜索结果数量或字号超出允许范围。"
            }
        }
    }
}
