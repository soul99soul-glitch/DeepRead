import Foundation
import Markdown

/// 阅读内容的 Markdown 与纯文本导出。原文模式沿用阅读页的正文范围，
/// 纯文本转换使用 swift-markdown AST。
enum DeepReadTextExporter {
    static func markdown(for task: IOSDeepReadTask, originalOnly: Bool = false) -> String {
        guard originalOnly, let reading = DeepReadCloseReading.decode(task.structuredJSON) else {
            return task.resultMarkdown.hasPrefix("# ") ? task.resultMarkdown : "# \(task.title)\n\n\(task.resultMarkdown)"
        }
        let title = reading.originalTitle.isEmpty ? reading.title : reading.originalTitle
        var markdown = "# \(title)\n\n"
        for paragraph in reading.bodyParagraphs {
            switch paragraph.kind {
            case .heading: markdown += "## \(paragraph.text)\n\n"
            case .image: markdown += "![](\(paragraph.text))\n\n"
            case .text: markdown += "\(paragraph.text)\n\n"
            }
        }
        if let url = reading.url { markdown += "原文：[\(reading.site)](\(url))\n" }
        return markdown
    }

    static func text(from markdown: String) -> String {
        let document = Document(parsing: markdown)
        return render(document).trimmingCharacters(in: .newlines)
    }

    private static func render(_ node: some Markup) -> String {
        let children = Array(node.children)
        switch node {
        case is Document, is BlockQuote:
            return children.map { render($0) }.joined(separator: "\n\n")
        case let list as OrderedList:
            return children.enumerated().map { index, item in
                "\(list.startIndex + UInt(index)). " + render(item).replacingOccurrences(of: "\n", with: "\n  ")
            }.joined(separator: "\n")
        case let list as UnorderedList:
            return children.map { item in
                "- " + render(item).replacingOccurrences(of: "\n", with: "\n  ")
            }.joined(separator: "\n")
        case let item as ListItem:
            var prefix = ""
            if let checkbox = item.checkbox { prefix = (checkbox == .checked ? "[x]" : "[ ]") + " " }
            let hasBlocks = children.contains { isBlockElement($0) }
            return prefix + children.map { render($0) }.joined(separator: hasBlocks ? "\n" : "")
        case let table as Table:
            return children.map { render($0) }.joined(separator: "\n")
        case let body as Table.Body:
            return children.map { render($0) }.joined(separator: "\n")
        case let head as Table.Head:
            return head.children.compactMap { $0 as? Table.Cell }.map { render($0) }.joined(separator: "\t")
        case let row as Table.Row:
            return row.children.compactMap { $0 as? Table.Cell }.map { render($0) }.joined(separator: "\t")
        case let code as CodeBlock:
            return code.code.trimmingCharacters(in: .newlines)
        case let inline as InlineCode:
            return inline.code
        case let text as Text:
            return text.plainText
        case let html as HTMLBlock:
            return html.rawHTML.trimmingCharacters(in: .newlines)
        case is SoftBreak, is LineBreak:
            return "\n"
        case is ThematicBreak:
            return "---"
        default:
            // 强调/链接/图片等：只保留文字内容（与旧实现一致）。
            return children.map { render($0) }.joined()
        }
    }

    private static func isBlockElement(_ node: some Markup) -> Bool {
        switch node {
        case is Paragraph, is CodeBlock, is BlockQuote, is UnorderedList, is OrderedList:
            return true
        default:
            return false
        }
    }
}
