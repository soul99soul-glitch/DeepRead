package app.amber.feature.webmount.adapters.github

import android.app.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GithubClientTest {
    @Test
    fun filePathsKeepUriDelimitersInsideThePath() = runBlocking {
        val requestedUrls = mutableListOf<Url>()
        val http = HttpClient(MockEngine { request ->
            requestedUrls += request.url
            respond(
                content = """{"type":"file","encoding":"base64","content":"YW1iZXI=","size":5}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        try {
            val paths = linkedMapOf(
                "docs/release#1?.md" to "/repos/octocat/Hello-World/contents/docs/release%231%3F.md",
                "docs/100% literal name.md" to "/repos/octocat/Hello-World/contents/docs/100%25%20literal%20name.md",
                "docs/%23.md" to "/repos/octocat/Hello-World/contents/docs/%2523.md",
            )
            for ((path, expectedEncodedPath) in paths) {
                val file = GithubClient(http).readFile("octocat", "Hello-World", path, "feature/topic#1?", null)
                val url = requestedUrls.last()

                assertEquals("amber", file?.content)
                assertEquals(path, file?.path)
                assertEquals(expectedEncodedPath, url.encodedPath)
                assertEquals("", url.fragment)
                assertEquals(setOf("ref"), url.parameters.names())
                assertEquals("feature/topic#1?", url.parameters["ref"])
            }
        } finally {
            http.close()
        }
    }
}
