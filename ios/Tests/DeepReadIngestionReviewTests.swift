import XCTest
@testable import AmberDeepRead

@MainActor
final class DeepReadIngestionReviewTests: XCTestCase {
    func testFreeSearchKeepsCaseSensitivePathsAndQueryValues() {
        let results = [
            IOSSearchResult(title: "Upper path", url: "https://Example.org/Article?id=A", snippet: "first"),
            IOSSearchResult(title: "Lower path", url: "https://example.org/article?id=A", snippet: "second"),
            IOSSearchResult(title: "Lower value", url: "https://example.org/Article?id=a", snippet: "third"),
            IOSSearchResult(title: "Same page", url: "http://www.example.org/Article?id=A", snippet: "duplicate")
        ]
        let merged = IOSFreeSearchAggregator.roundRobinMerge([.bing: results], order: [.bing], limit: 10)
        XCTAssertEqual(merged.map(\.title), ["Upper path", "Lower path", "Lower value"])
    }

    func testWebEntitiesDecodeExactlyOneLayer() {
        XCTAssertEqual(IOSSearchExecutor.decodeEntities("&amp;lt;tag&amp;gt; &amp;#65;"), "&lt;tag&gt; &#65;")
        XCTAssertEqual(IOSSearchExecutor.decodeEntities("&lt;tag&gt; &quot;x&quot; &apos;y&apos; &amp; &#65; &#x1F600;"), "<tag> \"x\" 'y' & A 😀")
    }

    func testDOCXEntitiesDecodeExactlyOneLayer() async throws {
        let xml = "<w:document><w:p><w:r><w:t>&amp;lt;tag&amp;gt; &amp;#65; &#65; &#x4E2D;</w:t></w:r></w:p></w:document>"
        let archive = zipEntry(Data(xml.utf8), method: 0, declaredSize: xml.utf8.count)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathExtension("docx")
        try archive.write(to: url)
        defer { try? FileManager.default.removeItem(at: url) }
        let source = try await DeepReadFileImporter.read(url: url)
        XCTAssertEqual(source.content, "&lt;tag&gt; &#65; A 中")
    }

    func testInlineImagesKeepTheirPlaceBetweenText() {
        let paragraphs = DeepReadCloseReader.segment("Before ![chart](https://example.org/chart.png) after.")
        XCTAssertEqual(paragraphs.map(\.kind), [.text, .image, .text])
        XCTAssertEqual(paragraphs.map(\.text), ["Before", "https://example.org/chart.png", "after."])
        XCTAssertEqual(paragraphs.map(\.id), [1, 2, 3])
        XCTAssertEqual(DeepReadCloseReader.segment("甲 ![chart](https://example.org/chart.png) 乙").map(\.text),
                       ["甲", "https://example.org/chart.png", "乙"])
    }

    func testLinkedImageKeepsTheImageInsteadOfItsOuterLink() {
        let paragraphs = DeepReadCloseReader.segment("[![Image 1: chart](https://example.org/chart.png)](https://example.org/article)")
        XCTAssertEqual(paragraphs.map(\.kind), [.image])
        XCTAssertEqual(paragraphs.map(\.text), ["https://example.org/chart.png"])
    }

    func testDirectHTMLResolvesImagesAgainstTheFinalPageURL() async throws {
        let transport = DirectPageTransport(html: """
        <article><p>Article body.</p>
        <img src="/root.png"><img src="../chart.png"><img src="//cdn.example.org/picture.png">
        <img src="https://cdn.example.org/full.png?x=1&amp;y=2"><img src="data:image/png;base64,AA">
        </article>
        """, finalURL: URL(string: "https://example.org/news/2026/article")!)
        let page = try await DeepReadCloseReader.fetch(url: "https://example.org/start", settings: Settings(searchBuiltinJinaEnabled: false), transport: transport)
        let images = DeepReadCloseReader.segment(page.text).filter { $0.kind == .image }.map(\.text)
        XCTAssertEqual(images, [
            "https://example.org/root.png", "https://example.org/news/chart.png",
            "https://cdn.example.org/picture.png", "https://cdn.example.org/full.png?x=1&y=2"
        ])
    }

    func testJinaFallbackUsesTheExistingVerifiedScrapeTransport() async throws {
        let transport = FakeIPTransport()
        let output = try await IOSSearchExecutor.execute(toolName: "scrape_web", toolInput: "https://example.org/article",
                                                       settings: Settings(searchBuiltinJinaEnabled: true), transport: transport)
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: Data(output.utf8)) as? [String: Any])
        XCTAssertEqual(object["content"] as? String, "Verified article body.")
        XCTAssertEqual(object["via"] as? String, "jina_reader")
        XCTAssertEqual(transport.verifiedHosts, ["example.org", "r.jina.ai"])
        XCTAssertEqual(transport.publicCalls, 0)
    }

    func testDOCXRejectsDeclaredExpandedSizeOverTheImportLimit() throws {
        let xml = Data(repeating: 65, count: DeepReadFileImporter.maxReadableBytes + 1)
        let compressed = try (xml as NSData).compressed(using: .zlib) as Data
        let archive = zipEntry(compressed, method: 8, declaredSize: xml.count)
        XCTAssertLessThan(archive.count, DeepReadFileImporter.maxReadableBytes)
        XCTAssertThrowsError(try DeepReadDocumentZipReader.entry(named: "word/document.xml", in: archive)) { error in
            XCTAssertEqual(error as? DocumentAccessError, .fileTooLarge)
        }
    }

    func testDOCXRejectsActualExpansionEvenWhenTheDirectoryUnderreportsSize() throws {
        let xml = Data(repeating: 65, count: DeepReadFileImporter.maxReadableBytes + 1)
        let compressed = try (xml as NSData).compressed(using: .zlib) as Data
        let archive = zipEntry(compressed, method: 8, declaredSize: 0)
        XCTAssertThrowsError(try DeepReadDocumentZipReader.entry(named: "word/document.xml", in: archive)) { error in
            XCTAssertEqual(error as? DocumentAccessError, .fileTooLarge)
        }
    }

    private struct DirectPageTransport: IOSSearchHTTPTransport {
        let html: String
        let finalURL: URL

        func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
            (HTTPURLResponse(url: finalURL, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "text/html"])!, Data(html.utf8))
        }
    }

    private final class FakeIPTransport: IOSSearchHTTPTransport, IOSVerifiedHTTPScrapeTransport {
        var verifiedHosts: [String] = []
        var publicCalls = 0

        func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
            throw IOSSearchExecutorError.disallowedURL("host resolves to a non-public address")
        }

        func sendPublic(_ request: URLRequest, maximumResponseBytes: Int) async throws -> (HTTPURLResponse, Data) {
            publicCalls += 1
            throw IOSSearchExecutorError.disallowedURL("host resolves to a non-public address")
        }

        func sendVerifiedHTTPSGET(_ request: URLRequest, maximumResponseBytes: Int) async throws -> (HTTPURLResponse, Data) {
            let url = try XCTUnwrap(request.url)
            verifiedHosts.append(url.host ?? "")
            let jina = url.host == "r.jina.ai"
            return (HTTPURLResponse(url: url, statusCode: jina ? 200 : 403, httpVersion: "HTTP/1.1", headerFields: nil)!,
                    Data((jina ? "Title: Article\nMarkdown Content:\nVerified article body." : "Forbidden").utf8))
        }
    }

    /// One normal ZIP entry; declaredSize can model an untrusted central-directory value.
    private func zipEntry(_ payload: Data, method: UInt16, declaredSize: Int) -> Data {
        let name = Data("word/document.xml".utf8)
        var archive = Data()
        func append16(_ value: UInt16) { archive.append(UInt8(truncatingIfNeeded: value)); archive.append(UInt8(truncatingIfNeeded: value >> 8)) }
        func append32(_ value: UInt32) { append16(UInt16(truncatingIfNeeded: value)); append16(UInt16(truncatingIfNeeded: value >> 16)) }
        append32(0x04034b50)
        append16(20); append16(0); append16(method); append16(0); append16(0)
        append32(0); append32(UInt32(payload.count)); append32(UInt32(declaredSize))
        append16(UInt16(name.count)); append16(0)
        archive.append(name); archive.append(payload)
        let centralOffset = archive.count
        append32(0x02014b50)
        append16(20); append16(20); append16(0); append16(method); append16(0); append16(0)
        append32(0); append32(UInt32(payload.count)); append32(UInt32(declaredSize))
        append16(UInt16(name.count)); append16(0); append16(0); append16(0); append16(0)
        append32(0); append32(0)
        archive.append(name)
        let centralSize = archive.count - centralOffset
        append32(0x06054b50)
        append16(0); append16(0); append16(1); append16(1)
        append32(UInt32(centralSize)); append32(UInt32(centralOffset)); append16(0)
        return archive
    }
}
