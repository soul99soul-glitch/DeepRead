package app.amber.feature.ui.pages.backup

import android.net.Uri
import androidx.annotation.StringRes
import app.amber.core.sync.core.SyncEncryptionMode
import app.amber.core.sync.core.SyncPreview
import app.amber.core.sync.google.GoogleDriveFile
import app.amber.core.sync.provider.SyncSnapshot
import app.amber.core.sync.provider.UploadConflictPolicy

data class GoogleCloudConflict(
    val remoteFile: GoogleDriveFile,
    val localRevision: String,
)

/** P7-01：上传冲突选择（覆盖本机旧快照 / 创建副本）。 */
data class UploadConflictChoice(
    val snapshot: SyncSnapshot,
    val providerId: String,
)

data class BackupMessage(
    @StringRes val resourceId: Int,
    val formatArgs: List<Any> = emptyList(),
)

internal fun backupMessage(@StringRes resourceId: Int, vararg formatArgs: Any): BackupMessage =
    BackupMessage(resourceId = resourceId, formatArgs = formatArgs.toList())

data class BackupActivity(
    @StringRes val titleRes: Int,
    @StringRes val detailRes: Int? = null,
    val detail: String = "",
    val progress: Float? = null,
)

/**
 * P7-02：导出加密方式选择弹窗的请求种子。
 * - [ExportSource.LocalFile] 携带已选保存 URI（文档选择器回调后弹窗）。
 * - provider 上传携带冲突策略（弹窗确认后解析）。
 */
data class ExportRequestSeed(
    val source: ExportSource,
    val conflictPolicy: UploadConflictPolicy? = null,
    val uri: android.net.Uri? = null,
) {
    companion object {
        fun localFile(uri: android.net.Uri) = ExportRequestSeed(ExportSource.LocalFile, uri = uri)
    }
}

enum class ExportSource {
    Google,
    WebDav,
    LocalFolder,
    LocalFile,
}

/** P7-02：恢复是否需要用户输入口令（v2 口令模式与 v1 受口令保护的旧格式需要）。 */
fun restoreNeedsPassphrase(preview: SyncPreview): Boolean = when {
    preview.legacyFormat && !preview.manifest.passphraseProtected -> false
    preview.manifest.encryptionMode == SyncEncryptionMode.DEVICE_BOUND -> false
    else -> true
}
