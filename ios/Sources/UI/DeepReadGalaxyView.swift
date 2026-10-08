import SwiftUI
@preconcurrency import WebKit

/// Full-screen Jupiter view of a debate reading: the dispute is the planet, each camp a moon.
/// This is the only reader page that runs JavaScript, so it is sealed off: it may load only the
/// bundled page and the article it shows, never the network, and never navigate elsewhere.
struct DeepReadGalaxyView: View {
    let article: DeepReadTemplateArticle
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        DeepReadGalaxyWebView(article: article)
            .ignoresSafeArea()
            .background(Color.black)
            .overlay(alignment: .topTrailing) {
                Button("关闭", systemImage: "xmark") { dismiss() }
                    .labelStyle(.iconOnly)
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 44, height: 44)
                    .glassEffect(.regular.interactive(), in: .circle)
                    .padding(.trailing, 14)
                    .padding(.top, 6)
                    .accessibilityIdentifier("deepread.galaxy.close")
            }
            .preferredColorScheme(.dark)
    }
}

private struct DeepReadGalaxyWebView: UIViewRepresentable {
    let article: DeepReadTemplateArticle

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        configuration.websiteDataStore = .nonPersistent()
        configuration.setURLSchemeHandler(DeepReadGalaxySchemeHandler(article: article), forURLScheme: DeepReadGalaxySchemeHandler.scheme)
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        view.isOpaque = false
        view.backgroundColor = .black
        view.scrollView.isScrollEnabled = false
        view.scrollView.contentInsetAdjustmentBehavior = .never
        view.allowsLinkPreview = false
        view.load(URLRequest(url: DeepReadGalaxySchemeHandler.entryURL))
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator() }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void) {
            let allowed = action.request.url.flatMap(DeepReadGalaxySchemeHandler.resourcePath(for:)) == "index.html"
            decisionHandler(allowed ? .allow : .cancel)
        }

        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            webView.load(URLRequest(url: DeepReadGalaxySchemeHandler.entryURL))
        }
    }
}

/// The Jupiter view is a hidden easter egg: roughly one debate reading in five gets the entry card.
/// The draw is a fixed FNV-1a hash of the reading's id, so a lucky reading stays lucky across launches.
enum DeepReadGalaxyEgg {
    static let odds: UInt64 = 5

    static func appears(for readingID: String) -> Bool {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-DeepReadGalaxyAlways") { return true }
        #endif
        var hash: UInt64 = 0xcbf29ce484222325
        for byte in readingID.utf8 { hash = (hash ^ UInt64(byte)) &* 0x100000001b3 }
        return hash % odds == 0
    }
}

final class DeepReadGalaxySchemeHandler: NSObject, WKURLSchemeHandler {
    static let scheme = "deepread-galaxy"
    static let entryURL = URL(string: "deepread-galaxy://app/index.html")!
    /// Link the article page uses for its entry card; the reader intercepts it (no JavaScript there).
    static let entryLink = "https://deepread.amber.local/galaxy"

    private static let files: [String: String] = [
        "index.html": "text/html",
        "main.js": "text/javascript",
        "shaders.js": "text/javascript",
        "vendor/three.module.min.js": "text/javascript",
    ]
    private static let articlePath = "article.js"

    private let articleScript: Data
    private var liveTasks = Set<ObjectIdentifier>()

    init(article: DeepReadTemplateArticle) {
        articleScript = Data(Self.articleScript(for: article).utf8)
    }

    /// The article as a JSON literal inside an ES module: data the page reads, never markup or code.
    static func articleScript(for article: DeepReadTemplateArticle) -> String {
        let json = (try? JSONEncoder().encode(article)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return "export const article = \(json);\n"
    }

    /// Whitelisted path for a request, or nil. Exact matches only, so `..` and other folders are unreachable.
    static func resourcePath(for url: URL) -> String? {
        guard url.scheme == scheme, url.host == "app" else { return nil }
        let path = String(url.path.drop(while: { $0 == "/" }))
        return files[path] != nil || path == articlePath ? path : nil
    }

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        let id = ObjectIdentifier(task)
        liveTasks.insert(id)
        defer { liveTasks.remove(id) }
        guard let url = task.request.url, let path = Self.resourcePath(for: url) else {
            task.didFailWithError(URLError(.fileDoesNotExist))
            return
        }
        let data: Data?
        let mime: String
        if path == Self.articlePath {
            data = articleScript
            mime = "text/javascript"
        } else {
            data = Bundle.main.resourceURL.flatMap { try? Data(contentsOf: $0.appendingPathComponent("Galaxy").appendingPathComponent(path)) }
            mime = Self.files[path] ?? "application/octet-stream"
        }
        guard let data, liveTasks.contains(id) else {
            if liveTasks.contains(id) { task.didFailWithError(URLError(.fileDoesNotExist)) }
            return
        }
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1",
                                       headerFields: ["Content-Type": "\(mime); charset=utf-8", "Content-Length": "\(data.count)"])!
        task.didReceive(response)
        task.didReceive(data)
        task.didFinish()
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {
        liveTasks.remove(ObjectIdentifier(task))
    }
}
