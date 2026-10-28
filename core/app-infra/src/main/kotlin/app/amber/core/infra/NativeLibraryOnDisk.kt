package app.amber.core.infra

import android.content.Context
import android.os.Build
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Resolves a JNI `.so` to a real filesystem path.
 *
 * With `extractNativeLibs=false` (AGP default, required for 16 KB zip
 * alignment) `ApplicationInfo.nativeLibraryDir` is often empty. Callers that
 * `dlopen` a path (SQLite extensions) need a file on disk, so this
 * copies the library out of the APK (or split APK) into no-backup storage.
 */
object NativeLibraryOnDisk {
    @Synchronized
    fun resolveOrNull(context: Context, fileName: String): File? {
        require(File(fileName).name == fileName && fileName.endsWith(".so")) {
            "Expected a .so file name, got $fileName"
        }
        val extracted = File(context.applicationInfo.nativeLibraryDir, fileName)
        if (extracted.isFile && extracted.length() > 0L) return extracted

        val apkPaths = buildList {
            add(context.applicationInfo.sourceDir)
            context.applicationInfo.splitSourceDirs?.forEach { add(it) }
        }.map(::File).filter { it.isFile }
        if (apkPaths.isEmpty()) return null

        val dest = File(context.noBackupFilesDir, "native-libs/$fileName")
        val abiCandidates = (Build.SUPPORTED_ABIS.toList() + "arm64-v8a" + "x86_64")
            .distinct()
            .map { abi -> "lib/$abi/$fileName" }
        dest.parentFile?.mkdirs()
        // Never promote a fragment left by an older, interrupted extraction.
        File(dest.parentFile, "$fileName.part").delete()
        File(dest.parentFile, "$fileName.stamp").delete()
        for (apk in apkPaths) {
            ZipFile(apk).use { zip ->
                val entry = abiCandidates.firstNotNullOfOrNull { name -> zip.getEntry(name) } ?: return@use
                // Validate against the selected entry, including on a cache hit. A stamp
                // alone cannot distinguish a previously committed partial/corrupt file.
                if (matchesEntry(dest, entry)) return dest
                val tmp = File.createTempFile("$fileName-", ".part", dest.parentFile)
                try {
                    tmp.outputStream().use { output ->
                        zip.getInputStream(entry).use { input -> input.copyTo(output) }
                        output.fd.sync()
                    }
                    check(matchesEntry(tmp, entry)) { "Incomplete or corrupt APK entry: ${entry.name}" }
                    tmp.setReadable(true, false)
                    // Same-directory rename atomically replaces the previous cache. A
                    // process death during copying leaves the old committed file intact.
                    check(tmp.renameTo(dest)) { "Unable to place $fileName at ${dest.absolutePath}" }
                    return dest
                } finally {
                    tmp.delete()
                }
            }
        }
        return null
    }

    private fun matchesEntry(file: File, entry: ZipEntry): Boolean {
        if (!file.isFile || entry.size <= 0L || file.length() != entry.size || entry.crc < 0L) return false
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                crc.update(buffer, 0, count)
            }
        }
        return crc.value == entry.crc
    }
}
