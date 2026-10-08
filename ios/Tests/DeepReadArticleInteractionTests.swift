import XCTest
@testable import AmberDeepRead

final class DeepReadArticleInteractionTests: XCTestCase {
    func testImagesBecomeTapTargetsUnlessAlreadyLinked() {
        let html = #"<html><head><title>t</title></head><body><img src="https://a.com/1.jpg?x=1&amp;y=2"/><a href="https://b.com"><img src="https://b.com/2.jpg"></a><img src=""><img src='data:image/png;base64,AAA'></body></html>"#
        let prepared = DeepReadArticleDocument.prepare(html)
        XCTAssertEqual(prepared.images, ["https://a.com/1.jpg?x=1&y=2", "data:image/png;base64,AAA"])
        XCTAssertTrue(prepared.html.contains(#"<a class="dr-zoom" href="https://deepread.amber.local/image/0"><img src="https://a.com/1.jpg?x=1&amp;y=2"/></a>"#))
        XCTAssertTrue(prepared.html.contains(#"<a href="https://b.com"><img src="https://b.com/2.jpg"></a>"#))
        XCTAssertTrue(prepared.html.contains(#"<a class="dr-zoom" href="https://deepread.amber.local/image/1">"#))
        XCTAssertEqual(DeepReadArticleDocument.imageIndex(for: URL(string: "https://deepread.amber.local/image/1")!), 1)
        XCTAssertNil(DeepReadArticleDocument.imageIndex(for: URL(string: "https://b.com/image/1")!))
    }

    func testViewportDisablesPageZoomAfterRendererViewport() {
        let prepared = DeepReadArticleDocument.prepare(#"<html><head><meta name="viewport" content="width=device-width, initial-scale=1"/></head><body></body></html>"#)
        let original = prepared.html.range(of: "initial-scale=1\"/>")!
        let override = prepared.html.range(of: "user-scalable=no")!
        XCTAssertLessThan(original.lowerBound, override.lowerBound, "the later viewport meta wins")
        XCTAssertTrue(prepared.html.contains("touch-action:manipulation"))
    }

    func testTapStreakRevealsRingAfterFiveAndCompletesAfterTenQuickTaps() {
        var streak = DeepReadTapStreak()
        var t = 0.0
        for _ in 0..<4 { streak.tap(at: t); t += 0.2 }
        XCTAssertNil(streak.progress)
        streak.tap(at: t); t += 0.2
        XCTAssertEqual(streak.progress, 0)
        for _ in 0..<4 { streak.tap(at: t); t += 0.2 }
        XCTAssertEqual(streak.progress!, 0.8, accuracy: 1e-9)
        XCTAssertFalse(streak.completed)
        streak.tap(at: t)
        XCTAssertTrue(streak.completed)
        XCTAssertEqual(streak.progress, 1)
    }

    func testPauseRestartsTheStreak() {
        var streak = DeepReadTapStreak()
        for i in 0..<7 { streak.tap(at: Double(i) * 0.2) }
        XCTAssertNotNil(streak.progress)
        streak.tap(at: 1.2 + DeepReadTapStreak.gap + 0.1)
        XCTAssertEqual(streak.count, 1)
        XCTAssertNil(streak.progress)
    }

    func testComparisonTableScrollsSidewaysWithHint() {
        let markdown = "| 项目 | A | B | C |\n|---|---|---|---|\n| 一 | 1 | 2 | 3 |\n"
        let html = IOSDeepReadEditorialRenderer.renderHTML(.init(title: "t", markdown: markdown))
        XCTAssertTrue(html.contains(#"<div class="table-wrap"><table>"#))
        XCTAssertTrue(html.contains("左右滑动查看"))
    }
}
