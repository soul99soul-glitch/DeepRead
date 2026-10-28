package app.amber.feature.terminal

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.amber.core.infra.AppScope
import app.amber.core.settings.prefs.AgentPrefs
import app.amber.core.settings.prefs.ChatPrefs
import app.amber.core.settings.prefs.ExtensionPrefs
import app.amber.core.settings.prefs.ProviderPrefs
import app.amber.core.settings.prefs.SearchPrefs
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.settings.prefs.UIPrefs
import app.amber.core.settings.secret.SecretCipher
import app.amber.core.settings.secret.SecretRedactor
import app.amber.core.settings.secret.SecretStore
import app.amber.core.settings.secret.SecretStoreBackend
import app.amber.core.settings.ssh.SshProfileStore
import app.amber.feature.runtime.AgentToolActivityStore
import app.amber.feature.task.AgentTaskStore
import app.amber.feature.workspace.WorkspaceManager
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TerminalAdmissionTest {
    private lateinit var context: Context
    private lateinit var testRoot: File
    private lateinit var appScope: AppScope
    private lateinit var settingsStore: SettingsAggregator
    private lateinit var sshProfiles: SshProfileStore

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val base: Context = RuntimeEnvironment.getApplication()
        testRoot = File(base.cacheDir, "terminal-admission-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir(): File = File(testRoot, "files").apply { mkdirs() }
            override fun getCacheDir(): File = File(testRoot, "cache").apply { mkdirs() }
        }
        appScope = AppScope()
        val dataStore = PreferenceDataStoreFactory.create(scope = appScope) {
            File(testRoot, "settings.preferences_pb")
        }
        val secrets = SecretStore(
            backend = object : SecretStoreBackend {
                private val values = mutableMapOf<String, String>()
                override fun get(key: String): String? = values[key]
                override fun put(key: String, value: String) { values[key] = value }
                override fun remove(key: String) { values.remove(key) }
                override fun keys(): Set<String> = values.keys.toSet()
            },
            cipher = object : SecretCipher {
                override fun encrypt(plaintext: String): String = "enc:$plaintext"
                override fun decrypt(stored: String): String? = stored.removePrefix("enc:")
            },
        )
        settingsStore = SettingsAggregator(
            dataStore = dataStore,
            uiPrefs = UIPrefs(dataStore, appScope),
            searchPrefs = SearchPrefs(dataStore, appScope, secrets),
            agentPrefs = AgentPrefs(dataStore, appScope),
            providerPrefs = ProviderPrefs(dataStore, appScope, secrets),
            chatPrefs = ChatPrefs(dataStore, appScope, secrets),
            extensionPrefs = ExtensionPrefs(dataStore, appScope, secrets),
            scope = appScope,
            secretRedactor = SecretRedactor(secrets),
        )
        sshProfiles = SshProfileStore(dataStore, secrets)
        withTimeout(5_000) { settingsStore.settingsFlow.first { !it.init } }
        settingsStore.update { it.copy(agentRuntime = it.agentRuntime.copy(terminalMaxConcurrentJobs = 1)) }
    }

    @After
    fun tearDown() = runBlocking {
        if (::appScope.isInitialized) {
            appScope.cancel()
            appScope.coroutineContext[Job]?.join()
        }
        if (::testRoot.isInitialized) testRoot.deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test
    fun `cancel during task store startup releases admitted slot and install owner`() = runBlocking {
        val loader = QueuedDispatcher()
        val tasks = AgentTaskStore(context, Json, loadDispatcher = loader)
        val runtime = runtime(tasks)
        val admission = launch(Dispatchers.Default) {
            runtime.startJob("pkg install test", runtime = TerminalRuntimeKind.TERMUX_EXTERNAL, isInstall = true)
        }
        val abandonedId = withTimeout(5_000) {
            while (admittedIds(runtime).isEmpty()) delay(1)
            admittedIds(runtime).single()
        }

        // The real TaskStore has not loaded, so no backend can have been started.
        assertFalse(loader.isEmpty())
        admission.cancelAndJoin()
        loader.runAll()
        tasks.awaitReady()

        val next = runtime.startJob("pkg install next", runtime = TerminalRuntimeKind.TERMUX_EXTERNAL, isInstall = true)

        assertAdmittedToBackend(next)
        assertFalse("Cancelled admission must not retain a running slot", abandonedId in admittedIds(runtime))
        assertTrue("No task was registered before cancellation", tasks.read(abandonedId) == null)
    }

    @Test
    fun `task snapshot write failure releases admitted slot and install owner`() = runBlocking {
        val tasks = AgentTaskStore(context, Json)
        tasks.awaitReady()
        val taskDir = File(context.filesDir, "amberagent/tasks")
        assertTrue(taskDir.delete())
        taskDir.writeText("blocks the real snapshot parent directory")
        val runtime = runtime(tasks)

        val failure = runCatching {
            runtime.startJob("pkg install test", runtime = TerminalRuntimeKind.TERMUX_EXTERNAL, isInstall = true)
        }.exceptionOrNull()

        assertTrue("Expected the real task snapshot write to fail", failure is IOException)
        assertTrue(taskDir.delete())
        assertTrue(taskDir.mkdirs())
        val next = runtime.startJob("pkg install next", runtime = TerminalRuntimeKind.TERMUX_EXTERNAL, isInstall = true)

        assertAdmittedToBackend(next)
        assertEquals("Only the next backend should have a terminal task", 1, tasks.list(type = "terminal").size)
    }

    @Test
    fun `successful admission reaches selected backend and releases failed install owner`() = runBlocking {
        val tasks = AgentTaskStore(context, Json)
        val runtime = runtime(tasks)

        val first = runtime.startJob("pkg install first", runtime = TerminalRuntimeKind.TERMUX_EXTERNAL, isInstall = true)
        val next = runtime.startJob("pkg install next", runtime = TerminalRuntimeKind.TERMUX_EXTERNAL, isInstall = true)

        assertAdmittedToBackend(first)
        assertAdmittedToBackend(next)
        assertEquals(2, tasks.list(type = "terminal").size)
    }

    @Test
    fun `environment uses the installer extracted loader instead of a stale private copy`() {
        val installer = AlpineRuntimeInstaller(context)
        val nativeDir = context.filesDir.resolve("native-libs").apply { mkdirs() }
        context.applicationInfo.nativeLibraryDir = nativeDir.absolutePath
        val loader = nativeDir.resolve("libproot-loader.so").apply { writeText("loader"); setExecutable(true) }
        context.filesDir.resolve("libproot-loader.so").writeText("stale-loader")
        val env = installer.environment(workspacePath = "/workspace", sessionId = "s1")
        assertEquals(loader.absolutePath, env["PROOT_LOADER"])
    }

    @Test
    fun `session process launch failure releases its workspace lease`(): Unit = runBlocking {
        // Robolectric omits the installed app's native library directory.
        context.applicationInfo.nativeLibraryDir = context.filesDir.resolve("native-libs")
            .apply { mkdirs() }.absolutePath
        val installer = AlpineRuntimeInstaller(context)
        val runtimeVersion = AlpineRuntimeInstaller::class.java.getDeclaredField("runtimeVersion")
            .apply { isAccessible = true }.get(installer) as String
        listOf(
            context.filesDir.resolve("proot"),
            context.filesDir.resolve("libtalloc.so.2"),
            context.filesDir.resolve("alpine.tar.gz"),
            File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so"),
            installer.localBinDir.resolve("init-host"),
            installer.localBinDir.resolve("init"),
        ).forEach { file ->
            file.parentFile?.mkdirs()
            file.writeText("fixture")
            file.setExecutable(true)
        }
        context.filesDir.resolve("embedded-terminal-runtime.version").writeText(runtimeVersion)
        assertTrue(installer.getInstallStatus().success)
        val workspace = WorkspaceManager(context)
        val runtime = runtime(AgentTaskStore(context, Json), workspace)

        // JVM tests cannot launch Android's /system/bin/sh, after acquiring the real lease.
        val failure = runCatching { runtime.startSession() }.exceptionOrNull()

        assertTrue("Expected native process launch IOException; actual: ${failure?.stackTraceToString()}", failure is IOException)
        workspace.acquireTerminalMirror(refreshFromWorkspace = false).release(syncBack = false)
    }

    @Test
    fun `late Termux running projection cannot resurrect the completed durable task`(): Unit = runBlocking {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = "com.termux" })
        val originalContext = context
        val originalScope = appScope
        val projectionGate = FirstLaunchGate()
        val gatedJob = SupervisorJob()
        appScope = AppScope().also { scope ->
            // Keep the real runtime launch and TaskStore update; delay only the first
            // child at coroutine entry so the real completion projection overtakes it.
            val delegate = AppScope::class.java.declaredFields.single {
                CoroutineScope::class.java.isAssignableFrom(it.type)
            }
            delegate.isAccessible = true
            delegate.set(scope, CoroutineScope(gatedJob + projectionGate))
        }
        context = object : ContextWrapper(originalContext) {
            override fun checkSelfPermission(permission: String): Int =
                if (permission == "com.termux.permission.RUN_COMMAND") PackageManager.PERMISSION_GRANTED
                else super.checkSelfPermission(permission)

            override fun startForegroundService(service: Intent): ComponentName {
                check(projectionGate.entered.await(5, TimeUnit.SECONDS)) { "Initial running projection did not start" }
                return ComponentName("com.termux", "com.termux.app.RunCommandService")
            }
        }
        try {
            val tasks = AgentTaskStore(context, Json)
            val runtime = runtime(tasks)
            val started = runtime.startJob("true", runtime = TerminalRuntimeKind.TERMUX_EXTERNAL)
            assertEquals(TerminalJobStatus.RUNNING, started.status)
            val initialProjection = gatedJob.children.first()
            runtime.handleTermuxResult(Intent().apply {
                putExtra("job_id", started.jobId)
                putExtra("result", Bundle().apply { putInt("exitCode", 0) })
            })
            withTimeout(5_000) {
                while (tasks.read(started.jobId)?.status != app.amber.feature.task.AgentTaskStatus.COMPLETED) delay(1)
            }

            projectionGate.release.countDown()
            withTimeout(5_000) { initialProjection.join() }
            assertEquals(app.amber.feature.task.AgentTaskStatus.COMPLETED, tasks.read(started.jobId)?.status)
            assertEquals(TerminalJobStatus.COMPLETED, runtime.readJob(started.jobId).status)
        } finally {
            projectionGate.release.countDown()
            gatedJob.cancelAndJoin()
            appScope = originalScope
            context = originalContext
        }
    }

    private fun runtime(tasks: AgentTaskStore, workspace: WorkspaceManager = WorkspaceManager(context)) = TerminalRuntime(
        context = context,
        appScope = appScope,
        workspaceManager = workspace,
        alpineRuntimeInstaller = AlpineRuntimeInstaller(context),
        activityStore = AgentToolActivityStore(),
        settingsStore = settingsStore,
        agentTaskStore = tasks,
        sshProfileStore = sshProfiles,
        sshClient = SshClient(),
    )

    private fun assertAdmittedToBackend(snapshot: TerminalJobSnapshot) {
        assertEquals(TerminalJobStatus.FAILED, snapshot.status)
        assertEquals("Termux is not installed.", snapshot.error)
    }

    private fun admittedIds(runtime: TerminalRuntime): Set<String> {
        val field = TerminalRuntime::class.java.getDeclaredField("jobs").apply { isAccessible = true }
        val jobs = field.get(runtime) as Map<*, *>
        return jobs.keys.map { it as String }.toSet()
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun isEmpty(): Boolean = queue.isEmpty()
        fun runAll() {
            while (true) (queue.poll() ?: return).run()
        }
    }

    private class FirstLaunchGate : ThreadContextElement<Boolean>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<FirstLaunchGate>
        private val first = AtomicBoolean(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun updateThreadContext(context: CoroutineContext): Boolean {
            if (!first.compareAndSet(true, false)) return false
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Delayed projection was not released" }
            return true
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: Boolean) = Unit
    }
}
