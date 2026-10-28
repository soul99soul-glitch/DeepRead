package app.amber.feature.terminal

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AlpineRuntimeUpgradeTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var installer: AlpineRuntimeInstaller
    private lateinit var versionFile: File
    private lateinit var loader: File
    private val guestFiles = mapOf(
        "root/notes.txt" to "user notes must survive",
        "root/.profile" to "export USER_SETTING=kept",
        "usr/local/bin/user-tool" to "#!/bin/sh\necho installed-tool\n",
        "lib/apk/db/installed" to "P:user-installed-package\nV:1.0\n",
        "etc/user-config" to "custom config",
    )

    @Before
    fun setUp() {
        val base = RuntimeEnvironment.getApplication()
        root = File(base.cacheDir, "alpine-upgrade-${System.nanoTime()}").apply { mkdirs() }
        val info = ApplicationInfo(base.applicationInfo).apply {
            nativeLibraryDir = File(root, "native-libs").apply { mkdirs() }.absolutePath
        }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getApplicationInfo() = info
        }
        installer = AlpineRuntimeInstaller(context)
        guestFiles.forEach { (path, content) ->
            File(installer.localDir, "alpine/$path").apply { parentFile!!.mkdirs(); writeText(content) }
        }
        File(root, "workspace/external.txt").apply { parentFile!!.mkdirs(); writeText("workspace kept") }
        listOf(installer.localBinDir.resolve("proot"), installer.localLibDir.resolve("libtalloc.so.2"))
            .forEach { it.parentFile!!.mkdirs(); it.writeText("old-host-binary") }
        loader = File(info.nativeLibraryDir, "libproot-loader.so").apply { writeText("loader"); setExecutable(true) }
        versionFile = File(context.filesDir, "embedded-terminal-runtime.version")
        versionFile.writeText("alpine-3.24.1-proot-b6326a3b")
    }

    @After
    fun cleanUp() { root.deleteRecursively() }

    @Test
    fun `revision upgrade preserves the entire guest and refreshes host assets`() = runBlocking {
        val status = installer.ensureInstalled()
        assertTrue(status.message, status.success)
        assertGuestPreserved()
        assertTrue(installer.getInstallStatus().success)
        assertFalse(versionFile.readText() == "alpine-3.24.1-proot-b6326a3b")
        assertTrue(context.filesDir.resolve("proot").length() > 100)
        assertFalse(installer.localBinDir.resolve("proot").exists())
        assertFalse(installer.localLibDir.resolve("libtalloc.so.2").exists())
    }

    @Test
    fun `missing revision stamp does not reset an existing guest`() = runBlocking {
        versionFile.delete()
        val status = installer.ensureInstalled()
        assertTrue(status.message, status.success)
        assertGuestPreserved()
    }

    @Test
    fun `explicit repair preserves installed packages and user files`() = runBlocking {
        val status = installer.installOrRepair()
        assertTrue(status.message, status.success)
        assertGuestPreserved()
    }

    @Test
    fun `failed upgrade keeps guest and old revision so retry can recover`() = runBlocking {
        loader.delete()
        assertFalse(installer.ensureInstalled().success)
        assertGuestPreserved()
        assertEquals("alpine-3.24.1-proot-b6326a3b", versionFile.readText())
        loader.writeText("loader")
        loader.setExecutable(true)
        val retry = installer.ensureInstalled()
        assertTrue(retry.message, retry.success)
        assertGuestPreserved()
    }

    private fun assertGuestPreserved() {
        val guest = installer.localDir.resolve("alpine")
        assertEquals(guestFiles, guest.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(guest).invariantSeparatorsPath to it.readText() })
        assertEquals("workspace kept", File(root, "workspace/external.txt").readText())
    }
}
