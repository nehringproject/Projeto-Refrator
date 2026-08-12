package dev.agentworkbench

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class InstructionScope { GLOBAL, WORKSPACE, CHAT }
enum class InstructionState { ENABLED, DISABLED }
enum class MemoryState { ACTIVE, DISABLED, CONFLICT, DELETED }
enum class ConversationTitleMode { AUTO, MANUAL }

@Entity(
    tableName = "instructions",
    indices = [Index(value = ["scopeType", "scopeId"], unique = true), Index("state")],
)
data class InstructionRecord(
    @PrimaryKey val id: String,
    val scopeType: String,
    val scopeId: String,
    val body: String,
    val state: String,
    val revision: Long,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "memories",
    indices = [Index("scopeType"), Index("scopeId"), Index("state"), Index("category")],
)
data class MemoryEntry(
    @PrimaryKey val id: String,
    val scopeType: String,
    val scopeId: String,
    val category: String,
    val body: String,
    val sourceConversationId: String?,
    val sourceMessageId: String?,
    val confidence: Double,
    val pinned: Boolean,
    val state: String,
    val expiresAtMillis: Long?,
    val useCount: Long,
    val lastUsedAtMillis: Long?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(tableName = "memory_revisions", indices = [Index("memoryId"), Index("createdAtMillis")])
data class MemoryRevision(
    @PrimaryKey val id: String,
    val memoryId: String,
    val previousBody: String,
    val reason: String,
    val author: String,
    val createdAtMillis: Long,
)

@Entity(tableName = "context_injection_logs", indices = [Index("conversationId"), Index("createdAtMillis")])
data class ContextInjectionLog(
    @PrimaryKey val id: String,
    val conversationId: String,
    val messageId: String?,
    val instructionIdsJson: String,
    val memoryIdsJson: String,
    val estimatedTokens: Int,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "conversations",
    indices = [Index("updatedAtMillis"), Index("workspaceId"), Index("archived"), Index("deletedAtMillis")],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val titleMode: String,
    val workspaceId: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val providerName: String,
    val modelId: String,
    val messageCount: Int,
    val pinned: Boolean,
    val archived: Boolean,
    val deletedAtMillis: Long?,
    val activeBranchId: String,
    val payloadJson: String,
)

@Entity(tableName = "conversation_branches", indices = [Index("conversationId"), Index("parentBranchId")])
data class ConversationBranch(
    @PrimaryKey val id: String,
    val conversationId: String,
    val parentBranchId: String?,
    val forkMessageId: String?,
    val name: String,
    val createdAtMillis: Long,
)

@Fts4
@Entity(tableName = "message_fts")
data class MessageFtsEntry(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long = 0,
    val conversationId: String,
    val messageId: String,
    val body: String,
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(@PrimaryKey val id: String, val name: String, val color: Long)

@Entity(
    tableName = "conversation_tags",
    primaryKeys = ["conversationId", "tagId"],
    indices = [Index("tagId")],
)
data class ConversationTag(val conversationId: String, val tagId: String)

@Entity(tableName = "workspace_checkpoints", indices = [Index("workspaceId"), Index("conversationId"), Index("messageId")])
data class WorkspaceCheckpoint(
    @PrimaryKey val id: String,
    val workspaceId: String,
    val conversationId: String,
    val branchId: String,
    val messageId: String?,
    val manifestJson: String,
    val reversible: Boolean,
    val byteCount: Long,
    val createdAtMillis: Long,
)

@Entity(tableName = "agent_profiles", indices = [Index(value = ["name"], unique = true), Index("enabled")])
data class AgentProfile(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val providerPreset: String?,
    val modelId: String?,
    val reasoningLevel: String,
    val toolPolicyJson: String,
    val skillIdsJson: String,
    val mcpServerIdsJson: String,
    val browserProfileId: String?,
    val maxDelegates: Int,
    val enabled: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(tableName = "agent_run_nodes", indices = [Index("runId"), Index("parentNodeId"), Index("state")])
data class AgentRunNode(
    @PrimaryKey val id: String,
    val runId: String,
    val parentNodeId: String?,
    val profileId: String,
    val task: String,
    val state: String,
    val providerPreset: String,
    val modelId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double?,
    val resultJson: String?,
    val artifactRefsJson: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(tableName = "mcp_servers", indices = [Index(value = ["name"], unique = true), Index("enabled")])
data class McpServerConfig(
    @PrimaryKey val id: String,
    val name: String,
    val transport: String,
    val commandOrUrl: String,
    val argumentsJson: String,
    val environmentKeysJson: String,
    val workspaceId: String?,
    val permissionPolicyJson: String,
    val enabled: Boolean,
    val updatedAtMillis: Long,
)

@Entity(tableName = "hook_definitions", indices = [Index("event"), Index("enabled")])
data class HookDefinition(
    @PrimaryKey val id: String,
    val name: String,
    val event: String,
    val conditionJson: String,
    val actionType: String,
    val actionPayload: String,
    val timeoutMillis: Long,
    val failurePolicy: String,
    val enabled: Boolean,
    val updatedAtMillis: Long,
)

@Entity(tableName = "command_templates", indices = [Index(value = ["name"], unique = true), Index("enabled")])
data class CommandTemplate(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val argumentsSchemaJson: String,
    val promptTemplate: String,
    val defaultAgentProfileId: String?,
    val enabled: Boolean,
    val updatedAtMillis: Long,
)

@Entity(tableName = "browser_profiles", indices = [Index(value = ["name"], unique = true), Index("ephemeral")])
data class BrowserProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val webViewProfileName: String,
    val ephemeral: Boolean,
    val color: Long,
    val createdAtMillis: Long,
    val lastUsedAtMillis: Long,
)

@Entity(tableName = "browser_tabs", indices = [Index("profileId"), Index("workspaceId"), Index("position")])
data class BrowserTabEntity(
    @PrimaryKey val id: String,
    val profileId: String,
    val workspaceId: String,
    val title: String,
    val url: String,
    val position: Int,
    val selected: Boolean,
    val controlOwner: String,
    val frozen: Boolean,
    val updatedAtMillis: Long,
)

@Entity(tableName = "browser_events", indices = [Index("tabId"), Index("type"), Index("createdAtMillis")])
data class BrowserEventEntity(
    @PrimaryKey val id: String,
    val tabId: String,
    val type: String,
    val payloadJson: String,
    val createdAtMillis: Long,
)

@Entity(
    tableName = "browser_site_permissions",
    primaryKeys = ["profileId", "origin", "capability"],
    indices = [Index("origin")],
)
data class BrowserSitePermissionEntity(
    val profileId: String,
    val origin: String,
    val capability: String,
    val decision: String,
    val expiresAtMillis: Long?,
    val updatedAtMillis: Long,
)

@Dao
interface WorkbenchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInstruction(value: InstructionRecord)

    @Query("SELECT * FROM instructions WHERE scopeType = :scopeType AND scopeId = :scopeId LIMIT 1")
    suspend fun instruction(scopeType: String, scopeId: String): InstructionRecord?

    @Query("SELECT * FROM instructions ORDER BY scopeType, updatedAtMillis DESC")
    suspend fun instructions(): List<InstructionRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(value: MemoryEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addRevision(value: MemoryRevision)

    @Query("SELECT * FROM memories WHERE state = 'ACTIVE' AND (expiresAtMillis IS NULL OR expiresAtMillis > :now) AND (scopeType = 'GLOBAL' OR (scopeType = 'WORKSPACE' AND scopeId = :workspaceId) OR (scopeType = 'CHAT' AND scopeId = :conversationId)) ORDER BY pinned DESC, confidence DESC, updatedAtMillis DESC LIMIT :limit")
    suspend fun activeMemories(workspaceId: String, conversationId: String, now: Long, limit: Int): List<MemoryEntry>

    @Query("SELECT * FROM memories WHERE state = 'ACTIVE' AND scopeType = 'CHAT' AND scopeId = :conversationId AND (expiresAtMillis IS NULL OR expiresAtMillis > :now) ORDER BY pinned DESC, confidence DESC, updatedAtMillis DESC LIMIT :limit")
    suspend fun activeChatMemories(conversationId: String, now: Long, limit: Int): List<MemoryEntry>

    @Query("SELECT * FROM memories WHERE state != 'DELETED' ORDER BY pinned DESC, updatedAtMillis DESC")
    suspend fun memories(): List<MemoryEntry>

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun memory(id: String): MemoryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addInjectionLog(value: ContextInjectionLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(value: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun conversation(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE archived = 0 AND deletedAtMillis IS NULL ORDER BY pinned DESC, updatedAtMillis DESC")
    suspend fun activeConversations(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE archived = 1 AND deletedAtMillis IS NULL ORDER BY updatedAtMillis DESC")
    suspend fun archivedConversations(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE deletedAtMillis IS NOT NULL ORDER BY deletedAtMillis DESC")
    suspend fun trashedConversations(): List<ConversationEntity>

    @Query("UPDATE conversations SET title = :title, titleMode = 'MANUAL', updatedAtMillis = :now WHERE id = :id")
    suspend fun renameConversation(id: String, title: String, now: Long): Int

    @Query("UPDATE conversations SET pinned = :pinned, updatedAtMillis = :now WHERE id = :id")
    suspend fun pinConversation(id: String, pinned: Boolean, now: Long): Int

    @Query("UPDATE conversations SET archived = :archived, updatedAtMillis = :now WHERE id = :id")
    suspend fun archiveConversation(id: String, archived: Boolean, now: Long): Int

    @Query("UPDATE conversations SET deletedAtMillis = :now, updatedAtMillis = :now WHERE id = :id")
    suspend fun trashConversation(id: String, now: Long): Int

    @Query("UPDATE conversations SET deletedAtMillis = NULL, archived = 0, updatedAtMillis = :now WHERE id = :id")
    suspend fun restoreConversation(id: String, now: Long): Int

    @Query("DELETE FROM conversations WHERE deletedAtMillis IS NOT NULL AND deletedAtMillis < :before")
    suspend fun purgeTrash(before: Long): Int

    @Query("SELECT id FROM conversations WHERE deletedAtMillis IS NOT NULL AND deletedAtMillis < :before")
    suspend fun expiredTrashIds(before: Long): List<String>

    @Query("DELETE FROM conversations WHERE id = :id AND deletedAtMillis IS NOT NULL")
    suspend fun purgeTrashedConversation(id: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBranch(value: ConversationBranch)

    @Query("SELECT * FROM conversation_branches WHERE conversationId = :conversationId ORDER BY createdAtMillis")
    suspend fun branches(conversationId: String): List<ConversationBranch>

    @Query("UPDATE conversations SET activeBranchId = :branchId, updatedAtMillis = :now WHERE id = :conversationId")
    suspend fun setActiveBranch(conversationId: String, branchId: String, now: Long): Int

    @Query("DELETE FROM message_fts WHERE conversationId = :conversationId")
    suspend fun clearSearch(conversationId: String)

    @Insert
    suspend fun addSearchRows(values: List<MessageFtsEntry>)

    @Query("SELECT DISTINCT conversationId FROM message_fts WHERE message_fts MATCH :query LIMIT :limit")
    suspend fun searchConversationIds(query: String, limit: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgentProfile(value: AgentProfile)

    @Query("SELECT * FROM agent_profiles WHERE enabled = 1 ORDER BY name")
    suspend fun agentProfiles(): List<AgentProfile>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgentRunNode(value: AgentRunNode)

    @Query("SELECT * FROM agent_run_nodes WHERE runId = :runId ORDER BY createdAtMillis")
    suspend fun agentRunNodes(runId: String): List<AgentRunNode>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMcpServer(value: McpServerConfig)

    @Query("SELECT * FROM mcp_servers ORDER BY name")
    suspend fun mcpServers(): List<McpServerConfig>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHook(value: HookDefinition)

    @Query("SELECT * FROM hook_definitions WHERE enabled = 1 AND event = :event ORDER BY name")
    suspend fun hooks(event: String): List<HookDefinition>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCommand(value: CommandTemplate)

    @Query("SELECT * FROM command_templates WHERE enabled = 1 ORDER BY name")
    suspend fun commands(): List<CommandTemplate>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBrowserProfile(value: BrowserProfileEntity)

    @Query("SELECT * FROM browser_profiles ORDER BY ephemeral, name")
    suspend fun browserProfiles(): List<BrowserProfileEntity>

    @Query("SELECT * FROM browser_profiles WHERE id = :id LIMIT 1")
    suspend fun browserProfile(id: String): BrowserProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBrowserTab(value: BrowserTabEntity)

    @Query("SELECT * FROM browser_tabs WHERE workspaceId = :workspaceId ORDER BY position")
    suspend fun browserTabs(workspaceId: String): List<BrowserTabEntity>

    @Query("SELECT * FROM browser_tabs WHERE workspaceId = :workspaceId ORDER BY position")
    fun observeBrowserTabs(workspaceId: String): Flow<List<BrowserTabEntity>>

    @Query("DELETE FROM browser_tabs WHERE id = :id")
    suspend fun deleteBrowserTab(id: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBrowserEvent(value: BrowserEventEntity)

    @Query("SELECT * FROM browser_events WHERE tabId = :tabId ORDER BY createdAtMillis DESC LIMIT :limit")
    suspend fun browserEvents(tabId: String, limit: Int): List<BrowserEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSitePermission(value: BrowserSitePermissionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addWorkspaceCheckpoint(value: WorkspaceCheckpoint)

    @Query("SELECT * FROM workspace_checkpoints WHERE conversationId = :conversationId ORDER BY createdAtMillis DESC")
    suspend fun workspaceCheckpoints(conversationId: String): List<WorkspaceCheckpoint>

    @Transaction
    suspend fun replaceConversation(value: ConversationEntity, messages: List<MessageFtsEntry>) {
        upsertConversation(value)
        clearSearch(value.id)
        if (messages.isNotEmpty()) addSearchRows(messages)
    }
}

@Database(
    entities = [
        InstructionRecord::class,
        MemoryEntry::class,
        MemoryRevision::class,
        ContextInjectionLog::class,
        ConversationEntity::class,
        ConversationBranch::class,
        MessageFtsEntry::class,
        TagEntity::class,
        ConversationTag::class,
        WorkspaceCheckpoint::class,
        AgentProfile::class,
        AgentRunNode::class,
        McpServerConfig::class,
        HookDefinition::class,
        CommandTemplate::class,
        BrowserProfileEntity::class,
        BrowserTabEntity::class,
        BrowserEventEntity::class,
        BrowserSitePermissionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class WorkbenchDatabase : RoomDatabase() {
    abstract fun dao(): WorkbenchDao

    companion object {
        @Volatile private var instance: WorkbenchDatabase? = null

        fun get(context: Context): WorkbenchDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                WorkbenchDatabase::class.java,
                "agent-workbench.db",
            ).build().also { instance = it }
        }

        fun newId(): String = UUID.randomUUID().toString()
    }
}
