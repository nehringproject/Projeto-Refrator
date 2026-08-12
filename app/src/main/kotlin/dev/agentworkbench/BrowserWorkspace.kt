package dev.agentworkbench

import java.util.UUID
import kotlinx.coroutines.flow.Flow

class BrowserWorkspaceRepository(context: android.content.Context) {
    private val dao = WorkbenchDatabase.get(context).dao()

    suspend fun ensureProfiles(): List<BrowserProfileEntity> {
        val current = dao.browserProfiles()
        if (current.isNotEmpty()) return current
        val now = System.currentTimeMillis()
        val profiles = buildList {
            add(
                BrowserProfileEntity(
                    id = "default",
                    name = "Padrão",
                    webViewProfileName = AgentBrowserSession.DEFAULT_PROFILE_NAME,
                    ephemeral = false,
                    color = 0xFF5DA33A,
                    createdAtMillis = now,
                    lastUsedAtMillis = now,
                ),
            )
            if (AgentBrowserSession.supportsMultipleProfiles()) {
                add(profile("personal", "Pessoal", false, 0xFFDFA72F, now))
                add(profile("work", "Trabalho", false, 0xFF8B6FC1, now))
                add(profile("disposable", "Descartável", true, 0xFFC3485B, now))
            }
        }
        profiles.forEach { dao.upsertBrowserProfile(it) }
        return profiles
    }

    suspend fun tabs(workspaceId: String): List<BrowserTabEntity> {
        val existing = dao.browserTabs(workspaceId)
        if (existing.isNotEmpty()) {
            existing.filter { !it.url.isUsableBrowserUrl() }.forEach { tab ->
                dao.upsertBrowserTab(
                    tab.copy(
                        title = tab.title.takeUnless(String::isJsonNull).orEmpty().ifBlank { "Nova aba" },
                        url = DEFAULT_START_URL,
                        updatedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
            return dao.browserTabs(workspaceId)
        }
        val value = newTab(workspaceId, "default", 0)
        dao.upsertBrowserTab(value)
        return listOf(value)
    }

    fun observeTabs(workspaceId: String): Flow<List<BrowserTabEntity>> = dao.observeBrowserTabs(workspaceId)

    suspend fun addTab(workspaceId: String, profileId: String): BrowserTabEntity {
        val existing = dao.browserTabs(workspaceId)
        require(existing.size < MAX_TABS) { "Limite de $MAX_TABS abas atingido." }
        val value = newTab(workspaceId, profileId, existing.size)
        dao.upsertBrowserTab(value)
        select(workspaceId, value.id)
        return value
    }

    suspend fun select(workspaceId: String, tabId: String) {
        dao.browserTabs(workspaceId).forEach { tab ->
            dao.upsertBrowserTab(tab.copy(selected = tab.id == tabId))
        }
    }

    suspend fun update(tab: BrowserTabEntity) = dao.upsertBrowserTab(tab.copy(updatedAtMillis = System.currentTimeMillis()))
    suspend fun close(tabId: String) = dao.deleteBrowserTab(tabId)
    suspend fun profiles(): List<BrowserProfileEntity> = dao.browserProfiles()
    suspend fun profile(id: String): BrowserProfileEntity? = dao.browserProfile(id)

    private fun profile(id: String, name: String, ephemeral: Boolean, color: Long, now: Long) =
        BrowserProfileEntity(id, name, "agent-workbench-$id", ephemeral, color, now, now)

    private fun newTab(workspaceId: String, profileId: String, position: Int): BrowserTabEntity =
        BrowserTabEntity(
            id = UUID.randomUUID().toString(),
            profileId = profileId,
            workspaceId = workspaceId,
            title = "Nova aba",
            url = DEFAULT_START_URL,
            position = position,
            selected = true,
            controlOwner = "agent",
            frozen = false,
            updatedAtMillis = System.currentTimeMillis(),
        )

    companion object {
        const val MAX_TABS = 12
        const val DEFAULT_START_URL = "https://duckduckgo.com/"
    }
}

private fun String?.isJsonNull(): Boolean =
    this == null || isBlank() || equals("null", ignoreCase = true)

private fun String?.isUsableBrowserUrl(): Boolean {
    val candidate = this?.takeUnless { it.isJsonNull() } ?: return false
    return candidate.startsWith("https://") || candidate.startsWith("http://")
}
