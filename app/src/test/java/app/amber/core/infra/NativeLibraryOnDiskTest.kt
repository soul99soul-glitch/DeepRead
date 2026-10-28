package app.amber.core.infra

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NativeLibraryOnDiskTest {
    private lateinit var testRoot: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        val base = RuntimeEnvironment.getApplication()
        testRoot = File(base.cacheDir, "native-lib-on-disk-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = File(testRoot, "files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(testRoot, "no_backup").apply { mkdirs() }
        }
    }

    @Test
    fun `prefers a file already extracted into nativeLibraryDir`() {
        val nativeDir = File(testRoot, "native-libs").apply { mkdirs() }
        val extracted = File(nativeDir, "libsimple.so").apply { writeText("from-native-dir") }
        context.applicationInfo.nativeLibraryDir = nativeDir.absolutePath

        val resolved = NativeLibraryOnDisk.resolveOrNull(context, "libsimple.so")

        assertEquals(extracted.absolutePath, resolved?.absolutePath)
        assertEquals("from-native-dir", resolved?.readText())
    }

    @Test
    fun `copies the library out of the APK when nativeLibraryDir is empty`() {
        val nativeDir = File(testRoot, "empty-native").apply { mkdirs() }
        context.applicationInfo.nativeLibraryDir = nativeDir.absolutePath
        val apk = File(testRoot, "base.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libsimple.so"))
            zip.write("from-apk".toByteArray())
            zip.closeEntry()
        }
        context.applicationInfo.sourceDir = apk.absolutePath
        context.applicationInfo.splitSourceDirs = emptyArray()

        val resolved = NativeLibraryOnDisk.resolveOrNull(context, "libsimple.so")

        assertTrue(resolved!!.isFile)
        assertEquals("from-apk", resolved.readText())
        assertTrue(resolved.absolutePath.startsWith(context.noBackupFilesDir.absolutePath))
        val again = NativeLibraryOnDisk.resolveOrNull(context, "libsimple.so")
        assertEquals(resolved.absolutePath, again?.absolutePath)
    }

    @Test
    fun `returns null when the APK does not contain the library`() {
        val nativeDir = File(testRoot, "empty-native").apply { mkdirs() }
        context.applicationInfo.nativeLibraryDir = nativeDir.absolutePath
        val apk = File(testRoot, "empty.apk")
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        context.applicationInfo.sourceDir = apk.absolutePath
        context.applicationInfo.splitSourceDirs = emptyArray()

        assertNull(NativeLibraryOnDisk.resolveOrNull(context, "libsimple.so"))
    }

    @Test
    fun `interrupted base copy cannot hide a valid library in a split APK`() {
        configureApks(splitContent = "complete-split-library")
        val fragment = cacheFile("libsimple.so.part").apply { writeText("partial") }

        val resolved = NativeLibraryOnDisk.resolveOrNull(context, "libsimple.so")

        assertEquals("complete-split-library", resolved?.readText())
        assertFalse(fragment.exists())
        assertEquals("complete-split-library", NativeLibraryOnDisk.resolveOrNull(context, "libsimple.so")?.readText())
    }

    @Test
    fun `orphan fragment is not returned when no APK contains the library`() {
        configureApks()
        cacheFile("libsimple.so.part").writeText("partial")

        assertNull(NativeLibraryOnDisk.resolveOrNull(context, "libsimple.so"))
        assertFalse(cacheFile("libsimple.so").exists())
    }

    @Test
    fun `repairs a poisoned cache even with a matching legacy stamp and length`() {
        configureApks(splitContent = "valid")
        cacheFile("libsimple.so").writeText("wrong")
        val apks = listOf(context.applicationInfo.sourceDir) + context.applicationInfo.splitSourceDirs.orEmpty()
        cacheFile("libsimple.so.stamp").writeText(apks.map(::File).joinToString("|") {
            "${it.absolutePath}:${it.length()}:${it.lastModified()}"
        })

        assertEquals("valid", NativeLibraryOnDisk.resolveOrNull(context, "libsimple.so")?.readText())
        cacheFile("libsimple.so").writeText("truncated")
        assertEquals("valid", NativeLibraryOnDisk.resolveOrNull(context, "libsimple.so")?.readText())
    }

    @Test
    fun `rejects corrupt extraction without replacing committed cache and can retry`() {
        configureApks(splitContent = "valid")
        val dest = cacheFile("libsimple.so").apply { writeText("previous-library") }
        val split = File(context.applicationInfo.splitSourceDirs!!.single())
        val bytes = split.readBytes()
        // Change central-directory CRC while preserving a readable ZIP/entry stream.
        val central = bytes.indices.first { i -> i + 4 < bytes.size &&
            bytes.copyOfRange(i, i + 4).contentEquals(byteArrayOf(0x50, 0x4b, 0x01, 0x02)) }
        bytes[central + 16] = (bytes[central + 16].toInt() xor 1).toByte()
        split.writeBytes(bytes)

        assertTrue(runCatching { NativeLibraryOnDisk.resolveOrNull(context, "libsimple.so") }.isFailure)
        assertEquals("previous-library", dest.readText())
        assertTrue(dest.parentFile!!.listFiles()!!.none { it.extension == "part" })

        writeApk(split, "valid")
        assertEquals("valid", NativeLibraryOnDisk.resolveOrNull(context, "libsimple.so")?.readText())
    }

    private fun configureApks(splitContent: String? = null) {
        context.applicationInfo.nativeLibraryDir = File(testRoot, "empty-native").apply { mkdirs() }.absolutePath
        context.applicationInfo.sourceDir = File(testRoot, "base.apk").also { writeApk(it) }.absolutePath
        context.applicationInfo.splitSourceDirs = if (splitContent == null) emptyArray() else arrayOf(
            File(testRoot, "split_config.arm64_v8a.apk").also { writeApk(it, splitContent) }.absolutePath,
        )
    }

    private fun writeApk(apk: File, content: String? = null) {
        ZipOutputStream(apk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(if (content == null) "AndroidManifest.xml" else "lib/arm64-v8a/libsimple.so"))
            zip.write((content ?: "manifest").toByteArray())
            zip.closeEntry()
        }
    }

    private fun cacheFile(name: String): File = File(context.noBackupFilesDir, "native-libs/$name")
        .apply { parentFile!!.mkdirs() }
}
