package app.amber.core.sync.core

import android.content.Context
import androidx.core.net.toUri
import app.amber.agent.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json


/** Archive framing, bounded entry IO and portable attachment URL encoding. */
internal class SyncArchiveCodec(private val context: Context, private val json: Json) {
    fun zipArchive(
        manifest: SyncManifest,
        encryptedPayloadFile: File,
        encryptResult: EncryptResult,
        archiveFile: File,
    ) {
        archiveFile.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(archiveFile).buffered()).use { zip ->
            // Put the small manifest first so preview can read metadata without inflating the payload entry first.
            writeTextEntry(zip, SYNC_MANIFEST_ENTRY, json.encodeToString(manifest))
            // payload.enc is AES-GCM ciphertext; DEFLATE would burn CPU for zero gain.
            // We already have size + CRC32 from the encrypt pass, so STORE it straight.
            writeStoredFileEntry(
                zip = zip,
                name = SYNC_PAYLOAD_ENTRY,
                file = encryptedPayloadFile,
                sizeBytes = encryptResult.sizeBytes,
                crc32 = encryptResult.crc32,
            )
        }
    }

    fun parseArchive(file: File, payloadTarget: File? = null): ParsedSyncArchive {
        var manifestJson: String? = null
        var hasEncryptedPayload = false
        ZipInputStream(FileInputStream(file).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                requireSafeRelativePath(entry.name)
                if (!entry.isDirectory) {
                    when (entry.name) {
                        SYNC_MANIFEST_ENTRY -> {
                            manifestJson = zip.readBytesWithinLimit(MAX_MANIFEST_ENTRY_BYTES, entry.name).decodeToString()
                        }

                        SYNC_PAYLOAD_ENTRY -> {
                            hasEncryptedPayload = true
                            if (payloadTarget != null) {
                                payloadTarget.parentFile?.mkdirs()
                                FileOutputStream(payloadTarget).buffered().use { output ->
                                    zip.copyToWithinLimit(output, MAX_PAYLOAD_ENTRY_BYTES, entry.name)
                                }
                            }
                        }
                    }
                }
                if (payloadTarget == null && manifestJson != null && hasEncryptedPayload) break
                zip.closeEntry()
            }
        }
        val manifestJsonValue = manifestJson ?: error(
            context.getString(R.string.backup_format_incompatible, "manifest.json")
        )
        require(hasEncryptedPayload) {
            context.getString(R.string.backup_format_incompatible, "payload.enc")
        }
        val manifest = json.decodeFromString<SyncManifest>(manifestJsonValue)
        // P7-02：v2 = 当前新格式；v1 = 只读迁移（含历史固定口令格式的受控兼容分支）。
        require(
            manifest.archiveVersion == CURRENT_ARCHIVE_VERSION ||
                manifest.archiveVersion == LEGACY_ARCHIVE_VERSION
        ) {
            context.getString(R.string.backup_archive_incompatible, manifest.archiveVersion)
        }
        return ParsedSyncArchive(manifest = manifest)
    }

    /**
     * 解密成功后读取负载内的 payload_manifest.json（缺失时按空预览处理）。
     * EVERYTHING 会在 Manager 的 payload compatibility check 中拒绝该空预览；
     * CONFIG_ONLY 仍可沿用 settings-only 迁移兼容路径。
     */

    fun readPayloadPreview(payloadFile: File): SyncPayloadPreview {
        ZipInputStream(FileInputStream(payloadFile).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                requireSafeRelativePath(entry.name)
                if (!entry.isDirectory && entry.name == PAYLOAD_MANIFEST_ENTRY) {
                    val raw = zip.readBytesWithinLimit(MAX_CONFIG_ENTRY_BYTES, entry.name).decodeToString()
                    return runCatching { json.decodeFromString<SyncPayloadPreview>(raw) }
                        .getOrElse { SyncPayloadPreview() }
                }
                zip.closeEntry()
            }
        }
        return SyncPayloadPreview()
    }

    fun normalizeStringForExport(table: String, column: String, value: String): String =
        if (table == "message_node" && column == "messages") {
            value.replace(filesUriPrefix(), AMBER_FILE_PREFIX)
        } else {
            value
        }

    fun rewriteStringForImport(table: String, column: String, value: String): String =
        if (table == "message_node" && column == "messages") {
            value.replace(AMBER_FILE_PREFIX, filesUriPrefix())
        } else {
            value
        }

    fun requireSafeRelativePath(path: String) {
        require(path.isNotBlank()) { "Invalid archive path" }
        require('\\' !in path) { "Invalid archive path: $path" }
        require(!path.startsWith('/')) { "Invalid archive path: $path" }
        require(!path.contains("//")) { "Invalid archive path: $path" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(path)) { "Invalid archive path: $path" }
        require(path.split('/').none { it == "." || it == ".." }) { "Invalid archive path: $path" }
    }

    fun writeTextEntry(zip: ZipOutputStream, name: String, text: String) =
        writeBytesEntry(zip, name, text.toByteArray())

    private fun writeFileEntry(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun writeStoredFileEntry(
        zip: ZipOutputStream,
        name: String,
        file: File,
        sizeBytes: Long,
        crc32: Long,
    ) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = sizeBytes
            compressedSize = sizeBytes
            crc = crc32
        }
        zip.putNextEntry(entry)
        file.inputStream().buffered().use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun computeCrc32(file: File): Long {
        val crc = CRC32()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private fun shouldStoreUncompressed(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in STORE_UNCOMPRESSED_EXTENSIONS
    }

    fun writeBytesEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    fun writeFileTreeEntry(zip: ZipOutputStream, name: String, file: File) {
        // Images, audio, video, and pre-compressed archives carry incompressible
        // payloads — DEFLATE just spends CPU for no gain. STORE them straight; the
        // extra CRC32 pass is still cheaper than a wasted DEFLATE pass.
        if (shouldStoreUncompressed(name)) {
            writeStoredFileEntry(
                zip = zip,
                name = name,
                file = file,
                sizeBytes = file.length(),
                crc32 = computeCrc32(file),
            )
        } else {
            writeFileEntry(zip, name, file)
        }
    }

    private fun filesUriPrefix(): String =
        context.filesDir.toUri().toString().trimEnd('/') + "/"

    data class ParsedSyncArchive(
        val manifest: SyncManifest,
    )

    companion object {
        private const val AMBER_FILE_PREFIX = "amber-file://"
        private const val MAX_MANIFEST_ENTRY_BYTES = 1024 * 1024
        internal const val MAX_CONFIG_ENTRY_BYTES = 8 * 1024 * 1024
        private const val MAX_PAYLOAD_ENTRY_BYTES = 1024L * 1024 * 1024
        internal const val PAYLOAD_MANIFEST_ENTRY = "payload_manifest.json"
        internal const val SETTINGS_ENTRY = "settings.json"
        internal const val SECRETS_ENTRY = "secrets.json"
        // Pre-compressed/lossy formats: DEFLATE has no headroom and just burns CPU.
        // Listed by extension so the check stays cheap and stable across content
        // types we cannot otherwise detect from a File handle.
        private val STORE_UNCOMPRESSED_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif",
            "mp3", "m4a", "aac", "ogg", "opus", "flac",
            "mp4", "m4v", "mov", "webm", "mkv",
            "pdf", "zip", "gz", "tgz", "7z", "rar", "xz", "br", "zst",
        )
    }
}

internal fun ZipInputStream.readBytesWithinLimit(limit: Int, entryName: String): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(total <= limit - read) { "Sync archive entry $entryName exceeds $limit bytes" }
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}

internal fun ZipInputStream.copyToWithinLimit(output: java.io.OutputStream, limit: Long, entryName: String): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        require(total <= limit - read) { "Sync archive entry $entryName exceeds $limit bytes" }
        output.write(buffer, 0, read)
        total += read
    }
    return total
}
