package app.amber.review

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import app.amber.core.infra.AppScope
import app.amber.feature.terminal.AlpineRuntimeInstaller
import app.amber.feature.terminal.TerminalReadResult
import app.amber.feature.workspace.WorkspaceManager
import java.io.BufferedWriter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real session cleanup and workspace lease ownership; only the native Process is fake. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TerminalSessionLifecycleTest {
    @get:Rule val files = TemporaryFolder()
    private lateinit var workspace: WorkspaceManager
    private lateinit var owner: Any
    private val ownerType = Class.forName("app.amber.feature.terminal.TerminalSessionManager")
    private val sessionType = Class.forName("app.amber.feature.terminal.TerminalSession")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val base: Context = RuntimeEnvironment.getApplication()
        val context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = files.root.resolve("files").apply { mkdirs() }
            override fun getCacheDir(): File = files.root.resolve("cache").apply { mkdirs() }
        }
        workspace = WorkspaceManager(context)
        owner = ownerType.getDeclaredConstructor(
            AppScope::class.java, WorkspaceManager::class.java, AlpineRuntimeInstaller::class.java,
        ).apply { isAccessible = true }.newInstance(AppScope(), workspace, AlpineRuntimeInstaller(context))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun exitedReaderReleasesItsLeaseAndLaterStopCannotReleaseANewerLease(): Unit = runBlocking {
        val process = SessionProcess(alive = false)
        val session = addSession("finished", process)

        readOutput(session)
        val fresh = workspace.acquireTerminalMirror(refreshFromWorkspace = false)
        val result = stop("finished")

        assertFalse(result.running)
        assertTrue(result.output.contains("session stopped"))
        assertTrue(runCatching { workspace.acquireTerminalMirror(false) }.exceptionOrNull() is IllegalStateException)
        fresh.release(syncBack = false)
        workspace.acquireTerminalMirror(false).release(syncBack = false)
    }

    @Test
    fun readerExitAndStopReleaseOneLeaseAndDrainTheOriginalOutput(): Unit = runBlocking {
        val process = SessionProcess(alive = true)
        val session = addSession("running", process)
        val reader = async(Dispatchers.IO) { readOutput(session) }
        ReflectionFixture.set(session, "reader", reader)
        process.waiting.await()

        val result = try {
            stop("running")
        } finally {
            if (process.isAlive) process.destroy()
            reader.await()
        }

        assertFalse(result.running)
        assertEquals(1, process.destroyCalls)
        assertTrue(result.output.contains("session 中文🙂"))
        workspace.acquireTerminalMirror(false).release(syncBack = false)
    }

    private suspend fun addSession(id: String, process: Process): Any {
        val outputType = Class.forName("app.amber.feature.terminal.TerminalSessionOutput")
        val output = outputType.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1024)
        val lease = workspace.acquireTerminalMirror(refreshFromWorkspace = false)
        val session = ReflectionFixture.allocate(sessionType)
        ReflectionFixture.set(session, "id", id)
        ReflectionFixture.set(session, "process", process)
        ReflectionFixture.set(session, "writer", BufferedWriter(OutputStreamWriter(process.outputStream)))
        ReflectionFixture.set(session, "mirrorLease", lease)
        ReflectionFixture.set(session, "output", output)
        @Suppress("UNCHECKED_CAST")
        val sessions = ownerType.getDeclaredField("sessions").apply { isAccessible = true }
            .get(owner) as MutableMap<String, Any>
        sessions[id] = session
        return session
    }

    private suspend fun readOutput(session: Any) {
        val method = ownerType.getDeclaredMethod("readSessionOutput", sessionType, Continuation::class.java)
        ReflectionFixture.invokeSuspend(owner, method, session)
    }

    private suspend fun stop(id: String): TerminalReadResult {
        val method = ownerType.getDeclaredMethod("stopSession", String::class.java, Continuation::class.java)
        return ReflectionFixture.invokeSuspend(owner, method, id) as TerminalReadResult
    }

    private class SessionProcess(alive: Boolean) : Process() {
        private val active = AtomicBoolean(alive)
        private val stopped = CountDownLatch(if (alive) 1 else 0)
        private val input = ByteArrayInputStream("session 中文🙂".toByteArray(Charsets.UTF_8))
        val waiting = CompletableDeferred<Unit>()
        var destroyCalls = 0

        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun isAlive(): Boolean = active.get()
        override fun exitValue(): Int = if (active.get()) throw IllegalThreadStateException() else 0
        override fun waitFor(): Int {
            waiting.complete(Unit)
            stopped.await()
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = stopped.await(timeout, unit)
        override fun destroy() {
            destroyCalls++
            active.set(false)
            stopped.countDown()
        }
    }
}
