package dev.agentworkbench

import android.content.Context
import dev.agentworkbench.core.ChatMessage
import dev.agentworkbench.core.ChatMessageStatus
import dev.agentworkbench.core.ChatRole
import dev.agentworkbench.core.MessagePart
import dev.agentworkbench.core.ProviderMessage
import dev.agentworkbench.core.ProviderToolCall
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class ChatSessionSummary(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val providerName: String,
    val modelId: String,
    val messageCount: Int,
    val titleMode: ConversationTitleMode = ConversationTitleMode.AUTO,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val deletedAtMillis: Long? = null,
    val workspaceId: String = "default",
)

data class ChatSessionSnapshot(
    val summary: ChatSessionSummary,
    val messages: List<ChatMessage>,
    val providerLedger: List<ProviderMessage>,
    val toolActivities: List<ToolActivity>,
)

class ChatSessionRepository(context: Context) {
    private val appContext = context.applicationContext
    private val sessionsDirectory = File(context.filesDir, "chat-sessions").apply { mkdirs() }
    private val branchesDirectory = File(context.filesDir, "chat-branches").apply { mkdirs() }
    private val dao = WorkbenchDatabase.get(appContext).dao()
    private val migrationPreferences = appContext.getSharedPreferences(
        "conversation-room-migration",
        Context.MODE_PRIVATE,
    )

    suspend fun create(settings: ProviderSettings): ChatSessionSnapshot =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureMigratedLocked()
                val now = System.currentTimeMillis()
                val snapshot = ChatSessionSnapshot(
                    summary = ChatSessionSummary(
                        id = UUID.randomUUID().toString(),
                        title = "Nova conversa",
                        createdAtMillis = now,
                        updatedAtMillis = now,
                        providerName = settings.preset.displayName,
                        modelId = settings.modelId,
                        messageCount = 0,
                    ),
                    messages = emptyList(),
                    providerLedger = emptyList(),
                    toolActivities = emptyList(),
                )
                writeLocked(snapshot)
                snapshot
            }
        }

    suspend fun list(): List<ChatSessionSummary> = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureMigratedLocked()
            dao.activeConversations().map { it.toSummary() }
        }
    }

    suspend fun archived(): List<ChatSessionSummary> = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureMigratedLocked()
            dao.archivedConversations().map { it.toSummary() }
        }
    }

    suspend fun trashed(): List<ChatSessionSummary> = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureMigratedLocked()
            purgeExpiredTrashLocked(System.currentTimeMillis() - TRASH_RETENTION_MILLIS)
            dao.trashedConversations().map { it.toSummary() }
        }
    }

    suspend fun restore(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureMigratedLocked()
            dao.restoreConversation(id, System.currentTimeMillis()) > 0
        }
    }

    suspend fun load(id: String): ChatSessionSnapshot? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureMigratedLocked()
            val entity = dao.conversation(id) ?: return@withLock null
            decodeRoot(JSONObject(entity.payloadJson)).copy(summary = entity.toSummary())
        }
    }

    suspend fun save(
        id: String,
        settings: ProviderSettings,
        messages: List<ChatMessage>,
        providerLedger: List<ProviderMessage>,
        toolActivities: List<ToolActivity>,
    ): ChatSessionSummary = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureMigratedLocked()
            require(id.matches(SESSION_ID_PATTERN)) { "Invalid session id" }
            val existingEntity = dao.conversation(id)
            val existing = existingEntity?.let {
                decodeRoot(JSONObject(it.payloadJson)).copy(summary = it.toSummary())
            }
            val now = System.currentTimeMillis()
            val normalizedMessages = messages.map(::normalizeMessage)
            val title = if (existing?.summary?.titleMode == ConversationTitleMode.MANUAL) {
                existing.summary.title
            } else {
                normalizedMessages
                    .firstOrNull { it.role == ChatRole.USER && it.text.isNotBlank() }
                    ?.text
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    ?.take(TITLE_MAX_CHARS)
                    ?: existing?.summary?.title
                    ?: "Nova conversa"
            }
            val summary = ChatSessionSummary(
                id = id,
                title = title,
                createdAtMillis = existing?.summary?.createdAtMillis ?: now,
                updatedAtMillis = now,
                providerName = settings.preset.displayName,
                modelId = settings.modelId,
                messageCount = normalizedMessages.size,
                titleMode = existing?.summary?.titleMode ?: ConversationTitleMode.AUTO,
                pinned = existing?.summary?.pinned ?: false,
                archived = existing?.summary?.archived ?: false,
                deletedAtMillis = existing?.summary?.deletedAtMillis,
                workspaceId = existing?.summary?.workspaceId ?: "default",
            )
            writeLocked(
                ChatSessionSnapshot(
                    summary = summary,
                    messages = normalizedMessages,
                    providerLedger = providerLedger,
                    toolActivities = toolActivities.map(::normalizeToolActivity),
                ),
            )
            summary
        }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureMigratedLocked()
            dao.trashConversation(id, System.currentTimeMillis()) > 0
        }
    }

    suspend fun rename(id: String, title: String): ChatSessionSummary? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureMigratedLocked()
            val normalized = title.replace(Regex("\\s+"), " ").trim().take(TITLE_MAX_CHARS)
            require(normalized.isNotEmpty()) { "O título não pode ficar vazio." }
            if (dao.renameConversation(id, normalized, System.currentTimeMillis()) == 0) return@withLock null
            dao.conversation(id)?.toSummary()
        }
    }

    suspend fun pin(id: String, pinned: Boolean): ChatSessionSummary? = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureMigratedLocked()
            if (dao.pinConversation(id, pinned, System.currentTimeMillis()) == 0) return@withLock null
            dao.conversation(id)?.toSummary()
        }
    }

    suspend fun archive(id: String, archived: Boolean): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureMigratedLocked()
            dao.archiveConversation(id, archived, System.currentTimeMillis()) > 0
        }
    }

    suspend fun search(query: String, limit: Int = 100): List<ChatSessionSummary> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureMigratedLocked()
                val normalized = query.trim()
                if (normalized.isEmpty()) return@withLock dao.activeConversations().map { it.toSummary() }
                val fts = normalized.split(Regex("\\s+")).joinToString(" AND ") { token ->
                    "\"${token.replace("\"", "\"\"")}\"*"
                }
                dao.searchConversationIds(fts, limit.coerceIn(1, 500))
                    .mapNotNull { dao.conversation(it) }
                    .filter { !it.archived && it.deletedAtMillis == null }
                    .sortedWith(
                        compareByDescending<ConversationEntity> { it.pinned }
                            .thenByDescending { it.updatedAtMillis },
                    )
                    .map { it.toSummary() }
            }
        }

    /**
     * Preserves the complete pre-edit line and switches subsequent saves to a new branch.
     * The branch payload is deliberately kept outside the Room row: large transcripts remain
     * atomic files and do not make a schema migration necessary on already-installed builds.
     */
    suspend fun fork(id: String, forkMessageId: String?, name: String? = null): ConversationBranch? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                ensureMigratedLocked()
                val current = dao.conversation(id) ?: return@withLock null
                val now = System.currentTimeMillis()
                branchFile(id, current.activeBranchId).also { target ->
                    target.parentFile?.mkdirs()
                    val temporary = File.createTempFile(".branch-", ".tmp", target.parentFile)
                    try {
                        temporary.writeText(current.payloadJson, Charsets.UTF_8)
                        Files.move(
                            temporary.toPath(),
                            target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } finally {
                        temporary.delete()
                    }
                }
                val branch = ConversationBranch(
                    id = UUID.randomUUID().toString(),
                    conversationId = id,
                    parentBranchId = current.activeBranchId,
                    forkMessageId = forkMessageId,
                    name = name?.trim()?.take(48).takeUnless { it.isNullOrEmpty() }
                        ?: "Alternativa ${dao.branches(id).size + 1}",
                    createdAtMillis = now,
                )
                dao.upsertBranch(branch)
                dao.setActiveBranch(id, branch.id, now)
                branch
            }
        }

    suspend fun branches(id: String): List<ConversationBranch> = withContext(Dispatchers.IO) {
        dao.branches(id)
    }

    private suspend fun writeLocked(snapshot: ChatSessionSnapshot) {
        val target = sessionFile(snapshot.summary.id)
            ?: throw IllegalArgumentException("Invalid session id")
        val encoded = encode(snapshot).toString()
        require(encoded.toByteArray().size <= MAX_SESSION_BYTES) {
            "Conversation exceeds the local persistence limit"
        }
        val entity = snapshot.toEntity(
            payload = encoded,
            activeBranchId = dao.conversation(snapshot.summary.id)?.activeBranchId,
        )
        dao.replaceConversation(
            entity,
            snapshot.messages.filter { it.text.isNotBlank() }.map { message ->
                MessageFtsEntry(
                    rowId = ftsRowId(snapshot.summary.id, message.id),
                    conversationId = snapshot.summary.id,
                    messageId = message.id,
                    body = listOf(snapshot.summary.title, message.text).joinToString("\n"),
                )
            },
        )
        if (dao.branches(snapshot.summary.id).isEmpty()) {
            dao.upsertBranch(
                ConversationBranch(
                    id = entity.activeBranchId,
                    conversationId = entity.id,
                    parentBranchId = null,
                    forkMessageId = null,
                    name = "Principal",
                    createdAtMillis = entity.createdAtMillis,
                ),
            )
        }
        // Keep an atomic JSON mirror until the Room migration is proven on real devices.
        val temporary = File.createTempFile(".chat-", ".tmp", sessionsDirectory)
        try {
            temporary.writeText(encoded, Charsets.UTF_8)
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    private fun readLocked(file: File): ChatSessionSnapshot {
        require(file.length() in 1..MAX_SESSION_BYTES) { "Invalid conversation size" }
        val root = JSONObject(file.readText(Charsets.UTF_8))
        return decodeRoot(root)
    }

    private fun decodeRoot(root: JSONObject): ChatSessionSnapshot {
        require(root.optInt("version") == FORMAT_VERSION) { "Unsupported conversation version" }
        val summaryJson = root.getJSONObject("summary")
        val summary = ChatSessionSummary(
            id = summaryJson.getString("id"),
            title = summaryJson.optString("title", "Nova conversa").take(TITLE_MAX_CHARS),
            createdAtMillis = summaryJson.getLong("created_at"),
            updatedAtMillis = summaryJson.getLong("updated_at"),
            providerName = summaryJson.optString("provider"),
            modelId = summaryJson.optString("model"),
            messageCount = summaryJson.optInt("message_count"),
        )
        return ChatSessionSnapshot(
            summary = summary,
            messages = root.optJSONArray("messages").mapObjects(::decodeMessage),
            providerLedger = root.optJSONArray("provider_ledger").mapObjects(::decodeProviderMessage),
            toolActivities = root.optJSONArray("tool_activities").mapObjects(::decodeToolActivity),
        )
    }

    private fun encode(snapshot: ChatSessionSnapshot): JSONObject = JSONObject()
        .put("version", FORMAT_VERSION)
        .put(
            "summary",
            JSONObject()
                .put("id", snapshot.summary.id)
                .put("title", snapshot.summary.title)
                .put("created_at", snapshot.summary.createdAtMillis)
                .put("updated_at", snapshot.summary.updatedAtMillis)
                .put("provider", snapshot.summary.providerName)
                .put("model", snapshot.summary.modelId)
                .put("message_count", snapshot.summary.messageCount),
        )
        .put("messages", JSONArray(snapshot.messages.map(::encodeMessage)))
        .put("provider_ledger", JSONArray(snapshot.providerLedger.map(::encodeProviderMessage)))
        .put("tool_activities", JSONArray(snapshot.toolActivities.map(::encodeToolActivity)))

    private fun encodeMessage(message: ChatMessage): JSONObject = JSONObject()
        .put("id", message.id)
        .put("role", message.role.name)
        .put("text", message.text)
        .put("status", message.status.name)
        .putNullable("provider_id", message.providerId)
        .putNullable("provider_name", message.providerDisplayName)
        .putNullable("requested_model", message.requestedModelId)
        .putNullable("resolved_model", message.resolvedModelId)
        .putNullable("error", message.error)
        .put("reasoning", message.reasoning)

    private fun decodeMessage(value: JSONObject): ChatMessage = normalizeMessage(
        ChatMessage(
            id = value.getString("id"),
            role = ChatRole.valueOf(value.getString("role")),
            text = value.optString("text"),
            status = ChatMessageStatus.valueOf(value.getString("status")),
            providerId = value.optNullableString("provider_id"),
            providerDisplayName = value.optNullableString("provider_name"),
            requestedModelId = value.optNullableString("requested_model"),
            resolvedModelId = value.optNullableString("resolved_model"),
            error = value.optNullableString("error"),
            // Ausente em sessões salvas antes deste campo existir; optString já cai para "".
            reasoning = value.optString("reasoning"),
        ),
    )

    private fun encodeProviderMessage(message: ProviderMessage): JSONObject = JSONObject()
        .put("role", message.role)
        .put(
            "parts",
            JSONArray(
                message.parts.map { part ->
                    when (part) {
                        is MessagePart.Text -> JSONObject()
                            .put("type", "text")
                            .put("value", part.value)

                        is MessagePart.ImageReference -> JSONObject()
                            .put("type", "image")
                            .put("uri", part.uri)
                            .put("mime_type", part.mimeType)

                        is MessagePart.ToolResult -> JSONObject()
                            .put("type", "tool_result")
                            .put("call_id", part.callId)
                            .put("payload", part.payload)
                            .put("is_error", part.isError)
                    }
                },
            ),
        )
        .put(
            "tool_calls",
            JSONArray(
                message.toolCalls.map { call ->
                    JSONObject()
                        .put("call_id", call.callId)
                        .put("tool_name", call.toolName)
                        .put("arguments", call.argumentsJson)
                },
            ),
        )
        .putNullable("tool_call_id", message.toolCallId)

    private fun decodeProviderMessage(value: JSONObject): ProviderMessage = ProviderMessage(
        role = value.getString("role"),
        parts = value.optJSONArray("parts").mapObjects { part ->
            when (part.getString("type")) {
                "text" -> MessagePart.Text(part.optString("value"))
                "image" -> MessagePart.ImageReference(
                    uri = part.getString("uri"),
                    mimeType = part.getString("mime_type"),
                )
                "tool_result" -> MessagePart.ToolResult(
                    callId = part.getString("call_id"),
                    payload = part.optString("payload"),
                    isError = part.optBoolean("is_error"),
                )
                else -> throw IllegalArgumentException("Unknown message part")
            }
        },
        toolCalls = value.optJSONArray("tool_calls").mapObjects { call ->
            ProviderToolCall(
                callId = call.getString("call_id"),
                toolName = call.getString("tool_name"),
                argumentsJson = call.optString("arguments", "{}"),
            )
        },
        toolCallId = value.optNullableString("tool_call_id"),
    )

    private fun encodeToolActivity(activity: ToolActivity): JSONObject = JSONObject()
        .put("call_id", activity.callId)
        .put("tool_name", activity.toolName)
        .put("summary", activity.summary)
        .put("status", activity.status.name)
        .putNullable("after_message_id", activity.afterMessageId)
        .putNullable("result_preview", activity.resultPreview)

    private fun decodeToolActivity(value: JSONObject): ToolActivity = normalizeToolActivity(
        ToolActivity(
            callId = value.getString("call_id"),
            toolName = value.getString("tool_name"),
            summary = value.optString("summary"),
            status = ToolActivityStatus.valueOf(value.getString("status")),
            afterMessageId = value.optNullableString("after_message_id"),
            resultPreview = value.optNullableString("result_preview"),
        ),
    )

    private fun normalizeMessage(message: ChatMessage): ChatMessage = message

    private fun normalizeToolActivity(activity: ToolActivity): ToolActivity = activity

    private fun sessionFile(id: String): File? {
        if (!id.matches(SESSION_ID_PATTERN)) return null
        return File(sessionsDirectory, "$id$SESSION_SUFFIX")
    }

    private fun JSONObject.putNullable(name: String, value: String?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name)

    private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> =
        if (this == null) {
            emptyList()
        } else {
            buildList {
                for (index in 0 until length()) {
                    add(transform(getJSONObject(index)))
                }
            }
        }

    private suspend fun ensureMigratedLocked() {
        if (migrationPreferences.getBoolean(KEY_ROOM_MIGRATED, false)) return
        var allMigrated = true
        sessionsDirectory.listFiles { file -> file.isFile && file.name.endsWith(SESSION_SUFFIX) }
            .orEmpty()
            .forEach { file ->
                runCatching {
                    val snapshot = readLocked(file)
                    if (dao.conversation(snapshot.summary.id) == null) {
                        val encoded = encode(snapshot).toString()
                        dao.replaceConversation(
                            snapshot.toEntity(encoded),
                            snapshot.messages.filter { it.text.isNotBlank() }.map { message ->
                                MessageFtsEntry(
                                    rowId = ftsRowId(snapshot.summary.id, message.id),
                                    conversationId = snapshot.summary.id,
                                    messageId = message.id,
                                    body = listOf(snapshot.summary.title, message.text).joinToString("\n"),
                                )
                            },
                        )
                    }
                }.onFailure { allMigrated = false }
            }
        if (allMigrated) {
            migrationPreferences.edit().putBoolean(KEY_ROOM_MIGRATED, true).commit()
        }
    }

    private suspend fun purgeExpiredTrashLocked(before: Long) {
        dao.expiredTrashIds(before).forEach { id ->
            // FTS4 has no foreign key cascade. Remove its rows explicitly before deleting the
            // conversation, then remove the compatibility mirror and branch snapshots as well.
            dao.clearSearch(id)
            if (dao.purgeTrashedConversation(id) > 0) {
                sessionFile(id)?.delete()
                File(branchesDirectory, id).deleteRecursively()
            }
        }
    }

    private fun ConversationEntity.toSummary(): ChatSessionSummary = ChatSessionSummary(
        id = id,
        title = title,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        providerName = providerName,
        modelId = modelId,
        messageCount = messageCount,
        titleMode = runCatching { ConversationTitleMode.valueOf(titleMode) }
            .getOrDefault(ConversationTitleMode.AUTO),
        pinned = pinned,
        archived = archived,
        deletedAtMillis = deletedAtMillis,
        workspaceId = workspaceId,
    )

    private fun ChatSessionSnapshot.toEntity(
        payload: String,
        activeBranchId: String? = null,
    ): ConversationEntity =
        ConversationEntity(
            id = summary.id,
            title = summary.title,
            titleMode = summary.titleMode.name,
            workspaceId = summary.workspaceId,
            createdAtMillis = summary.createdAtMillis,
            updatedAtMillis = summary.updatedAtMillis,
            providerName = summary.providerName,
            modelId = summary.modelId,
            messageCount = summary.messageCount,
            pinned = summary.pinned,
            archived = summary.archived,
            deletedAtMillis = summary.deletedAtMillis,
            activeBranchId = activeBranchId ?: "${summary.id}:main",
            payloadJson = payload,
        )

    private fun ftsRowId(conversationId: String, messageId: String): Long {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$conversationId:$messageId".toByteArray(Charsets.UTF_8))
        var value = 0L
        repeat(7) { index -> value = (value shl 8) or (digest[index].toLong() and 0xff) }
        return value.coerceAtLeast(1L)
    }

    private fun branchFile(conversationId: String, branchId: String): File {
        require(conversationId.matches(SESSION_ID_PATTERN)) { "Invalid conversation id" }
        val safeBranch = branchId.replace(Regex("[^A-Za-z0-9._:-]"), "_")
        return File(File(branchesDirectory, conversationId), "$safeBranch.json")
    }

    private companion object {
        // Activity, foreground service and recovery worker may all touch a chat. A process-wide
        // lock keeps their atomic replace operations ordered rather than merely atomic per owner.
        val mutex = Mutex()
        const val FORMAT_VERSION = 1
        const val SESSION_SUFFIX = ".json"
        const val TITLE_MAX_CHARS = 56
        const val KEY_ROOM_MIGRATED = "room_migrated_v1"
        // The visible transcript is intentionally retained even after the
        // provider ledger is compacted. Keep a corruption/OOM guard, but make
        // it large enough for genuinely long-running local conversations.
        const val MAX_SESSION_BYTES = 128L * 1024 * 1024
        const val TRASH_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        val SESSION_ID_PATTERN =
            Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }
}
