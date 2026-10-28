package app.amber.feature.ui.pages.backup

import android.net.Uri
import app.amber.agent.R
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.core.sync.core.RestoreScope
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.SyncPreview
import app.amber.core.sync.core.SyncRestorePartialCommitException
import app.amber.core.sync.core.SyncRestoreRequest
import app.amber.core.sync.core.SyncRestoreVerification
import app.amber.core.sync.local.LocalBackupRepository
import app.amber.core.utils.UiState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** External URI ownership and downloaded/retained encrypted copies stay distinct. */
sealed interface BackupRestoreSource {
    data class Local(val uri: Uri, val retainedArchive: File? = null) : BackupRestoreSource
    data class Google(val archive: File, val revision: String) : BackupRestoreSource
    data class Provider(val archive: File, val providerId: String) : BackupRestoreSource
}

data class BackupRestoreState(
    val source: BackupRestoreSource? = null,
    val preview: SyncPreview? = null,
    val verification: SyncRestoreVerification? = null,
    val operation: UiState<SyncPreview> = UiState.Idle,
    val activity: BackupActivity? = null,
    val message: BackupMessage? = null,
    val applying: Boolean = false,
    /** Set only after durable apply, metadata update and owned-source cleanup finish. */
    val completed: SyncPreview? = null,
)

/** One selected archive from inspect through verify, retry and explicit completion. */
class BackupRestoreSession(
    private val archiveManager: SyncArchiveManager,
    private val localRepository: LocalBackupRepository,
    private val settingsStore: SettingsAggregator,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(BackupRestoreState())
    val state = mutableState.asStateFlow()

    fun inspectImport(uri: Uri) {
        dismiss()
        val source = BackupRestoreSource.Local(uri)
        mutableState.value = BackupRestoreState(
            source = source,
            operation = UiState.Loading,
            activity = BackupActivity(R.string.backup_activity_reading_local_backup, R.string.backup_activity_parsing_backup_file),
        )
        scope.launch {
            try {
                val preview = localRepository.inspectUri(uri)
                if (state.value.source !== source) return@launch
                mutableState.value = mutableState.value.copy(
                    preview = preview,
                    activity = null,
                    message = backupMessage(R.string.backup_local_imported),
                    operation = UiState.Success(preview),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (state.value.source !== source) return@launch
                mutableState.value = BackupRestoreState(
                    operation = UiState.Error(error),
                    message = backupMessage(R.string.backup_local_import_failed, error.message.orEmpty()),
                )
                recordError(error)
            }
        }
    }

    /** Providers retain transport/digest validation; this boundary takes ownership of their copy. */
    fun acceptDownloaded(source: BackupRestoreSource, preview: SyncPreview) {
        require(source !is BackupRestoreSource.Local)
        dismiss()
        mutableState.value = BackupRestoreState(source = source, preview = preview, operation = UiState.Success(preview))
    }

    fun verify(
        passphrase: String,
        scope: RestoreScope = RestoreScope.EVERYTHING,
        preserveConversations: Boolean = true,
        preserveGenMedia: Boolean = true,
    ) {
        val source = state.value.source
        if (source == null) {
            mutableState.value = state.value.copy(operation = UiState.Error(IllegalStateException("没有待恢复的本地备份")))
            return
        }
        val request = SyncRestoreRequest(passphrase, scope, preserveConversations, preserveGenMedia)
        mutableState.value = state.value.copy(
            operation = UiState.Loading,
            activity = BackupActivity(R.string.backup_activity_verifying_backup, R.string.backup_activity_verifying_encryption_header),
        )
        this.scope.launch {
            try {
                val verification = when (source) {
                    is BackupRestoreSource.Local -> source.retainedArchive?.let { archiveManager.verifyArchive(it, request) }
                        ?: localRepository.verifyUri(source.uri, request)
                    is BackupRestoreSource.Google -> archiveManager.verifyArchive(source.archive, request)
                    is BackupRestoreSource.Provider -> archiveManager.verifyArchive(source.archive, request)
                }
                if (state.value.source !== source) {
                    archiveManager.discardVerification(verification)
                    if (source is BackupRestoreSource.Local && localRepository.isOwnedTempCopy(verification.archiveFile)) {
                        verification.archiveFile.delete()
                    }
                    return@launch
                }
                val retainedSource = if (source is BackupRestoreSource.Local) {
                    source.copy(retainedArchive = verification.archiveFile)
                } else source
                mutableState.value = state.value.copy(
                    source = retainedSource,
                    verification = verification,
                    preview = null,
                    activity = null,
                    operation = UiState.Success(verification.preview),
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (state.value.source !== source) return@launch
                mutableState.value = state.value.copy(
                    operation = UiState.Error(error),
                    activity = null,
                    message = backupMessage(R.string.backup_restore_verification_failed, error.message.orEmpty()),
                )
                runCatching { recordError(error) }
            }
        }
    }

    fun apply(
        scope: RestoreScope = RestoreScope.EVERYTHING,
        preserveConversations: Boolean = true,
        preserveGenMedia: Boolean = true,
    ) {
        val before = state.value
        val verification = before.verification
        if (verification == null) {
            mutableState.value = before.copy(operation = UiState.Error(IllegalStateException("没有已验证的备份，请重新选择备份文件")))
            return
        }
        if (before.applying) return
        val source = checkNotNull(before.source)
        mutableState.value = before.copy(
            applying = true,
            operation = UiState.Loading,
            activity = BackupActivity(R.string.backup_activity_restoring_backup, R.string.backup_activity_overwriting_local_data),
        )
        this.scope.launch {
            var dataApplied = false
            try {
                // Verification remains single-use in ArchiveManager, under the existing restore gate.
                val preview = archiveManager.applyRestore(
                    verification,
                    SyncRestoreRequest("", scope, preserveConversations, preserveGenMedia),
                )
                dataApplied = true
                finishMetadata(source, preview)
                cleanupSource(source)
                mutableState.value = BackupRestoreState(
                    operation = UiState.Success(preview),
                    message = backupMessage(R.string.backup_restore_applied_restart_hint),
                    completed = preview,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                archiveManager.discardVerification(verification)
                mutableState.value = state.value.copy(
                    verification = null,
                    preview = verification.preview,
                    applying = false,
                    activity = null,
                    operation = UiState.Error(error),
                    message = backupMessage(if (dataApplied || error is SyncRestorePartialCommitException) {
                        R.string.restore_partial_commit_retry
                    } else R.string.restore_apply_failed_retry),
                )
                runCatching { recordError(error) }
            } finally {
                if (state.value.applying) mutableState.value = state.value.copy(applying = false)
            }
        }
    }

    fun dismiss() {
        val current = state.value
        current.verification?.let(archiveManager::discardVerification)
        current.source?.let(::cleanupSource)
        mutableState.value = BackupRestoreState()
    }

    private fun cleanupSource(source: BackupRestoreSource) {
        when (source) {
            is BackupRestoreSource.Local -> source.retainedArchive?.takeIf(localRepository::isOwnedTempCopy)?.delete()
            is BackupRestoreSource.Google -> source.archive.delete()
            is BackupRestoreSource.Provider -> source.archive.delete()
        }
    }

    private suspend fun finishMetadata(source: BackupRestoreSource, preview: SyncPreview) {
        val manifest = preview.manifest
        settingsStore.update { current ->
            current.copy(syncSettings = current.syncSettings.copy(
                googleEnabled = source is BackupRestoreSource.Google || current.syncSettings.googleEnabled,
                lastDownloadAt = System.currentTimeMillis(),
                lastRemoteRevision = if (source is BackupRestoreSource.Google) source.revision else current.syncSettings.lastRemoteRevision,
                lastError = "",
                lastBackupVersionName = manifest.appVersionName,
                lastBackupVersionCode = manifest.appVersionCode,
                lastBackupDeviceLabel = manifest.deviceLabel,
            ))
        }
    }

    private suspend fun recordError(error: Throwable) {
        settingsStore.update { current -> current.copy(syncSettings = current.syncSettings.copy(lastError = error.message.orEmpty())) }
    }
}
