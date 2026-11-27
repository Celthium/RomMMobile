package com.rommmobile.app.feature.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.rommmobile.app.BuildConfig
import com.rommmobile.app.MainActivity
import com.rommmobile.app.R
import com.rommmobile.app.core.network.ApiErrorKind
import com.rommmobile.app.core.network.ApiException
import com.rommmobile.app.core.network.AuthMode
import com.rommmobile.app.core.network.AuthStore
import com.rommmobile.app.core.network.apiCall
import com.rommmobile.app.core.storage.StorageRoot
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.data.api.ClientSaveDto
import com.rommmobile.app.data.api.ConfirmDownloadBody
import com.rommmobile.app.data.api.DeviceCreateBody
import com.rommmobile.app.data.api.NegotiateBody
import com.rommmobile.app.data.api.RommApi
import com.rommmobile.app.data.api.SYNC_SCOPES
import com.rommmobile.app.data.api.StateDto
import com.rommmobile.app.data.api.SyncCompleteBody
import com.rommmobile.app.data.api.SyncOperationDto
import com.rommmobile.app.data.db.DownloadDao
import com.rommmobile.app.data.db.LocalFileDao
import com.rommmobile.app.data.db.PlatformDao
import com.rommmobile.app.data.db.RomDao
import com.rommmobile.app.data.prefs.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A save or state file found on the device. */
data class LocalSaveFile(val file: File, val kind: SaveKind, val stem: String, val coreDir: String?)

/** A game the device holds, with every name RetroArch may have filed its saves under. */
data class GameRef(
    val romId: Int,
    val name: String,
    val platformSlug: String,
    /** ROM file names without extension, the device's own first. */
    val stems: List<String>,
    /** Folders that hold the ROM, absolute; empty on a SAF root. */
    val contentDirs: List<File>,
    /** First folder under the ROM root, which is ES-DE's system name (`gba`). */
    val system: String?,
)

data class RunResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val pending: Int = 0,
    val conflicts: Int = 0,
    val errors: Int = 0,
    val skipped: String? = null,
)

enum class ConflictChoice { KEEP_DEVICE, KEEP_SERVER }

/**
 * Two-way sync of RetroArch saves and states with RomM, one kind at a time.
 *
 * Saves go through the server's own negotiation (`/api/sync/negotiate`): it tracks this device and
 * answers upload / download / conflict per (game, slot). Slot `autosave` and RomM's core ids, as
 * RomM's web player and RetroArch sync use. States have no server-side tracking, so the last synced
 * hash and server version of each file are kept here ([Baseline]) and compared on both sides.
 *
 * Rules that hold everywhere:
 * - nothing is ever overwritten silently: when both sides changed, the file is set aside as a
 *   [Conflict] and nothing is written until the user picks a side;
 * - before a file on the device is replaced, the old one is copied into `files/sync/backups`;
 * - a file from the server is written only while RetroArch is closed ([RetroArchWatcher]); else it
 *   waits as a [PendingRestore] and a notification says so.
 */
@Singleton
class SaveSyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: RommApi,
    private val authStore: AuthStore,
    private val prefs: SyncPrefs,
    private val store: SyncStateStore,
    private val watcher: RetroArchWatcher,
    private val settings: SettingsStore,
    private val downloads: DownloadDao,
    private val localFiles: LocalFileDao,
    private val romDao: RomDao,
    private val platformDao: PlatformDao,
    private val log: FileLogger,
) {
    private val runLock = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    val state: StateFlow<SyncState> = store.state

    private val syncDir get() = File(context.filesDir, "sync")
    private val incomingDir get() = File(syncDir, "incoming")
    private val backupDir get() = File(syncDir, "backups")

    suspend fun setup(): RetroArchSetup = withContext(Dispatchers.IO) {
        val p = prefs.snapshot()
        RetroArchConfig.detect(context, p.manualSavesDir, p.manualStatesDir)
    }

    /**
     * Scopes the session lacks for the sync. A login made by this version stores what it was
     * granted; a session kept from an older version (2.0 asked for read scopes only), or a pasted or
     * paired token, did not, so ask the server with `GET /api/devices`, which needs `devices.read`,
     * a scope that comes with the write ones.
     */
    suspend fun missingScopes(): List<String> = missingSyncScopes(authStore.grantedScopes, authStore.state.value.mode) {
        (apiCall { api.devices() }.exceptionOrNull() as? ApiException)?.kind == ApiErrorKind.FORBIDDEN
    }

    suspend fun runAll(auto: Boolean): RunResult = run(null, auto)

    suspend fun syncGame(romId: Int): RunResult = run(romId, auto = false)

    private suspend fun run(onlyRom: Int?, auto: Boolean): RunResult = runLock.withLock {
        _running.value = true
        try {
            withContext(Dispatchers.IO) { runLocked(onlyRom, auto) }
        } finally {
            _running.value = false
        }
    }

    private class Counters {
        var up = 0; var down = 0; var pending = 0; var conflicts = 0; var errors = 0
        var firstError: String? = null
        fun error(msg: String) { errors++; if (firstError == null) firstError = msg }
        fun result() = RunResult(up, down, pending, conflicts, errors)
    }

    private suspend fun runLocked(onlyRom: Int?, auto: Boolean): RunResult {
        val p = prefs.snapshot()
        if (!p.enabled) return RunResult(skipped = "disabled")
        if (!authStore.state.value.isLoggedIn) return RunResult(skipped = "logged out")
        val kinds = SaveKind.entries.filter { if (auto) p.kindAuto(it) else p.kindEnabled(it) }
        if (kinds.isEmpty()) return RunResult(skipped = "nothing to sync")
        val setup = RetroArchConfig.detect(context, p.manualSavesDir, p.manualStatesDir)
        val c = Counters()
        log.i(TAG, "run start rom=$onlyRom auto=$auto kinds=$kinds saves=${setup.saves.dir} (${setup.saves.source}) states=${setup.states.dir} cfg=${setup.configFile}")

        // Whatever is already waiting goes first, if RetroArch has been closed since.
        applyPendingLocked(force = false)

        val scanned = kinds.associateWith { scan(setup.dirs(it), it) }
        val games = runCatching { buildGames(scanned.values.flatten(), onlyRom) }.getOrElse { e ->
            c.error(e.message ?: e.javaClass.simpleName); emptyList()
        }
        for (kind in kinds) {
            try {
                if (kind == SaveKind.SAVE) syncSaves(games, scanned[kind].orEmpty(), setup, c)
                else for (g in games) {
                    try { syncStates(g, scanned[kind].orEmpty(), setup, c) } catch (e: CancellationException) { throw e } catch (e: Throwable) {
                        c.error(describe(e)); setStatus(g.romId, SaveKind.STATE, GameSyncStatus(ItemStatus.ERROR, now(), detail = describe(e)))
                        log.w(TAG, "states ${g.name}: ${e.message}", e)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                c.error(describe(e))
                log.w(TAG, "$kind sync failed: ${e.message}", e)
            }
        }
        val r = c.result()
        val summary = context.getString(R.string.sync_summary, r.uploaded, r.downloaded, r.pending, r.conflicts)
        store.update { it.copy(lastRunAt = now(), lastRunSummary = summary, lastRunError = c.firstError) }
        log.i(TAG, "run end $r ${c.firstError ?: ""}")
        notifyState()
        return r
    }

    /* ------------------------------------------------------------------ discovery */

    private val saveExtensions = setOf("srm", "sav", "eep", "sra", "fla", "mpk", "mcr", "mcd", "nv", "rtc", "dsv", "bkr", "brm")
    private val stateSuffix = Regex("\\.state\\d*(\\.auto)?$", RegexOption.IGNORE_CASE)

    private fun accept(kind: SaveKind, name: String): Boolean = when (kind) {
        SaveKind.SAVE -> name.substringAfterLast('.', "").lowercase() in saveExtensions
        SaveKind.STATE -> stateSuffix.containsMatchIn(name)
    }

    private fun stemOf(kind: SaveKind, name: String): String = when (kind) {
        SaveKind.SAVE -> name.substringBeforeLast('.')
        SaveKind.STATE -> name.replace(stateSuffix, "")
    }

    /** The folder and, two levels down, its core and content folders. */
    private fun scan(kd: KindDirs, kind: SaveKind): List<LocalSaveFile> {
        val base = kd.dir
        if (!base.isDirectory) return emptyList()
        val out = ArrayList<LocalSaveFile>()
        fun take(dir: File, core: String?) {
            dir.listFiles()?.forEach { f -> if (f.isFile && accept(kind, f.name)) out += LocalSaveFile(f, kind, stemOf(kind, f.name), core) }
        }
        take(base, null)
        base.listFiles()?.filter { it.isDirectory }?.forEach { d1 ->
            val core = if (kd.sortByCore) d1.name else null
            take(d1, core)
            d1.listFiles()?.filter { it.isDirectory }?.forEach { d2 -> take(d2, core) }
        }
        return out
    }

    /** Saves next to the ROM, when RetroArch is set to keep them there. */
    private fun scanContent(g: GameRef, kind: SaveKind): List<LocalSaveFile> = g.contentDirs.flatMap { d ->
        d.listFiles()?.filter { it.isFile && accept(kind, it.name) }?.map { LocalSaveFile(it, kind, stemOf(kind, it.name), null) }.orEmpty()
    }

    private fun localsFor(g: GameRef, kind: SaveKind, scanned: List<LocalSaveFile>, setup: RetroArchSetup): List<LocalSaveFile> {
        val pool = if (setup.dirs(kind).inContentDir) scanContent(g, kind) else scanned.filter { it.kind == kind }
        val exact = pool.filter { it.stem in g.stems }
        if (exact.isNotEmpty()) return exact
        val lower = g.stems.map { it.lowercase() }.toSet()
        return pool.filter { it.stem.lowercase() in lower }
    }

    private class GameBuilder(val romId: Int, var name: String, val slug: String) {
        val stems = LinkedHashSet<String>()
        val relDirs = LinkedHashSet<String>()
    }

    /**
     * The games on this device and their RomM ids: the ones this app downloaded, then the files of
     * the local library, matched on the ROM cache and, for files that have a save, a server search.
     */
    private suspend fun buildGames(localSaves: List<LocalSaveFile>, onlyRom: Int?): List<GameRef> {
        val byId = LinkedHashMap<Int, GameBuilder>()
        fun add(id: Int, name: String, slug: String, stem: String, relDir: String?) {
            val b = byId.getOrPut(id) { GameBuilder(id, name, slug) }
            b.stems += stem
            relDir?.let { b.relDirs += it }
        }
        val saveStems = localSaves.map { it.stem.lowercase() }.toSet()
        for (d in downloads.completedRoms()) {
            add(d.romId, d.title, d.platformSlug, d.fileName.substringBeforeLast('.'), d.targetRelDir)
        }
        val known = store.state.value.romIds
        val found = HashMap<String, Int>()
        for (f in localFiles.all()) {
            val stem = if (f.isDirectory) f.fileName else f.fileName.substringBeforeLast('.')
            val relDir = f.relPath.substringBeforeLast('/', "")
            val rom = romDao.byFile(f.platformSlug, stem, f.fileName)
            val key = "${f.platformSlug}/$stem"
            val id = rom?.id ?: known[key] ?: found[key] ?: if (stem.lowercase() in saveStems) searchRomId(f.platformSlug, stem).also { found[key] = it ?: -1 } else null
            if (id != null && id > 0) add(id, rom?.name ?: stem, f.platformSlug, stem, relDir)
        }
        if (found.isNotEmpty()) store.update { it.copy(romIds = it.romIds + found) }
        if (onlyRom != null && onlyRom !in byId) {
            romDao.byId(onlyRom)?.let { r -> add(r.id, r.name, r.platformSlug, r.fsNameNoExt, null) }
        }
        val root = (settings.snapshot().storageRoot as? StorageRoot.Direct)?.let { File(it.path) }
        return byId.values.filter { onlyRom == null || it.romId == onlyRom }.map { b ->
            // The cached record's ROM name too: RetroArch files saves by the file it loaded.
            romDao.byId(b.romId)?.let { r -> b.stems += r.fsNameNoExt; if (b.name.isBlank()) b.name = r.name }
            GameRef(
                romId = b.romId,
                name = b.name,
                platformSlug = b.slug,
                stems = b.stems.filter { it.isNotBlank() },
                contentDirs = if (root == null) emptyList() else b.relDirs.map { if (it.isEmpty()) root else File(root, it) },
                system = b.relDirs.firstOrNull { it.isNotEmpty() }?.substringBefore('/'),
            )
        }
    }

    private suspend fun searchRomId(slug: String, stem: String): Int? = runCatching {
        val pid = platformDao.bySlug(slug)?.id ?: return null
        val page = api.roms(
            platformIds = listOf(pid), searchTerm = stem.take(60), limit = 10, offset = 0,
            groupByMetaId = false, withCharIndex = false, withTotal = false,
        )
        page.items.firstOrNull { it.fsNameNoExt == stem || it.fsName?.substringBeforeLast('.') == stem }?.id
    }.getOrNull()

    /* ------------------------------------------------------------------ saves */

    private fun slotFor(f: LocalSaveFile): String {
        val ext = f.file.name.substringAfterLast('.', "").lowercase()
        // One slot per kind of file: the RTC or EEPROM beside a cartridge save must not pair with it.
        return if (ext == "srm" || ext == "sav") AUTOSAVE_SLOT else "$AUTOSAVE_SLOT-$ext"
    }

    private suspend fun ensureDevice(): String {
        authStore.deviceId?.let { return it }
        val created = api.createDevice(
            DeviceCreateBody(
                name = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Android" },
                platform = "android",
                client = "RomMMobile",
                clientVersion = BuildConfig.VERSION_NAME,
                hostname = Build.MODEL,
                allowExisting = true,
            )
        )
        authStore.setDeviceId(created.deviceId)
        return created.deviceId
    }

    private suspend fun syncSaves(games: List<GameRef>, scanned: List<LocalSaveFile>, setup: RetroArchSetup, c: Counters) {
        if (games.isEmpty()) return
        val deviceId = ensureDevice()
        data class Entry(val game: GameRef, val slot: String, val local: LocalSaveFile, val hash: String)
        val entries = games.flatMap { g ->
            localsFor(g, SaveKind.SAVE, scanned, setup).groupBy { slotFor(it) }.map { (slot, files) ->
                val newest = files.maxBy { it.file.lastModified() }
                Entry(g, slot, newest, md5(newest.file))
            }
        }
        val gamesById = games.associateBy { it.romId }
        val statusById = HashMap<Int, ItemStatus>()
        // Server saves the negotiation named, per game: the "on RomM" count of the Saves tab.
        val serverSaves = HashMap<Int, MutableSet<Int>>()
        fun mark(id: Int, s: ItemStatus) { statusById[id] = worst(statusById[id], s) }

        for (chunk in games.map { it.romId }.chunked(200)) {
            val ids = chunk.toSet()
            val mine = entries.filter { it.game.romId in ids }
            val neg = api.negotiate(
                NegotiateBody(
                    deviceId = deviceId,
                    saves = mine.map { e ->
                        ClientSaveDto(
                            romId = e.game.romId, fileName = e.local.file.name, slot = e.slot,
                            emulator = CoreNames.emulatorFor(e.local.coreDir), contentHash = e.hash,
                            updatedAt = Instant.ofEpochMilli(e.local.file.lastModified()).toString(),
                            fileSizeBytes = e.local.file.length(),
                        )
                    },
                    romIds = chunk,
                )
            )
            var done = 0; var failed = 0
            for (op in neg.operations) {
                val g = gamesById[op.romId] ?: continue
                op.saveId?.let { serverSaves.getOrPut(op.romId) { HashSet() } += it }
                val entry = mine.firstOrNull { it.game.romId == op.romId && it.slot == (op.slot ?: AUTOSAVE_SLOT) }
                try {
                    when (op.action) {
                        "upload" -> {
                            val e = entry ?: continue
                            try {
                                uploadSave(e.game.romId, e.local, e.slot, deviceId, e.hash, neg.sessionId, overwrite = false)
                                c.up++; mark(g.romId, ItemStatus.UPLOADED)
                            } catch (x: ApiException) {
                                if (x.httpCode != 409) throw x
                                // The slot moved on the server since this device last synced.
                                addConflict(g, SaveKind.SAVE, e.local, op.saveId ?: latestSaveId(g.romId, e.slot) ?: throw x, op.fileName, op.serverUpdatedAt, CoreNames.emulatorFor(e.local.coreDir), e.slot)
                                c.conflicts++; mark(g.romId, ItemStatus.CONFLICT)
                            }
                        }
                        "download" -> {
                            val saveId = op.saveId ?: continue
                            val target = entry?.local?.file ?: targetFor(SaveKind.SAVE, g, op.fileName, op.emulator, setup)
                            if (target == null) {
                                c.error(context.getString(R.string.sync_err_no_target, g.name)); mark(g.romId, ItemStatus.ERROR); failed++; continue
                            }
                            val temp = fetch { api.saveContent(saveId, deviceId, neg.sessionId, optimistic = false) }
                            val applied = queueRestore(SaveKind.SAVE, g, target, temp, saveId, op.serverUpdatedAt)
                            if (applied) { c.down++; mark(g.romId, ItemStatus.DOWNLOADED) } else { c.pending++; mark(g.romId, ItemStatus.PENDING) }
                        }
                        "conflict" -> {
                            val e = entry
                            val saveId = op.saveId
                            if (e == null || saveId == null) { mark(g.romId, ItemStatus.CONFLICT); continue }
                            addConflict(g, SaveKind.SAVE, e.local, saveId, op.fileName, op.serverUpdatedAt, op.emulator, e.slot)
                            c.conflicts++; mark(g.romId, ItemStatus.CONFLICT)
                        }
                        // "delete": the server dropped a version this device still has. Never
                        // delete a save here: it stays, and the next change uploads it again.
                        else -> mark(g.romId, ItemStatus.SYNCED)
                    }
                    done++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failed++
                    c.error("${g.name}: ${describe(e)}")
                    mark(g.romId, ItemStatus.ERROR)
                    log.w(TAG, "save op ${op.action} ${g.name}: ${e.message}", e)
                }
            }
            runCatching { api.completeSync(neg.sessionId, SyncCompleteBody(done, failed)) }
        }
        val counts = entries.groupingBy { it.game.romId }.eachCount()
        for (g in games) {
            val s = statusById[g.romId] ?: if ((counts[g.romId] ?: 0) > 0) ItemStatus.SYNCED else ItemStatus.NONE
            // A conflict or a waiting restore stays visible until it is dealt with.
            val open = openItem(g.romId, SaveKind.SAVE)
            setStatus(g.romId, SaveKind.SAVE, GameSyncStatus(worst(s, open), now(), localFiles = counts[g.romId] ?: 0, serverFiles = serverSaves[g.romId]?.size ?: 0))
        }
    }

    private suspend fun latestSaveId(romId: Int, slot: String): Int? =
        runCatching { api.saves(romId = romId).filter { it.slot == slot }.maxByOrNull { it.updatedAt.orEmpty() }?.id }.getOrNull()

    private suspend fun uploadSave(romId: Int, local: LocalSaveFile, slot: String, deviceId: String, hash: String, sessionId: Int?, overwrite: Boolean) {
        val part = MultipartBody.Part.createFormData("saveFile", local.file.name, local.file.asRequestBody(OCTET))
        api.uploadSave(
            romId = romId, emulator = CoreNames.emulatorFor(local.coreDir), slot = slot, deviceId = deviceId,
            contentHash = hash, sessionId = sessionId, overwrite = overwrite, autocleanup = true, autocleanupLimit = KEEP_VERSIONS,
            saveFile = part,
        )
    }

    /* ------------------------------------------------------------------ states */

    private fun stateKey(romId: Int, name: String) = "$romId/STATE/$name"

    private suspend fun syncStates(g: GameRef, scanned: List<LocalSaveFile>, setup: RetroArchSetup, c: Counters) {
        val locals = localsFor(g, SaveKind.STATE, scanned, setup).associateBy { it.file.name }
        val server = api.states(g.romId).filter { !it.missingFromFs }
        // Matched by the state's own file name (`file_name_no_tags` drops the extension, so it is
        // not used): the server keeps one row per name and RomMMobile
        // uploads under the device's name, so `Game.state1` is `Game.state1` on both sides.
        val serverByName = server.groupBy { stripTag(it.fileName) }.mapValues { (_, v) -> v.maxBy { it.updatedAt.orEmpty() } }
        var status: ItemStatus? = null
        fun mark(s: ItemStatus) { status = worst(status, s) }

        for (name in (locals.keys + serverByName.keys)) {
            val l = locals[name]
            val s = serverByName[name]
            val key = stateKey(g.romId, name)
            val b = store.state.value.baselines[key]
            when {
                l != null && s != null -> {
                    val lh = md5(l.file)
                    if (b != null) {
                        val localChanged = lh != b.localHash
                        // RomM writes `updated_at` with microseconds in upload replies and without
                        // them in lists: compared to the second, not as strings.
                        val serverChanged = s.id != b.serverId || !sameSecond(s.updatedAt, b.serverUpdatedAt)
                        log.i(TAG, "state ${g.romId}/$name local=$lh base=${b.localHash} server=${s.id}@${s.updatedAt} base=${b.serverId}@${b.serverUpdatedAt} -> local=$localChanged server=$serverChanged")
                        when {
                            localChanged && serverChanged -> { addConflict(g, SaveKind.STATE, l, s.id, s.fileName, s.updatedAt, s.emulator, null); c.conflicts++; mark(ItemStatus.CONFLICT) }
                            localChanged -> { val up = replaceState(s.id, l); setBaseline(key, lh, up); c.up++; mark(ItemStatus.UPLOADED) }
                            serverChanged -> { if (downloadState(g, s, l.file)) { c.down++; mark(ItemStatus.DOWNLOADED) } else { c.pending++; mark(ItemStatus.PENDING) } }
                            else -> mark(ItemStatus.SYNCED)
                        }
                    } else {
                        // First meeting: compare the bytes before deciding anything.
                        val temp = fetch { api.stateContent(s.id) }
                        if (md5(temp) == lh) { temp.delete(); setBaseline(key, lh, s); mark(ItemStatus.SYNCED) }
                        else { temp.delete(); addConflict(g, SaveKind.STATE, l, s.id, s.fileName, s.updatedAt, s.emulator, null); c.conflicts++; mark(ItemStatus.CONFLICT) }
                    }
                }
                l != null -> {
                    if (b?.serverId != null) {
                        // It was on the server and is gone: deleted there. Kept here, not sent back.
                        mark(ItemStatus.SYNCED)
                    } else {
                        val part = MultipartBody.Part.createFormData("stateFile", l.file.name, l.file.asRequestBody(OCTET))
                        val up = api.uploadState(g.romId, CoreNames.emulatorFor(l.coreDir), part)
                        setBaseline(key, md5(l.file), up); c.up++; mark(ItemStatus.UPLOADED)
                    }
                }
                s != null -> {
                    if (b != null) {
                        // Synced once, then deleted on the device: left deleted.
                        mark(ItemStatus.SYNCED)
                    } else {
                        val target = targetFor(SaveKind.STATE, g, s.fileName, s.emulator, setup)
                        if (target == null) { c.error(context.getString(R.string.sync_err_no_target, g.name)); mark(ItemStatus.ERROR) }
                        else if (downloadState(g, s, target)) { c.down++; mark(ItemStatus.DOWNLOADED) } else { c.pending++; mark(ItemStatus.PENDING) }
                    }
                }
            }
        }
        val st = worst(status ?: if (locals.isEmpty() && serverByName.isEmpty()) ItemStatus.NONE else ItemStatus.SYNCED, openItem(g.romId, SaveKind.STATE))
        setStatus(g.romId, SaveKind.STATE, GameSyncStatus(st, now(), localFiles = locals.size, serverFiles = serverByName.size))
    }

    private suspend fun replaceState(id: Int, l: LocalSaveFile): StateDto {
        val part = MultipartBody.Part.createFormData("stateFile", l.file.name, l.file.asRequestBody(OCTET))
        return api.replaceState(id, part)
    }

    private suspend fun downloadState(g: GameRef, s: StateDto, target: File): Boolean {
        val temp = fetch { api.stateContent(s.id) }
        return queueRestore(SaveKind.STATE, g, target, temp, s.id, s.updatedAt)
    }

    private suspend fun setBaseline(key: String, localHash: String, s: StateDto) =
        store.update { it.copy(baselines = it.baselines + (key to Baseline(localHash, s.id, s.updatedAt))) }

    /* ------------------------------------------------------------------ targets, restores, conflicts */

    /** Where a file that exists only on the server goes, following RetroArch's own layout. */
    private fun targetFor(kind: SaveKind, g: GameRef, serverName: String, emulator: String?, setup: RetroArchSetup): File? {
        val kd = setup.dirs(kind)
        val clean = stripTag(serverName)
        val stem = g.stems.firstOrNull() ?: return null
        val name = when (kind) {
            SaveKind.SAVE -> "$stem.${clean.substringAfterLast('.', "srm")}"
            SaveKind.STATE -> stem + (stateSuffix.find(clean)?.value ?: ".state")
        }
        if (kd.inContentDir) return g.contentDirs.firstOrNull()?.let { File(it, name) }
        var dir = kd.dir
        if (kd.sortByCore) {
            val core = CoreNames.dirFor(emulator) ?: g.system?.let { setup.esde?.coreDirFor(it) } ?: return null
            dir = File(dir, core)
        }
        if (kd.sortByContent) g.contentDirs.firstOrNull()?.name?.let { dir = File(dir, it) }
        return File(dir, name)
    }

    private suspend fun fetch(call: suspend () -> ResponseBody): File {
        incomingDir.mkdirs()
        val f = File(incomingDir, UUID.randomUUID().toString())
        call().use { body -> body.byteStream().use { input -> f.outputStream().use { input.copyTo(it) } } }
        return f
    }

    /** Writes now when RetroArch is closed, else waits. True when written. */
    private suspend fun queueRestore(
        kind: SaveKind, g: GameRef, target: File, temp: File, serverId: Int, serverUpdatedAt: String?,
        promoteSlot: String? = null, emulator: String? = null,
    ): Boolean {
        val key = "${g.romId}/${kind.name}/${target.absolutePath}"
        if (target.isFile && md5(target) == md5(temp)) {
            // Same bytes already on the device: nothing to write, only the bookkeeping.
            temp.delete()
            when (kind) {
                SaveKind.SAVE -> if (promoteSlot != null) promote(g.romId, target, promoteSlot, emulator)
                    else authStore.deviceId?.let { d -> runCatching { api.confirmSaveDownloaded(serverId, ConfirmDownloadBody(d, md5(target))) } }
                SaveKind.STATE -> store.update { s -> s.copy(baselines = s.baselines + (stateKey(g.romId, target.name) to Baseline(md5(target), serverId, serverUpdatedAt))) }
            }
            return true
        }
        val p = PendingRestore(
            key = key, kind = kind, romId = g.romId, game = g.name, target = target.absolutePath, temp = temp.absolutePath,
            serverId = serverId, serverUpdatedAt = serverUpdatedAt, targetMtime = if (target.exists()) target.lastModified() else 0L, createdAt = now(),
            promoteSlot = promoteSlot, emulator = emulator,
        )
        // A newer download for the same file replaces the older one waiting.
        val old = store.state.value.pending.filter { it.key == key }
        old.forEach { File(it.temp).delete() }
        store.update { s -> s.copy(pending = s.pending.filterNot { it.key == key } + p) }
        if (watcher.state() != RetroArchState.CLOSED) return false
        return apply(p) == ApplyOutcome.WRITTEN
    }

    private enum class ApplyOutcome { WRITTEN, CONFLICT, FAILED }

    private suspend fun apply(p: PendingRestore): ApplyOutcome {
        val target = File(p.target)
        val temp = File(p.temp)
        if (!temp.isFile) { store.update { s -> s.copy(pending = s.pending.filterNot { it.key == p.key }) }; return ApplyOutcome.FAILED }
        val nowMtime = if (target.exists()) target.lastModified() else 0L
        if (nowMtime != p.targetMtime) {
            // RetroArch (or the user) wrote this file after the download was decided: both sides
            // changed, so it becomes a conflict instead of being overwritten.
            store.update { s -> s.copy(pending = s.pending.filterNot { it.key == p.key }) }
            temp.delete()
            val g = GameRef(p.romId, p.game, "", listOf(target.name.substringBeforeLast('.')), emptyList(), null)
            addConflict(g, p.kind, LocalSaveFile(target, p.kind, "", target.parentFile?.name), p.serverId, target.name, p.serverUpdatedAt, null, if (p.kind == SaveKind.SAVE) AUTOSAVE_SLOT else null)
            return ApplyOutcome.CONFLICT
        }
        return try {
            if (target.exists()) backup(target)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".rommtmp")
            temp.inputStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
            if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) throw java.io.IOException("rename failed: $target") }
            temp.delete()
            val hash = md5(target)
            when (p.kind) {
                SaveKind.SAVE -> if (p.promoteSlot != null) promote(p.romId, target, p.promoteSlot, p.emulator)
                    else authStore.deviceId?.let { d -> runCatching { api.confirmSaveDownloaded(p.serverId, ConfirmDownloadBody(d, hash)) } }
                SaveKind.STATE -> store.update { s -> s.copy(baselines = s.baselines + (stateKey(p.romId, target.name) to Baseline(hash, p.serverId, p.serverUpdatedAt))) }
            }
            store.update { s -> s.copy(pending = s.pending.filterNot { it.key == p.key }) }
            setStatus(p.romId, p.kind, (store.state.value.game(p.romId, p.kind) ?: GameSyncStatus(ItemStatus.DOWNLOADED, now())).copy(status = worst(ItemStatus.DOWNLOADED, openItem(p.romId, p.kind)), at = now()))
            log.i(TAG, "restored ${p.kind} ${p.game} -> $target")
            ApplyOutcome.WRITTEN
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            log.w(TAG, "restore failed ${p.target}: ${e.message}", e)
            ApplyOutcome.FAILED
        }
    }

    /**
     * An older version the user picked is now on the device: it goes back up as the slot's newest
     * version. Otherwise the next negotiation would see the server ahead and bring the newer one back.
     */
    private suspend fun promote(romId: Int, file: File, slot: String, emulator: String?) {
        runCatching {
            uploadSave(romId, LocalSaveFile(file, SaveKind.SAVE, "", CoreNames.dirFor(emulator)), slot, ensureDevice(), md5(file), null, overwrite = true)
        }.onFailure { log.w(TAG, "promote $file: ${it.message}", it) }
    }

    /** What RomM holds for one game, and which of it is already on this device. */
    data class ServerData(
        val saves: List<com.rommmobile.app.data.api.SaveDto>,
        val states: List<StateDto>,
        /** MD5 of every save of this game on the device. */
        val localSaveHashes: Set<String>,
        /** State file name -> MD5, for this game's states on the device. */
        val localStates: Map<String, String>,
        /** Server states whose bytes are the ones on the device (last sync unchanged on both sides). */
        val syncedStateIds: Set<Int>,
    )

    suspend fun serverData(romId: Int): Result<ServerData> = withContext(Dispatchers.IO) {
        com.rommmobile.app.core.network.apiCall {
            val saves = api.saves(romId = romId).filter { !it.missingFromFs }
            val states = api.states(romId).filter { !it.missingFromFs }
            val setup = setup()
            val scanned = SaveKind.entries.flatMap { scan(setup.dirs(it), it) }
            val g = buildGames(scanned, romId).firstOrNull()
            val localStates = g?.let { localsFor(it, SaveKind.STATE, scanned, setup) }.orEmpty().associate { it.file.name to md5(it.file) }
            val baselines = store.state.value.baselines
            ServerData(
                saves = saves,
                states = states,
                localSaveHashes = g?.let { localsFor(it, SaveKind.SAVE, scanned, setup) }.orEmpty().map { md5(it.file) }.toSet(),
                localStates = localStates,
                syncedStateIds = states.filter { st ->
                    val name = stripTag(st.fileName)
                    val b = baselines[stateKey(romId, name)]
                    b != null && b.serverId == st.id && b.localHash == localStates[name] && sameSecond(b.serverUpdatedAt, st.updatedAt)
                }.map { it.id }.toSet(),
            )
        }
    }

    /**
     * Puts one version from RomM on the device, the user's pick in the Save data tab: same rules as
     * any restore (backup first, only while RetroArch is closed). True when written, false when waiting.
     */
    suspend fun restoreVersion(romId: Int, kind: SaveKind, serverId: Int): Result<Boolean> = runLock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val setup = setup()
                val scanned = scan(setup.dirs(kind), kind)
                val g = buildGames(scanned, romId).firstOrNull() ?: error(context.getString(R.string.sync_err_no_target, "#$romId"))
                val locals = localsFor(g, kind, scanned, setup)
                val noTarget = { error(context.getString(R.string.sync_err_no_target, g.name)) }
                val written = when (kind) {
                    SaveKind.SAVE -> {
                        val all = api.saves(romId = romId)
                        val dto = all.first { it.id == serverId }
                        val slot = dto.slot ?: AUTOSAVE_SLOT
                        // Only an older version has to go back up as the newest; the newest one is
                        // just downloaded like any other (no duplicate version on the server).
                        val head = all.filter { it.slot == dto.slot }.maxByOrNull { parseInstant(it.updatedAt)?.toEpochMilli() ?: 0L }
                        val promoteSlot = if (head?.id == dto.id) null else slot
                        val target = locals.filter { slotFor(it) == slot }.maxByOrNull { it.file.lastModified() }?.file
                            ?: targetFor(SaveKind.SAVE, g, dto.fileName, dto.emulator, setup) ?: noTarget()
                        val temp = fetch { api.saveContent(serverId, ensureDevice(), null, optimistic = false) }
                        queueRestore(SaveKind.SAVE, g, target, temp, serverId, dto.updatedAt, promoteSlot = promoteSlot, emulator = dto.emulator)
                    }
                    SaveKind.STATE -> {
                        val dto = api.states(romId).first { it.id == serverId }
                        val name = stripTag(dto.fileName)
                        val target = locals.firstOrNull { it.file.name == name }?.file
                            ?: targetFor(SaveKind.STATE, g, dto.fileName, dto.emulator, setup) ?: noTarget()
                        val temp = fetch { api.stateContent(serverId) }
                        queueRestore(SaveKind.STATE, g, target, temp, serverId, dto.updatedAt)
                    }
                }
                val prev = store.state.value.game(romId, kind)
                setStatus(romId, kind, (prev ?: GameSyncStatus(ItemStatus.NONE, now())).copy(status = worst(if (written) ItemStatus.DOWNLOADED else ItemStatus.PENDING, openItem(romId, kind)), at = now()))
                written
            }.also { r -> r.onFailure { log.w(TAG, "restore version $kind $serverId: ${it.message}", it) }; notifyState() }
        }
    }

    /**
     * Writes what is waiting. [force] is the user saying RetroArch is closed when the app cannot
     * tell (no usage access, or RetroArch left in the background).
     */
    suspend fun applyPending(force: Boolean): Int = runLock.withLock { withContext(Dispatchers.IO) { applyPendingLocked(force).also { notifyState() } } }

    private suspend fun applyPendingLocked(force: Boolean): Int {
        val waiting = store.state.value.pending
        if (waiting.isEmpty()) return 0
        if (!force && watcher.state() != RetroArchState.CLOSED) return 0
        var n = 0
        for (p in waiting) if (apply(p) == ApplyOutcome.WRITTEN) n++
        return n
    }

    private fun backup(f: File) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val dir = File(backupDir, stamp)
        dir.mkdirs()
        f.copyTo(File(dir, f.name), overwrite = true)
        // Keep the last few dozen; a backup is a safety net, not an archive (the server keeps versions).
        backupDir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_BACKUP_DIRS)?.forEach { it.deleteRecursively() }
    }

    private suspend fun addConflict(g: GameRef, kind: SaveKind, local: LocalSaveFile, serverId: Int, serverName: String, serverUpdatedAt: String?, emulator: String?, slot: String?) {
        val key = "${g.romId}/${kind.name}/${local.file.absolutePath}"
        val cf = Conflict(
            key = key, kind = kind, romId = g.romId, game = g.name, localPath = local.file.absolutePath,
            localSize = local.file.length(), localMtime = local.file.lastModified(), serverId = serverId,
            serverFileName = serverName, serverUpdatedAt = serverUpdatedAt, emulator = emulator ?: CoreNames.emulatorFor(local.coreDir), slot = slot, createdAt = now(),
        )
        store.update { s -> s.copy(conflicts = s.conflicts.filterNot { it.key == key } + cf) }
    }

    /** The user's answer to a conflict. Nothing was written on either side until now. */
    suspend fun resolve(conflictKey: String, choice: ConflictChoice): Result<Boolean> = runLock.withLock {
        withContext(Dispatchers.IO) {
            val cf = store.state.value.conflicts.firstOrNull { it.key == conflictKey } ?: return@withContext Result.success(false)
            val local = File(cf.localPath)
            val result = runCatching {
                val g = GameRef(cf.romId, cf.game, "", listOf(local.name.substringBeforeLast('.')), emptyList(), null)
                when (choice) {
                    ConflictChoice.KEEP_DEVICE -> {
                        val lf = LocalSaveFile(local, cf.kind, "", local.parentFile?.name)
                        when (cf.kind) {
                            SaveKind.SAVE -> uploadSave(cf.romId, lf, cf.slot ?: AUTOSAVE_SLOT, ensureDevice(), md5(local), null, overwrite = true)
                            SaveKind.STATE -> setBaseline(stateKey(cf.romId, local.name), md5(local), replaceState(cf.serverId, lf))
                        }
                        removeConflict(cf.key)
                        setStatus(cf.romId, cf.kind, GameSyncStatus(worst(ItemStatus.UPLOADED, openItem(cf.romId, cf.kind)), now()))
                        true
                    }
                    ConflictChoice.KEEP_SERVER -> {
                        val temp = when (cf.kind) {
                            SaveKind.SAVE -> fetch { api.saveContent(cf.serverId, ensureDevice(), null, optimistic = false) }
                            SaveKind.STATE -> fetch { api.stateContent(cf.serverId) }
                        }
                        removeConflict(cf.key)
                        val written = queueRestore(cf.kind, g, local, temp, cf.serverId, cf.serverUpdatedAt)
                        setStatus(cf.romId, cf.kind, GameSyncStatus(worst(if (written) ItemStatus.DOWNLOADED else ItemStatus.PENDING, openItem(cf.romId, cf.kind)), now()))
                        written
                    }
                }
            }
            notifyState()
            result.onFailure { log.w(TAG, "resolve ${cf.key}: ${it.message}", it) }
        }
    }

    private suspend fun removeConflict(key: String) = store.update { s -> s.copy(conflicts = s.conflicts.filterNot { it.key == key }) }

    private fun openItem(romId: Int, kind: SaveKind): ItemStatus? {
        val s = store.state.value
        return when {
            s.conflicts.any { it.romId == romId && it.kind == kind } -> ItemStatus.CONFLICT
            s.pending.any { it.romId == romId && it.kind == kind } -> ItemStatus.PENDING
            else -> null
        }
    }

    private suspend fun setStatus(romId: Int, kind: SaveKind, st: GameSyncStatus) =
        store.update { it.copy(games = it.games + (SyncState.gameKey(romId, kind) to st)) }

    /* ------------------------------------------------------------------ notification */

    private fun notifyState() {
        val s = store.state.value
        val nm = NotificationManagerCompat.from(context)
        val pending = s.pending.size
        val conflicts = s.conflicts.size
        if (pending == 0 && conflicts == 0) { nm.cancel(NOTIF_ID); return }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ensureChannel()
        val text = buildList {
            if (pending > 0) add(context.resources.getQuantityString(R.plurals.sync_notif_pending, pending, pending))
            if (conflicts > 0) add(context.resources.getQuantityString(R.plurals.sync_notif_conflicts, conflicts, conflicts))
        }.joinToString(" · ")
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.sync_notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + "\n" + context.getString(if (pending > 0) R.string.sync_notif_pending_hint else R.string.sync_notif_conflict_hint)))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (_: SecurityException) { }
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.sync_notif_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    /* ------------------------------------------------------------------ helpers */

    private fun describe(e: Throwable): String = when {
        e is ApiException && e.kind == ApiErrorKind.FORBIDDEN -> context.getString(R.string.sync_err_forbidden)
        e is ApiException -> e.userMessage(context)
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private const val TAG = "SaveSync"
        const val AUTOSAVE_SLOT = "autosave"
        private const val KEEP_VERSIONS = 10
        private const val MAX_BACKUP_DIRS = 40
        private const val CHANNEL = "save_sync"
        private const val NOTIF_ID = 4710
        private val OCTET = "application/octet-stream".toMediaType()
        private val TAG_PATTERN = Regex(" \\[\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}]")

        /** RomM's version tag (`Game [2026-11-27_10-00-00].srm`) removed. */
        fun stripTag(name: String): String = TAG_PATTERN.replace(name, "")

        fun md5(f: File): String {
            val md = MessageDigest.getInstance("MD5")
            f.inputStream().use { i ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = i.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** Same instant to the second; unparsable values compare as strings. */
        fun sameSecond(a: String?, b: String?): Boolean {
            val x = parseInstant(a); val y = parseInstant(b)
            return if (x != null && y != null) x.epochSecond == y.epochSecond else a == b
        }

        /** Server timestamps, with or without an offset (then UTC). */
        fun parseInstant(s: String?): Instant? = s?.let {
            runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(it).toInstant(ZoneOffset.UTC) }.getOrNull()
        }

        private val ORDER = listOf(ItemStatus.CONFLICT, ItemStatus.PENDING, ItemStatus.ERROR, ItemStatus.DOWNLOADED, ItemStatus.UPLOADED, ItemStatus.SYNCED, ItemStatus.NONE)

        /** The status that needs the user most. */
        fun worst(a: ItemStatus?, b: ItemStatus?): ItemStatus = listOfNotNull(a, b).minByOrNull { ORDER.indexOf(it) } ?: ItemStatus.NONE

        private fun now() = System.currentTimeMillis()
    }
}

/**
 * [SaveSyncEngine.missingScopes] without the I/O: [granted] is what the login stored (null when it
 * did not), [probeForbidden] asks the server. Basic auth carries the user's whole role, so it lacks
 * nothing; a refused probe means every sync scope is missing, as for a 2.0 session.
 */
internal suspend fun missingSyncScopes(granted: Set<String>?, mode: AuthMode, probeForbidden: suspend () -> Boolean): List<String> = when {
    granted != null -> SYNC_SCOPES.filterNot { it in granted }
    mode == AuthMode.NONE || mode == AuthMode.BASIC -> emptyList()
    probeForbidden() -> SYNC_SCOPES
    else -> emptyList()
}
