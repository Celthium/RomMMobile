package com.rommmobile.app.feature.sync

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Per game and kind, what the last run found. */
enum class ItemStatus { SYNCED, UPLOADED, DOWNLOADED, PENDING, CONFLICT, NONE, ERROR }

/**
 * What both sides looked like the last time a state file was in sync. States have no device
 * tracking on the server, so this is how a change on one side is told from a change on both.
 */
@Serializable
data class Baseline(
    val localHash: String,
    val serverId: Int?,
    val serverUpdatedAt: String?,
)

/** A file from the server waiting for RetroArch to be closed before it is written. */
@Serializable
data class PendingRestore(
    val key: String,
    val kind: SaveKind,
    val romId: Int,
    val game: String,
    val target: String,
    val temp: String,
    val serverId: Int,
    val serverUpdatedAt: String?,
    /** Target's mtime when the download was decided, 0 when it did not exist: a change means RetroArch wrote it since. */
    val targetMtime: Long,
    val createdAt: Long,
    /** Set when the user restored an older save version: once written, it goes back up as the slot's newest. */
    val promoteSlot: String? = null,
    /** RomM emulator of that version, kept on the new upload. */
    val emulator: String? = null,
)

@Serializable
data class Conflict(
    val key: String,
    val kind: SaveKind,
    val romId: Int,
    val game: String,
    val localPath: String,
    val localSize: Long,
    val localMtime: Long,
    val serverId: Int,
    val serverFileName: String,
    val serverUpdatedAt: String?,
    val emulator: String?,
    val slot: String?,
    val createdAt: Long,
)

@Serializable
data class GameSyncStatus(
    val status: ItemStatus,
    val at: Long,
    val localFiles: Int = 0,
    val serverFiles: Int = 0,
    val detail: String? = null,
)

@Serializable
data class SyncState(
    val baselines: Map<String, Baseline> = emptyMap(),
    val pending: List<PendingRestore> = emptyList(),
    val conflicts: List<Conflict> = emptyList(),
    /** "romId/KIND" -> status. */
    val games: Map<String, GameSyncStatus> = emptyMap(),
    /** "platformSlug/stem" -> RomM id, found once by a server search. */
    val romIds: Map<String, Int> = emptyMap(),
    val lastRunAt: Long? = null,
    val lastRunSummary: String? = null,
    val lastRunError: String? = null,
) {
    fun game(romId: Int, kind: SaveKind): GameSyncStatus? = games[gameKey(romId, kind)]

    companion object {
        fun gameKey(romId: Int, kind: SaveKind) = "$romId/${kind.name}"
    }
}

/** Small JSON file in the app's private folder: no Room migration for what is a cache of decisions. */
@Singleton
class SyncStateStore @Inject constructor(@ApplicationContext context: Context) {
    private val file = File(context.filesDir, "sync/state.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private fun load(): SyncState = runCatching { json.decodeFromString(SyncState.serializer(), file.readText()) }.getOrDefault(SyncState())

    suspend fun update(transform: (SyncState) -> SyncState): SyncState = lock.withLock {
        val next = transform(_state.value)
        _state.value = next
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(SyncState.serializer(), next))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        next
    }
}
