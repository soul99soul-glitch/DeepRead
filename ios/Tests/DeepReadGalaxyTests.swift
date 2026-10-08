import XCTest
@testable import AmberDeepRead

final class DeepReadGalaxyTests: XCTestCase {
    private func debateArticle(label: String = "体系崩盘论") -> DeepReadTemplateArticle {
        var article = DeepReadTemplateArticle(template: DeepReadSynthesisTemplate.debate.rawValue, title: "标题", lede: "导语",
                                              sources: [.init(id: 1, title: "来源", url: nil, site: "观察者网")])
        article.debate = .init(dispute: "争议", camps: [
            .init(stance: "pro", label: label, holders: ["甲"], argument: "论点", quote: "", quoteBy: "", sources: [1]),
            .init(stance: "con", label: "不宜定论", holders: [], argument: "论点二", quote: "原话", quoteBy: "某人", sources: [1]),
        ], takeaway: "怎么看")
        return article
    }

    func testServesOnlyWhitelistedBundleFilesAndArticle() {
        let ok = ["index.html", "main.js", "shaders.js", "vendor/three.module.min.js", "article.js"]
        for path in ok {
            XCTAssertEqual(DeepReadGalaxySchemeHandler.resourcePath(for: URL(string: "deepread-galaxy://app/\(path)")!), path)
        }
        for bad in ["deepread-galaxy://app/../Info.plist", "deepread-galaxy://app/vendor/../../tasks.json",
                    "deepread-galaxy://other/main.js", "https://app/main.js", "deepread-galaxy://app/Fonts/noto_serif_sc.otf"] {
            XCTAssertNil(DeepReadGalaxySchemeHandler.resourcePath(for: URL(string: bad)!), bad)
        }
    }

    func testArticleModuleIsJSONDataThatRoundTrips() throws {
        let hostile = "</script><img src=x onerror=alert(1)>\"; alert(2); //"
        let script = DeepReadGalaxySchemeHandler.articleScript(for: debateArticle(label: hostile))
        XCTAssertTrue(script.hasPrefix("export const article = {"))
        XCTAssertTrue(script.hasSuffix(";\n"))
        let json = script.dropFirst("export const article = ".count).dropLast(2)
        let decoded = try JSONDecoder().decode(DeepReadTemplateArticle.self, from: Data(json.utf8))
        XCTAssertEqual(decoded.debate?.camps.first?.label, hostile)
    }

    func testEntryCardOnlyOnDebateReadingsAndNotInPrint() {
        let palette = DeepReadCloseReadingRenderer.Palette(accent: "#C0392B", bg: "#FFFFFF", fg: "#111111", surface: "#F5F5F5",
                                                           muted: "#666666", border: "#DDDDDD", dark: false)
        func html(_ a: DeepReadTemplateArticle, entry: Bool) -> String {
            DeepReadTemplateArticleRenderer.html(a, palette: palette, fontMode: "serif", styleCSS: "", scale: 1, galaxyEntry: entry)
        }
        XCTAssertTrue(html(debateArticle(), entry: true).contains(DeepReadGalaxySchemeHandler.entryLink))
        XCTAssertFalse(html(debateArticle(), entry: false).contains(DeepReadGalaxySchemeHandler.entryLink))
        var brief = debateArticle(); brief.debate = nil
        brief.brief = .init(points: ["一"], background: "", impact: "", uncertain: [])
        XCTAssertFalse(html(brief, entry: true).contains(DeepReadGalaxySchemeHandler.entryLink))
    }

    func testEasterEggIsStablePerReadingAndRoughlyOneInFive() {
        let ids = (0..<5000).map { _ in UUID().uuidString }
        for id in ids.prefix(50) { XCTAssertEqual(DeepReadGalaxyEgg.appears(for: id), DeepReadGalaxyEgg.appears(for: id)) }
        let share = Double(ids.filter(DeepReadGalaxyEgg.appears(for:)).count) / Double(ids.count)
        XCTAssertEqual(share, 0.2, accuracy: 0.03)
        // FNV-1a reference values (computed outside Swift): unlike hashValue, the answer survives relaunches.
        XCTAssertTrue(DeepReadGalaxyEgg.appears(for: "reading-2"))
        XCTAssertFalse(DeepReadGalaxyEgg.appears(for: "reading-0"))
    }
}
