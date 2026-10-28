package app.amber.agent

import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.amber.feature.terminal.AlpineRuntimeInstaller
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the packaged proot/loader under the app UID and SELinux policy, never adb shell/root. */
@RunWith(AndroidJUnit4::class)
class EmbeddedTerminalStartupDeviceTest {
    @Test
    fun packagedRuntimeExecutesGuestCommandsAndInteractiveShell() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Android 10+ execution policy required", Build.VERSION.SDK_INT >= 29)
        assertTrue("Installer must extract the execve loader",
            base.applicationInfo.flags and ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS != 0)
        assertTrue(File(base.applicationInfo.nativeLibraryDir, "libproot-loader.so").canExecute())
        val root = File(base.cacheDir, "terminal-startup-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        try {
            val installer = AlpineRuntimeInstaller(context)
            val installed = installer.ensureInstalled()
            assertTrue(installed.message, installed.success)
            val workspace = File(root, "workspace").apply { mkdirs() }
            fun builder(vararg guestArgs: String) = ProcessBuilder(
                listOf("/system/bin/sh", installer.localBinDir.resolve("init-host").absolutePath) + guestArgs,
            ).directory(workspace).redirectErrorStream(true).also {
                it.environment().putAll(installer.environment(workspace.absolutePath, "smoke"))
            }
            val batch = execute(builder("/bin/sh", "-lc",
                "printf 'AMBER_GUEST_OK\\n'; /bin/uname -m; printf 'bound' > /workspace/guest.txt"))
            assertTrue(batch, batch.contains("AMBER_GUEST_OK") && batch.contains("aarch64"))
            assertEquals("bound", File(workspace, "guest.txt").readText())

            // Upgrade an already populated guest, including user files, a custom
            // executable and the actual Alpine package database. A fresh-install
            // smoke alone cannot detect a destructive runtime revision change.
            val guest = installer.localDir.resolve("alpine")
            val notes = guest.resolve("root/upgrade-notes.txt").apply { writeText("user notes kept") }
            val tool = guest.resolve("usr/local/bin/amber-upgrade-tool").apply {
                parentFile!!.mkdirs()
                writeText("#!/bin/sh\nprintf 'AMBER_UPGRADE_TOOL_OK\\n'\n")
                setExecutable(true)
            }
            val packageDatabase = guest.resolve("lib/apk/db/installed")
            val installedPackages = packageDatabase.readBytes()
            val version = context.filesDir.resolve("embedded-terminal-runtime.version")
            version.writeText("alpine-3.24.1-proot-b6326a3b")
            val upgraded = installer.ensureInstalled()
            assertTrue(upgraded.message, upgraded.success)
            assertEquals("user notes kept", notes.readText())
            assertTrue(tool.canExecute())
            assertArrayEquals(installedPackages, packageDatabase.readBytes())
            val afterUpgrade = execute(builder("/bin/sh", "-lc", "/usr/local/bin/amber-upgrade-tool"))
            assertTrue(afterUpgrade, afterUpgrade.contains("AMBER_UPGRADE_TOOL_OK"))

            val repaired = installer.installOrRepair()
            assertTrue(repaired.message, repaired.success)
            version.delete()
            val recovered = installer.ensureInstalled()
            assertTrue(recovered.message, recovered.success)
            assertEquals("user notes kept", notes.readText())
            assertArrayEquals(installedPackages, packageDatabase.readBytes())
            val interactive = execute(builder("/bin/sh", "-l"),
                "printf 'AMBER_SESSION_OK\\n'\n/bin/uname -m\n/usr/local/bin/amber-upgrade-tool\nexit\n")
            assertTrue(interactive, interactive.contains("AMBER_SESSION_OK") &&
                interactive.contains("aarch64") && interactive.contains("AMBER_UPGRADE_TOOL_OK"))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun execute(builder: ProcessBuilder, input: String = ""): String {
        val process = builder.start()
        val output = StringBuilder()
        val reader = thread { process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { output.appendLine(it) }
        } }
        try {
            process.outputStream.bufferedWriter().use { it.write(input) }
            assertTrue("Guest timed out: $output", process.waitFor(45, TimeUnit.SECONDS))
            reader.join(5_000)
            assertEquals("Guest failed: $output", 0, process.exitValue())
            return output.toString()
        } finally {
            if (process.isAlive) process.destroyForcibly()
            reader.join(5_000)
        }
    }
}
