package com.yang136.sshhelper.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yang136.sshhelper.diagnosticlog.DiagnosticEventEntity
import com.yang136.sshhelper.diagnosticlog.DiagnosticLogDao
import com.yang136.sshhelper.diagnosticlog.DiagnosticTraceEntity
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun authType(value: String): AuthType = AuthType.valueOf(value)
    @TypeConverter fun authType(value: AuthType): String = value.name
    @TypeConverter fun proxyType(value: String?): ProxyType? = value?.let { ProxyType.valueOf(it) }
    @TypeConverter fun proxyType(value: ProxyType?): String? = value?.name
    @TypeConverter fun transferDirection(value: String): TransferDirection = TransferDirection.valueOf(value)
    @TypeConverter fun transferDirection(value: TransferDirection): String = value.name
    @TypeConverter fun transferStatus(value: String): TransferStatus = TransferStatus.valueOf(value)
    @TypeConverter fun transferStatus(value: TransferStatus): String = value.name
    @TypeConverter fun conflictPolicy(value: String): ConflictPolicy = ConflictPolicy.valueOf(value)
    @TypeConverter fun conflictPolicy(value: ConflictPolicy): String = value.name
    @TypeConverter fun forwardType(value: String): ForwardType = ForwardType.valueOf(value)
    @TypeConverter fun forwardType(value: ForwardType): String = value.name
}

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY COALESCE(lastConnectedAt, 0) DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts")
    suspend fun getAll(): List<HostEntity>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun get(id: Long): HostEntity?

    @Insert suspend fun insert(host: HostEntity): Long
    @Update suspend fun update(host: HostEntity)
    @Delete suspend fun delete(host: HostEntity)

    @Query("UPDATE hosts SET lastConnectedAt = :time WHERE id = :id")
    suspend fun markConnected(id: Long, time: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM hosts WHERE jumpHostId = :hostId")
    suspend fun jumpDependentCount(hostId: Long): Int
}

@Dao
interface SecretDao {
    @Query("SELECT * FROM secrets WHERE hostId = :hostId LIMIT 1")
    suspend fun getForHost(hostId: Long): SecretEntity?

    @Query("SELECT * FROM secrets")
    suspend fun getAll(): List<SecretEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(secret: SecretEntity)

    @Query("DELETE FROM secrets WHERE hostId = :hostId")
    suspend fun deleteForHost(hostId: Long)

    @Query("DELETE FROM secrets")
    suspend fun deleteAll()
}

@Dao
interface VaultMetadataDao {
    @Query("SELECT * FROM vault_metadata WHERE id = 1")
    suspend fun get(): VaultMetadataEntity?

    @Query("SELECT * FROM vault_metadata WHERE id = 1")
    fun observe(): Flow<VaultMetadataEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(metadata: VaultMetadataEntity)
}

@Dao
interface LocalRootDao {
    @Query("SELECT * FROM local_roots ORDER BY sortOrder, displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<LocalRootEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(root: LocalRootEntity): Long

    @Delete suspend fun delete(root: LocalRootEntity)
}

@Dao
interface SftpBookmarkDao {
    @Query("SELECT * FROM sftp_bookmarks WHERE hostId = :hostId ORDER BY label COLLATE NOCASE")
    fun observeForHost(hostId: Long): Flow<List<SftpBookmarkEntity>>

    @Insert suspend fun insert(bookmark: SftpBookmarkEntity): Long
    @Delete suspend fun delete(bookmark: SftpBookmarkEntity)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfer_jobs ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TransferJobEntity>>

    @Query("SELECT * FROM transfer_jobs WHERE status IN ('QUEUED','RUNNING','PAUSED','WAITING_NETWORK','WAITING_UNLOCK') ORDER BY createdAt")
    suspend fun pending(): List<TransferJobEntity>

    @Query("UPDATE transfer_jobs SET status = 'PAUSED', error = '应用重新启动，请手动继续', updatedAt = :updatedAt WHERE status IN ('QUEUED','RUNNING','WAITING_NETWORK','WAITING_UNLOCK')")
    suspend fun pauseInterrupted(updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE transfer_jobs SET status = 'QUEUED', error = NULL, updatedAt = :updatedAt WHERE status = 'WAITING_UNLOCK'")
    suspend fun requeueWaitingUnlock(updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM transfer_jobs WHERE id = :id")
    suspend fun get(id: Long): TransferJobEntity?

    @Insert suspend fun insertBatch(batch: TransferBatchEntity): Long
    @Insert suspend fun insertJob(job: TransferJobEntity): Long
    @Update suspend fun updateJob(job: TransferJobEntity)

    @Query("UPDATE transfer_jobs SET status = :status, error = :error, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setStatus(id: Long, status: TransferStatus, error: String? = null, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE transfer_jobs SET transferredBytes = :bytes, totalBytes = :total, status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setProgress(id: Long, bytes: Long, total: Long, status: TransferStatus, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE transfer_jobs SET temporaryPath = :path, totalBytes = CASE WHEN :total >= 0 THEN :total ELSE totalBytes END, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setTemporaryPath(id: Long, path: String?, total: Long = -1, updatedAt: Long = System.currentTimeMillis())
}

@Dao
interface PortForwardRuleDao {
    @Query("SELECT * FROM port_forward_rules ORDER BY id")
    fun observeAll(): Flow<List<PortForwardRuleEntity>>

    @Query("SELECT * FROM port_forward_rules WHERE hostId = :hostId ORDER BY id")
    fun observeForHost(hostId: Long): Flow<List<PortForwardRuleEntity>>

    @Query("SELECT * FROM port_forward_rules WHERE id = :id")
    suspend fun get(id: Long): PortForwardRuleEntity?

    @Insert suspend fun insert(rule: PortForwardRuleEntity): Long
    @Update suspend fun update(rule: PortForwardRuleEntity)
    @Delete suspend fun delete(rule: PortForwardRuleEntity)
}

@Dao
interface KnownHostDao {
    @Query("SELECT * FROM known_hosts WHERE hostname = :hostname AND port = :port LIMIT 1")
    suspend fun find(hostname: String, port: Int): KnownHostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(knownHost: KnownHostEntity)

    @Query("DELETE FROM known_hosts WHERE hostname = :hostname AND port = :port")
    suspend fun delete(hostname: String, port: Int)
}

@Dao
interface CommandSnippetDao {
    @Query("SELECT * FROM command_snippets ORDER BY groupName COLLATE NOCASE, sortOrder, title COLLATE NOCASE")
    fun observeAll(): Flow<List<CommandSnippetEntity>>

    @Query("SELECT * FROM command_snippets")
    suspend fun getAll(): List<CommandSnippetEntity>

    @Query("SELECT * FROM command_snippets WHERE hostId IS NULL OR hostId = :hostId ORDER BY groupName COLLATE NOCASE, sortOrder, title COLLATE NOCASE")
    fun observeForHost(hostId: Long): Flow<List<CommandSnippetEntity>>

    @Query("SELECT * FROM command_snippets WHERE id = :id")
    suspend fun get(id: Long): CommandSnippetEntity?

    @Insert suspend fun insert(snippet: CommandSnippetEntity): Long
    @Update suspend fun update(snippet: CommandSnippetEntity)
    @Delete suspend fun delete(snippet: CommandSnippetEntity)
}

@Dao
interface DocumentAccessDao {
    @Query("SELECT * FROM document_roots ORDER BY enabledAt")
    fun observeRoots(): Flow<List<DocumentRootEntity>>

    @Query("SELECT * FROM document_roots ORDER BY enabledAt")
    suspend fun roots(): List<DocumentRootEntity>

    @Query("SELECT * FROM document_roots WHERE hostId = :hostId LIMIT 1")
    suspend fun root(hostId: Long): DocumentRootEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRoot(root: DocumentRootEntity)

    @Query("DELETE FROM document_roots WHERE hostId = :hostId")
    suspend fun deleteRoot(hostId: Long)

    @Query("SELECT * FROM document_writebacks ORDER BY updatedAt DESC")
    fun observeWritebacks(): Flow<List<DocumentWritebackEntity>>

    @Query("SELECT * FROM document_writebacks ORDER BY updatedAt DESC")
    suspend fun writebacks(): List<DocumentWritebackEntity>

    @Query("SELECT * FROM document_writebacks WHERE id = :id LIMIT 1")
    suspend fun writeback(id: Long): DocumentWritebackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putWriteback(writeback: DocumentWritebackEntity): Long

    @Query("DELETE FROM document_writebacks WHERE id = :id")
    suspend fun deleteWriteback(id: Long)
}

@Database(
    entities = [
        HostEntity::class,
        SecretEntity::class,
        KnownHostEntity::class,
        CommandSnippetEntity::class,
        VaultMetadataEntity::class,
        LocalRootEntity::class,
        SftpBookmarkEntity::class,
        TransferBatchEntity::class,
        TransferJobEntity::class,
        PortForwardRuleEntity::class,
        DocumentRootEntity::class,
        DocumentWritebackEntity::class,
        DiagnosticTraceEntity::class,
        DiagnosticEventEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun secretDao(): SecretDao
    abstract fun knownHostDao(): KnownHostDao
    abstract fun commandSnippetDao(): CommandSnippetDao
    abstract fun vaultMetadataDao(): VaultMetadataDao
    abstract fun localRootDao(): LocalRootDao
    abstract fun sftpBookmarkDao(): SftpBookmarkDao
    abstract fun transferDao(): TransferDao
    abstract fun portForwardRuleDao(): PortForwardRuleDao
    abstract fun documentAccessDao(): DocumentAccessDao
    abstract fun diagnosticLogDao(): DiagnosticLogDao

    companion object {
        fun create(context: Context): AppDatabase = Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            "ssh_helper.db",
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE hosts ADD COLUMN autoReconnect INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS command_snippets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        command TEXT NOT NULL,
                        groupName TEXT NOT NULL,
                        hostId INTEGER,
                        executeImmediately INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_command_snippets_hostId ON command_snippets(hostId)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE secrets ADD COLUMN encryptionVersion INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE TABLE IF NOT EXISTS vault_metadata (id INTEGER NOT NULL PRIMARY KEY, enabled INTEGER NOT NULL, wrappedKeyIv BLOB, wrappedKeyCiphertext BLOB, migrationState TEXT NOT NULL, updatedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS local_roots (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, uri TEXT NOT NULL, displayName TEXT NOT NULL, sortOrder INTEGER NOT NULL, addedAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS sftp_bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, hostId INTEGER NOT NULL, path TEXT NOT NULL, label TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sftp_bookmarks_hostId ON sftp_bookmarks(hostId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS transfer_batches (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS transfer_jobs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, batchId INTEGER NOT NULL, hostId INTEGER NOT NULL, direction TEXT NOT NULL, source TEXT NOT NULL, destination TEXT NOT NULL, temporaryPath TEXT, totalBytes INTEGER NOT NULL, transferredBytes INTEGER NOT NULL, conflictPolicy TEXT NOT NULL, status TEXT NOT NULL, error TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(batchId) REFERENCES transfer_batches(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transfer_jobs_hostId ON transfer_jobs(hostId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transfer_jobs_batchId ON transfer_jobs(batchId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transfer_jobs_status ON transfer_jobs(status)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // SQLite cannot add a self-referencing FK via ALTER TABLE, so the hosts table is
                // rebuilt with the jumpHostId column and its RESTRICT constraint, preserving rows.
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `hosts_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `hostname` TEXT NOT NULL,
                        `port` INTEGER NOT NULL,
                        `username` TEXT NOT NULL,
                        `authType` TEXT NOT NULL,
                        `rememberCredential` INTEGER NOT NULL,
                        `privateKeyName` TEXT,
                        `autoReconnect` INTEGER NOT NULL DEFAULT 0,
                        `jumpHostId` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `lastConnectedAt` INTEGER,
                        FOREIGN KEY(`jumpHostId`) REFERENCES `hosts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )""".trimIndent(),
                )
                db.execSQL(
                    """INSERT INTO `hosts_new` (`id`,`name`,`hostname`,`port`,`username`,`authType`,`rememberCredential`,`privateKeyName`,`autoReconnect`,`createdAt`,`updatedAt`,`lastConnectedAt`)
                       SELECT `id`,`name`,`hostname`,`port`,`username`,`authType`,`rememberCredential`,`privateKeyName`,`autoReconnect`,`createdAt`,`updatedAt`,`lastConnectedAt` FROM `hosts`""".trimIndent(),
                )
                db.execSQL("DROP TABLE `hosts`")
                db.execSQL("ALTER TABLE `hosts_new` RENAME TO `hosts`")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_hosts_jumpHostId ON hosts(jumpHostId)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS port_forward_rules (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        hostId INTEGER NOT NULL,
                        name TEXT NOT NULL,
                        type TEXT NOT NULL,
                        bindAddress TEXT NOT NULL,
                        listenPort INTEGER NOT NULL,
                        targetHost TEXT,
                        targetPort INTEGER,
                        autoStart INTEGER NOT NULL,
                        FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_port_forward_rules_hostId ON port_forward_rules(hostId)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE hosts ADD COLUMN proxyType TEXT")
                db.execSQL("ALTER TABLE hosts ADD COLUMN proxyHost TEXT")
                db.execSQL("ALTER TABLE hosts ADD COLUMN proxyPort INTEGER")
                db.execSQL("ALTER TABLE hosts ADD COLUMN proxyUsername TEXT")
                db.execSQL("ALTER TABLE secrets ADD COLUMN proxyIv BLOB")
                db.execSQL("ALTER TABLE secrets ADD COLUMN proxyCiphertext BLOB")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS document_roots (
                        hostId INTEGER NOT NULL PRIMARY KEY,
                        credentialIv BLOB NOT NULL,
                        credentialCiphertext BLOB NOT NULL,
                        credentialVersion INTEGER NOT NULL,
                        routeSignature TEXT NOT NULL,
                        enabledAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_document_roots_hostId ON document_roots(hostId)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS document_writebacks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        hostId INTEGER NOT NULL,
                        remotePath TEXT NOT NULL,
                        localPath TEXT NOT NULL,
                        baselineSize INTEGER NOT NULL,
                        baselineModifiedAt INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        error TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY(hostId) REFERENCES hosts(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_writebacks_hostId ON document_writebacks(hostId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_document_writebacks_status ON document_writebacks(status)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS diagnostic_traces (
                        id TEXT NOT NULL PRIMARY KEY,
                        source TEXT NOT NULL,
                        target TEXT,
                        hostId INTEGER,
                        sessionId TEXT,
                        feature TEXT,
                        startedAt INTEGER NOT NULL,
                        endedAt INTEGER,
                        status TEXT NOT NULL,
                        summary TEXT
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_diagnostic_traces_startedAt ON diagnostic_traces(startedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_diagnostic_traces_source ON diagnostic_traces(source)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_diagnostic_traces_hostId ON diagnostic_traces(hostId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_diagnostic_traces_status ON diagnostic_traces(status)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS diagnostic_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        traceId TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        timestamp INTEGER NOT NULL,
                        elapsedMillis INTEGER NOT NULL,
                        level TEXT NOT NULL,
                        stage TEXT NOT NULL,
                        hop TEXT,
                        code TEXT NOT NULL,
                        message TEXT NOT NULL,
                        detailsJson TEXT NOT NULL,
                        FOREIGN KEY(traceId) REFERENCES diagnostic_traces(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_diagnostic_events_traceId ON diagnostic_events(traceId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_diagnostic_events_traceId_sequence ON diagnostic_events(traceId, sequence)")
            }
        }
    }
}
