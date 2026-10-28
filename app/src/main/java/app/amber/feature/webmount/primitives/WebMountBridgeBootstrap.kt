package app.amber.feature.webmount.primitives

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** One ordered, private-scope bootstrap for primary, popup and document-start injection. */
internal object WebMountBridgeBootstrap {
    const val MANIFEST_ASSET = "webmount/bootstrap-sources.json"

    fun load(readAsset: (String) -> String): String {
        val sources = Json.decodeFromString<List<String>>(readAsset(MANIFEST_ASSET))
        // Read every source before returning any executable text. Missing assets
        // must reach WebViewPool's existing explicit bootstrap failure path.
        val contents = sources.map { name -> readAsset("webmount/$name") }
        return "(function () {\n" + contents.joinToString("\n;\n") + "\n})();"
    }
}
