package app.amber.feature.ui.pages.backup

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import app.amber.core.settings.prefs.SettingsAggregator
import app.amber.agent.R
import app.amber.core.sync.core.SyncEncryptionMode
import app.amber.core.sync.core.SyncPreview
import app.amber.core.sync.google.GoogleDriveAuthSession
import app.amber.core.sync.google.GoogleDriveAuthRequiredException
import app.amber.core.sync.google.GoogleDriveAuthorizationOutcome
import app.amber.core.sync.google.GoogleDriveSyncRepository
import app.amber.core.sync.google.GoogleOAuthConfigGate
import app.amber.core.utils.UiState

class BackupGoogleConnection(
    private val settingsStore: SettingsAggregator,
    private val googleDriveSyncRepository: GoogleDriveSyncRepository,
    googleOAuthConfigGate: GoogleOAuthConfigGate,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val operationState: MutableStateFlow<UiState<SyncPreview>>,
    private val onUploadReady: (String, SyncEncryptionMode) -> Unit,
) {
    private companion object { const val TAG = "BackupVM" }
    val googleConfigStatus = googleOAuthConfigGate.status()
    val googleSession = MutableStateFlow<GoogleDriveAuthSession?>(null)
    val googleMessage = MutableStateFlow<BackupMessage?>(null)
    val pendingGoogleAuthorization = MutableStateFlow<PendingIntent?>(null)
    private var googleAuthorizationInFlight = false
    private var pendingGoogleUpload: PendingGoogleUpload? = null

    fun deferUpload(passphrase: String, encryptionMode: SyncEncryptionMode) {
        pendingGoogleUpload = PendingGoogleUpload(passphrase, encryptionMode)
        connectGoogle()
    }

    fun connectGoogle() {
        if (googleUnavailable()) return
        if (googleAuthorizationInFlight) return
        googleAuthorizationInFlight = true
        scope.launch {
            operationState.value = UiState.Loading
            try {
                runCatching {
                    googleDriveSyncRepository.authorizeDrive()
                }.onSuccess { outcome ->
                    when (outcome) {
                        is GoogleDriveAuthorizationOutcome.Authorized -> {
                            applyGoogleSession(outcome.session)
                            operationState.value = UiState.Idle
                        }

                        is GoogleDriveAuthorizationOutcome.ResolutionRequired -> {
                            pendingGoogleAuthorization.value = outcome.pendingIntent
                            operationState.value = UiState.Idle
                        }
                    }
                }.onFailure { error ->
                    operationState.value = UiState.Error(error)
                    googleMessage.value = backupMessage(R.string.backup_google_authorization_failed, error.message.orEmpty())
                    recordError(error)
                }
            } finally {
                if (pendingGoogleAuthorization.value == null) {
                    googleAuthorizationInFlight = false
                }
            }
        }
    }

    fun consumePendingGoogleAuthorization() {
        pendingGoogleAuthorization.value = null
    }

    fun completeGoogleAuthorization(intent: Intent?) {
        scope.launch {
            operationState.value = UiState.Loading
            try {
                runCatching {
                    googleDriveSyncRepository.completeAuthorization(intent)
                }.onSuccess { session ->
                    applyGoogleSession(session)
                    operationState.value = UiState.Idle
                }.onFailure { error ->
                    operationState.value = UiState.Error(error)
                    googleMessage.value = backupMessage(R.string.backup_google_authorization_failed, error.message.orEmpty())
                    recordError(error)
                }
            } finally {
                googleAuthorizationInFlight = false
            }
        }
    }

    fun handleGoogleAuthorizationResult(resultCode: Int, intent: Intent?) {
        Log.i(TAG, "Google authorization result: resultCode=$resultCode hasData=${intent != null}")
        if (intent != null) {
            completeGoogleAuthorization(intent)
        } else {
            cancelGoogleAuthorization(resultCode)
        }
    }

    fun cancelGoogleAuthorization(resultCode: Int? = null) {
        googleAuthorizationInFlight = false
        // A cancelled auth must also cancel the export that was waiting for
        // it. Keeping the passphrase/request here would otherwise cause a
        // later, unrelated successful connection to upload without a fresh
        // user confirmation.
        pendingGoogleUpload = null
        googleMessage.value = if (resultCode == 0) {
            backupMessage(R.string.backup_google_authorization_not_completed)
        } else {
            backupMessage(R.string.backup_google_authorization_cancelled)
        }
        operationState.value = UiState.Idle
    }

    suspend fun restoreGoogleSessionIfPossible() {
        if (!googleConfigStatus.available) {
            return
        }
        val syncSettings = settingsStore.settingsFlow.value.syncSettings
        if (!syncSettings.googleEnabled || syncSettings.googleAccountEmail.isBlank()) return
        if (googleAuthorizationInFlight || googleSession.value != null) return
        googleMessage.value = backupMessage(R.string.backup_activity_restoring_google_connection)
        runCatching {
            googleDriveSyncRepository.restoreAuthorizedSession()
        }.onSuccess { outcome ->
            when (outcome) {
                is GoogleDriveAuthorizationOutcome.Authorized -> {
                    applyGoogleSession(outcome.session)
                }

                is GoogleDriveAuthorizationOutcome.ResolutionRequired -> {
                    googleMessage.value = backupMessage(
                        R.string.backup_google_reauthorize,
                        syncSettings.googleAccountEmail,
                    )
                }
            }
        }.onFailure { error ->
            googleMessage.value = backupMessage(R.string.backup_google_connection_failed, error.message.orEmpty())
            recordError(error)
        }
    }

    suspend fun refreshGoogleSessionForOperation(): GoogleDriveAuthSession? {
        if (!googleConfigStatus.available) return null
        val syncSettings = settingsStore.settingsFlow.value.syncSettings
        if (!syncSettings.googleEnabled && googleSession.value == null) return null
        return runCatching {
            googleDriveSyncRepository.restoreAuthorizedSession()
        }.mapCatching { outcome ->
            when (outcome) {
                is GoogleDriveAuthorizationOutcome.Authorized -> {
                    applyGoogleSession(outcome.session)
                    outcome.session
                }

                is GoogleDriveAuthorizationOutcome.ResolutionRequired -> {
                    googleSession.value
                }
            }
        }.getOrElse {
            googleSession.value
        }
    }

    private suspend fun applyGoogleSession(session: GoogleDriveAuthSession) {
        googleSession.value = session
        googleMessage.value = backupMessage(R.string.backup_google_connected, session.label)
        settingsStore.update { current ->
            current.copy(
                syncSettings = current.syncSettings.copy(
                    googleEnabled = true,
                    googleAccountEmail = session.accountEmail,
                    googleAccountId = session.accountId,
                    googleDisplayName = session.displayName,
                    lastError = "",
                )
            )
        }
        // P7-02：授权完成前已选好的导出（口令 + 加密方式）在此自动继续。
        pendingGoogleUpload?.let { pending ->
            pendingGoogleUpload = null
            onUploadReady(pending.passphrase, pending.encryptionMode)
        }
    }

    suspend fun recordGoogleDriveError(error: Throwable) {
        if (error is GoogleDriveAuthRequiredException) {
            runCatching {
                googleDriveSyncRepository.clearCachedToken(error.accessToken)
            }
            googleSession.value = null
            googleMessage.value = backupMessage(R.string.backup_google_authorization_expired)
            settingsStore.update { current ->
                current.copy(
                    syncSettings = current.syncSettings.copy(
                        lastError = error.message.orEmpty()
                    )
                )
            }
            return
        }
        recordError(error)
    }

    fun googleUnavailable(): Boolean {
        if (googleConfigStatus.available) return false
        val error = IllegalStateException(googleConfigStatus.reason)
        operationState.value = UiState.Error(error)
        return true
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

private data class PendingGoogleUpload(val passphrase: String, val encryptionMode: SyncEncryptionMode)
