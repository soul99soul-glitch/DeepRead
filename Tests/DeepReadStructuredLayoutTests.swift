import XCTest
@testable import AmberDeepRead

final class DeepReadStructuredLayoutTests: XCTestCase {
    private func output(topicType: String = "event") throws -> IOSDeepReadOutput {
        let json = """
        {"topic_type":"\(topicType)","bottom_line":"一句话结论","summary":"导语",
         "timeline":[{"date":"10月1日","event":"起因","is_highlight":false},{"date":"10月2日","event":"转折","is_highlight":true,"why":"改变了走向"}],
         "core_points":[{"point":"判断一","supporting":"依据","sources":[1,7]},{"point":"判断二","supporting":"依据"}],
         "diagram":{"type":"causal_chain","title":"因果","nodes":[{"id":"a","label":"原因"},{"id":"b","label":"经过"},{"id":"c","label":"结果"}],
                    "edges":[{"from":"a","to":"b","label":"引发"},{"from":"b","to":"c"},{"from":"a","to":"c","label":"间接影响"}]},
         "analysis":{"core_dispute":"争什么？","perspectives":[{"viewpoint":"观点","holder":"甲方","interest":"保住份额","quote":"原话","quote_by":"甲方代表","sources":[2]}]},
         "impacts":[{"target":"用户","horizon":"long","effect":"价格上涨"}],
         "watch":["下月听证会"],
         "uncertainties":[{"claim":"比赛性质仍待足协确认","status":"pending_official"}],
         "sources":[{"title":"甲文","url":"https://example.com/a","source":"example.com"},{"title":"乙文","url":"","source":"文件"}]}
        """
        return try JSONDecoder().decode(IOSDeepReadOutput.self, from: Data(json.utf8))
    }

    private func html(_ output: IOSDeepReadOutput, order: [IOSDeepReadStructuredRenderer.Section]? = nil) -> String {
        IOSDeepReadEditorialRenderer.renderHTML(.init(title: "标题", markdown: "", structured: output,
                                                      sectionOrder: order ?? IOSDeepReadStructuredRenderer.Section.order(topicType: output.topicType)))
    }

    private func section(_ title: String) -> String { #"<p class="section">\#(title)</p>"# }

    private func assertOrder(_ page: String, _ needles: [String], file: StaticString = #filePath, line: UInt = #line) throws {
        let positions = try needles.map { try XCTUnwrap(page.range(of: $0), $0, file: file, line: line).lowerBound }
        XCTAssertEqual(positions, positions.sorted(), "\(needles)", file: file, line: line)
    }

    func testHeadlineLeadsWithConclusionThenJudgments() throws {
        let page = html(try output())
        try assertOrder(page, [#"<p class="bottom-line">一句话结论</p>"#, "导语", section("关键判断"), section("时间轴")])
        XCTAssertEqual(page.components(separatedBy: "判断一").count - 1, 1, "judgments are listed once")
    }

    func testTopicTypeChoosesWhichQuestionUnfoldsFirst() throws {
        try assertOrder(html(try output(topicType: "event")), [section("关键判断"), section("时间轴"), section("各方立场")])
        try assertOrder(html(try output(topicType: "opinion")), [section("关键判断"), section("各方立场"), section("影响与走向"), section("时间轴")])
        try assertOrder(html(try output(topicType: "product")), [section("关键判断"), #"<section class="diagram-block">"#, section("各方立场"), section("时间轴")])
    }

    func testExplicitLayoutOverridesTopicOrder() throws {
        let page = html(try output(), order: DeepReadReaderLayout.debate.order(topicType: "event"))
        try assertOrder(page, [section("各方立场"), section("时间轴")])
    }

    func testCitationsDropIdsOutsideTheSourceList() throws {
        let page = html(try output())
        XCTAssertTrue(page.contains(#"判断一<sup class="cite">[1]</sup></h2>"#), "id 7 is not in the two-source list")
        XCTAssertTrue(page.contains(#"<sup class="cite">[2]</sup>"#))
    }

    func testPerspectiveCarriesInterestAndItsOwnQuote() throws {
        let page = html(try output())
        try assertOrder(page, [#"<p class="holder">甲方</p>"#, #"<p class="interest">诉求：保住份额</p>"#, "观点", #"<p class="quote-text">原话</p>"#, "—— 甲方代表"])
    }

    func testOutlookShowsImpactsAndWatchList() throws {
        let page = html(try output())
        XCTAssertTrue(page.contains(#"<li><p class="impact-target">用户<small>长期</small></p><p>价格上涨</p></li>"#))
        XCTAssertTrue(page.contains("<li>下月听证会</li>"))
    }

    func testTurningPointShowsWhy() throws {
        let page = html(try output())
        XCTAssertEqual(page.components(separatedBy: #"class="timeline-item highlight""#).count - 1, 1)
        XCTAssertTrue(page.contains(#"<p class="timeline-why">转折：改变了走向</p>"#))
    }

    func testUncertaintiesShowStatusAndRenderAfterAnalysisInEveryLayout() throws {
        let data = try output()
        for layout in DeepReadReaderLayout.allCases {
            let page = html(data, order: layout.order(topicType: data.topicType))
            try assertOrder(page, [section("各方立场"), section("待核实")])
            XCTAssertTrue(page.contains(#"<li><span class="claim-status">待官方确认</span>比赛性质仍待足协确认</li>"#))
        }
    }

    func testSourcesAreNumberedAndMarkCitedOnes() throws {
        let page = html(try output())
        XCTAssertTrue(page.contains(#"<a class="reading-link" href="https://example.com/a"><p><span class="src-no">[1]</span>甲文</p><small>example.com · 本文引用</small></a>"#))
        XCTAssertTrue(page.contains(#"<div class="reading-link"><p><span class="src-no">[2]</span>乙文</p><small>文件 · 本文引用</small></div>"#))
    }

    func testLegacyArticlesKeepTheirQuotesImplicationsAndLinks() throws {
        let json = """
        {"summary":"导语","key_entities":["甲方"],
         "analysis":{"core_dispute":"争议","perspectives":[{"viewpoint":"观点","holder":"甲方"}],"implications":"旧影响段落","quotes":[{"text":"旧原话","attribution":"某人"}]},
         "uncertainties":["旧式待核实"],
         "references":[{"title":"旧来源","url":"https://example.com/r","source":"站点"}]}
        """
        let legacy = try JSONDecoder().decode(IOSDeepReadOutput.self, from: Data(json.utf8))
        let page = html(legacy)
        XCTAssertTrue(page.contains(#"<p class="quote-text">旧原话</p>"#))
        try assertOrder(page, [section("影响与走向"), "旧影响段落"])
        XCTAssertTrue(page.contains("<li>旧式待核实</li>"))
        XCTAssertTrue(page.contains(section("参考来源")))
    }

    func testStepDiagramKeepsOnlyNonAdjacentRelations() throws {
        let page = html(try output())
        XCTAssertTrue(page.contains(#"<p class="diagram-next">↓ 引发</p>"#))
        // a→b and b→c are implied by the step order; only a→c remains as a relation.
        let relations = try XCTUnwrap(page.range(of: #"<ul class="diagram-relations">"#).map { page[$0.upperBound...] })
        let list = relations[..<(relations.range(of: "</ul>")?.lowerBound ?? relations.endIndex)]
        XCTAssertEqual(list.components(separatedBy: "<li>").count - 1, 1)
        XCTAssertTrue(page.contains("间接影响"))
    }

    func testNewFieldsSurviveStageMerge() {
        var analysisStage = IOSDeepReadOutput()
        analysisStage.uncertainties = [IOSDeepReadUncertainty(claim: "待确认")]
        analysisStage.impacts = [IOSDeepReadImpact(target: "用户", horizon: "short", effect: "变化")]
        analysisStage.watch = ["关注"]
        var overview = IOSDeepReadOutput()
        overview.bottomLine = "结论"
        let merged = overview.merged(with: analysisStage).merged(with: IOSDeepReadOutput())
        XCTAssertEqual(merged.bottomLine, "结论")
        XCTAssertEqual(merged.uncertainties.map(\.claim), ["待确认"])
        XCTAssertEqual(merged.impacts.count, 1)
        XCTAssertEqual(merged.watch, ["关注"])
        XCTAssertTrue(analysisStage.hasStructuredBody, "an outlook-only stage still counts as content")
    }

    func testReferencesAloneCountAsStructuredBody() {
        var output = IOSDeepReadOutput()
        output.references = [IOSDeepReadLink(title: "来源", url: "https://example.com")]
        XCTAssertTrue(output.hasStructuredBody)
    }

    func testJudgmentKeepsInlineMarkupAndEscapeCombiningQuotes() throws {
        var data = try output()
        data.corePoints[0].point = "用 **AI** 做事"
        XCTAssertTrue(html(data).contains("<h2>用 <strong>AI</strong> 做事<sup"))
        XCTAssertFalse(IOSDeepReadEditorialRenderer.esc("x\"\u{301}y").contains("\""), "a quote with a combining mark is still escaped")
    }
}
