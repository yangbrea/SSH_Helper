package com.yang136.sshhelper.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val databaseName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    @Throws(IOException::class)
    fun migrate1To2_preservesHostsAndCreatesSnippets() {
        helper.createDatabase("$databaseName-1-2", 1).apply {
            execSQL(
                """INSERT INTO hosts
                    (id,name,hostname,port,username,authType,rememberCredential,privateKeyName,createdAt,updatedAt,lastConnectedAt)
                    VALUES (1,'测试机','192.0.2.1',22,'user','PASSWORD',0,NULL,1,1,NULL)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate("$databaseName-1-2", 2, true, AppDatabase.MIGRATION_1_2).use { database ->
            database.query("SELECT name, autoReconnect FROM hosts WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("测试机", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
            }
            database.query("SELECT COUNT(*) FROM command_snippets").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate2To3_preservesExistingDataAndCreatesSftpTables() {
        val name = "$databaseName-2-3"
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO hosts (id,name,hostname,port,username,authType,rememberCredential,privateKeyName,autoReconnect,createdAt,updatedAt,lastConnectedAt) VALUES (1,'旧主机','example.test',22,'root','PASSWORD',1,NULL,1,1,2,NULL)")
            execSQL("INSERT INTO secrets (id,hostId,credentialIv,credentialCiphertext,passphraseIv,passphraseCiphertext) VALUES (1,1,X'0102',X'0304',NULL,NULL)")
            execSQL("INSERT INTO known_hosts (id,hostname,port,keyType,keyBase64,fingerprintSha256,trustedAt) VALUES ('example.test:22','example.test',22,'ssh-ed25519','AAAA','SHA256:test',1)")
            execSQL("INSERT INTO command_snippets (id,title,command,groupName,hostId,executeImmediately,sortOrder,createdAt,updatedAt) VALUES (1,'检查','uptime','常用',1,0,0,1,1)")
            close()
        }

        helper.runMigrationsAndValidate(name, 3, true, AppDatabase.MIGRATION_2_3).use { database ->
            database.query("SELECT name, autoReconnect FROM hosts WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("旧主机", cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
            database.query("SELECT encryptionVersion, length(credentialCiphertext) FROM secrets WHERE hostId = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals(2, cursor.getInt(1))
            }
            listOf("vault_metadata", "local_roots", "sftp_bookmarks", "transfer_batches", "transfer_jobs").forEach { table ->
                database.query("SELECT COUNT(*) FROM $table").use { cursor ->
                    cursor.moveToFirst()
                    assertEquals(0, cursor.getInt(0))
                }
            }
            database.query("SELECT title FROM command_snippets WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("检查", cursor.getString(0))
            }
        }
    }

    @Test
    @Throws(IOException::class)
    fun migrate3To4_preservesHostsAndCreatesJumpColumnAndForwardRules() {
        val name = "$databaseName-3-4"
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO hosts (id,name,hostname,port,username,authType,rememberCredential,privateKeyName,autoReconnect,createdAt,updatedAt,lastConnectedAt) VALUES (1,'旧主机','example.test',22,'root','PASSWORD',1,NULL,1,1,2,NULL)")
            execSQL("INSERT INTO secrets (id,hostId,credentialIv,credentialCiphertext,passphraseIv,passphraseCiphertext,encryptionVersion) VALUES (1,1,X'0102',X'0304',NULL,NULL,1)")
            execSQL("INSERT INTO known_hosts (id,hostname,port,keyType,keyBase64,fingerprintSha256,trustedAt) VALUES ('example.test:22','example.test',22,'ssh-ed25519','AAAA','SHA256:test',1)")
            close()
        }

        helper.runMigrationsAndValidate(name, 4, true, AppDatabase.MIGRATION_3_4).use { database ->
            database.query("SELECT name, jumpHostId FROM hosts WHERE id = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals("旧主机", cursor.getString(0))
                assertNull(cursor.getString(1))
            }
            database.query("SELECT encryptionVersion FROM secrets WHERE hostId = 1").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM port_forward_rules").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM known_hosts WHERE hostname = 'example.test'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
        }
    }
}
