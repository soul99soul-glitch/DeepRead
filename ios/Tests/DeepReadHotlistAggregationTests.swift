import XCTest
@testable import AmberDeepRead

final class DeepReadHotlistAggregationTests: XCTestCase {
    func testBridgeMatchesFirstClusterAndLaterSourcesMatchItsMembers() {
        let topics = aggregate(["abcdef", "ghijkl", "abcdefghijkl", "ghijklm"])
        XCTAssertEqual(topics.count, 2)
        XCTAssertEqual(topics[0].sources.map(\.rank), [1, 3, 4])
        XCTAssertEqual(topics[1].sources.map(\.rank), [2])
    }

    func testShortDuplicatesMatchButEmptyNormalizedTitlesStaySeparate() {
        let topics = aggregate(["AI", "ai!!", "A", "A", "B", "", "!!!"])
        XCTAssertEqual(topics.map { $0.sources.map(\.rank) }, [[1, 2], [3, 4], [5], [6], [7]])
    }

    func testTwoEntitiesMatchAcrossLanguages() {
        let topics = aggregate(["苹果推出人工智能服务", "Apple AI launch", "华为发布新设备"])
        XCTAssertEqual(topics.map { $0.sources.map(\.rank) }, [[1, 2], [3]])
    }

    func testSingleSharedEntityDoesNotMergeUnrelatedTitles() {
        let topics = aggregate(["Tesla quantum cosmology", "Tesla culinary traditions"])
        XCTAssertEqual(topics.count, 2)
    }

    func testTranslatedTitleMatchingRetainsOriginalSourceAndStableTopicID() {
        var first = item("Original English headline", rank: 1)
        first.displayTitle = "abcdef"
        var second = item("Another English headline", rank: 2)
        second.displayTitle = "ABCDEF!!"
        let snapshots = [IOSHotListProviderSnapshot(
            providerId: "source", providerName: "Source", items: [first, second], fetchedAt: 10
        )]
        let topics = IOSHotListAggregator.aggregate(providerSnapshots: snapshots)
        XCTAssertEqual(topics.count, 1)
        XCTAssertEqual(topics[0].title, "abcdef")
        XCTAssertEqual(topics[0].sources.map(\.title), [first.title, second.title])
        XCTAssertEqual(topics[0].id, "03671df123fe67a31f4a0a64")
        XCTAssertTrue(IOSHotListAggregator.aggregate(providerSnapshots: snapshots, limit: 0).isEmpty)
    }

    private func aggregate(_ titles: [String]) -> [IOSHotTopic] {
        IOSHotListAggregator.aggregate(providerSnapshots: [IOSHotListProviderSnapshot(
            providerId: "source", providerName: "Source",
            items: titles.enumerated().map { item($0.element, rank: $0.offset + 1) }, fetchedAt: 10
        )], limit: titles.count)
    }

    private func item(_ title: String, rank: Int) -> IOSHotlistItem {
        IOSHotlistItem(providerId: "source", title: title, url: nil, rank: rank, score: nil, fetchedAt: 10)
    }
}
