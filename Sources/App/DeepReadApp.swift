import SwiftUI

@main
struct DeepReadApp: App {
    @State private var settings: DeepReadSettingsStore
    @State private var runtime: DeepReadRuntime
    @State private var tab = 0
    @State private var visitedTabs: Set<Int> = [0]
    @State private var previousTab = 0
    @State private var dockClearance: CGFloat = 0
    @State private var discoveryReaderId: String?
    @State private var libraryReaderId: String?
    @State private var librarySearchPresented = false
    @State private var keyboardVisible = false
    @State private var discoveryComposerPresented = false
    @State private var libraryComposerPresented = false
    @State private var discoverySettingsPresented = false

    init() {
        let settings = DeepReadSettingsStore()
        _settings = State(initialValue: settings)
        _runtime = State(initialValue: DeepReadRuntime(settings: settings))
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-DeepReadSeedDebateDemo") { DeepReadDebugSeed.debate(into: _runtime.wrappedValue.store) }
        let args = ProcessInfo.processInfo.arguments
        if let flag = args.firstIndex(of: "-DeepReadImportTask"), args.indices.contains(flag + 1) {
            DeepReadDebugSeed.importTask(from: args[flag + 1], into: _runtime.wrappedValue.store)
        }
        #endif
    }

    private var selection: Binding<Int> {
        Binding(get: { tab }, set: { new in
            guard new != tab else { return }
            DeepReadFrameRateBoost.shared.request()
            previousTab = tab
            tab = new
        })
    }

    private var showsDock: Bool {
        switch tab {
        case 0: discoveryReaderId == nil
        case 1: libraryReaderId == nil && !librarySearchPresented
        default: true
        }
    }

    var body: some Scene {
        WindowGroup {
            // Retain visited navigation stacks so outgoing and incoming pages can slide as whole pages.
            ZStack {
                if visitedTabs.contains(0) {
                    NavigationStack {
                        DeepReadDiscoveryView(settings: settings, runtime: runtime,
                            presentingCreate: $discoveryComposerPresented, presentingSourceSettings: $discoverySettingsPresented,
                            selectedTaskId: $discoveryReaderId)
                            .deepReadDockClearance()
                    }
                    .modifier(DeepReadTabPage(index: 0, tab: tab, previousTab: previousTab))
                    .environment(\.deepReadTabActive, tab == 0)
                }
                if visitedTabs.contains(1) {
                    NavigationStack {
                        DeepReadLibraryView(settings: settings, runtime: runtime, presentingCreate: $libraryComposerPresented,
                            selectedTaskId: $libraryReaderId, isSearchPresented: $librarySearchPresented)
                            .deepReadDockClearance()
                    }
                    .modifier(DeepReadTabPage(index: 1, tab: tab, previousTab: previousTab))
                    .environment(\.deepReadTabActive, tab == 1)
                }
                if visitedTabs.contains(2) {
                    NavigationStack {
                        DeepReadSettingsView(store: settings)
                            .deepReadDockClearance()
                    }
                    .modifier(DeepReadTabPage(index: 2, tab: tab, previousTab: previousTab))
                    .environment(\.deepReadTabActive, tab == 2)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            // Paper shows through if an interrupted slide briefly leaves a gap between pages.
            .background(DeepReadPalette.paper.ignoresSafeArea())
            // The Dock floats as an overlay; pages shown with it reserve its height via deepReadDockClearance().
            .environment(\.deepReadDockClearance, dockClearance)
            .overlay(alignment: .bottom) {
                DeepReadDockPresentation(isVisible: showsDock && !keyboardVisible, selection: selection, height: $dockClearance)
            }
            .overlay(alignment: .topTrailing) {
                DeepReadRootActions(tab: tab,
                    isVisible: showsDock && tab < 2 && !(tab == 0 && discoverySettingsPresented),
                    create: {
                        if tab == 0 { discoveryComposerPresented = true }
                        else { libraryComposerPresented = true }
                    }, sources: { discoverySettingsPresented = true })
                    .padding(.trailing, 16)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { _ in keyboardVisible = true }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in keyboardVisible = false }
            .scrollEdgeEffectStyle(.soft, for: .vertical)
            .tint(DeepReadPalette.accent)
            .sensoryFeedback(.selection, trigger: tab)
            .task {
                runtime.recoverInterruptedRuns()
                // Build the other tabs after the first frame so each can slide in from its side on first visit.
                visitedTabs = [0, 1, 2]
            }
        }
    }
}

/// A single glass surface survives tab changes so its width can morph between two actions and one.
private struct DeepReadRootActions: View {
    let tab: Int
    let isVisible: Bool
    let create: () -> Void
    let sources: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var glass

    var body: some View {
        GlassEffectContainer {
            if isVisible {
                HStack(spacing: 12) {
                    if tab == 0 {
                        Button("热点来源设置", systemImage: "line.3.horizontal.decrease", action: sources)
                            .frame(width: 44, height: 44)
                            .accessibilityIdentifier("deepread.discovery.sources")
                            .transition(.opacity)
                    }
                    Button("创建阅读", systemImage: "square.and.pencil", action: create)
                        .frame(width: 44, height: 44)
                        .accessibilityIdentifier(tab == 0 ? "deepread.discovery.create" : "deepread.library.create")
                }
                .labelStyle(.iconOnly)
                .font(.system(size: 22))
                .buttonStyle(.plain)
                .foregroundStyle(DeepReadPalette.accent)
                .padding(.horizontal, tab == 0 ? 4 : 0)
                .glassEffect(.regular.interactive(), in: .capsule)
                .glassEffectID("root-actions", in: glass)
                .transition(reduceMotion ? .opacity : .scale(scale: 0.8, anchor: .trailing).combined(with: .opacity))
            }
        }
        .animation(reduceMotion ? .easeOut(duration: 0.12) : DeepReadTabTransition.glass, value: tab)
        .animation(reduceMotion ? .easeOut(duration: 0.12) : DeepReadTabTransition.animation, value: isVisible)
    }
}

private struct DeepReadDockPresentation: View {
    let isVisible: Bool
    @Binding var selection: Int
    /// Height the Dock covers above the bottom safe area; kept while hidden so page insets stay stable.
    @Binding var height: CGFloat
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var dockTransition: AnyTransition {
        if reduceMotion { return .opacity }
        return .asymmetric(
            insertion: .move(edge: .bottom).combined(with: .scale(scale: 0.82, anchor: .bottom)).combined(with: .opacity),
            removal: .offset(y: 48).combined(with: .scale(scale: 0.92, anchor: .bottom)).combined(with: .opacity)
        )
    }

    var body: some View {
        Group {
            if isVisible {
                HStack {
                    Spacer(minLength: 0)
                    DeepReadDock(selection: $selection)
                    Spacer(minLength: 0)
                }
                .padding(.horizontal, 18)
                .padding(.top, 6)
                // Match the system tab bar's lower placement within the home indicator safe area.
                .padding(.bottom, -13)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
                .background {
                    Rectangle().fill(.ultraThinMaterial)
                        .mask(LinearGradient(colors: [.clear, .black], startPoint: .top, endPoint: .bottom))
                        .padding(.top, -26)
                        .ignoresSafeArea(.container, edges: .bottom)
                }
                .transition(dockTransition)
            }
        }
        // Animate only the Dock, leaving the NavigationStack's page transition independent.
        .animation(reduceMotion ? .easeOut(duration: 0.16)
            : (isVisible ? .spring(duration: 0.5, bounce: 0.24) : .easeIn(duration: 0.18)), value: isVisible)
    }
}

private struct DeepReadDock: View {
    @Binding var selection: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var highlight

    var body: some View {
        HStack(spacing: 0) {
            item("发现", symbol: "newspaper", value: 0, identifier: "discovery")
            item("阅读库", symbol: "books.vertical", value: 1, identifier: "library")
            item("设置", symbol: "gearshape", value: 2, identifier: "settings")
        }
        .padding(4)
        .frame(maxWidth: 274)
        .glassEffect(.regular.interactive(), in: .capsule)
        .animation(reduceMotion ? nil : DeepReadTabTransition.glass, value: selection)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("deepread.dock")
    }

    private func item(_ title: String, symbol: String, value: Int, identifier: String) -> some View {
        let selected = selection == value
        return Button { selection = value } label: {
            VStack(spacing: 2) {
                Image(systemName: symbol)
                    .symbolVariant(.fill)
                    .font(.system(size: 25, weight: .regular))
                Text(title).font(.caption2.weight(.medium))
            }
            .foregroundStyle(selected ? DeepReadPalette.accent : Color.primary)
            .frame(maxWidth: .infinity, minHeight: 54)
            .background {
                if selected {
                    Capsule().fill(Color(uiColor: .tertiarySystemFill))
                        .matchedGeometryEffect(id: "selection", in: highlight)
                }
            }
            .contentShape(.capsule)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("deepread.dock.\(identifier)")
    }
}
