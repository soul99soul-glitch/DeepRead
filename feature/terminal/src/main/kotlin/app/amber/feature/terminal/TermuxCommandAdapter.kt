package app.amber.feature.terminal

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** The Android Termux service protocol; job admission and settlement stay in TerminalRuntime. */
internal class TermuxCommandAdapter(private val context: Context) {
    fun probe(): TermuxRuntimeStatus {
        val pm = context.packageManager
        val installed = runCatching {
            pm.getPackageInfo(TERMUX_PACKAGE_NAME, 0)
        }.isSuccess
        val permissionGranted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.checkSelfPermission(TERMUX_PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED
            } else {
                pm.checkPermission(TERMUX_PERMISSION_RUN_COMMAND, context.packageName) == PackageManager.PERMISSION_GRANTED
            }
        }.getOrDefault(false)
        val ready = installed && permissionGranted
        val message = when {
            !installed -> "Termux is not installed."
            !permissionGranted -> "Termux is installed, but AmberAgent does not have RUN_COMMAND permission."
            else -> "Termux is installed and RUN_COMMAND permission is granted. If commands still fail, enable allow-external-apps=true in Termux."
        }
        return TermuxRuntimeStatus(
            installed = installed,
            runCommandPermissionGranted = permissionGranted,
            allowExternalAppsConfigured = null,
            ready = ready,
            message = message,
        )
    }

    fun start(jobId: String, command: String) {
        val resultIntent = Intent(context, TermuxCommandResultReceiver::class.java).apply {
            action = ACTION_TERMUX_RESULT
            putExtra(EXTRA_JOB_ID, jobId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getBroadcast(context, jobId.hashCode(), resultIntent, flags)
        val intent = Intent(ACTION_TERMUX_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE_NAME, TERMUX_RUN_COMMAND_SERVICE)
            putExtra(EXTRA_TERMUX_COMMAND_PATH, TERMUX_SHELL_PATH)
            putExtra(EXTRA_TERMUX_ARGUMENTS, arrayOf("-lc", command))
            putExtra(EXTRA_TERMUX_WORKDIR, HOME_DIRECTORY)
            putExtra(EXTRA_TERMUX_BACKGROUND, true)
            putExtra(EXTRA_TERMUX_COMMAND_LABEL, "AmberAgent terminal job")
            putExtra(EXTRA_TERMUX_PENDING_INTENT, pendingIntent)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun decodeResult(intent: Intent): TermuxCommandResult {
        val result = intent.getBundleExtra(EXTRA_PLUGIN_RESULT_BUNDLE)
        val stdout = result?.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT).orEmpty()
        val stderr = result?.getString(EXTRA_PLUGIN_RESULT_BUNDLE_STDERR).orEmpty()
        val err = result?.getInt(EXTRA_PLUGIN_RESULT_BUNDLE_ERR, 0) ?: 0
        val errMsg = result?.getString(EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG).orEmpty()
        val exitCode = result?.getInt(EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE, if (err == 0) 0 else 1)
            ?: if (err == 0) 0 else 1
        return TermuxCommandResult(stdout, stderr, err, errMsg, exitCode)
    }

    companion object {
        private const val TERMUX_PACKAGE_NAME = "com.termux"
        private const val TERMUX_PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
        private const val TERMUX_RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        private const val TERMUX_SHELL_PATH = "/data/data/com.termux/files/usr/bin/sh"
        const val HOME_DIRECTORY = "/data/data/com.termux/files/home"

        private const val ACTION_TERMUX_RESULT = "app.amber.agent.action.TERMUX_COMMAND_RESULT"
        private const val ACTION_TERMUX_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val EXTRA_JOB_ID = "job_id"
        private const val EXTRA_TERMUX_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        private const val EXTRA_TERMUX_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        private const val EXTRA_TERMUX_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        private const val EXTRA_TERMUX_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        private const val EXTRA_TERMUX_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        private const val EXTRA_TERMUX_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE = "result"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT = "stdout"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_STDERR = "stderr"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE = "exitCode"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_ERR = "err"
        private const val EXTRA_PLUGIN_RESULT_BUNDLE_ERRMSG = "errmsg"
    }
}

internal data class TermuxCommandResult(
    val stdout: String,
    val stderr: String,
    val errorCode: Int,
    val errorMessage: String,
    val exitCode: Int,
)
