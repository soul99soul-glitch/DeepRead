package app.amber.feature.ui.pages.backup

import app.amber.feature.ui.pages.backup.components.BackupDialog
import app.amber.agent.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.amber.core.sync.core.SYNC_ARCHIVE_MIME
import app.amber.core.sync.core.SyncRestorePartialCommitException
import app.amber.core.sync.local.LocalBackupRepository
import android.provider.OpenableColumns
import app.amber.feature.ui.components.ds.amberCanvas
import app.amber.feature.ui.components.nav.BackButton
import app.amber.feature.ui.components.ui.WorkspaceTopBar
import app.amber.core.utils.UiState
import org.koin.androidx.compose.koinViewModel

private enum class GoogleSyncAction {
    Upload,
    Download,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupPage(vm: BackupVM = koinViewModel()) {
    val settings by vm.settings.collectAsState()
    val restoreSession = vm.restoreSession
    val restoreState by restoreSession.state.collectAsState()
    val googleConnection = vm.googleConnection
    val transferOperation by vm.operationState.collectAsState()
    val operationState = if (restoreState.operation !is UiState.Idle) {
        restoreState.operation
    } else {
        transferOperation
    }
    val importPreview = restoreState.preview
    val googleSession by googleConnection.googleSession.collectAsState()
    val googleMessage by googleConnection.googleMessage.collectAsState()
    val localMessage by vm.localMessage.collectAsState()
    val transferActivity by vm.backupActivity.collectAsState()
    val backupActivity = restoreState.activity ?: transferActivity
    val pendingGoogleAuthorization by googleConnection.pendingGoogleAuthorization.collectAsState()
    val cloudConflict by vm.cloudConflict.collectAsState()
    val cloudSnapshots by vm.cloudSnapshots.collectAsState()
    val cloudSnapshotPickerVisible by vm.cloudSnapshotPickerVisible.collectAsState()
    val context = LocalContext.current
    // P7-01 provider 状态。
    val providerV2Enabled by vm.providerV2Enabled.collectAsState()
    val webDavSnapshots by vm.webDavSnapshots.collectAsState()
    val webDavMessage by vm.webDavMessage.collectAsState()
    val localFolderSnapshots by vm.localFolderSnapshots.collectAsState()
    val folderMessage by vm.folderMessage.collectAsState()
    val folderInfo by vm.folderInfo.collectAsState()
    val pendingDeleteConfirm by vm.pendingDeleteConfirm.collectAsState()
    val pendingUploadConflict by vm.pendingUploadConflict.collectAsState()
    val pendingExportDialog by vm.pendingExportDialog.collectAsState()
    val verifiedRestore = restoreState.verification
    val restoreMessageText = restoreState.message.resolveBackupMessage()
    val restoreErrorText = restoreMessageText.takeIf { restoreState.operation is UiState.Error }
    val googleMessageText = restoreErrorText ?: googleMessage.resolveBackupMessage()
    val localMessageText = restoreMessageText ?: localMessage.resolveBackupMessage()
    val webDavMessageText = restoreErrorText ?: webDavMessage.resolveBackupMessage()
    val folderMessageText = restoreErrorText ?: folderMessage.resolveBackupMessage()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var pendingGoogleAction by remember { mutableStateOf<GoogleSyncAction?>(null) }
    var showGoogleConfigDetails by remember { mutableStateOf(false) }
    // Restore scope is always EVERYTHING; within it the user opts in to
    // including chat history / generated images. Default OFF for both — the
    // safe path is "leave local chat & gallery alone". User framed it as:
    // "下载的时候有两个选项可以选，不勾选的话恢复就不会恢复这俩".
    // (Engine API still speaks in terms of `preserveX` = the inverse —
    // we translate at the call site.)
    var restoreConversations by remember { mutableStateOf(false) }
    var restoreGenMedia by remember { mutableStateOf(false) }
    val googleAvailable = googleConnection.googleConfigStatus.available
    val hasGoogleConnection = googleSession != null ||
        (settings.syncSettings.googleEnabled && settings.syncSettings.googleAccountEmail.isNotBlank())

    val googleAuthorizationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.data == null) {
            pendingGoogleAction = null
        }
        googleConnection.handleGoogleAuthorizationResult(result.resultCode, result.data)
    }

    LaunchedEffect(pendingGoogleAuthorization) {
        val pendingIntent = pendingGoogleAuthorization ?: return@LaunchedEffect
        googleConnection.consumePendingGoogleAuthorization()
        googleAuthorizationLauncher.launch(
            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
        )
    }

    LaunchedEffect(googleSession, pendingGoogleAction) {
        val action = pendingGoogleAction
        if (googleSession != null && action != null) {
            pendingGoogleAction = null
            when (action) {
                GoogleSyncAction.Upload -> vm.requestExport(ExportSource.Google)
                GoogleSyncAction.Download -> vm.downloadGooglePreview()
            }
        }
    }

    LaunchedEffect(operationState, googleSession) {
        if (googleSession == null && operationState is UiState.Error) {
            pendingGoogleAction = null
        }
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(SYNC_ARCHIVE_MIME),
    ) { uri ->
        if (uri != null) {
            // P7-02：导出必须选择口令加密或设备绑定加密（二选一），
            // 由 ExportEncryptionDialog 收集后调用 vm.confirmExport。
            vm.requestExport(ExportRequestSeed.localFile(uri))
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            restoreSession.inspectImport(uri)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // P7-01：持久 URI permission —— 授权跨进程重启保留，下次直接复用文件夹。
            val contentResolver = context.contentResolver
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            val displayName = runCatching {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                }
            }.getOrNull().orEmpty()
            vm.saveLocalFolder(uri, displayName)
        }
    }

    // P7-01 WebDAV 配置草稿（本地编辑态，保存时才写入 settings）。
    var webDavUrl by remember { mutableStateOf(settings.webDavConfig.url) }
    var webDavUsername by remember { mutableStateOf(settings.webDavConfig.username) }
    var webDavPassword by remember { mutableStateOf(settings.webDavConfig.password) }
    var webDavPath by remember { mutableStateOf(settings.webDavConfig.path) }
    var webDavUrlDirty by remember { mutableStateOf(false) }
    var webDavUsernameDirty by remember { mutableStateOf(false) }
    var webDavPasswordDirty by remember { mutableStateOf(false) }
    var webDavPathDirty by remember { mutableStateOf(false) }
    val latestWebDavDraft by rememberUpdatedState(
        WebDavDraft(
            url = webDavUrl,
            username = webDavUsername,
            password = webDavPassword,
            path = webDavPath,
            urlDirty = webDavUrlDirty,
            usernameDirty = webDavUsernameDirty,
            passwordDirty = webDavPasswordDirty,
            pathDirty = webDavPathDirty,
        )
    )

    LaunchedEffect(settings.init, settings.webDavConfig) {
        if (settings.init) return@LaunchedEffect
        val synced = latestWebDavDraft.syncFrom(settings.webDavConfig)
        webDavUrl = synced.url
        webDavUsername = synced.username
        webDavPassword = synced.password
        webDavPath = synced.path
    }

    Scaffold(
        topBar = {
            WorkspaceTopBar(
                title = stringResource(R.string.backup_page_title),
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .amberCanvas(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GoogleBackupPanel(
                googleAvailable = googleAvailable,
                credentialManagerAvailable = googleConnection.googleConfigStatus.credentialManagerAvailable,
                googleSessionLabel = googleSession?.label,
                hasGoogleConnection = hasGoogleConnection,
                syncSettings = settings.syncSettings,
                googleMessageText = googleMessageText,
                backupActivity = backupActivity,
                onConnect = googleConnection::connectGoogle,
                onShowConfigDetails = { showGoogleConfigDetails = true },
                onUpload = {
                    if (googleSession == null) {
                        pendingGoogleAction = GoogleSyncAction.Upload
                        googleConnection.connectGoogle()
                    } else {
                        vm.requestExport(ExportSource.Google)
                    }
                },
                onDownload = {
                    if (googleSession == null) {
                        pendingGoogleAction = GoogleSyncAction.Download
                        googleConnection.connectGoogle()
                    } else {
                        vm.downloadGooglePreview()
                    }
                },
            )
            if (providerV2Enabled) {
                WebDavBackupPanel(
                    draft = latestWebDavDraft,
                    webDavMessageText = webDavMessageText,
                    webDavSnapshots = webDavSnapshots,
                    onUrlChange = {
                        webDavUrl = it
                        webDavUrlDirty = true
                    },
                    onUsernameChange = {
                        webDavUsername = it
                        webDavUsernameDirty = true
                    },
                    onPasswordChange = {
                        webDavPassword = it
                        webDavPasswordDirty = true
                    },
                    onPathChange = {
                        webDavPath = it
                        webDavPathDirty = true
                    },
                    onSave = {
                        vm.saveWebDavConfig(webDavUrl, webDavUsername, webDavPassword, webDavPath)
                        webDavUrlDirty = false
                        webDavUsernameDirty = false
                        webDavPasswordDirty = false
                        webDavPathDirty = false
                    },
                    onRefresh = vm::refreshWebDavSnapshots,
                    onUpload = { vm.requestExport(ExportSource.WebDav) },
                    onRestore = vm::downloadWebDavSnapshot,
                    onDelete = vm::requestDelete,
                )
                FolderBackupPanel(
                    folderDisplayName = folderInfo?.displayName,
                    folderMessageText = folderMessageText,
                    localFolderSnapshots = localFolderSnapshots,
                    onChooseFolder = { folderPickerLauncher.launch(null) },
                    onRefresh = vm::refreshLocalFolderSnapshots,
                    onUpload = { vm.requestExport(ExportSource.LocalFolder) },
                    onRestore = vm::downloadLocalFolderSnapshot,
                    onDelete = vm::requestDelete,
                )
            }
            LocalBackupPanel(
                localMessageText = localMessageText,
                onExport = { createDocumentLauncher.launch(LocalBackupRepository.suggestedFileName()) },
                onImport = {
                    restoreSession.dismiss()
                    vm.dismissCloudSnapshotPicker()
                    openDocumentLauncher.launch(arrayOf(SYNC_ARCHIVE_MIME, "application/zip", "*/*"))
                },
            )
            Spacer(Modifier.height(12.dp))
        }
    }

    if (cloudSnapshotPickerVisible) {
        CloudSnapshotPickerDialog(
            snapshots = cloudSnapshots,
            onDismiss = { vm.dismissCloudSnapshotPicker() },
            onSelect = { vm.downloadGoogleSnapshot(it) },
            onDelete = { vm.requestDeleteGoogle(it) },
        )
    }

    // P7-02 恢复两阶段：先输入口令并验证（头部 + 认证标签 + 解密，不写入），
    // 解密成功后展示恢复 preview（复用 ImportPreviewDialog），确认后才写入。
    if (importPreview != null && verifiedRestore == null) {
        val restoreRetryHint = (operationState as? UiState.Error)?.error?.let { error ->
            if (error is SyncRestorePartialCommitException) {
                stringResource(R.string.restore_partial_commit_retry)
            } else {
                stringResource(R.string.restore_apply_failed_retry)
            }
        }
        RestorePassphraseDialog(
            preview = importPreview,
            verifying = operationState is UiState.Loading,
            onDismiss = {
                restoreSession.dismiss()
                restoreConversations = false
                restoreGenMedia = false
            },
            onVerify = { passphrase ->
                // UI's "restoreX" maps inversely to engine's "preserveX".
                val preserveConversations = !restoreConversations
                val preserveGenMedia = !restoreGenMedia
                restoreSession.verify(
                    passphrase = passphrase,
                    preserveConversations = preserveConversations,
                    preserveGenMedia = preserveGenMedia,
                )
            },
            retryHint = restoreRetryHint,
        )
    }

    verifiedRestore?.let { verification ->
        ImportPreviewDialog(
            preview = verification.preview,
            payloadPreview = verification.payloadPreview,
            restoreConversations = restoreConversations,
            restoreGenMedia = restoreGenMedia,
            restoreActivity = restoreState.activity,
            restoring = restoreState.applying,
            onRestoreConversationsChange = { restoreConversations = it },
            onRestoreGenMediaChange = { restoreGenMedia = it },
            onDismiss = {
                restoreSession.dismiss()
                // Reset toggles to safe defaults (don't override local data)
                // for the next restore.
                restoreConversations = false
                restoreGenMedia = false
            },
            onRestore = {
                // UI's "restoreX" maps inversely to engine's "preserveX".
                val preserveConversations = !restoreConversations
                val preserveGenMedia = !restoreGenMedia
                restoreSession.apply(
                    preserveConversations = preserveConversations,
                    preserveGenMedia = preserveGenMedia,
                )
            },
        )
    }

    pendingExportDialog?.let { seed ->
        ExportEncryptionDialog(
            seed = seed,
            onDismiss = { vm.dismissExportDialog() },
            onConfirm = { passphrase, encryptionMode ->
                vm.confirmExport(passphrase, encryptionMode)
            },
        )
    }

    pendingDeleteConfirm?.let { snapshot ->
        BackupDeleteSnapshotDialog(snapshot, vm::dismissPendingDelete, vm::confirmPendingDelete)
    }

    pendingUploadConflict?.let { choice ->
        BackupUploadConflictDialog(choice, vm::dismissUploadConflict, vm::resolveUploadConflict)
    }

    if (restoreState.completed != null) {
        // 整量恢复替换了全部表和文件，但内存会话与 Room Flow 不会自动失效——
        // 必须强制重启进程，否则旧内存状态会被重新写回新库造成数据混合。
        // （BackupDialog 仅一个确认按钮，点击后 exitProcess(0)）
        BackupDialog()
    }

    if (showGoogleConfigDetails) {
        GoogleBackupConfigDialog(context.packageName, onDismiss = { showGoogleConfigDetails = false })
    }

    cloudConflict?.let { conflict ->
        GoogleBackupConflictDialog(conflict, vm::dismissCloudConflict, vm::confirmOverwriteCloud)
    }
}
