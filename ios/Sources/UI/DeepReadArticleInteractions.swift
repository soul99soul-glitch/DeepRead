import SwiftUI
import UIKit

/// Final touches on every reader document. The page runs no JavaScript, so taps on images become
/// links the native side intercepts, and zoom is fixed in the viewport (double-tap no longer
/// rescales the layout; images get their own pinch-zoom viewer instead).
enum DeepReadArticleDocument {
    static let imageLinkPrefix = "https://deepread.amber.local/image/"

    private static let viewportOverride = #"<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no"><style>html{touch-action:manipulation;-webkit-text-size-adjust:100%;}a.dr-zoom{display:contents;}</style>"#
    private static let tagPattern = try! NSRegularExpression(pattern: #"<a\b[^>]*>|</a\s*>|<img\b[^>]*>"#, options: [.caseInsensitive])
    private static let srcPattern = try! NSRegularExpression(pattern: #"\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)')"#, options: [.caseInsensitive])

    /// Returns the prepared document and the image sources, indexed by their link number.
    static func prepare(_ html: String) -> (html: String, images: [String]) {
        let ns = html as NSString
        var out = ""
        var images: [String] = []
        var cursor = 0
        var linkDepth = 0
        for match in tagPattern.matches(in: html, range: NSRange(location: 0, length: ns.length)) {
            let tag = ns.substring(with: match.range)
            out += ns.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
            cursor = match.range.location + match.range.length
            let lower = tag.lowercased()
            if lower.hasPrefix("</a") { linkDepth = max(0, linkDepth - 1); out += tag; continue }
            if lower.hasPrefix("<a") { linkDepth += 1; out += tag; continue }
            // An image already inside a link keeps that link.
            guard linkDepth == 0, let src = source(of: tag), !src.isEmpty else { out += tag; continue }
            out += #"<a class="dr-zoom" href="\#(imageLinkPrefix)\#(images.count)">"# + tag + "</a>"
            images.append(src)
        }
        out += ns.substring(from: cursor)
        if let head = out.range(of: "</head>", options: .caseInsensitive) {
            out.insert(contentsOf: viewportOverride, at: head.lowerBound)
        } else {
            out = viewportOverride + out
        }
        return (out, images)
    }

    static func imageIndex(for url: URL) -> Int? {
        let string = url.absoluteString
        guard string.hasPrefix(imageLinkPrefix) else { return nil }
        return Int(string.dropFirst(imageLinkPrefix.count))
    }

    private static func source(of tag: String) -> String? {
        let ns = tag as NSString
        guard let match = srcPattern.firstMatch(in: tag, range: NSRange(location: 0, length: ns.length)) else { return nil }
        let range = match.range(at: 1).location != NSNotFound ? match.range(at: 1) : match.range(at: 2)
        return ns.substring(with: range)
            .replacingOccurrences(of: "&quot;", with: "\"").replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&lt;", with: "<").replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&amp;", with: "&")
    }
}

/// Rapid-tap easter egg: five quick taps draw a ring, five more fill it.
struct DeepReadTapStreak: Equatable {
    static let gap: TimeInterval = {
        #if DEBUG
        // Automation taps arrive slower than fingers; `-DeepReadTapGap 3` widens the window.
        let args = ProcessInfo.processInfo.arguments
        if let i = args.firstIndex(of: "-DeepReadTapGap"), args.indices.contains(i + 1), let gap = Double(args[i + 1]) { return gap }
        #endif
        return 0.6
    }()
    static let reveal = 5
    static let fill = 5

    private(set) var count = 0
    private var last: TimeInterval = -.infinity

    /// Nil while hidden; 0...1 once the ring shows.
    var progress: Double? { count < Self.reveal ? nil : min(1, Double(count - Self.reveal) / Double(Self.fill)) }
    var completed: Bool { count >= Self.reveal + Self.fill }

    mutating func tap(at time: TimeInterval) {
        if time - last > Self.gap || completed { count = 0 }
        last = time
        count += 1
    }

    mutating func reset() { count = 0 }
}

struct DeepReadTapRing: View {
    let progress: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            Circle().stroke(DeepReadPalette.accent.opacity(0.18), lineWidth: 5)
            Circle().trim(from: 0, to: max(0.001, progress))
                .stroke(DeepReadPalette.accent, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Image(systemName: progress >= 1 ? "sparkles" : "circle.dotted")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(DeepReadPalette.accent)
                .contentTransition(.symbolEffect(.replace))
        }
        .frame(width: 64, height: 64)
        .padding(10)
        .glassEffect(.regular, in: .circle)
        .animation(reduceMotion ? nil : .spring(duration: 0.3, bounce: 0.35), value: progress)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

struct DeepReadImageViewing: Identifiable {
    let id = UUID()
    let source: String
    /// The image's frame in window coordinates, when the page could report it.
    var origin: CGRect?
    var naturalSize: CGSize?
    /// What the page shows, so the image can fly before the full file loads.
    var placeholder: UIImage?
}

/// Full-screen image that grows out of its place in the article and shrinks back on close,
/// with pinch, double-tap zoom and swipe-down to close. Present it without animation.
struct DeepReadImageViewer: View {
    let viewing: DeepReadImageViewing
    let onClosed: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var image: UIImage?
    @State private var failed = false
    @State private var expanded = false
    /// The zoomable view takes over once the image has landed.
    @State private var settled = false
    @State private var drag: CGFloat = 0

    var body: some View {
        ZStack(alignment: .topTrailing) {
            stage
            // Sits in the same stack above the zoomable UIKit view; as an overlay its taps were lost to it.
            Button(action: close) {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 44, height: 44)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .glassEffect(.regular.interactive(), in: .circle)
            .accessibilityLabel("关闭")
            .padding(.trailing, 14)
            .padding(.top, 6)
            .opacity(expanded && drag == 0 ? 1 : 0)
        }
        .preferredColorScheme(.dark)
        .task {
            withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .spring(duration: 0.38, bounce: 0.12)) {
                expanded = true
            } completion: {
                if expanded { settled = true }
            }
            await load()
        }
    }

    private var stage: some View {
        GeometryReader { geo in
            let global = geo.frame(in: .global).origin
            let bounds = CGRect(origin: .zero, size: geo.size)
            let fitted = fittedRect(in: bounds)
            let origin = viewing.origin?.offsetBy(dx: -global.x, dy: -global.y)
            let flies = origin != nil && !reduceMotion
            let frame = expanded || !flies ? dragged(fitted) : origin ?? fitted
            ZStack {
                Color.black.opacity(expanded ? 1 - min(drag, 400) / 500 : 0)
                if let shown = image ?? viewing.placeholder {
                    Image(uiImage: shown)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: frame.width, height: frame.height)
                        .clipped()
                        .position(x: frame.midX, y: frame.midY)
                        .opacity(settled || (!flies && !expanded) ? 0 : 1)
                    if settled {
                        DeepReadZoomableImage(image: shown, onDrag: { dy in
                            if dy == 0 { withAnimation(.easeOut(duration: 0.25)) { drag = 0 } } else { drag = dy }
                        }, onDismiss: close)
                    }
                } else if failed {
                    ContentUnavailableView("图片加载失败", systemImage: "photo", description: Text("检查网络后重试。"))
                        .foregroundStyle(.white)
                } else {
                    ProgressView().tint(.white).opacity(expanded ? 1 : 0)
                }
            }
        }
        .ignoresSafeArea()
    }

    private func close() {
        settled = false
        withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .spring(duration: 0.32, bounce: 0)) {
            expanded = false
        } completion: { onClosed() }
    }

    private func fittedRect(in bounds: CGRect) -> CGRect {
        let size = image?.size ?? viewing.naturalSize ?? viewing.placeholder?.size ?? bounds.size
        guard size.width > 0, size.height > 0 else { return bounds }
        let fit = min(bounds.width / size.width, bounds.height / size.height)
        let fitted = CGSize(width: size.width * fit, height: size.height * fit)
        return CGRect(x: bounds.midX - fitted.width / 2, y: bounds.midY - fitted.height / 2, width: fitted.width, height: fitted.height)
    }

    /// Matches the transform the zoomable view applies while following a downward swipe.
    private func dragged(_ rect: CGRect) -> CGRect {
        let scale = 1 - min(drag, 300) / 1200
        let size = CGSize(width: rect.width * scale, height: rect.height * scale)
        return CGRect(x: rect.midX - size.width / 2, y: rect.midY + drag - size.height / 2, width: size.width, height: size.height)
    }

    private func load() async {
        guard let url = URL(string: viewing.source) else { failed = true; return }
        if url.scheme == "data", let data = try? Data(contentsOf: url), let decoded = UIImage(data: data) {
            image = decoded
            return
        }
        guard ["http", "https"].contains(url.scheme?.lowercased() ?? "") else { failed = true; return }
        var request = URLRequest(url: url)
        // Same Referer the reader page sends for SSPai's CDN (see imageHTML in the close-reading renderer).
        if url.host?.lowercased() == "cdnfile.sspai.com" { request.setValue("https://deepread.amber.local/", forHTTPHeaderField: "Referer") }
        do {
            let (data, _) = try await URLSession.shared.data(for: request)
            if let decoded = UIImage(data: data) { image = decoded } else { failed = true }
        } catch { failed = true }
    }
}

private struct DeepReadZoomableImage: UIViewRepresentable {
    let image: UIImage
    let onDrag: (CGFloat) -> Void
    let onDismiss: () -> Void

    /// Fits the image whenever its own bounds settle; SwiftUI's first update can arrive at zero size.
    final class LayoutScrollView: UIScrollView {
        var onLayout: ((UIScrollView) -> Void)?
        override func layoutSubviews() {
            super.layoutSubviews()
            onLayout?(self)
        }
    }

    func makeUIView(context: Context) -> UIScrollView {
        let scroll = LayoutScrollView()
        scroll.onLayout = { [weak coordinator = context.coordinator] in coordinator?.layout(in: $0) }
        scroll.delegate = context.coordinator
        scroll.maximumZoomScale = 5
        scroll.minimumZoomScale = 1
        scroll.showsVerticalScrollIndicator = false
        scroll.showsHorizontalScrollIndicator = false
        scroll.contentInsetAdjustmentBehavior = .never
        scroll.backgroundColor = .clear
        let imageView = UIImageView(image: image)
        imageView.contentMode = .scaleAspectFit
        imageView.isUserInteractionEnabled = true
        scroll.addSubview(imageView)
        context.coordinator.imageView = imageView
        let doubleTap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.doubleTapped(_:)))
        doubleTap.numberOfTapsRequired = 2
        scroll.addGestureRecognizer(doubleTap)
        let pan = UIPanGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.panned(_:)))
        pan.delegate = context.coordinator
        scroll.addGestureRecognizer(pan)
        return scroll
    }

    func updateUIView(_ scroll: UIScrollView, context: Context) {
        context.coordinator.onDrag = onDrag
        context.coordinator.onDismiss = onDismiss
        if let imageView = context.coordinator.imageView, imageView.image !== image {
            // The full file replaces the page snapshot.
            imageView.image = image
            context.coordinator.layout(in: scroll, force: true)
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(onDrag: onDrag, onDismiss: onDismiss) }

    @MainActor
    final class Coordinator: NSObject, UIScrollViewDelegate, UIGestureRecognizerDelegate {
        weak var imageView: UIImageView?
        var onDrag: (CGFloat) -> Void
        var onDismiss: () -> Void
        private var laidOutSize: CGSize = .zero
        init(onDrag: @escaping (CGFloat) -> Void, onDismiss: @escaping () -> Void) {
            self.onDrag = onDrag
            self.onDismiss = onDismiss
        }

        func layout(in scroll: UIScrollView, force: Bool = false) {
            guard let imageView, let image = imageView.image, force || scroll.bounds.size != laidOutSize, scroll.bounds.width > 0 else { return }
            laidOutSize = scroll.bounds.size
            scroll.zoomScale = 1
            let fit = min(scroll.bounds.width / image.size.width, scroll.bounds.height / image.size.height)
            imageView.frame = CGRect(origin: .zero, size: CGSize(width: image.size.width * fit, height: image.size.height * fit))
            scroll.contentSize = imageView.frame.size
            center(in: scroll)
        }

        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }
        func scrollViewDidZoom(_ scrollView: UIScrollView) { center(in: scrollView) }

        private func center(in scroll: UIScrollView) {
            guard let imageView else { return }
            let x = max(0, (scroll.bounds.width - imageView.frame.width) / 2)
            let y = max(0, (scroll.bounds.height - imageView.frame.height) / 2)
            scroll.contentInset = UIEdgeInsets(top: y, left: x, bottom: y, right: x)
        }

        @objc func doubleTapped(_ gesture: UITapGestureRecognizer) {
            guard let scroll = gesture.view as? UIScrollView, let imageView else { return }
            if scroll.zoomScale > 1.01 {
                scroll.setZoomScale(1, animated: true)
            } else {
                let point = gesture.location(in: imageView)
                let size = CGSize(width: scroll.bounds.width / 2.5, height: scroll.bounds.height / 2.5)
                scroll.zoom(to: CGRect(x: point.x - size.width / 2, y: point.y - size.height / 2, width: size.width, height: size.height), animated: true)
            }
        }

        /// Swipe down at the fitted size to close, with the image following the finger.
        @objc func panned(_ gesture: UIPanGestureRecognizer) {
            guard let scroll = gesture.view as? UIScrollView, let imageView, scroll.zoomScale <= 1.01 else { return }
            let dy = max(0, gesture.translation(in: scroll).y)
            switch gesture.state {
            case .changed:
                imageView.transform = CGAffineTransform(translationX: 0, y: dy).scaledBy(x: 1 - min(dy, 300) / 1200, y: 1 - min(dy, 300) / 1200)
                onDrag(dy)
            case .ended, .cancelled:
                if dy > 120 || gesture.velocity(in: scroll).y > 900 {
                    // The viewer flies the image home from where the finger left it.
                    onDismiss()
                } else {
                    UIView.animate(withDuration: 0.25) { imageView.transform = .identity }
                    onDrag(0)
                }
            default: break
            }
        }

        func gestureRecognizerShouldBegin(_ gesture: UIGestureRecognizer) -> Bool {
            guard let pan = gesture as? UIPanGestureRecognizer, let scroll = pan.view as? UIScrollView else { return true }
            let v = pan.velocity(in: scroll)
            return scroll.zoomScale <= 1.01 && v.y > abs(v.x)
        }

        func gestureRecognizer(_ gesture: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { false }
    }
}
