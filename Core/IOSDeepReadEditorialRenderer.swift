import Foundation
import Markdown

/// Renders a Deep Read article as a self-contained HTML "editorial" magazine
/// page for the WKWebView reader — a Swift port of the Android
/// `DeepReadTemplateRenderer.renderEditorialSlant` plus its BASE / runtime / dark
/// CSS (kept verbatim so both platforms share one visual language).
///
/// Shell-first scope: a magazine headline (with an OPTIONAL diagonal hero image —
/// the `.hero-cut` clip-path slant) over a magazine-typeset Markdown body, plus a
/// sources list. The Markdown is parsed with swift-markdown (apple/swift-markdown)
/// and walked to HTML here.
/// `MarkdownView` uses, then walked to HTML here (no second parser, no drift).
/// The structured timeline / core-points / diagram cards Android renders from a
/// typed `DeepReadOutput` are a later increment (their CSS is already included).
enum IOSDeepReadEditorialRenderer {

    struct SourceLink {
        let title: String
        let url: String
        var source: String? = nil
    }

    struct Input {
        let title: String
        let markdown: String
        var kicker: String = "DEEP READ"
        /// When non-nil/non-empty, renders the diagonal hero figure. Degrades to a
        /// kicker headline when absent.
        var heroImageURL: String? = nil
        var heroCaption: String? = nil
        var sourceLabel: String? = nil
        var sources: [SourceLink] = []
        var dark: Bool = false
        /// Structured Android-parity output. When present (and non-empty), the reader
        /// renders the rich editorial cards (timeline / core-points / diagram /
        /// analysis / reading-links); otherwise it falls back to the flat Markdown body.
        var structured: IOSDeepReadOutput? = nil
        /// Order of the structured sections; the default leads with the key judgments.
        var sectionOrder: [IOSDeepReadStructuredRenderer.Section] = IOSDeepReadStructuredRenderer.Section.standard
        /// When false, the body renders WITHOUT the kicker + `<h1>` headline (the native
        /// SwiftUI masthead owns those); the summary/lead is kept as the body's opener.
        var showHeadline: Bool = true
        /// App accent color as a CSS hex ("#RRGGBB"). Drives every accent in the reader
        /// (timeline markers, blockquote rule, diagram indices, links) via the injected
        /// `--deep-read-accent` variable, so the editorial palette follows the user's swatch.
        var accentHex: String = "#C8402F"
        /// Reading font mode wire name: "serif" (bundled Noto Serif SC) or "system"
        /// (the platform sans). Mirrors the board's 阅读字体 setting.
        var fontMode: String = "serif"
        /// Resolved canvas palette (the app theme's colors for the current appearance) as
        /// CSS hex, injected as --deep-read-bg/fg/surface/muted/border so the reader follows
        /// the chosen background theme. Defaults to the warm-paper palette.
        var bgHex: String = "#fbf7f1"
        var fgHex: String = "#2a2320"
        var surfaceHex: String = "#f2eade"
        var mutedHex: String = "#6e6254"
        var borderHex: String = "#dbcebc"
        /// When true the page paints no background, so the native page canvas shows through
        /// the transparent WKWebView. Only for a flat canvas (no texture/gradient) — over a
        /// texture the body keeps its solid color for legibility. PDF export leaves this off.
        var transparentCanvas: Bool = false
    }

    // MARK: - Entry point

    static func renderHTML(_ input: Input) -> String {
        let hero = input.heroImageURL?.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasHero = !(hero ?? "").isEmpty
        let structured = input.structured.flatMap { $0.hasStructuredBody ? $0 : nil }

        var html = "<!doctype html><html><head>"
        html += #"<meta name="viewport" content="width=device-width, initial-scale=1"/>"#
        html += "<style>\n"
        html += defaultFontCSS + "\n" + baseCSS + "\n" + runtimeCSS + "\n"
        // After the base/runtime CSS so the :root + code overrides win the cascade.
        html += bundledFontCSS + "\n"
        // User accent + canvas palette drive every var(--deep-read-*) in the CSS, injected
        // last so they win. The palette is already resolved for the current appearance by
        // the caller, so the reader follows the chosen background theme (paper or immersive,
        // light or dark) with no baked light/dark stylesheet. "system" font mode swaps the
        // serif body stack for the platform sans.
        html += ":root{"
            + "--deep-read-accent:" + input.accentHex + ";"
            + "--deep-read-bg:" + input.bgHex + ";"
            + "--deep-read-fg:" + input.fgHex + ";"
            + "--deep-read-surface:" + input.surfaceHex + ";"
            + "--deep-read-muted:" + input.mutedHex + ";"
            + "--deep-read-border:" + input.borderHex + ";"
            + "}\n"
        if input.transparentCanvas {
            html += "html,body{background:transparent;}\n"
        }
        if input.fontMode == "system" {
            html += #":root{--deep-read-serif:"PingFang SC","Source Han Sans SC","Noto Sans SC",system-ui,sans-serif;}"# + "\n"
        }
        html += emptyImageFallbackCSS + "\n</style></head><body><article>"

        if hasHero, let hero {
            html += #"<figure class="hero"><img src=""# + esc(hero) + #""/><div class="hero-cut"><div>"#
            html += #"<span class="hero-type">"# + esc(input.kicker) + "</span>"
            if let sourceLabel = input.sourceLabel, !sourceLabel.isEmpty {
                html += #"<span class="hero-source">"# + esc(sourceLabel) + "</span>"
            }
            html += "</div>"
            if let caption = input.heroCaption, !caption.isEmpty {
                html += "<figcaption>" + esc(caption) + "</figcaption>"
            }
            html += "</div></figure>"
        }

        if input.showHeadline {
            html += #"<section class="headline">"#
            if !hasHero {
                html += #"<p class="kicker">"# + esc(input.kicker) + "</p>"
            }
            html += "<h1>" + esc(input.title) + "</h1>"
            if let structured {
                html += IOSDeepReadStructuredRenderer.bottomLineHTML(structured.bottomLine)
                html += IOSDeepReadStructuredRenderer.summaryHTML(structured.summary)
            }
            html += "</section>"
        } else if let structured, !structured.summary.isEmpty || !structured.bottomLine.isEmpty {
            // Body-only: native masthead owns kicker + h1; keep the conclusion and summary as the lead.
            html += #"<section class="headline">"# + IOSDeepReadStructuredRenderer.bottomLineHTML(structured.bottomLine)
                + IOSDeepReadStructuredRenderer.summaryHTML(structured.summary) + "</section>"
        }

        if let structured {
            // Rich editorial sections from the typed output (timeline / core-points /
            // diagram / analysis / extended-reading) — Android parity.
            html += IOSDeepReadStructuredRenderer.sectionsHTML(structured, order: input.sectionOrder)
        } else {
            // Fallback: magazine-typeset flat Markdown body + the raw sources list.
            html += #"<section><div class="markdown-body">"# + markdownToHTML(stripLeadingH1(input.markdown)) + "</div></section>"
            if !input.sources.isEmpty {
                html += #"<section><p class="section">来源</p>"#
                for source in input.sources where !source.url.isEmpty {
                    html += #"<a class="reading-link" href=""# + esc(source.url) + #"">"#
                    html += "<p>" + esc(source.title) + "</p>"
                    if let src = source.source, !src.isEmpty {
                        html += "<small>" + esc(src) + "</small>"
                    }
                    html += "</a>"
                }
                html += "</section>"
            }
        }

        html += "</article></body></html>"
        return html
    }

    // MARK: - Markdown -> HTML (swift-markdown AST)

    /// Block Markdown → HTML — internal so IOSDeepReadStructuredRenderer reuses it.
    /// `Document(parsing:)` 总能产出语法树（解析器自恢复），无需失败回退。
    static func markdownToHTML(_ md: String) -> String {
        let source = md.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !source.isEmpty else { return "" }
        let document = Document(parsing: source)
        return document.children.map { blockHTML($0) }.joined()
    }

    /// Inline Markdown → HTML: strip a single surrounding `<p>…</p>` so the result can
    /// sit inside an `<h2>`/`<h3>` (mirrors Android's markdownInlineHtml).
    static func markdownToInlineHTML(_ md: String) -> String {
        var html = markdownToHTML(md).trimmingCharacters(in: .whitespacesAndNewlines)
        // Collapse to inline so heading text keeps the heading's own font/size/weight:
        // always drop the outer <p> wrapper (Android strips ^<p>(.*)</p>$ unconditionally)
        // and fold any interior paragraph breaks into spaces, instead of leaking nested
        // <p> (which would re-impose body 15px/1.68 inside an <h2>/<h3>).
        if html.hasPrefix("<p>"), html.hasSuffix("</p>") {
            html = String(html.dropFirst(3).dropLast(4))
        }
        html = html.replacingOccurrences(of: "</p><p>", with: " ")
        return html
    }

    private static func blockHTML(_ node: some Markup) -> String {
        switch node {
        case let paragraph as Paragraph:
            return "<p>" + inlineHTML(Array(paragraph.children)) + "</p>"
        case let heading as Heading:
            let level = min(max(heading.level, 1), 6)
            return "<h\(level)>" + inlineHTML(Array(heading.children)) + "</h\(level)>"
        case let quote as BlockQuote:
            return "<blockquote>" + quote.children.map { blockHTML($0) }.joined() + "</blockquote>"
        case let list as UnorderedList:
            return "<ul>" + list.children.map { "<li>" + listItemHTML($0) + "</li>" }.joined() + "</ul>"
        case let list as OrderedList:
            return "<ol>" + list.children.map { "<li>" + listItemHTML($0) + "</li>" }.joined() + "</ol>"
        case let code as CodeBlock:
            return "<pre><code>" + esc(code.code) + "</code></pre>"
        case is ThematicBreak:
            return "<hr/>"
        case let table as Table:
            return tableHTML(table)
        case let html as HTMLBlock:
            return "<p>" + esc(html.rawHTML) + "</p>"
        default:
            return inlineHTML(Array(node.children))
        }
    }

    /// A list item is usually a loose paragraph or a tight run of inline nodes;
    /// coalesce inline runs and keep genuine block children (nested lists, code).
    private static func listItemHTML(_ node: some Markup) -> String {
        let children = Array(node.children)
        if !children.isEmpty, children.allSatisfy({ $0 is Paragraph }) {
            return children.compactMap { $0 as? Paragraph }
                .map { inlineHTML(Array($0.children)) }.joined(separator: "<br/>")
        }
        var out = ""
        var inlineRun: [Markup] = []
        func flush() {
            if !inlineRun.isEmpty { out += inlineHTML(inlineRun); inlineRun.removeAll() }
        }
        for child in children {
            if isBlockLevelForHTML(child) {
                flush()
                out += blockHTML(child)
            } else {
                inlineRun.append(child)
            }
        }
        flush()
        return out
    }

    private static func tableHTML(_ table: Table) -> String {
        var head = ""
        var body = ""
        for child in table.children {
            switch child {
            case let headRow as Table.Head:
                let cells = headRow.children.compactMap { $0 as? Table.Cell }
                head += "<tr>" + cells.map { "<th>" + inlineHTML(Array($0.children)) + "</th>" }.joined() + "</tr>"
            case let bodySection as Table.Body:
                for row in bodySection.children.compactMap({ $0 as? Table.Row }) {
                    let cells = row.children.compactMap { $0 as? Table.Cell }
                    body += "<tr>" + cells.map { "<td>" + inlineHTML(Array($0.children)) + "</td>" }.joined() + "</tr>"
                }
            default:
                break
            }
        }
        // Wide tables scroll sideways instead of squeezing every column to a sliver.
        let columns = table.maxColumnCount
        var html = columns >= 4 ? #"<p class="table-hint">左右滑动查看 ›</p>"# : ""
        html += #"<div class="table-wrap"><table>"#
        if !head.isEmpty { html += "<thead>" + head + "</thead>" }
        if !body.isEmpty { html += "<tbody>" + body + "</tbody>" }
        return html + "</table></div>"
    }

    private static func inlineHTML(_ nodes: [Markup]) -> String {
        var out = ""
        for node in nodes {
            switch node {
            case _ as SoftBreak:
                // CommonMark soft break = space; but no space between CJK characters.
                if let last = out.last, !last.isWhitespace, !isCJK(last) { out += " " }
            case _ as LineBreak:
                out += "<br/>"
            case let text as Text:
                out += esc(text.plainText)
            case let emphasis as Emphasis:
                out += "<em>" + inlineHTML(Array(emphasis.children)) + "</em>"
            case let strong as Strong:
                out += "<strong>" + inlineHTML(Array(strong.children)) + "</strong>"
            case let strike as Strikethrough:
                out += "<s>" + inlineHTML(Array(strike.children)) + "</s>"
            case let code as InlineCode:
                out += "<code>" + esc(code.code) + "</code>"
            case let link as Link:
                let inner = inlineHTML(Array(link.children))
                if let destination = link.destination, destination.hasPrefix("http") {
                    out += #"<a href=""# + esc(destination) + #"">"# + inner + "</a>"
                } else {
                    out += inner
                }
            case is Image:
                break // hero / inline images are handled out-of-band; skip in body text
            default:
                out += inlineHTML(Array(node.children))
            }
        }
        return out
    }

    /// Block-level nodes get their own HTML element; everything else is inline
    /// content coalesced into a flowing run (see `listItemHTML`).
    private static func isBlockLevelForHTML(_ node: some Markup) -> Bool {
        switch node {
        case is Paragraph, is Heading, is BlockQuote, is CodeBlock,
             is UnorderedList, is OrderedList, is ListItem,
             is Table, is ThematicBreak, is HTMLBlock:
            return true
        default:
            return false
        }
    }

    // MARK: - Helpers

    /// The generated markdown leads with `# <title>`; the headline already shows the
    /// title, so drop a single leading level-1 heading to avoid duplication.
    private static func stripLeadingH1(_ md: String) -> String {
        var lines = md.components(separatedBy: "\n")
        while let first = lines.first, first.trimmingCharacters(in: .whitespaces).isEmpty {
            lines.removeFirst()
        }
        if let first = lines.first, first.hasPrefix("# ") {
            lines.removeFirst()
        }
        return lines.joined(separator: "\n")
    }

    static func esc(_ text: String) -> String {
        var result = ""
        result.reserveCapacity(text.count)
        // Scalars, not Characters: a quote followed by a combining mark is one Character and would slip through.
        for scalar in text.unicodeScalars {
            switch scalar {
            case "&": result += "&amp;"
            case "<": result += "&lt;"
            case ">": result += "&gt;"
            case "\"": result += "&quot;"
            case "'": result += "&#39;"
            default: result.unicodeScalars.append(scalar)
            }
        }
        return result
    }

    private static func isCJK(_ char: Character) -> Bool {
        for scalar in char.unicodeScalars {
            switch scalar.value {
            case 0x3000...0x303F, 0x3040...0x30FF, 0x3400...0x4DBF,
                 0x4E00...0x9FFF, 0xF900...0xFAFF, 0xFF00...0xFFEF:
                return true
            default:
                continue
            }
        }
        return false
    }

    // MARK: - CSS (verbatim port of Android DeepReadTemplateRenderer constants)

    private static let defaultFontCSS = """
    :root{
      --deep-read-serif:"Noto Serif SC","Source Han Serif SC","Songti SC",serif;
      --deep-read-sans:"PingFang SC","Source Han Sans SC","Noto Sans SC",system-ui,sans-serif;
      --deep-read-font-scale:1;
    }
    """

    private static let baseCSS = """
    html,body{margin:0;padding:0;background:var(--deep-read-bg);color:var(--deep-read-fg);font-family:var(--deep-read-serif);-webkit-user-select:text;overflow-wrap:anywhere;}
    /* Tight bottom pad — height is measured from article; avoid phantom whitespace. */
    article{padding-bottom:12px;display:block;}
    .hero{margin:0 0 8px 0;position:relative;background:var(--deep-read-surface);overflow:hidden;}
    .hero img{display:block;width:100%;height:265px;object-fit:cover;}
    .hero-cut{height:106px;background:var(--deep-read-bg);clip-path:polygon(0 24%,100% 0,100% 100%,0 100%);margin-top:-54px;position:relative;padding:46px 22px 0;box-sizing:border-box;}
    .hero-cut>div{display:flex;align-items:center;justify-content:space-between;gap:14px;}
    .hero-type{font-family:var(--deep-read-sans);letter-spacing:.24em;color:var(--deep-read-accent);font-size:10px;}
    .hero-source{font-family:var(--deep-read-sans);letter-spacing:.18em;color:var(--deep-read-muted);font-size:10px;white-space:nowrap;}
    figcaption{font-size:9px;color:var(--deep-read-muted);line-height:1.45;margin:10px 0 0;text-align:right;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;}
    .headline,section{padding:0 22px;}
    .kicker,.section,.date,.holder,small{font-family:var(--deep-read-sans);letter-spacing:.18em;text-transform:uppercase;color:var(--deep-read-muted);font-size:10px;}
    h1{font-weight:500;font-size:32px;line-height:1.13;margin:12px 0 16px;}
    h2{font-weight:650;font-size:18px;line-height:1.34;margin:0 0 6px;}
    p{font-size:15px;line-height:1.68;margin:0 0 13px;}
    .summary{font-size:15px;line-height:1.68;}
    section{margin-top:28px;}
    .timeline{display:grid;grid-template-columns:32px 1fr;gap:10px;padding:11px 0;border-top:1px solid var(--deep-read-border);}
    .num{font-family:var(--deep-read-sans);color:var(--deep-read-accent);letter-spacing:.12em;font-size:12px;padding-top:4px;}
    .timeline-item{display:grid;grid-template-columns:32px minmax(0,1fr);gap:10px;padding:11px 0;border-top:1px solid var(--deep-read-border);}
    .timeline-marker{width:18px;height:18px;border-radius:50%;border:1px solid var(--deep-read-accent);margin-top:3px;}
    .timeline-body{min-width:0;}
    .timeline-date{font-family:var(--deep-read-sans);letter-spacing:.18em;text-transform:uppercase;color:var(--deep-read-accent);font-size:10px;margin-bottom:4px;}
    .core-point{padding:12px 0;border-top:1px solid var(--deep-read-border);}
    .inline{margin:12px 0 4px;background:var(--deep-read-surface);}
    .inline img{display:block;width:100%;aspect-ratio:16/9;object-fit:cover;}
    .inline figcaption{text-align:left;margin:7px 9px 9px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
    .timeline-item figure,.core-point figure{margin:12px 0 4px;background:var(--deep-read-surface);}
    .timeline-item img,.core-point img{display:block;width:100%;aspect-ratio:16/9;object-fit:cover;}
    .timeline-item figcaption,.core-point figcaption{text-align:left;margin:7px 9px 9px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
    .diagram-block{padding:0 22px;margin-top:30px;}
    .diagram-block h2{margin:4px 0 14px;font-size:18px;}
    .diagram-frame{background:var(--deep-read-surface);border-top:1px solid var(--deep-read-border);border-bottom:1px solid var(--deep-read-border);padding:8px 12px 10px;}
    .diagram-steps{list-style:none;margin:0;padding:0;}
    .diagram-step{display:grid;grid-template-columns:34px minmax(0,1fr);gap:10px;padding:12px 0;border-top:1px solid rgba(107,114,128,.18);}
    .diagram-step:first-child{border-top:0;}
    .diagram-step-index{font-family:var(--deep-read-sans);font-size:11px;letter-spacing:.12em;color:var(--deep-read-accent);padding-top:2px;}
    .diagram-grid{display:grid;grid-template-columns:1fr;gap:8px;margin:0;}
    .diagram-card{background:var(--deep-read-bg);border:1px solid var(--deep-read-border);padding:11px 12px;}
    .diagram-step h3,.diagram-card h3{font-size:15px;line-height:1.42;margin:0 0 5px;font-weight:500;}
    .diagram-step p,.diagram-card p{font-family:var(--deep-read-sans);font-size:12px;line-height:1.58;color:var(--deep-read-muted);margin:0;}
    .diagram-group{display:block;font-family:var(--deep-read-sans);letter-spacing:.14em;text-transform:uppercase;color:var(--deep-read-accent);font-size:9px;margin-bottom:4px;}
    .diagram-relations{list-style:none;margin:10px 0 0;padding:8px 0 0;border-top:1px solid rgba(107,114,128,.18);}
    .diagram-relations li{font-family:var(--deep-read-sans);font-size:11px;line-height:1.55;color:var(--deep-read-muted);margin:4px 0;}
    .diagram-relations b{font-weight:500;color:var(--deep-read-accent);margin:0 5px;}
    .diagram-caption{font-family:var(--deep-read-sans);font-size:11px;line-height:1.5;color:var(--deep-read-muted);margin:10px 0 0;}
    blockquote{font-size:18px;line-height:1.48;margin:0 0 16px;padding-left:12px;border-left:3px solid var(--deep-read-accent);}
    .perspective{margin:0 0 26px;}
    .perspective .holder{display:block;margin:0 0 13px;}
    .quote{margin:0 0 18px;padding:0 0 0 14px;border-left:2px solid var(--deep-read-border);}
    .quote .quote-text{font-size:15px;line-height:1.68;margin:0;color:inherit;}
    .quote .quote-attribution{display:block;font-family:var(--deep-read-sans);font-size:11px;letter-spacing:.06em;color:var(--deep-read-muted);margin-top:6px;}
    .reading{display:grid;grid-template-columns:30px 1fr;gap:10px;border-top:1px solid var(--deep-read-border);padding:10px 0;text-decoration:none;color:inherit;}
    .reading span{font-family:var(--deep-read-sans);color:var(--deep-read-accent);font-size:12px;letter-spacing:.12em;}
    .reading p{font-size:13px;line-height:1.45;margin-bottom:2px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
    .reading small{letter-spacing:.08em;font-size:9px;}
    .reading-link{display:block;border-top:1px solid var(--deep-read-border);padding:10px 0;text-decoration:none;color:inherit;}
    .reading-link p{font-size:13px;line-height:1.45;margin-bottom:2px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;}
    .reading-link small{font-family:var(--deep-read-sans);letter-spacing:.08em;text-transform:uppercase;color:var(--deep-read-muted);font-size:9px;}
    """

    private static let runtimeCSS = """
    @keyframes deepReadPulse{0%,100%{opacity:.36}50%{opacity:.76}}
    .markdown-body>:first-child{margin-top:0;}
    .markdown-body>:last-child{margin-bottom:0;}
    .markdown-body strong,.markdown-body b{font-weight:650;color:inherit;}
    .markdown-body em,.markdown-body i{font-style:italic;}
    .markdown-body s,.markdown-body del{text-decoration:line-through;}
    .markdown-body h2,.markdown-body h3,.markdown-body h4{font-weight:500;line-height:1.35;margin:14px 0 7px;}
    .markdown-body h2{font-size:18px;}
    .markdown-body h3{font-size:16px;}
    .markdown-body h4{font-size:15px;}
    .markdown-body ul,.markdown-body ol{font-size:15px;line-height:1.68;margin:0 0 13px 1.25em;padding:0;}
    .markdown-body li{margin:0 0 6px;padding-left:2px;}
    .markdown-body li>p{margin:0 0 6px;}
    .markdown-body code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:.88em;background:rgba(107,114,128,.12);padding:0 .22em;border-radius:4px;}
    .markdown-body pre{overflow:auto;background:var(--deep-read-surface);padding:10px 12px;border-radius:10px;margin:0 0 13px;}
    .markdown-body pre code{background:transparent;padding:0;border-radius:0;}
    .markdown-body a{color:var(--deep-read-accent);text-decoration:none;border-bottom:1px solid currentColor;}
    .markdown-body .table-wrap{overflow-x:auto;-webkit-overflow-scrolling:touch;background:linear-gradient(to right,var(--deep-read-bg) 30%,transparent) left/28px 100% no-repeat local,linear-gradient(to left,var(--deep-read-bg) 30%,transparent) right/28px 100% no-repeat local,radial-gradient(farthest-side at 0 50%,rgba(0,0,0,.16),transparent) left/12px 100% no-repeat scroll,radial-gradient(farthest-side at 100% 50%,rgba(0,0,0,.16),transparent) right/12px 100% no-repeat scroll;}
    .markdown-body .table-wrap table{width:max-content;min-width:100%;}
    .markdown-body .table-wrap td,.markdown-body .table-wrap th{min-width:7.5em;max-width:15em;}
    .markdown-body .table-wrap td:first-child,.markdown-body .table-wrap th:first-child{min-width:4.5em;max-width:7em;}
    .markdown-body .table-hint{font-family:var(--deep-read-sans);font-size:11px;color:var(--deep-read-muted);margin:0 0 6px;text-align:right;}
    .markdown-body table{width:100%;border-collapse:collapse;font-family:var(--deep-read-sans);font-size:12px;line-height:1.5;margin:0 0 13px;}
    .markdown-body th,.markdown-body td{border-top:1px solid var(--deep-read-border);padding:7px 6px;text-align:left;vertical-align:top;}
    .markdown-body blockquote{margin:0 0 13px;padding-left:10px;border-left:2px solid var(--deep-read-accent);font-size:15px;line-height:1.68;}
    blockquote.markdown-body p{font-size:18px;line-height:1.48;}
    .diagram-note.markdown-body p{font-family:var(--deep-read-sans);font-size:12px;line-height:1.58;color:var(--deep-read-muted);margin:0;}
    .diagram-step .diagram-next{font-family:var(--deep-read-sans);font-size:11px;line-height:1.5;color:var(--deep-read-accent);margin:6px 0 0;}
    .bottom-line{font-size:18px;line-height:1.5;font-weight:650;margin:0 0 14px;}
    .cite{font-family:var(--deep-read-sans);font-size:10px;font-weight:500;color:var(--deep-read-accent);margin-left:3px;vertical-align:super;line-height:0;}
    .timeline-why{font-family:var(--deep-read-sans);font-size:12px;line-height:1.55;color:var(--deep-read-accent);margin:6px 0 0;}
    .perspective .interest{font-family:var(--deep-read-sans);font-size:12px;line-height:1.55;color:var(--deep-read-muted);margin:-6px 0 8px;}
    .perspective .quote{margin:12px 0 0;}
    .impacts{list-style:none;margin:0 0 16px;padding:0;}
    .impacts li{padding:10px 0;border-top:1px solid var(--deep-read-border);}
    .impacts p{margin:0;}
    .impacts .impact-target{font-weight:650;margin-bottom:3px;}
    .impacts small{margin-left:8px;color:var(--deep-read-accent);}
    .watch ul{margin:8px 0 0 1.25em;padding:0;}
    .watch li{font-size:15px;line-height:1.6;margin:0 0 6px;}
    .claim-status{font-family:var(--deep-read-sans);font-size:10px;letter-spacing:.06em;color:var(--deep-read-accent);border:1px solid var(--deep-read-accent);border-radius:4px;padding:0 5px;margin-right:6px;white-space:nowrap;}
    .src-no{font-family:var(--deep-read-sans);color:var(--deep-read-accent);margin-right:6px;}
    .timeline-item.highlight .timeline-marker{background:var(--deep-read-accent);}
    .timeline-item.highlight .timeline-date{font-weight:700;}
    .uncertain ul{list-style:none;margin:0;padding:12px 14px;border:1px dashed var(--deep-read-border);border-radius:12px;background:var(--deep-read-surface);}
    .uncertain li{position:relative;padding-left:22px;font-size:14px;line-height:1.6;margin:0 0 8px;}
    .uncertain li:last-child{margin-bottom:0;}
    .uncertain li::before{content:"?";position:absolute;left:0;top:2px;width:15px;height:15px;border-radius:50%;
    border:1px solid var(--deep-read-accent);color:var(--deep-read-accent);font-family:var(--deep-read-sans);font-size:10px;font-weight:700;line-height:15px;text-align:center;}
    """

    /// App-bundled fonts served via the `amberfont://` scheme handler
    /// (IOSDeepReadFontSchemeHandler): Noto Serif SC for the body (matches Android),
    /// JetBrains Mono for code. Prepended to the serif / code font stacks; the system
    /// fonts remain as fallbacks.
    private static let bundledFontCSS = """
    @font-face{font-family:"AmberDeepReadSerif";src:url("amberfont://deepread/serif.otf") format("opentype");font-weight:200 900;font-style:normal;font-display:swap;}
    @font-face{font-family:"AmberDeepReadMono";src:url("amberfont://deepread/mono.ttf") format("truetype");font-weight:400;font-style:normal;font-display:swap;}
    :root{--deep-read-serif:"AmberDeepReadSerif","Noto Serif SC","Source Han Serif SC","Songti SC",serif;}
    .markdown-body code,.markdown-body pre code{font-family:"AmberDeepReadMono",ui-monospace,SFMono-Regular,Menlo,monospace;}
    """

    // (No separate dark stylesheet: the injected canvas palette — already resolved for the
    //  current appearance by the caller — drives every var(--deep-read-*), light or dark.)

    private static let emptyImageFallbackCSS = """
    img:not([src]),img[src=""]{display:none!important;}
    figure:has(> img:not([src])),figure:has(> img[src=""]){display:none!important;}
    """
}
