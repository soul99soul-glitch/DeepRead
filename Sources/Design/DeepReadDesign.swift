import SwiftUI
import UIKit

enum DeepReadPalette {
    static let paper = adaptive(light: 0xFBF7F1, dark: 0x201C19)
    static let card = adaptive(light: 0xFFFDF9, dark: 0x2A2521)
    static let ink = adaptive(light: 0x2A2320, dark: 0xF1E9DF)
    /// Follows the user's swatch; reading it in a view body subscribes that view to changes.
    @MainActor static var accent: Color { accents[DeepReadAppearance.shared.accent]! }
    static let muted = adaptive(light: 0x6E6254, dark: 0xB9AA98)
    static let rule = adaptive(light: 0xE4D9CB, dark: 0x3D3530)
    static let done = adaptive(light: 0x3F6A4A, dark: 0x8DBB98)
    static let warn = adaptive(light: 0x8F5A10, dark: 0xE2A75A)
    static let danger = adaptive(light: 0xA8261F, dark: 0xFF7B6E)
    static let lamp = Color(red: 1, green: 0.78, blue: 0.45)

    private static let accents = Dictionary(uniqueKeysWithValues: DeepReadAccent.allCases.map { ($0, adaptive(light: $0.light, dark: $0.dark)) })

    static func adaptive(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
        })
    }
}

/// Date-driven flourishes. Pure so the calendar rules stay testable.
enum DeepReadMoment {
    static func isNight(_ date: Date, calendar: Calendar = .current) -> Bool {
        let hour = calendar.component(.hour, from: date)
        return hour >= 22 || hour < 5
    }

    static func festival(on date: Date, calendar: Calendar = .current) -> (name: String, symbol: String)? {
        // Solar holidays are Gregorian dates even when the user's calendar is not.
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = calendar.timeZone
        let day = gregorian.dateComponents([.month, .day], from: date)
        var lunar = Calendar(identifier: .chinese)
        lunar.timeZone = calendar.timeZone
        let lunarDay = lunar.dateComponents([.month, .day, .isLeapMonth], from: date)
        if lunarDay.isLeapMonth != true {
            switch (lunarDay.month ?? 0, lunarDay.day ?? 0) {
            case (1, 1...7): return ("新春 · 开卷有益", "fireworks")
            case (1, 15): return ("元宵 · 灯下读", "lamp.table")
            case (8, 15): return ("中秋 · 月下读", "moon.stars")
            default: break
            }
        }
        switch (day.month ?? 0, day.day ?? 0) {
        case (1, 1): return ("元旦 · 新年第一读", "sparkles")
        case (4, 23): return ("世界读书日", "book.closed")
        case (10, 1...7): return ("国庆 · 慢慢读", "flag")
        case (12, 24), (12, 25): return ("圣诞 · 围炉夜读", "snowflake")
        default: return nil
        }
    }

    static func milestone(completedCount: Int) -> String? {
        switch completedCount {
        case 1: "首篇"
        case 10: "十篇"
        case 100: "百篇"
        default: nil
        }
    }

    /// Seal for a run that just finished while the reader watched. A retry that kept the old
    /// article after cancel/failure is not a fresh print; only a first draft counts toward milestones.
    static func pressSeal(finishedWithError: Bool, wasFirstDraft: Bool, completedCount: Int) -> (inscription: String, caption: String)? {
        guard !finishedWithError else { return nil }
        if wasFirstDraft, let milestone = milestone(completedCount: completedCount) {
            return (milestone, "你的第 \(completedCount) 篇深度阅读")
        }
        return ("付印", "文章已排版完成")
    }
}

/// Warm paper with a fixed grain, plus a desk-lamp glow late at night.
struct DeepReadPaperBackground: View {
    var night = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    @State private var lampOn = false

    var body: some View {
        ZStack {
            DeepReadPalette.paper
            Canvas { context, size in
                var seed: UInt64 = 0x9E3779B97F4A7C15
                func next() -> CGFloat {
                    seed = seed &* 6364136223846793005 &+ 1442695040888963407
                    return CGFloat(seed >> 33) / CGFloat(UInt32.max >> 1)
                }
                let count = Int(size.width * size.height / 260)
                for _ in 0..<count {
                    let rect = CGRect(x: next() * size.width, y: next() * size.height, width: 1.1, height: 1.1)
                    context.fill(Path(ellipseIn: rect), with: .color(DeepReadPalette.ink.opacity(0.05 * next())))
                }
            }
            .allowsHitTesting(false)
            if night {
                // Additive light washes cream paper to white, so light mode tints instead.
                RadialGradient(colors: [DeepReadPalette.lamp.opacity(lampOn ? (colorScheme == .dark ? 0.26 : 0.2) : 0), .clear],
                               center: .init(x: 0.82, y: 0), startRadius: 10, endRadius: 520)
                    .blendMode(colorScheme == .dark ? .plusLighter : .normal)
                    .allowsHitTesting(false)
                    .onAppear {
                        withAnimation(reduceMotion ? .easeIn(duration: 0.3) : .easeOut(duration: 1.6).delay(0.2)) { lampOn = true }
                    }
            }
        }
        .ignoresSafeArea()
    }
}

/// A cinnabar seal. Multiply blending lets the paper show through like real ink.
struct DeepReadInkStamp: View {
    let text: String
    var size: CGFloat = 64
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.14).stroke(DeepReadPalette.accent, lineWidth: size * 0.07)
            RoundedRectangle(cornerRadius: size * 0.08).stroke(DeepReadPalette.accent, lineWidth: size * 0.02)
                .padding(size * 0.1)
            // Two characters set vertically, like a traditional seal.
            Text(verbatim: text.map(String.init).joined(separator: "\n"))
                .font(.system(size: size * 0.3, weight: .heavy, design: .serif))
                .foregroundStyle(DeepReadPalette.accent)
                .multilineTextAlignment(.center)
                .lineSpacing(-size * 0.04)
                .fixedSize()
        }
        .frame(width: size, height: size)
        .opacity(0.88)
        .blendMode(colorScheme == .dark ? .normal : .multiply)
        .accessibilityLabel("印章：\(text)")
    }
}

/// Slams a stamp down on appearance and whenever `trigger` changes; stays on paper afterwards.
struct DeepReadStampSlam: ViewModifier {
    let trigger: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Local counter so a stamp inserted together with its trigger still plays on first appearance.
    @State private var fire = 0

    func body(content: Content) -> some View {
        content
            .opacity(fire == 0 ? 0 : 1)
            .onAppear { fire += 1 }
            .onChange(of: trigger) { fire += 1 }
            .keyframeAnimator(initialValue: StampFrame(), trigger: fire) { view, frame in
            view.scaleEffect(frame.scale).rotationEffect(.degrees(frame.angle)).opacity(frame.opacity)
        } keyframes: { _ in
            KeyframeTrack(\.scale) {
                LinearKeyframe(reduceMotion ? 1 : 2.6, duration: 0.01)
                CubicKeyframe(reduceMotion ? 1 : 0.92, duration: reduceMotion ? 0.01 : 0.18)
                SpringKeyframe(1, duration: 0.35, spring: .bouncy)
            }
            KeyframeTrack(\.angle) {
                LinearKeyframe(reduceMotion ? -10 : 8, duration: 0.01)
                CubicKeyframe(-10, duration: reduceMotion ? 0.01 : 0.18)
            }
            KeyframeTrack(\.opacity) {
                LinearKeyframe(0, duration: 0.01)
                CubicKeyframe(1, duration: reduceMotion ? 0.25 : 0.14)
            }
        }
    }

    struct StampFrame {
        var scale = 1.0
        var angle = -10.0
        var opacity = 1.0
    }
}

extension EnvironmentValues {
    /// Whether the enclosing main tab is the selected one.
    @Entry var deepReadTabActive = true
    /// Height the floating Dock covers above the bottom safe area.
    @Entry var deepReadDockClearance: CGFloat = 0
}

extension View {
    /// Keeps a page's bottom content clear of the floating Dock. Apply to every page shown with the Dock:
    /// NavigationStack does not pass outer safe-area insets to its pages, so each page reserves the space itself.
    func deepReadDockClearance() -> some View { modifier(DeepReadDockClearance()) }
}

private struct DeepReadDockClearance: ViewModifier {
    @Environment(\.deepReadDockClearance) private var clearance
    func body(content: Content) -> some View { content.safeAreaPadding(.bottom, clearance) }
}

enum DeepReadTabTransition {
    /// Whole-page slide between main tabs, retargeting smoothly when tabs are tapped mid-flight.
    static let animation = Animation.smooth(duration: 0.38)
    /// Glass surfaces (Dock highlight, top actions) move like physical objects.
    static let glass = Animation.spring(duration: 0.42, bounce: 0.22)
}

/// Briefly requests ProMotion's full rate. Left to itself the system ran tab-switch animations at
/// 60 Hz (~80% of frames on device); holding a 120 Hz display link through the transition lifts that to ~90%.
@MainActor final class DeepReadFrameRateBoost: NSObject {
    static let shared = DeepReadFrameRateBoost()
    private var link: CADisplayLink?
    private var until: CFTimeInterval = 0

    /// Covers the page slide plus the Dock and glass springs settling.
    func request(seconds: CFTimeInterval = 1.1) {
        until = max(until, CACurrentMediaTime() + seconds)
        guard link == nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick(_:)))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 80, maximum: 120, preferred: 120)
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    @objc private func tick(_ link: CADisplayLink) {
        guard CACurrentMediaTime() > until else { return }
        link.invalidate()
        self.link = nil
    }
}

/// Slides retained navigation stacks as whole pages in Dock order, so text never drifts within the page.
/// Pages left of the selected tab rest one width to the left, pages to the right one width to the right.
struct DeepReadTabPage: ViewModifier {
    let index: Int
    let tab: Int
    let previousTab: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var active: Bool { index == tab }
    /// The page lying between the two tabs of a 0↔2 switch.
    private var skipped: Bool { index > min(tab, previousTab) && index < max(tab, previousTab) }
    private var side: CGFloat { reduceMotion ? 0 : CGFloat((index - tab).signum()) }

    func body(content: Content) -> some View {
        content
            // Render-layer offset: moves the whole page without re-laying out the NavigationStack.
            .visualEffect { [side] page, proxy in page.offset(x: side * proxy.size.width) }
            .opacity(reduceMotion ? (active ? 1 : 0) : 1)
            // A skipped page jumps to its new side unanimated, so it never sweeps across the screen.
            .animation(skipped ? nil : reduceMotion ? .easeOut(duration: 0.16) : DeepReadTabTransition.animation, value: tab)
            .opacity(skipped && !reduceMotion ? 0 : 1)
            .zIndex(active ? 1 : 0)
            .allowsHitTesting(active)
            .accessibilityHidden(!active)
    }
}

/// Grows a text button's hit area to 44pt without changing layout.
struct DeepReadTapTarget: ViewModifier {
    func body(content: Content) -> some View {
        content.padding(12).contentShape(.rect).padding(-12)
    }
}

/// Paper card that sinks slightly under the finger.
struct DeepReadPressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.975 : 1)
            .brightness(configuration.isPressed ? -0.02 : 0)
            .animation(.spring(response: 0.28, dampingFraction: 0.62), value: configuration.isPressed)
    }
}

extension View {
    func deepReadCard() -> some View {
        // Shadow the opaque card shape only: same look, but a shape shadow skips the per-card
        // offscreen pass that shadowing the whole composited card (text included) requires.
        background {
            RoundedRectangle(cornerRadius: 18).fill(DeepReadPalette.card)
                .shadow(color: DeepReadPalette.ink.opacity(0.06), radius: 10, y: 4)
        }
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(DeepReadPalette.rule, lineWidth: 0.6))
    }

    /// Entrance: rises from below with a stagger. Only the first rows wait, so long lists stay instant.
    func deepReadEntrance(_ index: Int, shown: Bool) -> some View {
        modifier(DeepReadEntrance(index: index, shown: shown))
    }
}

private struct DeepReadEntrance: ViewModifier {
    let index: Int
    let shown: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content
            .opacity(shown ? 1 : 0)
            .offset(y: shown || reduceMotion ? 0 : 26)
            .animation(.spring(response: 0.55, dampingFraction: 0.82).delay(Double(min(index, 8)) * 0.05), value: shown)
    }
}

// MARK: Shake

extension Notification.Name {
    static let deepReadDeviceShaken = Notification.Name("app.amber.deepread.shake")
}

extension UIWindow {
    open override func motionEnded(_ motion: UIEvent.EventSubtype, with event: UIEvent?) {
        if motion == .motionShake { NotificationCenter.default.post(name: .deepReadDeviceShaken, object: nil) }
        super.motionEnded(motion, with: event)
    }
}
