package app.amber.core.sync.core

import android.content.Context
import app.amber.agent.data.db.AppDatabase
import app.amber.core.sync.core.SyncArchiveManager.Companion.SYNC_FILE_ROOTS
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** File replacement and its SQLite commit evidence. The caller owns the one restore gate. */
internal class SyncFileRestoreJournal(
    private val filesDir: File,
    private val cacheDir: File,
    private val database: AppDatabase,
) {
    fun prepare(
        stageRoot: File,
        preservedRoots: Set<String> = emptySet(),
        mergeLocalRoots: Set<String> = emptySet(),
    ): Handle {
        val filesDir = filesDir.canonicalFile
        val replacedRoots = SYNC_FILE_ROOTS.filterNot { it in preservedRoots }
        val targets = replacedRoots.associateWith { relativeRoot ->
            File(filesDir, relativeRoot).canonicalFile.also { target ->
                require(target.path.startsWith(filesDir.path + File.separator)) {
                    "Invalid sync file root: $relativeRoot"
                }
            }
        }
        // CHAT_IMAGES has chat and gallery consumers. In mixed preservation modes,
        // import archive files and retain local files, with local bytes winning collisions.
        mergeLocalRoots.forEach { relativeRoot ->
            val local = targets.getValue(relativeRoot)
            if (local.exists()) local.copyRecursively(File(stageRoot, relativeRoot), overwrite = true)
        }
        // Novel and artifact roots are nested below amberagent; restore complete roots,
        // never their shared parent directory.
        val stagedRoots = replacedRoots.associateWith { File(stageRoot, it).takeIf(File::exists) }
        val journalRoot = File(cacheDir, FILE_RESTORE_JOURNAL_DIR).canonicalFile
        val backupRoot = File(journalRoot, "backup").canonicalFile
        val restoreToken = UUID.randomUUID().toString()
        journalRoot.deleteRecursively()
        backupRoot.mkdirs()
        writeJournalText(File(journalRoot, FILE_RESTORE_ROOTS), replacedRoots.joinToString("\n"))
        writeJournalText(File(journalRoot, FILE_RESTORE_TOKEN), restoreToken)
        writeJournalText(File(journalRoot, FILE_RESTORE_STATE), FILE_RESTORE_PENDING)
        val journal = Handle(
            filesDir = filesDir,
            journalRoot = journalRoot,
            backupRoot = backupRoot,
            replacedRoots = replacedRoots,
            token = restoreToken,
        )
        try {
            replacedRoots.forEach { relativeRoot ->
                val target = targets.getValue(relativeRoot)
                if (target.exists()) {
                    val backup = File(backupRoot, relativeRoot).canonicalFile
                    backup.parentFile?.mkdirs()
                    if (!target.renameTo(backup)) {
                        target.copyRecursively(backup, overwrite = true)
                        target.deleteRecursively()
                    }
                }
            }
            writeJournalText(File(journalRoot, FILE_RESTORE_STATE), FILE_RESTORE_ROOTS_BACKED_UP)
            replacedRoots.forEach { relativeRoot ->
                stagedRoots[relativeRoot]?.copyRecursively(
                    target = File(filesDir, relativeRoot),
                    overwrite = true,
                )
            }
            writeJournalText(File(journalRoot, FILE_RESTORE_STATE), FILE_RESTORE_FILES_REPLACED)
            return journal
        } catch (error: Throwable) {
            runCatching { journal.rollback() }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    private fun committedFileRestoreToken(journalRoot: File): String? {
        val token = File(journalRoot, FILE_RESTORE_TOKEN).takeIf { it.isFile }?.readText() ?: return null
        return token.takeIf { readDatabaseRestoreMarker() == it }
    }

    fun writeDatabaseMarker(db: androidx.sqlite.db.SupportSQLiteDatabase, token: String) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS $RESTORE_MARKER_TABLE " +
                "(id INTEGER PRIMARY KEY CHECK (id = 1), token TEXT NOT NULL)"
        )
        db.execSQL(
            "INSERT OR REPLACE INTO $RESTORE_MARKER_TABLE(id, token) VALUES (1, ?)",
            arrayOf(token),
        )
    }

    private fun readDatabaseRestoreMarker(): String? {
        val db = database.openHelper.writableDatabase
        val exists = db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(RESTORE_MARKER_TABLE),
        ).use { it.moveToFirst() }
        if (!exists) return null
        return db.query("SELECT token FROM $RESTORE_MARKER_TABLE WHERE id = 1").use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun clearDatabaseRestoreMarker(token: String) {
        runCatching {
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM $RESTORE_MARKER_TABLE WHERE id = 1 AND token = ?",
                arrayOf(token),
            )
        }
    }

    private fun writeJournalText(file: File, value: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            output.write(value.toByteArray())
            output.fd.sync()
        }
    }

    inner class Handle(
        private val filesDir: File,
        private val journalRoot: File,
        private val backupRoot: File,
        private val replacedRoots: List<String>,
        val token: String,
    ) {
        fun markDataCommitted() {
            markPhase(FILE_RESTORE_DATA_COMMITTED)
        }

        fun markSecretsApplied() = markPhase(FILE_RESTORE_SECRETS_APPLIED)

        fun markFtsApplied() = markPhase(FILE_RESTORE_FTS_APPLIED)

        fun markSettingsApplied() = markPhase(FILE_RESTORE_SETTINGS_APPLIED)

        private fun markPhase(phase: String) {
            writeJournalText(File(journalRoot, FILE_RESTORE_STATE), phase)
        }

        fun commit() = completeCommitted(token)

        fun rollback() {
            val state = File(journalRoot, FILE_RESTORE_STATE).takeIf { it.isFile }?.readText()
            val backupsComplete = state != null && state != FILE_RESTORE_PENDING
            replacedRoots.forEach { relativeRoot ->
                val backup = File(backupRoot, relativeRoot)
                val target = File(filesDir, relativeRoot)
                // Before backup completes, a later root may still be the untouched original.
                if (backupsComplete || backup.exists()) target.deleteRecursively()
                if (backup.exists()) backup.copyRecursively(target, overwrite = true)
            }
            journalRoot.deleteRecursively()
        }
    }

    val rootDirectory: File get() = File(cacheDir, FILE_RESTORE_JOURNAL_DIR).canonicalFile

    fun interruptedRoots(): List<String> = File(rootDirectory, FILE_RESTORE_ROOTS)
        .takeIf { it.isFile }?.readLines().orEmpty().filter { it in SYNC_FILE_ROOTS }

    fun committedToken(): String? = committedFileRestoreToken(rootDirectory)

    fun completeCommitted(token: String) {
        check(rootDirectory.deleteRecursively()) { "Unable to clean completed restore journal" }
        clearDatabaseRestoreMarker(token)
    }

    fun rollbackInterrupted() {
        Handle(
            filesDir = filesDir.canonicalFile,
            journalRoot = rootDirectory,
            backupRoot = File(rootDirectory, "backup").canonicalFile,
            replacedRoots = interruptedRoots(),
            token = File(rootDirectory, FILE_RESTORE_TOKEN).takeIf { it.isFile }?.readText().orEmpty(),
        ).rollback()
    }

    companion object {
        fun hasInterruptedRestore(context: Context): Boolean =
            File(context.cacheDir, FILE_RESTORE_JOURNAL_DIR).exists()

        private const val FILE_RESTORE_JOURNAL_DIR = "sync-restore-file-journal"
        private const val FILE_RESTORE_STATE = "state"
        private const val FILE_RESTORE_ROOTS = "roots"
        private const val FILE_RESTORE_TOKEN = "token"
        private const val FILE_RESTORE_PENDING = "pending"
        private const val FILE_RESTORE_ROOTS_BACKED_UP = "roots_backed_up"
        private const val FILE_RESTORE_FILES_REPLACED = "files_replaced"
        private const val FILE_RESTORE_DATA_COMMITTED = "data_committed"
        private const val FILE_RESTORE_SECRETS_APPLIED = "secrets_applied"
        private const val FILE_RESTORE_FTS_APPLIED = "fts_applied"
        private const val FILE_RESTORE_SETTINGS_APPLIED = "settings_applied"
        private const val RESTORE_MARKER_TABLE = "amber_sync_restore_marker"
    }
}
