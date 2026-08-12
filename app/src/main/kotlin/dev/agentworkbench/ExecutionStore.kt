package dev.agentworkbench

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Transaction
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

enum class AgentRunState {
    QUEUED,
    RUNNING,
    WAITING_INPUT,
    PAUSED,
    RECOVERING,
    SUCCEEDED,
    FAILED,
}

enum class AgentStepState {
    QUEUED,
    RUNNING,
    NEEDS_RECONCILIATION,
    SUCCEEDED,
    FAILED,
}

enum class AgentStepEffect {
    READ_ONLY,
    IDEMPOTENT,
    MUTATION,
}

@Entity(
    tableName = "agent_runs",
    indices = [Index("sessionId"), Index("state"), Index("updatedAtMillis")],
)
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val goalId: String?,
    val summary: String,
    val providerPreset: String,
    val modelId: String,
    val providerSettingsJson: String,
    val requiresNetwork: Boolean,
    val state: String,
    val checkpointVersion: Long,
    val noProgressCycles: Int,
    val lastError: String?,
    val pendingInteractionJson: String?,
    val resumePayloadJson: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "agent_steps",
    indices = [Index("runId"), Index(value = ["runId", "ordinal"], unique = true)],
)
data class AgentStepEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val ordinal: Int,
    val kind: String,
    val effect: String,
    val state: String,
    val payloadHash: String,
    val idempotencyKey: String?,
    val attempt: Int,
    val resultRef: String?,
    val error: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(tableName = "background_triggers", indices = [Index("enabled")])
data class BackgroundTriggerEntity(
    @PrimaryKey val id: String,
    val type: String,
    val configurationJson: String,
    val enabled: Boolean,
    val nextRunAtMillis: Long?,
    val updatedAtMillis: Long,
)

@Entity(tableName = "capability_leases", indices = [Index("capability"), Index("enabled")])
data class CapabilityLeaseEntity(
    @PrimaryKey val id: String,
    val capability: String,
    val scopeJson: String,
    val source: String,
    val enabled: Boolean,
    val grantedAtMillis: Long,
    val expiresAtMillis: Long?,
    val updatedAtMillis: Long,
)

@Dao
interface ExecutionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: AgentRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStep(step: AgentStepEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrigger(trigger: BackgroundTriggerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCapabilityLease(lease: CapabilityLeaseEntity)

    @Query("SELECT * FROM agent_runs WHERE id = :id LIMIT 1")
    suspend fun loadRun(id: String): AgentRunEntity?

    @Query("SELECT * FROM agent_steps WHERE runId = :runId ORDER BY ordinal")
    suspend fun loadSteps(runId: String): List<AgentStepEntity>

    @Query(
        "SELECT * FROM agent_steps WHERE runId = :runId AND idempotencyKey = :key " +
            "AND state = 'SUCCEEDED' LIMIT 1",
    )
    suspend fun successfulIdempotentStep(runId: String, key: String): AgentStepEntity?

    @Query(
        "SELECT * FROM agent_steps WHERE runId = :runId AND state = 'NEEDS_RECONCILIATION' " +
            "ORDER BY ordinal LIMIT 1",
    )
    suspend fun loadStepNeedingReconciliation(runId: String): AgentStepEntity?

    @Query("SELECT * FROM capability_leases ORDER BY capability")
    fun observeCapabilityLeases(): Flow<List<CapabilityLeaseEntity>>

    @Query("SELECT * FROM capability_leases ORDER BY capability")
    suspend fun loadCapabilityLeases(): List<CapabilityLeaseEntity>

    @Query(
        "SELECT * FROM agent_runs WHERE state IN " +
            "('QUEUED','RUNNING','WAITING_INPUT','PAUSED','RECOVERING') " +
            "ORDER BY updatedAtMillis DESC",
    )
    fun observeActiveRuns(): Flow<List<AgentRunEntity>>

    @Query(
        "SELECT * FROM agent_runs WHERE sessionId = :sessionId " +
            "ORDER BY updatedAtMillis DESC LIMIT 1",
    )
    fun observeLatestRun(sessionId: String): Flow<AgentRunEntity?>

    @Query(
        "SELECT * FROM agent_runs WHERE state IN " +
            "('QUEUED','RUNNING','WAITING_INPUT','PAUSED','RECOVERING') " +
            "ORDER BY updatedAtMillis DESC",
    )
    suspend fun loadActiveRuns(): List<AgentRunEntity>

    @Query("UPDATE agent_runs SET state = :state, updatedAtMillis = :now WHERE id = :runId")
    suspend fun setRunState(runId: String, state: String, now: Long)

    @Query(
        "UPDATE agent_runs SET noProgressCycles = :cycles, updatedAtMillis = :now WHERE id = :runId",
    )
    suspend fun setNoProgressCycles(runId: String, cycles: Int, now: Long)

    @Query(
        "UPDATE agent_runs SET state = :state, checkpointVersion = checkpointVersion + 1, " +
            "lastError = :error, updatedAtMillis = :now WHERE id = :runId",
    )
    suspend fun checkpointRun(runId: String, state: String, error: String?, now: Long)

    @Query(
        "UPDATE agent_runs SET state = 'WAITING_INPUT', pendingInteractionJson = :pendingJson, " +
            "resumePayloadJson = NULL, lastError = :summary, checkpointVersion = checkpointVersion + 1, " +
            "updatedAtMillis = :now WHERE id = :runId",
    )
    suspend fun waitForInput(runId: String, pendingJson: String, summary: String, now: Long)

    @Query(
        "UPDATE agent_runs SET state = 'QUEUED', resumePayloadJson = :payloadJson, " +
            "checkpointVersion = checkpointVersion + 1, updatedAtMillis = :now " +
            "WHERE id = :runId AND state = 'WAITING_INPUT'",
    )
    suspend fun resumeWithInput(runId: String, payloadJson: String, now: Long): Int

    @Query(
        "UPDATE agent_runs SET pendingInteractionJson = NULL, resumePayloadJson = NULL, " +
            "checkpointVersion = checkpointVersion + 1, updatedAtMillis = :now WHERE id = :runId",
    )
    suspend fun clearInteraction(runId: String, now: Long)

    @Query(
        "UPDATE agent_steps SET state = :state, resultRef = :resultRef, error = :error, " +
            "updatedAtMillis = :now WHERE id = :stepId",
    )
    suspend fun finishStep(
        stepId: String,
        state: String,
        resultRef: String?,
        error: String?,
        now: Long,
    )

    @Query(
        "UPDATE agent_runs SET state = 'RECOVERING', checkpointVersion = checkpointVersion + 1, " +
            "lastError = 'Processo Android interrompido; recuperacao segura pendente.', " +
            "updatedAtMillis = :now WHERE state = 'RUNNING'",
    )
    suspend fun markRunsForRecovery(now: Long)

    @Query(
        "UPDATE agent_steps SET state = CASE " +
            "WHEN effect IN ('READ_ONLY','IDEMPOTENT') THEN 'FAILED' " +
            "ELSE 'NEEDS_RECONCILIATION' END, " +
            "error = CASE WHEN effect IN ('READ_ONLY','IDEMPOTENT') " +
            "THEN 'Processo interrompido; repeticao segura criada em nova etapa.' " +
            "ELSE 'Mutacao interrompida; reconciliacao obrigatoria.' END, " +
            "attempt = attempt + 1, updatedAtMillis = :now " +
            "WHERE state = 'RUNNING'",
    )
    suspend fun markStepsForRecovery(now: Long)

    @Transaction
    suspend fun recoverInterrupted(now: Long) {
        markStepsForRecovery(now)
        markRunsForRecovery(now)
    }
}

@Database(
    entities = [
        AgentRunEntity::class,
        AgentStepEntity::class,
        BackgroundTriggerEntity::class,
        CapabilityLeaseEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class ExecutionDatabase : RoomDatabase() {
    abstract fun executionDao(): ExecutionDao

    companion object {
        @Volatile
        private var instance: ExecutionDatabase? = null

        fun get(context: Context): ExecutionDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ExecutionDatabase::class.java,
                "agent-execution.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_runs ADD COLUMN pendingInteractionJson TEXT")
                db.execSQL("ALTER TABLE agent_runs ADD COLUMN resumePayloadJson TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agent_runs ADD COLUMN providerSettingsJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE agent_runs ADD COLUMN requiresNetwork INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "UPDATE agent_runs SET requiresNetwork = 0 " +
                        "WHERE providerPreset IN ('DEMO', 'LOCAL_GGUF')",
                )
            }
        }
    }
}

class ExecutionRepository(context: Context) {
    private val dao = ExecutionDatabase.get(context).executionDao()

    fun observeActiveRuns(): Flow<List<AgentRunEntity>> = dao.observeActiveRuns()

    fun observeLatestRun(sessionId: String): Flow<AgentRunEntity?> =
        dao.observeLatestRun(sessionId)

    fun observeCapabilityLeases(): Flow<List<CapabilityLeaseEntity>> =
        dao.observeCapabilityLeases()

    suspend fun activeCapabilityLeaseIds(nowMillis: Long = System.currentTimeMillis()): Set<String> =
        dao.loadCapabilityLeases()
            .asSequence()
            .filter(CapabilityLeaseEntity::enabled)
            .filter { lease -> lease.expiresAtMillis?.let { it > nowMillis } != false }
            .mapTo(mutableSetOf(), CapabilityLeaseEntity::capability)

    suspend fun beginRun(
        sessionId: String,
        summary: String,
        settings: ProviderSettings,
        goalId: String? = null,
    ): AgentRunEntity {
        val now = System.currentTimeMillis()
        return AgentRunEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            goalId = goalId,
            summary = summary.take(500),
            providerPreset = settings.preset.name,
            modelId = settings.modelId,
            providerSettingsJson = settings.toRunSnapshotJson(),
            requiresNetwork = settings.requiresNetworkForRecovery(),
            state = AgentRunState.QUEUED.name,
            checkpointVersion = 0,
            noProgressCycles = 0,
            lastError = null,
            pendingInteractionJson = null,
            resumePayloadJson = null,
            createdAtMillis = now,
            updatedAtMillis = now,
        ).also { dao.upsertRun(it) }
    }

    fun settingsFor(run: AgentRunEntity): ProviderSettings? =
        runCatching { providerSettingsFromRunSnapshot(run.providerSettingsJson) }.getOrNull()

    suspend fun beginStep(
        runId: String,
        ordinal: Int,
        kind: String,
        effect: AgentStepEffect,
        payload: String,
        idempotencyKey: String? = null,
    ): AgentStepEntity {
        val now = System.currentTimeMillis()
        return AgentStepEntity(
            id = UUID.randomUUID().toString(),
            runId = runId,
            ordinal = ordinal,
            kind = kind.take(80),
            effect = effect.name,
            state = AgentStepState.RUNNING.name,
            payloadHash = sha256(payload),
            idempotencyKey = idempotencyKey,
            attempt = 1,
            resultRef = null,
            error = null,
            createdAtMillis = now,
            updatedAtMillis = now,
        ).also { dao.upsertStep(it) }
    }

    suspend fun checkpointRun(
        runId: String,
        state: AgentRunState,
        error: String? = null,
    ) = dao.checkpointRun(runId, state.name, error?.take(1_000), System.currentTimeMillis())

    suspend fun finishStep(
        stepId: String,
        succeeded: Boolean,
        resultRef: String? = null,
        error: String? = null,
    ) = dao.finishStep(
        stepId = stepId,
        state = if (succeeded) AgentStepState.SUCCEEDED.name else AgentStepState.FAILED.name,
        resultRef = resultRef?.take(1_024),
        error = error?.take(1_000),
        now = System.currentTimeMillis(),
    )

    suspend fun setRunState(runId: String, state: AgentRunState) =
        dao.setRunState(runId, state.name, System.currentTimeMillis())

    suspend fun recoverInterrupted(): List<AgentRunEntity> {
        dao.recoverInterrupted(System.currentTimeMillis())
        return dao.loadActiveRuns()
    }

    suspend fun activeRuns(): List<AgentRunEntity> = dao.loadActiveRuns()

    suspend fun loadRun(runId: String): AgentRunEntity? = dao.loadRun(runId)

    suspend fun steps(runId: String): List<AgentStepEntity> = dao.loadSteps(runId)

    suspend fun successfulIdempotentStep(runId: String, key: String): AgentStepEntity? =
        dao.successfulIdempotentStep(runId, key)

    suspend fun stepNeedingReconciliation(runId: String): AgentStepEntity? =
        dao.loadStepNeedingReconciliation(runId)

    suspend fun setNoProgressCycles(runId: String, cycles: Int) =
        dao.setNoProgressCycles(runId, cycles.coerceIn(0, 3), System.currentTimeMillis())

    suspend fun waitForInput(runId: String, pendingJson: String, summary: String) =
        dao.waitForInput(
            runId,
            pendingJson.take(64 * 1024),
            summary.take(1_000),
            System.currentTimeMillis(),
        )

    suspend fun resumeWithInput(runId: String, payloadJson: String): Boolean =
        dao.resumeWithInput(runId, payloadJson.take(64 * 1024), System.currentTimeMillis()) == 1

    suspend fun clearInteraction(runId: String) =
        dao.clearInteraction(runId, System.currentTimeMillis())

    suspend fun setCapabilityLease(
        capability: String,
        enabled: Boolean,
        scopeJson: String = "{}",
        source: String = "user",
        expiresAtMillis: Long? = null,
    ) {
        val now = System.currentTimeMillis()
        dao.upsertCapabilityLease(
            CapabilityLeaseEntity(
                id = capability,
                capability = capability,
                scopeJson = scopeJson,
                source = source,
                enabled = enabled,
                grantedAtMillis = now,
                expiresAtMillis = expiresAtMillis,
                updatedAtMillis = now,
            ),
        )
    }
}

internal fun ProviderSettings.toRunSnapshotJson(): String = JSONObject()
    .put("schema", 1)
    .put("preset", preset.name)
    .put("endpoint", endpoint)
    .put("model_id", modelId)
    .put("execution_mode", executionMode.name)
    .put(
        "continuous_chat",
        JSONObject()
            .put("enabled", continuousChat.enabled)
            .put("automatic_provider_switching", continuousChat.automaticProviderSwitching)
            .put("context_window_tokens", continuousChat.contextWindowTokens)
            .put("compaction_threshold_percent", continuousChat.compactionThresholdPercent)
            .put("recent_messages_to_keep", continuousChat.recentMessagesToKeep)
            .put(
                "provider_pool",
                JSONArray(continuousChat.providerPool.map(ProviderPreset::name)),
            ),
    )
    .toString()

internal fun providerSettingsFromRunSnapshot(raw: String): ProviderSettings {
    val root = JSONObject(raw)
    require(root.optInt("schema") == 1) { "Snapshot de provider ausente ou incompatível." }
    val continuous = root.getJSONObject("continuous_chat")
    val poolJson = continuous.getJSONArray("provider_pool")
    val pool = buildSet {
        repeat(poolJson.length()) { index ->
            add(ProviderPreset.valueOf(poolJson.getString(index)))
        }
    }
    return ProviderSettings(
        preset = ProviderPreset.valueOf(root.getString("preset")),
        endpoint = root.getString("endpoint"),
        modelId = root.getString("model_id"),
        executionMode = dev.agentworkbench.core.ExecutionMode.valueOf(root.getString("execution_mode")),
        continuousChat = ContinuousChatSettings(
            enabled = continuous.getBoolean("enabled"),
            automaticProviderSwitching = continuous.getBoolean("automatic_provider_switching"),
            contextWindowTokens = continuous.getInt("context_window_tokens"),
            compactionThresholdPercent = continuous.getInt("compaction_threshold_percent"),
            recentMessagesToKeep = continuous.getInt("recent_messages_to_keep"),
            providerPool = pool,
        ),
    )
}

internal fun ProviderSettings.requiresNetworkForRecovery(): Boolean {
    val localPresets = setOf(ProviderPreset.DEMO, ProviderPreset.LOCAL_GGUF)
    if (preset in localPresets) return false
    return !continuousChat.enabled || continuousChat.providerPool.none { it in localPresets }
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
