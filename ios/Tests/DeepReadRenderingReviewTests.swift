import XCTest
import PDFKit
@testable import AmberDeepRead

@MainActor
final class DeepReadRenderingReviewTests: XCTestCase {
    private func task(markdown: String, structuredJSON: String? = nil) -> IOSDeepReadTask {
        IOSDeepReadTask(id: "rendering-review", title: "审阅文章", status: .succeeded,
                        templateId: "custom:rendering-review", sources: [], resultMarkdown: markdown,
                        failureMessage: nil, createdAt: 0, updatedAt: 0, completedAt: 0, retryCount: 0,
                        structuredJSON: structuredJSON)
    }

    private func customHTML(_ task: IOSDeepReadTask) throws -> String {
        let template = IOSDeepReadCustomTemplate(name: "审阅模板", description: "",
                                                html: IOSDeepReadHTMLTemplateRenderer.starterHTML(), createdByAI: false)
        return try IOSDeepReadHTMLTemplateRenderer.render(task: task, template: template,
                                                         fontScale: 1, fontModeWireName: "system")
    }

    func testCustomTemplateUsesStructuredSummaryInsteadOfGeneratedDate() throws {
        var output = IOSDeepReadOutput()
        output.summary = "这是正文摘要。"
        let markdown = IOSDeepReadDraftGenerator.markdownFromStructured(output, title: "审阅文章", date: "2026-10-07")
        let json = String(decoding: try JSONEncoder().encode(output), as: UTF8.self)
        let html = try customHTML(task(markdown: markdown, structuredJSON: json))
        XCTAssertTrue(html.contains(#"<p class="summary">这是正文摘要。</p>"#))
        XCTAssertFalse(html.contains(#"<p class="summary">2026-10-07</p>"#))
    }

    func testPartialStructuredDraftDoesNotUseDateAsSummary() throws {
        var output = IOSDeepReadOutput()
        output.timeline = [.init(date: "10月7日", event: "保留已完成的时间线。")]
        output.analysis.coreDispute = "保留已完成的深度分析。"
        let date = "2026-10-07 12:00"
        let markdown = IOSDeepReadDraftGenerator.markdownFromStructured(output, title: "审阅文章", date: date)
        XCTAssertTrue(output.hasStructuredBody)
        XCTAssertFalse(markdown.contains("## 摘要"))
        let json = String(decoding: try JSONEncoder().encode(output), as: UTF8.self)
        let html = try customHTML(task(markdown: markdown, structuredJSON: json))
        XCTAssertTrue(html.contains(#"<p class="summary">暂无摘要。</p>"#))
        XCTAssertFalse(html.contains("<p class=\"summary\">\(date)</p>"))
        XCTAssertTrue(html.contains("保留已完成的时间线。"))
        XCTAssertTrue(html.contains("保留已完成的深度分析。"))
    }

    func testCustomTemplateFindsSummaryInLegacyGeneratedMarkdown() throws {
        let json = String(decoding: try JSONEncoder().encode(IOSDeepReadOutput()), as: UTF8.self)
        for structuredJSON in [nil, json] {
            let html = try customHTML(task(markdown: "# 审阅文章\n\n2026-10-07\n\n## 摘要\n这是旧文章摘要。\n\n## 背景\n正文",
                                           structuredJSON: structuredJSON))
            XCTAssertTrue(html.contains(#"<p class="summary">这是旧文章摘要。</p>"#))
        }
    }

    func testCustomTemplateKeepsFirstParagraphForUnstructuredMarkdown() throws {
        let html = try customHTML(task(markdown: "# 审阅文章\n\n普通文章的第一段。\n\n第二段。"))
        XCTAssertTrue(html.contains(#"<p class="summary">普通文章的第一段。</p>"#))
    }

    func testCustomTemplateRendersMarkdownBlocksAndKeepsResourcePolicy() throws {
        let markdown = """
        # 审阅文章

        **关键判断**见[原始来源](https://example.org/source)。

        > 引述原话

        3. 第三步
        4. 第四步

        ```swift
        let value = 1
        ```

        | 项目 | 参数 |
        | --- | --- |
        | 大小 | 10 |

        ![远程图片](https://example.org/image.png)

        <script>alert("untrusted")</script>
        """
        let html = try customHTML(task(markdown: markdown))
        XCTAssertTrue(html.contains("<strong>关键判断</strong>"))
        XCTAssertTrue(html.contains(#"<a href="https://example.org/source">原始来源</a>"#))
        XCTAssertTrue(html.contains("<blockquote><p>引述原话</p></blockquote>"))
        XCTAssertTrue(html.contains(#"<ol start="3">"#))
        XCTAssertTrue(html.contains("<pre><code>let value = 1"))
        XCTAssertTrue(html.contains("<th>项目</th>"))
        XCTAssertFalse(html.contains("<script>"))
        XCTAssertFalse(html.contains("<img"))
        let hardened = IOSDeepReadHTMLSecurity.hardenedDocument(html)
        XCTAssertTrue(hardened.contains("img-src data:;"))
        XCTAssertTrue(hardened.contains("script-src 'none'"))
    }

    func testEditorialPreservesOrderedListStart() {
        XCTAssertEqual(IOSDeepReadEditorialRenderer.markdownToHTML("3. 第三步\n4. 第四步"),
                       #"<ol start="3"><li>第三步</li><li>第四步</li></ol>"#)
        XCTAssertEqual(IOSDeepReadEditorialRenderer.markdownToHTML("1. 第一步\n2. 第二步"),
                       "<ol><li>第一步</li><li>第二步</li></ol>")
    }

    func testOriginalSelectionExportsOnlyTheOriginalBody() throws {
        let reading = DeepReadCloseReading(
            title: "精读标题", originalTitle: "原文标题", url: "https://example.org/original", site: "原始来源",
            genre: "general", guide: "AI 导读", focus: nil, bodyStart: 2, bodyEnd: 4, heroImageURL: nil,
            paragraphs: [.init(id: 1, kind: .text, text: "网页导航"),
                         .init(id: 2, kind: .heading, text: "正文标题"),
                         .init(id: 3, kind: .text, text: "原文正文 **强调**。"),
                         .init(id: 4, kind: .image, text: "https://example.org/original-image.png"),
                         .init(id: 5, kind: .text, text: "网页页脚")],
            notes: [.init(paragraph: 3, kind: .context, title: "批注标题", body: "AI 批注")])
        let article = task(markdown: DeepReadCloseReader.markdown(reading), structuredJSON: reading.encoded())
        let markdown = DeepReadTextExporter.markdown(for: article, originalOnly: true)
        XCTAssertTrue(markdown.hasPrefix("# 原文标题\n"))
        XCTAssertTrue(markdown.contains("## 正文标题"))
        XCTAssertTrue(markdown.contains("原文正文 **强调**。"))
        XCTAssertTrue(markdown.contains("![](https://example.org/original-image.png)"))
        for excluded in ["AI 导读", "AI 批注", "网页导航", "网页页脚"] {
            XCTAssertFalse(markdown.contains(excluded), excluded)
        }
        let text = DeepReadTextExporter.text(from: markdown)
        XCTAssertTrue(text.contains("原文标题"))
        XCTAssertTrue(text.contains("原文正文 强调。"))
        XCTAssertFalse(text.contains("AI 导读"))
        XCTAssertFalse(text.contains("AI 批注"))
        XCTAssertEqual(DeepReadTextExporter.markdown(for: article, originalOnly: false), article.resultMarkdown,
                       "close-reading selection must retain the existing guide and annotations")
    }

    func testReaderAllowsOnlyItsCurrentDocumentAnchors() throws {
        let base = try XCTUnwrap(URL(string: IOSDeepReadFontSchemeHandler.documentBaseURL))
        let first = try XCTUnwrap(URL(string: base.absoluteString + "#analysis"))
        let second = try XCTUnwrap(URL(string: base.absoluteString + "#sources"))
        XCTAssertTrue(DeepReadArticleWebView.Coordinator.isSameDocumentAnchor(first, currentURL: base))
        XCTAssertTrue(DeepReadArticleWebView.Coordinator.isSameDocumentAnchor(second, currentURL: first))
        for value in [base.absoluteString, "https://example.org/#analysis", "https://deepread.amber.local/other#analysis",
                      "https://deepread.amber.local/?request=other#analysis", "javascript:alert(1)"] {
            let url = try XCTUnwrap(URL(string: value))
            XCTAssertFalse(DeepReadArticleWebView.Coordinator.isSameDocumentAnchor(url, currentURL: base), value)
        }
        XCTAssertFalse(DeepReadArticleWebView.Coordinator.isSameDocumentAnchor(first, currentURL: nil))
        let template = IOSDeepReadHTMLTemplateRenderer.starterHTML()
            .replacingOccurrences(of: "<section>{{analysis_html}}</section>",
                                  with: ##"<a href="#analysis">目录</a><section id="analysis">{{analysis_html}}</section>"##)
        XCTAssertTrue(IOSDeepReadTemplateValidator.validateHTML(template).ok, "static templates permit local contents links")
    }

    func testTemplateSubstitutionLeavesArticlePlaceholderTokensLiteral() throws {
        var article = task(markdown: "Body literal {{title}} {{font_css}}.")
        article.title = "Title literal {{analysis_html}} {{font_css}}"
        let html = try customHTML(article)
        XCTAssertTrue(html.contains("<h1>Title literal {{analysis_html}} {{font_css}}</h1>"))
        XCTAssertTrue(html.contains("<p>Body literal {{title}} {{font_css}}.</p>"))
    }

    func testPDFEmbedsSelectedBundledReaderFonts() async throws {
        let html = IOSDeepReadEditorialRenderer.renderHTML(.init(title: "Font review", markdown: "Body sample.\n\n```swift\nlet value = 1\n```"))
        let data = try await IOSHTMLPDFRenderer.render(html: IOSHTMLPDFRenderer.printFriendly(html))
        let document = try XCTUnwrap(PDFDocument(data: data))
        XCTAssertGreaterThan(document.pageCount, 0)
        // PDF font dictionaries identify the font that was actually embedded, including subset prefixes.
        let pdf = String(decoding: data, as: UTF8.self)
        XCTAssertTrue(pdf.contains("NotoSerifSC"), "the selected serif must reach the exported PDF")
        XCTAssertTrue(pdf.contains("JetBrainsMono"), "code must retain the bundled monospace font")
    }

    func testPDFKeepsEveryColumnOfScrollableReaderTable() async throws {
        let markdown = """
        | Column1 | Column2 | Column3 | Column4 | Column5 | Column6 |
        | --- | --- | --- | --- | --- | --- |
        | Value1 | Value2 | Value3 | Value4 | Value5 | Value6 |
        """
        let html = IOSDeepReadEditorialRenderer.renderHTML(.init(title: "Table review", markdown: markdown, fontMode: "system"))
        let data = try await IOSHTMLPDFRenderer.render(html: IOSHTMLPDFRenderer.printFriendly(html))
        let document = try XCTUnwrap(PDFDocument(data: data))
        let text = try XCTUnwrap(document.string)
        let attachment = XCTAttachment(data: data, uniformTypeIdentifier: "com.adobe.pdf")
        attachment.name = "Scrollable reader table PDF"
        attachment.lifetime = .keepAlways
        add(attachment)
        for index in 1...6 {
            XCTAssertTrue(text.contains("Column\(index)"), "header \(index) must be visible in the printed table")
            XCTAssertTrue(text.contains("Value\(index)"), "value \(index) must be visible in the printed table")
        }
        XCTAssertFalse(text.contains("左右滑动查看"), "a PDF cannot scroll sideways")
    }
}
