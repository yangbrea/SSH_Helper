package com.yang136.sshhelper.diagnosticlog

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "diagnostic_traces",
    indices = [Index("startedAt"), Index("source"), Index("hostId"), Index("status")],
)
data class DiagnosticTraceEntity(
    @PrimaryKey val id: String,
    val source: String,
    val target: String?,
    val hostId: Long?,
    val sessionId: String?,
    val feature: String?,
    val startedAt: Long,
    val endedAt: Long?,
    val status: String,
    val summary: String?,
)

@Entity(
    tableName = "diagnostic_events",
    foreignKeys = [ForeignKey(
        entity = DiagnosticTraceEntity::class,
        parentColumns = ["id"],
        childColumns = ["traceId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("traceId"), Index(value = ["traceId", "sequence"], unique = true)],
)
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val traceId: String,
    val sequence: Long,
    val timestamp: Long,
    val elapsedMillis: Long,
    val level: String,
    val stage: String,
    val hop: String?,
    val code: String,
    val message: String,
    val detailsJson: String,
)

@Dao
interface DiagnosticLogDao {
    @Query("SELECT * FROM diagnostic_traces ORDER BY startedAt DESC")
    fun observeTraces(): Flow<List<DiagnosticTraceEntity>>

    @Query("SELECT * FROM diagnostic_events WHERE traceId = :traceId ORDER BY sequence")
    fun observeEvents(traceId: String): Flow<List<DiagnosticEventEntity>>

    @Query("SELECT * FROM diagnostic_traces WHERE id = :traceId LIMIT 1")
    suspend fun trace(traceId: String): DiagnosticTraceEntity?

    @Query("SELECT * FROM diagnostic_events WHERE traceId = :traceId ORDER BY sequence")
    suspend fun events(traceId: String): List<DiagnosticEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrace(trace: DiagnosticTraceEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvents(events: List<DiagnosticEventEntity>)

    @Query("UPDATE diagnostic_traces SET endedAt = :endedAt, status = :status, summary = :summary WHERE id = :traceId")
    suspend fun finish(traceId: String, endedAt: Long, status: String, summary: String?)

    @Query("UPDATE diagnostic_traces SET endedAt = :now, status = 'ABORTED', summary = '应用进程在诊断完成前结束' WHERE status = 'RUNNING'")
    suspend fun markInterrupted(now: Long)

    @Query("DELETE FROM diagnostic_traces WHERE COALESCE(endedAt, startedAt) < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM diagnostic_traces WHERE id NOT IN (SELECT id FROM diagnostic_traces ORDER BY startedAt DESC LIMIT :maximum)")
    suspend fun trimTo(maximum: Int)

    @Query("DELETE FROM diagnostic_traces WHERE id = :traceId")
    suspend fun deleteTrace(traceId: String)

    @Query("DELETE FROM diagnostic_traces")
    suspend fun clear()
}
