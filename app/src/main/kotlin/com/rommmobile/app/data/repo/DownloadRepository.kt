package com.rommmobile.app.data.repo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.rommmobile.app.core.network.ServerStore
import com.rommmobile.app.core.storage.StorageRoot
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.data.db.DownloadDao
import com.rommmobile.app.data.db.DownloadEntity
import com.rommmobile.app.data.db.DownloadKind
import com.rommmobile.app.data.db.DownloadState
import com.rommmobile.app.data.mapping.MappingRepository
import com.rommmobile.app.data.mapping.Resolution
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.feature.downloads.engine.DownloadEngine
import com.rommmobile.app.feature.downloads.engine.DownloadProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

sealed interface EnqueueResult {
    data class Enqueued(val id: Long, val relDir: String) : EnqueueResult
    data object AlreadyQueued : EnqueueResult
    data object AlreadyPresent : EnqueueResult
    data class NeedsFolder(val platform: Platform, val resolution: Resolution) : EnqueueResult
    data object NoRoot : EnqueueResult
    data object NotConfigured : EnqueueResult
}

@Singleton
class DownloadRepository @Inject constructor(
    private val dao: DownloadDao,
    private val engine: DownloadEngine,
    private val mapping: MappingRepository,
    private val settings: SettingsStore,
    private val serverStore: ServerStore,
    private val localLibrary: LocalLibraryRepository,
    private val platforms: PlatformRepository,
    private val log: FileLogger,
) {
    val progress: StateFlow<Map<Long, DownloadProgress>> get() = engine.progress
    fun observeAll(): Flow<List<DownloadEntity>> = dao.observeAll()
    fun observeOpen(): Flow<List<DownloadEntity>> = dao.observeOpen()
    fun observeOpenForRom(romId: Int): Flow<List<DownloadEntity>> = dao.observeOpenForRom(romId)
    suspend fun recent(limit: Int) = dao.recent(limit)

    /**
     * The "press download, done" path. Returns [EnqueueResult.NeedsFolder] only when the
     * platform has no folder yet, which the UI resolves with a single confirmation.
     */
    suspend fun enqueueRom(rom: Rom, force: Boolean = false): EnqueueResult {
        val base = serverStore.config.value.activeUrl ?: return EnqueueResult.NotConfigured
        val platform = platforms.byId(rom.platformId) ?: Platform(rom.platformId, rom.platformSlug, rom.platformSlug, rom.platformName, 0, 0, 0)
        val relDir = when (val res = mapping.resolve(platform)) {
            is Resolution.Resolved -> res.mapping.relDir
            Resolution.NoRoot -> return EnqueueResult.NoRoot
            else -> return EnqueueResult.NeedsFolder(platform, res)
        }
        val main = if (rom.isNestedSingle) rom.mainFile else null
        val fileName = main?.fileName ?: rom.downloadFileName
        if (!force && localLibrary.isPresent(rom.platformSlug, rom.fsName)) return EnqueueResult.AlreadyPresent

        dao.byRomAndName(rom.id, fileName)?.let { existing ->
            when {
                !existing.state.isTerminal -> return EnqueueResult.AlreadyQueued
                // A finished download only means "on the device" while the file is still there.
                // Uninstalled (or deleted by hand), the old row used to keep answering "already
                // on the device" and the game could not simply be downloaded again.
                existing.state == DownloadState.COMPLETED && !force && localLibrary.isPresent(rom.platformSlug, rom.fsName) ->
                    return EnqueueResult.AlreadyPresent
                else -> dao.delete(existing.id)
            }
        }

        val s = settings.snapshot()
        val folderPerGame = rom.hasMultipleFiles && rom.platformSlug in s.folderPerGameSlugs
        val targetRelDir = when {
            // Mirrors the server: the game goes into its own folder, where its DLC and updates
            // will land too, and the folder name is what marks the game as present.
            main != null -> "$relDir/${sanitizeFolder(rom.fsName)}"
            folderPerGame -> "$relDir/${sanitizeFolder(rom.fsNameNoExt)}"
            else -> relDir
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val extract = when {
            rom.hasMultipleFiles -> true // server-made zip with the .m3u: must be unpacked to be playable
            // An arcade zip IS the romset: MAME/FBNeo load it as one file and unpacking breaks
            // the game. The user's per-platform override still wins over this default.
            rom.platformSlug in NEVER_EXTRACT_SLUGS -> s.extractOverrides[rom.platformSlug] ?: false
            ext == "zip" -> s.extractOverrides[rom.platformSlug] ?: s.autoExtractZip
            ext == "7z" -> s.extractOverrides[rom.platformSlug] ?: s.autoExtract7z
            else -> false
        }
        // Relative on purpose: the host is resolved when the download actually runs, so a queue
        // built on the LAN still works after the app falls back to the remote address.
        val url = if (main != null) {
            // Only the game file: the folder's DLC and updates are separate, optional downloads.
            "api/roms/${rom.id}/content/" + encode(main.fileName) + "?file_ids=${main.id}"
        } else {
            "api/roms/${rom.id}/content/" + encode(rom.fsName)
        }

        val now = System.currentTimeMillis()
        // Two fast presses race on the (romId, fileName) unique index: treat the loser as
        // "already queued" instead of crashing the caller.
        val id = try {
            dao.insert(
            DownloadEntity(
                kind = DownloadKind.ROM,
                romId = rom.id,
                title = rom.name,
                platformSlug = rom.platformSlug,
                platformName = rom.platformName,
                fileName = fileName,
                url = url,
                targetRelDir = targetRelDir,
                extract = extract,
                totalBytes = main?.sizeBytes?.takeIf { it > 0 } ?: rom.sizeBytes,
                downloadedBytes = 0,
                state = DownloadState.PENDING,
                error = null,
                attempt = 0,
                priority = 0,
                notBefore = 0,
                md5 = if (main != null) main.md5 else rom.md5.takeIf { !rom.hasMultipleFiles },
                sha1 = if (main != null) main.sha1 else rom.sha1.takeIf { !rom.hasMultipleFiles },
                crc = if (main != null) main.crc else rom.crc.takeIf { !rom.hasMultipleFiles },
                coverSmallPath = rom.coverSmall,
                resultPath = null,
                createdAt = now,
                updatedAt = now,
                completedAt = null,
            )
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            return EnqueueResult.AlreadyQueued
        }
        log.i("Download", "enqueued ${rom.fsName} -> $targetRelDir (extract=$extract)")
        engine.kick()
        return EnqueueResult.Enqueued(id, targetRelDir)
    }

    /**
     * One file of a game on its own: a DLC, an update, a mod, a patch... RomM serves single files
     * at `/api/roms/{fileId}/files/content/{name}`. Lands in the game's folder, in a sub-folder
     * named after the category (`ps4/Bloodborne/dlc/`), the layout RomM itself scans.
     */
    suspend fun enqueueFile(rom: Rom, file: RomFile, label: String, force: Boolean = false): EnqueueResult {
        serverStore.config.value.activeUrl ?: return EnqueueResult.NotConfigured
        val platform = platforms.byId(rom.platformId) ?: Platform(rom.platformId, rom.platformSlug, rom.platformSlug, rom.platformName, 0, 0, 0)
        val relDir = when (val res = mapping.resolve(platform)) {
            is Resolution.Resolved -> res.mapping.relDir
            Resolution.NoRoot -> return EnqueueResult.NoRoot
            else -> return EnqueueResult.NeedsFolder(platform, res)
        }
        val gameFolder = sanitizeFolder(if (rom.isNestedSingle || rom.hasMultipleFiles) rom.fsName else rom.fsNameNoExt)
        val category = file.category?.takeIf { it.isNotBlank() && it != Rom.GAME }
        val targetRelDir = listOfNotNull(relDir, gameFolder, category?.let { sanitizeFolder(it) }).joinToString("/")
        dao.byRomAndName(rom.id, file.fileName)?.let { existing ->
            when {
                !existing.state.isTerminal -> return EnqueueResult.AlreadyQueued
                // Extras are not in the local index: "present" means the file is still in its
                // folder. Deleted by hand (or with the game), the old row must not block it.
                existing.state == DownloadState.COMPLETED && !force &&
                    withContext(Dispatchers.IO) { mapping.gateway()?.exists(targetRelDir, file.fileName) == true } ->
                    return EnqueueResult.AlreadyPresent
                else -> dao.delete(existing.id)
            }
        }
        val now = System.currentTimeMillis()
        val id = try {
            dao.insert(
                DownloadEntity(
                    kind = DownloadKind.ROM, romId = rom.id, title = "${rom.name} \u00B7 $label",
                    platformSlug = rom.platformSlug, platformName = rom.platformName,
                    fileName = file.fileName, url = "api/roms/${file.id}/files/content/" + encode(file.fileName),
                    targetRelDir = targetRelDir, extract = false, totalBytes = file.sizeBytes,
                    downloadedBytes = 0, state = DownloadState.PENDING, error = null, attempt = 0, priority = 0, notBefore = 0,
                    md5 = file.md5, sha1 = file.sha1, crc = file.crc, coverSmallPath = rom.coverSmall,
                    resultPath = null, createdAt = now, updatedAt = now, completedAt = null,
                )
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            return EnqueueResult.AlreadyQueued
        }
        log.i("Download", "enqueued file ${file.fileName} (${file.category}) -> $targetRelDir")
        engine.kick()
        return EnqueueResult.Enqueued(id, targetRelDir)
    }

    /** BIOS files go to RetroArch/system (configurable), never into platform folders. */
    suspend fun enqueueFirmware(firmwareId: Int, fileName: String, sizeBytes: Long, md5: String?, sha1: String?, crc: String?, platformName: String): EnqueueResult {
        val base = serverStore.config.value.activeUrl ?: return EnqueueResult.NotConfigured
        val s = settings.snapshot()
        val root = s.storageRoot as? StorageRoot.Direct ?: return EnqueueResult.NoRoot
        val biosDir = s.biosPath?.takeIf { it.isNotBlank() } ?: (root.path.substringBeforeLast('/') + "/RetroArch/system")
        dao.byRomAndName(-firmwareId, fileName)?.let { if (!it.state.isTerminal) return EnqueueResult.AlreadyQueued else dao.delete(it.id) }
        if (java.io.File(biosDir, fileName).exists()) return EnqueueResult.AlreadyPresent
        val url = "api/firmware/$firmwareId/content/" + java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val now = System.currentTimeMillis()
        val id = dao.insert(
            DownloadEntity(
                kind = DownloadKind.FIRMWARE, romId = -firmwareId, title = fileName, platformSlug = "bios", platformName = platformName,
                fileName = fileName, url = url, targetRelDir = "abs:$biosDir/$fileName", extract = shouldUnpackBios(fileName), totalBytes = sizeBytes,
                downloadedBytes = 0, state = DownloadState.PENDING, error = null, attempt = 0, priority = 1, notBefore = 0,
                md5 = md5, sha1 = sha1, crc = crc, coverSmallPath = null, resultPath = null, createdAt = now, updatedAt = now, completedAt = null,
            )
        )
        engine.kick()
        return EnqueueResult.Enqueued(id, biosDir)
    }

    fun pause(id: Long) = engine.pause(id)
    fun resume(id: Long) = engine.resume(id)
    fun cancel(id: Long) = engine.cancel(id)
    fun retry(id: Long) = engine.retry(id)
    fun remove(id: Long) = engine.remove(id)
    fun pauseAll() = engine.pauseAll()
    fun resumeAll() = engine.resumeAll()
    fun clearFinished() = engine.clearFinished()

    suspend fun hasOpen(): Boolean = dao.countOpen() > 0

    private companion object {
        /** Arcade BIOS romsets that emulators read zipped (FBNeo / MAME / Flycast naming). */
        val ARCADE_BIOS_SETS = setOf(
            "neogeo.zip", "neocdz.zip", "pgm.zip", "skns.zip", "naomi.zip", "naomi2.zip", "awbios.zip",
            "hikaru.zip", "cpzn1.zip", "cpzn2.zip", "decocass.zip", "isgsm.zip", "midssio.zip", "nmk004.zip",
            "ym2608.zip", "qsound.zip", "konamigx.zip", "stvbios.zip", "f355bios.zip", "f355dlx.zip",
            "airlbios.zip", "segasp.zip", "triforce.zip", "coleco.zip", "msx.zip", "spec128.zip", "spectrum.zip",
        )
        /** Systems whose emulators read the archive itself; extracting it destroys the game. */
        val NEVER_EXTRACT_SLUGS = setOf(
            "arcade", "mame", "fbneo", "neogeoaes", "neogeomvs", "neo-geo", "neogeo",
            "cps1", "cps2", "cps3", "daphne", "naomi", "atomiswave", "model2", "model3",
        )
    }

    private fun encode(name: String): String = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    /**
     * A BIOS pack zipped on the server ("PlayStation 1 BIOS.zip") is useless to RetroArch, which
     * looks for the loose scph*.bin in system/: unpack it. Arcade BIOS sets are the opposite:
     * FBNeo and MAME load neogeo.zip & co. as archives, so those stay whole.
     */
    private fun shouldUnpackBios(fileName: String): Boolean {
        val n = fileName.lowercase()
        if (!(n.endsWith(".zip") || n.endsWith(".7z"))) return false
        return n !in ARCADE_BIOS_SETS
    }

    private fun sanitizeFolder(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.').ifEmpty { "game" }
}
