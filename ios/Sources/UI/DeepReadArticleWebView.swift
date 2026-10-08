import SwiftUI
@preconcurrency import WebKit

struct DeepReadArticleWebView: UIViewRepresentable {
    let html: String
    @Binding var error: String?
    var allowsRemoteImages = false
    var hidesBottomEdgeEffect = false
    var readingPositionID: String?
    /// Called when the debate entry card is tapped.
    var onGalaxy: (() -> Void)?
    /// Called when an image is tapped, with where it sits on screen.
    var onImage: ((DeepReadImageViewing) -> Void)?
    /// Called on every tap, for the rapid-tap easter egg (point in the web view's coordinates).
    var onTap: ((CGPoint) -> Void)?

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = false
        configuration.websiteDataStore = .nonPersistent()
        configuration.setURLSchemeHandler(IOSDeepReadFontSchemeHandler(), forURLScheme: IOSDeepReadFontSchemeHandler.scheme)
        let view = WKWebView(frame: .zero, configuration: configuration)
        view.navigationDelegate = context.coordinator
        view.isOpaque = false
        view.backgroundColor = .clear
        view.scrollView.backgroundColor = .clear
        view.scrollView.topEdgeEffect.style = .soft
        view.scrollView.bottomEdgeEffect.style = .soft
        view.scrollView.bottomEdgeEffect.isHidden = hidesBottomEdgeEffect
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tapped(_:)))
        tap.cancelsTouchesInView = false
        tap.delaysTouchesEnded = false
        tap.delegate = context.coordinator
        view.addGestureRecognizer(tap)
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {
        context.coordinator.onGalaxy = onGalaxy
        context.coordinator.onImage = onImage
        context.coordinator.onTap = onTap
        view.scrollView.bottomEdgeEffect.isHidden = hidesBottomEdgeEffect
        // Status and toolbar updates reuse the article; skip the full security scan for unchanged inputs.
        guard context.coordinator.loadedSourceHTML != html
                || context.coordinator.loadedAllowsRemoteImages != allowsRemoteImages
                || context.coordinator.readingPositionID != readingPositionID else { return }
        let prepared = DeepReadArticleDocument.prepare(IOSDeepReadHTMLSecurity.hardenedDocument(html, allowsRemoteImages: allowsRemoteImages))
        let document = prepared.html
        context.coordinator.images = prepared.images
        let positionChanged = context.coordinator.readingPositionID != readingPositionID
        context.coordinator.rememberPosition(in: view)
        context.coordinator.readingPositionID = readingPositionID
        context.coordinator.loadedSourceHTML = html
        context.coordinator.loadedAllowsRemoteImages = allowsRemoteImages
        guard context.coordinator.loadedHTML != document || positionChanged else { return }
        context.coordinator.loadedHTML = document
        context.coordinator.awaitingDocument = true
        context.coordinator.documentFinished = false
        context.coordinator.cancelPositionRestore()
        view.loadHTMLString(document, baseURL: URL(string: IOSDeepReadFontSchemeHandler.documentBaseURL))
    }

    func makeCoordinator() -> Coordinator { Coordinator(error: $error) }

    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        coordinator.rememberPosition(in: view)
        coordinator.cancelPositionRestore()
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, UIGestureRecognizerDelegate {
        // Session-only offsets survive a pop without keeping WebViews alive or observing every scroll frame.
        private static var readingOffsets: [String: CGPoint] = [:]
        private var positionObservation: NSKeyValueObservation?
        var readingPositionID: String?
        var documentFinished = false
        var loadedHTML: String?
        var loadedSourceHTML: String?
        var loadedAllowsRemoteImages: Bool?
        var awaitingDocument = false
        var onGalaxy: (() -> Void)?
        var onImage: ((DeepReadImageViewing) -> Void)?
        var onTap: ((CGPoint) -> Void)?
        var images: [String] = []
        let error: Binding<String?>
        init(error: Binding<String?>) { self.error = error }

        func rememberPosition(in webView: WKWebView) {
            guard documentFinished, positionObservation == nil, let readingPositionID else { return }
            Self.readingOffsets[readingPositionID] = webView.scrollView.contentOffset
        }

        func cancelPositionRestore() { positionObservation = nil }

        static func isSameDocumentAnchor(_ url: URL, currentURL: URL?) -> Bool {
            guard let currentURL,
                  var target = URLComponents(url: url, resolvingAgainstBaseURL: true), target.fragment != nil,
                  var current = URLComponents(url: currentURL, resolvingAgainstBaseURL: true) else { return false }
            target.fragment = nil
            current.fragment = nil
            return target.url == current.url && current.url == URL(string: IOSDeepReadFontSchemeHandler.documentBaseURL)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            documentFinished = true
            Self.disableDoubleTapZoom(in: webView)
            if let readingPositionID, let offset = Self.readingOffsets[readingPositionID] {
                // didFinish can precede the first content layout. Observe only until that layout arrives.
                positionObservation = webView.scrollView.observe(\.contentSize, options: [.initial, .new]) { [weak self, weak webView] _, _ in
                    Task { @MainActor in
                        guard let self, let webView, self.positionObservation != nil,
                              self.readingPositionID == readingPositionID,
                              webView.scrollView.contentSize.height > 0 else { return }
                        let scroll = webView.scrollView
                        let top = -scroll.adjustedContentInset.top
                        let bottom = max(top, scroll.contentSize.height - scroll.bounds.height + scroll.adjustedContentInset.bottom)
                        self.cancelPositionRestore()
                        scroll.setContentOffset(CGPoint(x: offset.x, y: min(max(offset.y, top), bottom)), animated: false)
                    }
                }
            }
        }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url else { decisionHandler(.cancel); return }
            if awaitingDocument && action.navigationType == .other && action.targetFrame?.isMainFrame == true
                && (url.absoluteString == IOSDeepReadFontSchemeHandler.documentBaseURL || url.absoluteString == "about:blank") {
                awaitingDocument = false
                decisionHandler(.allow)
                return
            }
            if action.navigationType == .linkActivated && Self.isSameDocumentAnchor(url, currentURL: webView.url) {
                decisionHandler(.allow)
                return
            }
            decisionHandler(.cancel)
            if action.navigationType == .linkActivated, let index = DeepReadArticleDocument.imageIndex(for: url), images.indices.contains(index) {
                let source = images[index]
                Task { @MainActor in self.onImage?(await Self.viewing(source: source, link: url.absoluteString, in: webView)) }
                return
            }
            if action.navigationType == .linkActivated && url.absoluteString == DeepReadGalaxySchemeHandler.entryLink {
                onGalaxy?()
                return
            }
            if action.navigationType == .linkActivated && ["https", "http"].contains(url.scheme?.lowercased() ?? "") {
                UIApplication.shared.open(url)
            }
        }

        @objc func tapped(_ gesture: UITapGestureRecognizer) {
            guard let view = gesture.view else { return }
            if let webView = view as? WKWebView { Self.disableDoubleTapZoom(in: webView) }
            onTap?(gesture.location(in: view))
        }

        /// With zoom locked, WebKit's smart-magnify double-tap still scrolls the tapped block to the
        /// middle. Its recognizers are plain double-tap ones; text selection uses UITextTapRecognizer.
        /// WebKit may re-enable them on viewport updates, so this also runs on every tap.
        private static func disableDoubleTapZoom(in webView: WKWebView) {
            for view in webView.scrollView.subviews {
                for case let tap as UITapGestureRecognizer in view.gestureRecognizers ?? []
                where type(of: tap) == UITapGestureRecognizer.self && tap.numberOfTapsRequired == 2 && tap.numberOfTouchesRequired == 1 {
                    tap.isEnabled = false
                }
            }
        }

        /// Where the tapped image sits on screen, plus a snapshot to fly while the full image loads.
        /// Page scripts are off; scripts the app evaluates in its own world still run.
        private static func viewing(source: String, link: String, in webView: WKWebView) async -> DeepReadImageViewing {
            var viewing = DeepReadImageViewing(source: source)
            let script = """
            const a = [...document.querySelectorAll('a.dr-zoom')].find(a => a.getAttribute('href') === link);
            const img = a && a.querySelector('img');
            if (!img) return null;
            const r = img.getBoundingClientRect();
            return [r.left + scrollX, r.top + scrollY, r.width, r.height, img.naturalWidth, img.naturalHeight];
            """
            guard let values = try? await webView.callAsyncJavaScript(script, arguments: ["link": link], contentWorld: .defaultClient) as? [Double],
                  values.count == 6, values[2] > 0, values[3] > 0 else { return viewing }
            let scroll = webView.scrollView
            let content = CGRect(x: values[0], y: values[1], width: values[2], height: values[3])
                .applying(CGAffineTransform(scaleX: scroll.zoomScale, y: scroll.zoomScale))
            let onScreen = scroll.convert(content, to: nil)
            viewing.origin = onScreen
            if values[4] > 0, values[5] > 0 { viewing.naturalSize = CGSize(width: values[4], height: values[5]) }
            let snapshot = WKSnapshotConfiguration()
            snapshot.rect = webView.convert(onScreen, from: nil)
            viewing.placeholder = try? await webView.takeSnapshot(configuration: snapshot)
            return viewing
        }

        func gestureRecognizer(_ gesture: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError failure: Error) { error.wrappedValue = failure.localizedDescription }
        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError failure: Error) { error.wrappedValue = failure.localizedDescription }
        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { error.wrappedValue = "阅读页面加载中断，请返回后重新打开。" }
    }
}

struct DeepReadActivitySheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
