package app.amber.feature.ui.pages.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import app.amber.core.settings.Capability
import app.amber.core.settings.CapabilityFlags
import app.amber.core.settings.Settings
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.agent.R
import app.amber.core.sync.core.SyncArchiveManager
import app.amber.core.sync.core.SyncEncryptionMode
import app.amber.core.sync.core.SyncExportRequest
import app.amber.core.sync.core.SyncMode
import app.amber.core.sync.core.SyncPreview
import app.amber.core.sync.google.GoogleDriveAuthSession
import app.amber.core.sync.google.GoogleDriveFile
import app.amber.core.sync.google.GoogleDriveSyncRepository
import app.amber.core.sync.google.GoogleOAuthConfigGate
import app.amber.core.sync.local.LocalBackupRepository
import app.amber.core.sync.provider.GoogleDriveSyncProvider
import app.amber.core.sync.provider.LocalFolderSyncProvider
import app.amber.core.sync.provider.PersistedFolderStore
import app.amber.core.sync.provider.SyncProviderUploadRequest
import app.amber.core.sync.provider.SyncSnapshot
import app.amber.core.sync.provider.SyncSnapshotManifest
import app.amber.core.sync.provider.UploadConflictPolicy
import app.amber.core.sync.provider.WebDavSyncProvider
import app.amber.core.sync.provider.toArchiveManifest
import app.amber.core.utils.UiState
import kotlin.uuid.Uuid

class BackupVM(
    private val settingsStore: SettingsAggregator,
    private val localBackupRepository: LocalBackupRepository,
    private val googleDriveSyncRepository: GoogleDriveSyncRepository,
    googleOAuthConfigGate: GoogleOAuthConfigGate,
    private val archiveManager: SyncArchiveManager,
    private val webDavSyncProvider: WebDavSyncProvider,
    private val localFolderSyncProvider: LocalFolderSyncProvider,
    private val persistedFolderStore: PersistedFolderStore,
    private val capabilityFlags: CapabilityFlags,
) : ViewModel() {
    val settings = settingsStore.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = Settings.dummy()
    )

    // P7-01：sync_provider_v2 开关 —— off 时现有 Google Drive / 本地导入导出行为完全不变。
    val providerV2Enabled: StateFlow<Boolean> = capabilityFlags.flow
        .map { Capability.SyncProviderV2 in it.enabled }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val operationState = MutableStateFlow<UiState<SyncPreview>>(UiState.Idle)
    val restoreSession = BackupRestoreSession(archiveManager, localBackupRepository, settingsStore, viewModelScope)
    val googleConnection = BackupGoogleConnection(
        settingsStore, googleDriveSyncRepository, googleOAuthConfigGate, viewModelScope, operationState,
        onUploadReady = { passphrase, encryptionMode ->
            uploadGoogle(SyncMode.FULL, passphrase, encryptionMode = encryptionMode)
        },
    )

    val localMessage = MutableStateFlow<BackupMessage?>(null)
    val backupActivity = MutableStateFlow<BackupActivity?>(null)
    val cloudConflict = MutableStateFlow<GoogleCloudConflict?>(null)
    val cloudSnapshots = MutableStateFlow<List<GoogleDriveFile>>(emptyList())
    val cloudSnapshotPickerVisible = MutableStateFlow(false)

    // P7-01 provider 状态（WebDAV / 本地文件夹）。
    val webDavSnapshots = MutableStateFlow<List<SyncSnapshot>>(emptyList())
    val webDavMessage = MutableStateFlow<BackupMessage?>(null)
    val localFolderSnapshots = MutableStateFlow<List<SyncSnapshot>>(emptyList())
    val folderMessage = MutableStateFlow<BackupMessage?>(null)
    val folderInfo = MutableStateFlow<PersistedFolderStore.Folder?>(null)
    val pendingDeleteConfirm = MutableStateFlow<SyncSnapshot?>(null)
    val pendingUploadConflict = MutableStateFlow<UploadConflictChoice?>(null)


    // P7-02：导出加密方式选择（口令加密 / 设备绑定加密）与两阶段恢复。
    val pendingExportDialog = MutableStateFlow<ExportRequestSeed?>(null)

    private var pendingCloudUploadRequest: SyncExportRequest? = null
    /**
     * The encryption dialog completes before the provider conflict dialog is
     * shown. Keep that request only for the short conflict-resolution window;
     * otherwise resolving the conflict would retry with an empty passphrase.
     */
    private var pendingUploadConflictRequest: PendingUploadConflictRequest? = null

    init {
        viewModelScope.launch {
            val currentSettings = settingsStore.settingsFlow.value
            if (currentSettings.syncSettings.deviceId.isBlank()) {
                settingsStore.update { current ->
                    if (current.init || current.syncSettings.deviceId.isNotBlank()) {
                        current
                    } else {
                        current.copy(
                            syncSettings = current.syncSettings.copy(
                                deviceId = Uuid.random().toString()
                            )
                        )
                    }
                }
            }
            folderInfo.value = withContext(Dispatchers.IO) {
                persistedFolderStore.read()
            }
            googleConnection.restoreGoogleSessionIfPossible()
        }
    }

    fun updateMode(mode: SyncMode) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(syncSettings = current.syncSettings.copy(mode = mode))
            }
        }
    }


    /**
     * P7-02：导出前先选择加密方式（口令加密或设备绑定加密），UI 确认后
     * 通过 [confirmExport] 分发。
     */
    fun requestExport(source: ExportSource, conflictPolicy: UploadConflictPolicy? = null) {
        if (source == ExportSource.Google && googleConnection.googleUnavailable()) return
        pendingExportDialog.value = ExportRequestSeed(source, conflictPolicy)
    }

    fun requestExport(seed: ExportRequestSeed) {
        if (seed.source == ExportSource.Google && googleConnection.googleUnavailable()) return
        pendingExportDialog.value = seed
    }

    fun dismissExportDialog() {
        pendingExportDialog.value = null
    }

    fun confirmExport(passphrase: String, encryptionMode: SyncEncryptionMode) {
        val seed = pendingExportDialog.value ?: return
        pendingExportDialog.value = null
        when (seed.source) {
            ExportSource.Google -> {
                val session = googleConnection.googleSession.value
                if (session == null) {
                    // 授权成功后由 applyGoogleSession 自动继续上传。
                    googleConnection.deferUpload(passphrase, encryptionMode)
                    googleConnection.googleMessage.value = backupMessage(R.string.backup_google_authorization_required_upload)
                    return
                }
                uploadGoogle(SyncMode.FULL, passphrase, encryptionMode = encryptionMode)
            }

            ExportSource.WebDav -> uploadWebDav(seed.conflictPolicy, passphrase, encryptionMode)
            ExportSource.LocalFolder -> uploadLocalFolder(seed.conflictPolicy, passphrase, encryptionMode)
            ExportSource.LocalFile -> {
                val uri = seed.uri
                if (uri != null) {
                    exportLocal(uri, SyncMode.FULL, passphrase, encryptionMode)
                }
            }
        }
    }

    fun uploadGoogle(
        mode: SyncMode,
        passphrase: String,
        overwrite: Boolean = false,
        encryptionMode: SyncEncryptionMode = SyncEncryptionMode.PASSPHRASE,
    ) {
        if (googleConnection.googleUnavailable()) return
        val session = googleConnection.googleSession.value
        if (session == null) {
            googleConnection.connectGoogle()
            googleConnection.googleMessage.value = backupMessage(R.string.backup_google_authorization_required_upload)
            return
        }
        // P7-02：口令模式必须输入自定义口令；设备绑定模式由引擎使用本机密钥。
        if (encryptionMode == SyncEncryptionMode.PASSPHRASE && passphrase.isBlank()) {
            operationState.value = UiState.Error(IllegalArgumentException("请输入自定义备份口令"))
            return
        }
        val request = SyncExportRequest(mode = mode, passphrase = passphrase, encryptionMode = encryptionMode)
        startGoogleUpload(session, request, overwrite)
    }

    private fun startGoogleUpload(
        session: GoogleDriveAuthSession,
        request: SyncExportRequest,
        overwrite: Boolean,
    ) {
        if (googleConnection.googleUnavailable()) return
        val uploadTitleRes = if (overwrite) {
            R.string.backup_activity_uploading_cloud_overwrite
        } else {
            R.string.backup_activity_uploading_cloud
        }
        var lastUploadPercent: Int? = null
        var emittedUnknownProgress = false
        viewModelScope.launch {
            operationState.value = UiState.Loading
            backupActivity.value = BackupActivity(
                titleRes = if (overwrite) {
                    R.string.backup_activity_prepare_cloud_overwrite
                } else {
                    R.string.backup_activity_prepare_cloud_upload
                },
                detailRes = R.string.backup_activity_connect_google_drive,
            )
            runCatching {
                val activeSession = googleConnection.refreshGoogleSessionForOperation() ?: session
                backupActivity.value = BackupActivity(
                    titleRes = uploadTitleRes,
                    detailRes = R.string.backup_activity_generating_encrypted_backup,
                )
                googleDriveSyncRepository.upload(
                    session = activeSession,
                    request = request,
                    onProgress = { uploadedBytes, totalBytes ->
                        if (totalBytes > 0L) {
                            val percent = ((uploadedBytes.toDouble() / totalBytes.toDouble()) * 100)
                                .toInt()
                                .coerceIn(0, 100)
                            if (percent != lastUploadPercent) {
                                lastUploadPercent = percent
                                backupActivity.value = BackupActivity(
                                    titleRes = uploadTitleRes,
                                    detail = "$percent%",
                                    progress = percent / 100f,
                                )
                            }
                        } else {
                            if (!emittedUnknownProgress) {
                                emittedUnknownProgress = true
                                backupActivity.value = BackupActivity(
                                    titleRes = uploadTitleRes,
                                    detailRes = R.string.backup_activity_uploading,
                                )
                            }
                        }
                    },
                )
            }.onSuccess { result ->
                val manifest = result.preview.manifest
                settingsStore.update { current ->
                    current.copy(
                        syncSettings = current.syncSettings.copy(
                            googleEnabled = true,
                            googleAccountEmail = googleConnection.googleSession.value?.accountEmail ?: session.accountEmail,
                            googleAccountId = googleConnection.googleSession.value?.accountId ?: session.accountId,
                            googleDisplayName = googleConnection.googleSession.value?.displayName ?: session.displayName,
                            mode = request.mode,
                            lastUploadAt = System.currentTimeMillis(),
                            lastRemoteRevision = result.file.revisionKey,
                            lastError = "",
                            lastBackupVersionName = manifest.appVersionName,
                            lastBackupVersionCode = manifest.appVersionCode,
                            lastBackupDeviceLabel = manifest.deviceLabel,
                        )
                    )
                }
                googleConnection.googleMessage.value = null
                backupActivity.value = null
                operationState.value = UiState.Success(result.preview)
            }.onFailure { error ->
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                googleConnection.googleMessage.value = backupMessage(R.string.backup_cloud_upload_failed, error.message.orEmpty())
                googleConnection.recordGoogleDriveError(error)
            }
        }
    }

    fun confirmOverwriteCloud() {
        val request = pendingCloudUploadRequest ?: return
        if (googleConnection.googleUnavailable()) return
        val session = googleConnection.googleSession.value ?: run {
            googleConnection.connectGoogle()
            googleConnection.googleMessage.value = backupMessage(R.string.backup_google_authorization_required_overwrite)
            return
        }
        pendingCloudUploadRequest = null
        cloudConflict.value = null
        startGoogleUpload(session, request, overwrite = true)
    }

    fun dismissCloudConflict() {
        pendingCloudUploadRequest = null
        cloudConflict.value = null
        backupActivity.value = null
        operationState.value = UiState.Idle
    }
    fun downloadGooglePreview() {
        if (googleConnection.googleUnavailable()) return
        val session = googleConnection.googleSession.value
        if (session == null) {
            googleConnection.connectGoogle()
            googleConnection.googleMessage.value = backupMessage(R.string.backup_google_authorization_required_download)
            return
        }
        viewModelScope.launch {
            operationState.value = UiState.Loading
            backupActivity.value = BackupActivity(
                titleRes = R.string.backup_activity_reading_cloud_snapshot,
                detailRes = R.string.backup_activity_fetching_google_drive_list,
            )
            runCatching {
                googleDriveSyncRepository.listSnapshots(googleConnection.refreshGoogleSessionForOperation() ?: session)
            }.onSuccess { snapshots ->
                backupActivity.value = null
                if (snapshots.isEmpty()) {
                    operationState.value = UiState.Error(IllegalStateException("Google Drive 云端还没有同步快照"))
                    googleConnection.googleMessage.value = backupMessage(R.string.backup_cloud_no_snapshots)
                } else {
                    cloudSnapshots.value = snapshots
                    cloudSnapshotPickerVisible.value = true
                    googleConnection.googleMessage.value = null
                    operationState.value = UiState.Idle
                }
            }.onFailure { error ->
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                googleConnection.googleMessage.value = backupMessage(R.string.backup_cloud_list_failed, error.message.orEmpty())
                googleConnection.recordGoogleDriveError(error)
            }
        }
    }

    fun downloadGoogleSnapshot(file: GoogleDriveFile) {
        if (googleConnection.googleUnavailable()) return
        val session = googleConnection.googleSession.value
        if (session == null) {
            googleConnection.connectGoogle()
            googleConnection.googleMessage.value = backupMessage(R.string.backup_google_authorization_required_download)
            return
        }
        viewModelScope.launch {
            cloudSnapshotPickerVisible.value = false
            operationState.value = UiState.Loading
            backupActivity.value = BackupActivity(
                titleRes = R.string.backup_activity_downloading_cloud_snapshot,
                detail = file.name,
            )
            runCatching {
                googleDriveSyncRepository.download(
                    session = googleConnection.refreshGoogleSessionForOperation() ?: session,
                    file = file,
                )
            }.onSuccess { result ->
                backupActivity.value = null
                restoreSession.acceptDownloaded(
                    BackupRestoreSource.Google(result.archiveFile, result.file.revisionKey), result.preview,
                )
                googleConnection.googleMessage.value = null
                operationState.value = UiState.Success(result.preview)
            }.onFailure { error ->
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                googleConnection.googleMessage.value = backupMessage(R.string.backup_cloud_download_failed, error.message.orEmpty())
                googleConnection.recordGoogleDriveError(error)
            }
        }
    }

    fun dismissCloudSnapshotPicker() {
        cloudSnapshotPickerVisible.value = false
    }

    fun exportLocal(uri: Uri, mode: SyncMode, passphrase: String, encryptionMode: SyncEncryptionMode) {
        if (encryptionMode == SyncEncryptionMode.PASSPHRASE && passphrase.isBlank()) {
            operationState.value = UiState.Error(IllegalArgumentException("请输入自定义备份口令"))
            return
        }
        viewModelScope.launch {
            operationState.value = UiState.Loading
            localMessage.value = null
            backupActivity.value = BackupActivity(
                titleRes = R.string.backup_activity_exporting_local_backup,
                detailRes = R.string.backup_activity_generating_encrypted_backup,
            )
            runCatching {
                localBackupRepository.exportToUri(
                    uri = uri,
                    request = SyncExportRequest(mode = mode, passphrase = passphrase, encryptionMode = encryptionMode)
                )
            }.onSuccess { preview ->
                val manifest = preview.manifest
                settingsStore.update { current ->
                    current.copy(
                        syncSettings = current.syncSettings.copy(
                            mode = mode,
                            lastLocalExportAt = System.currentTimeMillis(),
                            lastError = "",
                            lastBackupVersionName = manifest.appVersionName,
                            lastBackupVersionCode = manifest.appVersionCode,
                            lastBackupDeviceLabel = manifest.deviceLabel,
                        )
                    )
                }
                localMessage.value = backupMessage(R.string.backup_local_exported)
                backupActivity.value = null
                operationState.value = UiState.Success(preview)
            }.onFailure { error ->
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                localMessage.value = backupMessage(R.string.backup_local_export_failed, error.message.orEmpty())
                recordError(error)
            }
        }
    }

    // ---------------- P7-01：WebDAV / 本地文件夹 provider ----------------

    fun saveWebDavConfig(url: String, username: String, password: String, path: String) {
        viewModelScope.launch {
            settingsStore.update { current ->
                current.copy(
                    webDavConfig = current.webDavConfig.copy(
                        url = url.trim(),
                        username = username,
                        password = password,
                        path = path.trim().ifBlank { "amber_agent_backups" },
                    )
                )
            }
            webDavMessage.value = backupMessage(R.string.backup_webdav_config_saved)
        }
    }

    fun refreshWebDavSnapshots() {
        if (!providerV2Enabled.value) return
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching { webDavSyncProvider.listSnapshots() }
                .onSuccess { snapshots ->
                    webDavSnapshots.value = snapshots
                    webDavMessage.value = if (snapshots.isEmpty()) {
                        backupMessage(R.string.backup_webdav_no_snapshots)
                    } else {
                        backupMessage(R.string.backup_snapshot_count, snapshots.size)
                    }
                    operationState.value = UiState.Idle
                }
                .onFailure { error ->
                    operationState.value = UiState.Error(error)
                    webDavMessage.value = backupMessage(R.string.backup_webdav_list_failed, error.message.orEmpty())
                }
        }
    }

    fun uploadWebDav(
        policy: UploadConflictPolicy?,
        passphrase: String = "",
        encryptionMode: SyncEncryptionMode = SyncEncryptionMode.PASSPHRASE,
    ) {
        if (!providerV2Enabled.value) return
        if (encryptionMode == SyncEncryptionMode.PASSPHRASE && passphrase.isBlank()) {
            operationState.value = UiState.Error(IllegalArgumentException("请输入自定义备份口令"))
            return
        }
        val resolved = resolveUploadPolicy(policy, webDavSnapshots.value)
        if (resolved == null) {
            webDavSnapshots.value.firstOrNull { it.manifest.deviceId == currentDeviceId() }?.let { snapshot ->
                pendingUploadConflict.value = UploadConflictChoice(snapshot, webDavSyncProvider.id)
                pendingUploadConflictRequest = PendingUploadConflictRequest(
                    providerId = webDavSyncProvider.id,
                    passphrase = passphrase,
                    encryptionMode = encryptionMode,
                )
            }
            return
        }
        viewModelScope.launch {
            operationState.value = UiState.Loading
            backupActivity.value = BackupActivity(
                titleRes = R.string.backup_activity_uploading_webdav_snapshot,
                detailRes = R.string.backup_activity_generating_encrypted_backup,
            )
            runCatching {
                webDavSyncProvider.uploadSnapshot(
                    SyncProviderUploadRequest(
                        mode = SyncMode.FULL,
                        passphrase = passphrase,
                        encryptionMode = encryptionMode,
                        conflictPolicy = resolved,
                    )
                )
            }.onSuccess { snapshot ->
                backupActivity.value = null
                webDavSnapshots.value = listOf(snapshot) + webDavSnapshots.value
                webDavMessage.value = backupMessage(R.string.backup_snapshot_uploaded, snapshot.name)
                operationState.value = UiState.Success(restoreSession.state.value.preview ?: SyncPreview(
                    manifest = snapshot.manifest.toArchiveManifest(),
                    fileName = snapshot.name,
                    sizeBytes = snapshot.sizeBytes,
                ))
            }.onFailure { error ->
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                webDavMessage.value = backupMessage(R.string.backup_webdav_upload_failed, error.message.orEmpty())
            }
        }
    }

    fun downloadWebDavSnapshot(snapshot: SyncSnapshot) {
        if (!providerV2Enabled.value) return
        viewModelScope.launch {
            cloudSnapshotPickerVisible.value = false
            operationState.value = UiState.Loading
            backupActivity.value = BackupActivity(
                titleRes = R.string.backup_activity_downloading_webdav_snapshot,
                detail = snapshot.name,
            )
            runCatching { webDavSyncProvider.downloadSnapshot(snapshot.snapshotId) }
                .onSuccess { file ->
                    backupActivity.value = null
                    val preview = withContext(Dispatchers.IO) {
                        archiveManager.inspectArchive(file, snapshot.name)
                    }
                    restoreSession.acceptDownloaded(BackupRestoreSource.Provider(file, webDavSyncProvider.id), preview)
                    operationState.value = UiState.Success(preview)
                }
                .onFailure { error ->
                    backupActivity.value = null
                    operationState.value = UiState.Error(error)
                    webDavMessage.value = backupMessage(R.string.backup_webdav_download_failed, error.message.orEmpty())
                }
        }
    }

    fun deleteWebDavSnapshot(snapshot: SyncSnapshot) {
        if (!providerV2Enabled.value) return
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching { webDavSyncProvider.deleteSnapshot(snapshot.snapshotId) }
                .onSuccess {
                    pendingDeleteConfirm.value = null
                    webDavSnapshots.value = webDavSnapshots.value.filterNot { it.snapshotId == snapshot.snapshotId }
                    webDavMessage.value = backupMessage(R.string.backup_remote_snapshot_deleted)
                    operationState.value = UiState.Idle
                }
                .onFailure { error ->
                    operationState.value = UiState.Error(error)
                    webDavMessage.value = backupMessage(R.string.backup_delete_failed, error.message.orEmpty())
                }
        }
    }

    fun deleteGoogleSnapshot(file: GoogleDriveFile) {
        if (googleConnection.googleUnavailable()) return
        val session = googleConnection.googleSession.value
        if (session == null) {
            googleConnection.connectGoogle()
            googleConnection.googleMessage.value = backupMessage(R.string.backup_google_authorization_required_delete)
            return
        }
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching {
                GoogleDriveSyncProvider(googleDriveSyncRepository, session).deleteSnapshot(file.id)
            }.onSuccess {
                pendingDeleteConfirm.value = null
                cloudSnapshots.value = cloudSnapshots.value.filterNot { it.id == file.id }
                googleConnection.googleMessage.value = backupMessage(R.string.backup_cloud_snapshot_deleted)
                operationState.value = UiState.Idle
            }.onFailure { error ->
                operationState.value = UiState.Error(error)
                googleConnection.googleMessage.value = backupMessage(R.string.backup_delete_failed, error.message.orEmpty())
                googleConnection.recordGoogleDriveError(error)
            }
        }
    }

    /** 请求删除 Google Drive 快照（复用统一的二次确认对话框）。 */
    fun requestDeleteGoogle(file: GoogleDriveFile) {
        pendingDeleteConfirm.value = SyncSnapshot(
            providerId = "google_drive",
            snapshotId = file.id,
            name = file.name,
            manifest = SyncSnapshotManifest(
                snapshotId = file.id,
                createdAt = file.backupCreatedAt ?: 0L,
                appVersionName = file.backupVersionName,
                appVersionCode = file.backupVersionCode ?: 0L,
                deviceLabel = file.backupDeviceLabel,
                sizeBytes = file.size?.toLongOrNull() ?: 0L,
            ),
        )
    }

    fun saveLocalFolder(uri: Uri, displayName: String) {
        if (!providerV2Enabled.value) return
        viewModelScope.launch {
            folderInfo.value = withContext(Dispatchers.IO) {
                persistedFolderStore.save(uri.toString(), displayName)
                persistedFolderStore.read()
            }
            folderMessage.value = backupMessage(
                R.string.backup_folder_selected,
                displayName.ifBlank { uri.toString() },
            )
            refreshLocalFolderSnapshots()
        }
    }

    fun refreshLocalFolderSnapshots() {
        if (!providerV2Enabled.value) return
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching { localFolderSyncProvider.listSnapshots() }
                .onSuccess { snapshots ->
                    localFolderSnapshots.value = snapshots
                    folderMessage.value = if (snapshots.isEmpty()) {
                        backupMessage(R.string.backup_folder_no_snapshots)
                    } else {
                        backupMessage(R.string.backup_snapshot_count, snapshots.size)
                    }
                    operationState.value = UiState.Idle
                }
                .onFailure { error ->
                    operationState.value = UiState.Error(error)
                    folderMessage.value = backupMessage(R.string.backup_folder_list_failed, error.message.orEmpty())
                }
        }
    }

    fun uploadLocalFolder(
        policy: UploadConflictPolicy?,
        passphrase: String = "",
        encryptionMode: SyncEncryptionMode = SyncEncryptionMode.PASSPHRASE,
    ) {
        if (!providerV2Enabled.value) return
        if (encryptionMode == SyncEncryptionMode.PASSPHRASE && passphrase.isBlank()) {
            operationState.value = UiState.Error(IllegalArgumentException("请输入自定义备份口令"))
            return
        }
        val resolved = resolveUploadPolicy(policy, localFolderSnapshots.value)
        if (resolved == null) {
            localFolderSnapshots.value.firstOrNull { it.manifest.deviceId == currentDeviceId() }?.let { snapshot ->
                pendingUploadConflict.value = UploadConflictChoice(snapshot, localFolderSyncProvider.id)
                pendingUploadConflictRequest = PendingUploadConflictRequest(
                    providerId = localFolderSyncProvider.id,
                    passphrase = passphrase,
                    encryptionMode = encryptionMode,
                )
            }
            return
        }
        viewModelScope.launch {
            operationState.value = UiState.Loading
            backupActivity.value = BackupActivity(
                titleRes = R.string.backup_activity_uploading_folder_snapshot,
                detailRes = R.string.backup_activity_generating_encrypted_backup,
            )
            runCatching {
                localFolderSyncProvider.uploadSnapshot(
                    SyncProviderUploadRequest(
                        mode = SyncMode.FULL,
                        passphrase = passphrase,
                        encryptionMode = encryptionMode,
                        conflictPolicy = resolved,
                    )
                )
            }.onSuccess { snapshot ->
                backupActivity.value = null
                localFolderSnapshots.value = listOf(snapshot) + localFolderSnapshots.value
                folderMessage.value = backupMessage(R.string.backup_snapshot_uploaded, snapshot.name)
                operationState.value = UiState.Success(SyncPreview(
                    manifest = snapshot.manifest.toArchiveManifest(),
                    fileName = snapshot.name,
                    sizeBytes = snapshot.sizeBytes,
                ))
            }.onFailure { error ->
                backupActivity.value = null
                operationState.value = UiState.Error(error)
                folderMessage.value = backupMessage(R.string.backup_folder_upload_failed, error.message.orEmpty())
            }
        }
    }

    fun downloadLocalFolderSnapshot(snapshot: SyncSnapshot) {
        if (!providerV2Enabled.value) return
        viewModelScope.launch {
            operationState.value = UiState.Loading
            backupActivity.value = BackupActivity(
                titleRes = R.string.backup_activity_downloading_folder_snapshot,
                detail = snapshot.name,
            )
            runCatching { localFolderSyncProvider.downloadSnapshot(snapshot.snapshotId) }
                .onSuccess { file ->
                    backupActivity.value = null
                    val preview = withContext(Dispatchers.IO) {
                        archiveManager.inspectArchive(file, snapshot.name)
                    }
                    restoreSession.acceptDownloaded(BackupRestoreSource.Provider(file, localFolderSyncProvider.id), preview)
                    operationState.value = UiState.Success(preview)
                }
                .onFailure { error ->
                    backupActivity.value = null
                    operationState.value = UiState.Error(error)
                    folderMessage.value = backupMessage(R.string.backup_folder_download_failed, error.message.orEmpty())
                }
        }
    }

    fun deleteLocalFolderSnapshot(snapshot: SyncSnapshot) {
        if (!providerV2Enabled.value) return
        viewModelScope.launch {
            operationState.value = UiState.Loading
            runCatching { localFolderSyncProvider.deleteSnapshot(snapshot.snapshotId) }
                .onSuccess {
                    pendingDeleteConfirm.value = null
                    localFolderSnapshots.value =
                        localFolderSnapshots.value.filterNot { it.snapshotId == snapshot.snapshotId }
                    folderMessage.value = backupMessage(R.string.backup_folder_snapshot_deleted)
                    operationState.value = UiState.Idle
                }
                .onFailure { error ->
                    operationState.value = UiState.Error(error)
                    folderMessage.value = backupMessage(R.string.backup_delete_failed, error.message.orEmpty())
                }
        }
    }

    fun requestDelete(snapshot: SyncSnapshot) {
        pendingDeleteConfirm.value = snapshot
    }

    fun confirmPendingDelete() {
        val snapshot = pendingDeleteConfirm.value ?: return
        when (snapshot.providerId) {
            webDavSyncProvider.id -> deleteWebDavSnapshot(snapshot)
            localFolderSyncProvider.id -> deleteLocalFolderSnapshot(snapshot)
            else -> {
                // Google Drive 的快照走 file 路径（snapshotId == file.id）。
                cloudSnapshots.value.firstOrNull { it.id == snapshot.snapshotId }?.let { file ->
                    deleteGoogleSnapshot(file)
                }
            }
        }
    }

    fun dismissPendingDelete() {
        pendingDeleteConfirm.value = null
    }

    fun requestUploadConflict(snapshot: SyncSnapshot) {
        pendingUploadConflict.value = UploadConflictChoice(
            snapshot = snapshot,
            providerId = snapshot.providerId,
        )
        pendingUploadConflictRequest = null
    }

    fun resolveUploadConflict(policy: UploadConflictPolicy) {
        val choice = pendingUploadConflict.value ?: return
        val request = pendingUploadConflictRequest
        pendingUploadConflict.value = null
        pendingUploadConflictRequest = null
        when (choice.providerId) {
            webDavSyncProvider.id -> uploadWebDav(
                policy = policy,
                passphrase = request?.takeIf { it.providerId == choice.providerId }?.passphrase.orEmpty(),
                encryptionMode = request?.takeIf { it.providerId == choice.providerId }?.encryptionMode
                    ?: SyncEncryptionMode.PASSPHRASE,
            )
            localFolderSyncProvider.id -> uploadLocalFolder(
                policy = policy,
                passphrase = request?.takeIf { it.providerId == choice.providerId }?.passphrase.orEmpty(),
                encryptionMode = request?.takeIf { it.providerId == choice.providerId }?.encryptionMode
                    ?: SyncEncryptionMode.PASSPHRASE,
            )
        }
    }

    fun dismissUploadConflict() {
        pendingUploadConflict.value = null
        pendingUploadConflictRequest = null
    }

    /**
     * 上传冲突策略解析：显式策略直接返回；null 时若远端已有本机同 deviceId
     * 快照则返回 null（调用方弹选择对话框），否则默认创建副本。
     */
    private fun resolveUploadPolicy(
        policy: UploadConflictPolicy?,
        existing: List<SyncSnapshot>,
    ): UploadConflictPolicy? {
        if (policy != null) return policy
        val hasSameDevice = existing.any {
            it.manifest.deviceId.isNotBlank() && it.manifest.deviceId == currentDeviceId()
        }
        return if (hasSameDevice) null else UploadConflictPolicy.CREATE_COPY
    }

    private fun currentDeviceId(): String = settingsStore.settingsFlow.value.syncSettings.deviceId

    fun clearOperationState() {
        operationState.value = UiState.Idle
    }


    private suspend fun recordError(error: Throwable) {
        settingsStore.update { current ->
            current.copy(
                syncSettings = current.syncSettings.copy(
                    lastError = error.message.orEmpty()
                )
            )
        }
    }


}


private data class PendingUploadConflictRequest(
    val providerId: String,
    val passphrase: String,
    val encryptionMode: SyncEncryptionMode,
)
