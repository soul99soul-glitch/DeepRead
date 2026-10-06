import Foundation

/// Renders a structured `IOSDeepReadOutput` into the Android-parity editorial cards
/// (timeline / core-points / diagram / analysis / reading-links) — a Swift port of the
/// Kotlin `DeepReadTemplateRenderer` section builders. The CSS classes it targets are
/// already in `IOSDeepReadEditorialRenderer`'s ported CSS; text fields run through the
/// same Markdown→HTML the flat reader uses.
enum IOSDeepReadStructuredRenderer {

    /// The summary block that sits inside the headline section.
    static func summaryHTML(_ summary: String) -> String {
        let body = md(summary)
        guard !body.isEmpty else { return "" }
        return #"<div class="summary markdown-body">"# + body + "</div>"
    }

    /// The one-sentence conclusion that opens the headline.
    static func bottomLineHTML(_ text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        return #"<p class="bottom-line">"# + mdInline(trimmed) + "</p>"
    }

    /// Sections answer one reader question each: judgments (what), timeline + diagram (how it
    /// got here), analysis (who disputes what), outlook (what next), uncertainties (what to
    /// trust), references (where to check).
    enum Section {
        case timeline, corePoints, diagram, analysis, outlook, uncertainties, extendedReading, references
        static let standard = order(topicType: "event")

        /// Judgments always lead; the topic type decides which question is unfolded next.
        static func order(topicType: String) -> [Section] {
            switch topicType {
            case "opinion": [.corePoints, .analysis, .outlook, .uncertainties, .timeline, .diagram, .extendedReading, .references]
            case "product": [.corePoints, .diagram, .analysis, .outlook, .uncertainties, .timeline, .extendedReading, .references]
            case "person": [.corePoints, .timeline, .analysis, .uncertainties, .outlook, .diagram, .extendedReading, .references]
            default: [.corePoints, .timeline, .diagram, .analysis, .uncertainties, .outlook, .extendedReading, .references]
            }
        }
    }

    /// All rich sections after the headline, in the given order.
    static func sectionsHTML(_ output: IOSDeepReadOutput, order: [Section] = Section.standard) -> String {
        let cite = Citer(count: output.sources.count)
        return order.map { section in
            switch section {
            case .timeline: timelineSection(output.timeline)
            case .corePoints: corePointsSection(output.corePoints, cite: cite)
            case .diagram: output.diagram.map(diagramSection) ?? ""
            case .analysis: analysisSection(output.analysis, cite: cite)
            case .outlook: outlookSection(output)
            case .uncertainties: uncertaintiesSection(output.uncertainties)
            case .extendedReading: extendedReadingSection(output.extendedReading)
            case .references: output.sources.isEmpty ? referencesSection(output.references) : sourcesSection(output)
            }
        }.joined()
    }

    /// Source numbers as a trailing superscript, dropping ids outside the numbered list.
    private struct Citer {
        let count: Int
        func callAsFunction(_ ids: [Int]) -> String {
            let valid = ids.filter { (1...max(count, 1)).contains($0) && count > 0 }
            guard !valid.isEmpty else { return "" }
            return #"<sup class="cite">"# + valid.map { "[\($0)]" }.joined() + "</sup>"
        }
    }

    // MARK: - Sections

    private static func timelineSection(_ events: [IOSDeepReadTimelineEvent]) -> String {
        let items = events.filter { !$0.event.isEmpty || !$0.date.isEmpty }
        guard !items.isEmpty else { return "" }
        var b = #"<section><p class="section">时间轴</p>"#
        for event in items {
            b += #"<div class="timeline-item"# + (event.isHighlight ? " highlight" : "") + #""><div class="timeline-marker"></div><div class="timeline-body">"#
            if !event.date.isEmpty { b += #"<p class="timeline-date">"# + esc(event.date) + "</p>" }
            b += #"<div class="timeline-copy markdown-body">"# + md(event.event) + "</div>"
            if event.isHighlight, let why = event.why, !why.isEmpty {
                b += #"<p class="timeline-why">转折："# + mdInline(why) + "</p>"
            }
            b += figure(event.imageUrl, event.imageCaption)
            b += "</div></div>"
        }
        return b + "</section>"
    }

    private static func corePointsSection(_ points: [IOSDeepReadCorePoint], cite: Citer) -> String {
        let items = points.filter { !$0.point.isEmpty }
        guard !items.isEmpty else { return "" }
        var b = #"<section class="judgments"><p class="section">关键判断</p>"#
        for point in items {
            b += #"<div class="core-point"><h2>"# + mdInline(point.point) + cite(point.sources) + "</h2>"
            if let supporting = point.supporting, !supporting.isEmpty {
                b += #"<div class="core-support markdown-body">"# + md(supporting) + "</div>"
            }
            b += figure(point.imageUrl, point.imageCaption)
            b += "</div>"
        }
        return b + "</section>"
    }

    private static func diagramSection(_ diagram: IOSDeepReadDiagram) -> String {
        let nodes = Array(diagram.nodes.prefix(6))
        guard nodes.count >= 2 else { return "" }
        let labels = Dictionary(nodes.map { ($0.id, $0.label) }, uniquingKeysWith: { first, _ in first })
        let edges = Array(diagram.edges.filter { labels[$0.from] != nil && labels[$0.to] != nil }.prefix(6))
        let typeLabel: String
        switch diagram.type {
        case "causal_chain": typeLabel = "因果链"
        case "process_flow": typeLabel = "流程图"
        case "stakeholder_map": typeLabel = "关系图"
        case "system_structure": typeLabel = "结构图"
        case "comparison_matrix": typeLabel = "对比图"
        default: typeLabel = "图解"
        }
        let useSteps = diagram.type == "causal_chain" || diagram.type == "process_flow"
        var b = #"<section class="diagram-block"><p class="section">"# + esc(typeLabel) + "</p>"
        b += "<h2>" + mdInline(diagram.title) + "</h2>"
        b += #"<div class="diagram-frame">"#
        if useSteps {
            // Steps already read top to bottom; only the labels of step-to-next edges add
            // information, so they sit under the step and drop out of the relations list.
            var nextLabels: [Int: String] = [:]
            for index in nodes.indices.dropLast() {
                let between = edges.filter { $0.from == nodes[index].id && $0.to == nodes[index + 1].id }
                if !between.isEmpty {
                    nextLabels[index] = between.compactMap(\.label).first { !$0.isEmpty } ?? ""
                }
            }
            b += diagramSteps(nodes, nextLabels: nextLabels)
            b += diagramRelations(edges.filter { edge in
                !nextLabels.keys.contains { edge.from == nodes[$0].id && edge.to == nodes[$0 + 1].id }
            }, labels: labels)
        } else {
            b += diagramCards(nodes)
            b += diagramRelations(edges, labels: labels)
        }
        b += "</div>"
        if let caption = diagram.caption, !caption.isEmpty {
            b += #"<p class="diagram-caption">"# + esc(caption) + "</p>"
        }
        return b + "</section>"
    }

    private static func diagramSteps(_ nodes: [IOSDeepReadDiagramNode], nextLabels: [Int: String]) -> String {
        var b = #"<ol class="diagram-steps">"#
        for (index, node) in nodes.enumerated() {
            b += #"<li class="diagram-step"><span class="diagram-step-index">"#
            b += String(format: "%02d", index + 1) + "</span><div>" + diagramNodeInner(node)
            if let label = nextLabels[index], !label.isEmpty { b += #"<p class="diagram-next">↓ "# + esc(label) + "</p>" }
            b += "</div></li>"
        }
        return b + "</ol>"
    }

    private static func diagramCards(_ nodes: [IOSDeepReadDiagramNode]) -> String {
        var b = #"<div class="diagram-grid">"#
        for node in nodes {
            b += #"<div class="diagram-card">"# + diagramNodeInner(node) + "</div>"
        }
        return b + "</div>"
    }

    private static func diagramNodeInner(_ node: IOSDeepReadDiagramNode) -> String {
        var b = ""
        if let group = node.group, !group.isEmpty { b += #"<small class="diagram-group">"# + esc(group) + "</small>" }
        b += "<h3>" + mdInline(node.label) + "</h3>"
        if let note = node.note, !note.isEmpty { b += #"<div class="diagram-note markdown-body">"# + md(note) + "</div>" }
        return b
    }

    private static func diagramRelations(_ edges: [IOSDeepReadDiagramEdge], labels: [String: String]) -> String {
        guard !edges.isEmpty else { return "" }
        var b = #"<ul class="diagram-relations">"#
        for edge in edges {
            let from = labels[edge.from] ?? edge.from
            let to = labels[edge.to] ?? edge.to
            b += "<li><span>" + esc(from) + "</span><b>→</b><span>" + esc(to) + "</span>"
            if let label = edge.label, !label.isEmpty { b += "：" + esc(label) }
            b += "</li>"
        }
        return b + "</ul>"
    }

    private static func analysisSection(_ analysis: IOSDeepReadAnalysis, cite: Citer) -> String {
        let perspectives = analysis.perspectives.prefix(6).filter { !$0.viewpoint.isEmpty }
        let quotes = analysis.quotes.prefix(6).filter { !$0.text.isEmpty }
        guard !(analysis.coreDispute ?? "").isEmpty || !perspectives.isEmpty || !quotes.isEmpty else { return "" }
        var b = #"<section><p class="section">各方立场</p>"#
        if let dispute = analysis.coreDispute, !dispute.isEmpty {
            b += #"<blockquote class="markdown-body">"# + md(dispute) + "</blockquote>"
        }
        for perspective in perspectives {
            b += #"<div class="perspective"><p class="holder">"# + esc(perspective.holder ?? "") + "</p>"
            if let interest = perspective.interest, !interest.isEmpty {
                b += #"<p class="interest">诉求："# + mdInline(interest) + "</p>"
            }
            b += #"<div class="markdown-body">"# + md(perspective.viewpoint) + "</div>" + cite(perspective.sources)
            if let quote = perspective.quote, !quote.isEmpty {
                b += quoteHTML(quote, attribution: perspective.quoteBy)
            }
            b += "</div>"
        }
        // Older articles kept quotes apart from the perspectives.
        for quote in quotes { b += quoteHTML(quote.text, attribution: quote.attribution) }
        return b + "</section>"
    }

    private static func quoteHTML(_ text: String, attribution: String?) -> String {
        var b = #"<div class="quote"><p class="quote-text">"# + esc(text) + "</p>"
        if let attribution, !attribution.isEmpty {
            b += #"<span class="quote-attribution">—— "# + esc(attribution) + "</span>"
        }
        return b + "</div>"
    }

    private static func outlookSection(_ output: IOSDeepReadOutput) -> String {
        let impacts = output.impacts.filter { !$0.effect.isEmpty }
        let watch = output.watch.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        let implications = output.analysis.implications ?? ""
        guard !impacts.isEmpty || !watch.isEmpty || !implications.isEmpty else { return "" }
        var b = #"<section class="outlook"><p class="section">影响与走向</p>"#
        if !impacts.isEmpty {
            b += #"<ul class="impacts">"#
            for impact in impacts {
                b += #"<li><p class="impact-target">"# + esc(impact.target)
                if !impact.horizonLabel.isEmpty { b += "<small>" + impact.horizonLabel + "</small>" }
                b += "</p><p>" + mdInline(impact.effect) + "</p></li>"
            }
            b += "</ul>"
        }
        if !implications.isEmpty { b += #"<div class="markdown-body">"# + md(implications) + "</div>" }
        if !watch.isEmpty {
            b += #"<div class="watch"><p class="holder">接下来关注</p><ul>"#
                + watch.map { "<li>" + mdInline($0) + "</li>" }.joined() + "</ul></div>"
        }
        return b + "</section>"
    }

    private static func uncertaintiesSection(_ items: [IOSDeepReadUncertainty]) -> String {
        let claims = items.filter { !$0.claim.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        guard !claims.isEmpty else { return "" }
        return #"<section class="uncertain"><p class="section">待核实</p><ul>"#
            + claims.map { item in
                "<li>" + (item.statusLabel.isEmpty ? "" : #"<span class="claim-status">"# + item.statusLabel + "</span>")
                    + mdInline(item.claim.trimmingCharacters(in: .whitespacesAndNewlines)) + "</li>"
            }.joined()
            + "</ul></section>"
    }

    private static func extendedReadingSection(_ links: [IOSDeepReadLink]) -> String {
        let items = links.filter { !$0.title.isEmpty && !$0.url.isEmpty }.prefix(10)
        guard !items.isEmpty else { return "" }
        var b = #"<section><p class="section">扩展阅读</p>"#
        for link in items {
            let label = (link.source?.isEmpty == false) ? link.source! : link.url
            b += #"<a class="reading-link" href=""# + esc(link.url) + #"">"#
            b += "<p>" + esc(link.title) + "</p><small>" + esc(label) + "</small></a>"
        }
        return b + "</section>"
    }

    private static func referencesSection(_ links: [IOSDeepReadLink]) -> String {
        let items = links.filter { !$0.title.isEmpty && !$0.url.isEmpty }.prefix(12)
        guard !items.isEmpty else { return "" }
        var b = #"<section><p class="section">参考来源</p>"#
        for link in items {
            b += #"<a class="reading-link" href=""# + esc(link.url) + #"">"#
            b += "<p>" + esc(link.title) + "</p><small>" + esc(link.source ?? link.url) + "</small></a>"
        }
        return b + "</section>"
    }

    /// The numbered generation sources, in citation order; cited ones are marked.
    private static func sourcesSection(_ output: IOSDeepReadOutput) -> String {
        let cited = Set(output.corePoints.flatMap(\.sources) + output.analysis.perspectives.flatMap(\.sources))
        var b = #"<section class="sources"><p class="section">来源</p>"#
        for (index, link) in output.sources.enumerated() where !link.title.isEmpty {
            let number = index + 1
            let inner = #"<p><span class="src-no">["# + String(number) + "]</span>" + esc(link.title) + "</p><small>"
                + esc(link.source ?? "") + (cited.contains(number) ? " · 本文引用" : "") + "</small>"
            b += link.url.isEmpty
                ? #"<div class="reading-link">"# + inner + "</div>"
                : #"<a class="reading-link" href=""# + esc(link.url) + #"">"# + inner + "</a>"
        }
        return b + "</section>"
    }

    // MARK: - Helpers

    private static func figure(_ url: String?, _ caption: String?) -> String {
        guard let url = url?.trimmingCharacters(in: .whitespaces), url.hasPrefix("http") else { return "" }
        var b = #"<figure><img src=""# + esc(url) + #""/>"#
        if let caption, !caption.isEmpty { b += "<figcaption>" + esc(caption) + "</figcaption>" }
        return b + "</figure>"
    }

    private static func esc(_ s: String) -> String { IOSDeepReadEditorialRenderer.esc(s) }
    private static func md(_ s: String) -> String { IOSDeepReadEditorialRenderer.markdownToHTML(s) }
    private static func mdInline(_ s: String) -> String { IOSDeepReadEditorialRenderer.markdownToInlineHTML(s) }
}
