package com.rommmobile.app.feature.esde

import android.content.Context
import android.net.Uri
import com.rommmobile.app.core.storage.DirectFileGateway
import com.rommmobile.app.core.storage.FileGateway
import com.rommmobile.app.core.storage.FileGatewayFactory
import com.rommmobile.app.core.storage.StorageLocations
import com.rommmobile.app.core.storage.StorageRoot
import com.rommmobile.app.core.util.FileLogger
import com.rommmobile.app.data.api.RommApi
import com.rommmobile.app.data.db.DownloadEntity
import com.rommmobile.app.data.db.DownloadKind
import com.rommmobile.app.data.mapping.MappingRepository
import com.rommmobile.app.data.prefs.Launcher
import com.rommmobile.app.data.prefs.SettingsStore
import com.rommmobile.app.data.repo.LocalLibraryRepository
import com.rommmobile.app.data.repo.PlatformRepository
import com.rommmobile.app.data.repo.Rom
import com.rommmobile.app.data.repo.RomRepository
import com.rommmobile.app.di.ApplicationScope
import com.rommmobile.app.di.ImageClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Where ES-DE keeps its data and its media on this device, or why it cannot be used. */
sealed interface EsdeStatus {
    data class Ready(val target: EsdeTarget) : EsdeStatus
    /** No `ES-DE/settings/es_settings.xml` on any volume, and no folder set by hand. */
    data object NotFound : EsdeStatus
    /** Without All files access the app cannot look outside the ROM folder. */
    data object NoAccess : EsdeStatus
}

/**
 * [data] is rooted at ES-DE's data folder (`gamelists/`, `settings/`). [media] is where the
 * `downloaded_media` tree lives, [mediaBase] its path inside that gateway; null when ES-DE's
 * `MediaDirectory` points somewhere this app cannot write ([mediaProblem] says what).
 */
data class EsdeTarget(
    val data: FileGateway,
    val media: FileGateway?,
    val mediaBase: String,
    val mediaProblem: String? = null,
    /** ES-DE keeps gamelists in the ROM folders instead (`LegacyGamelistFileLocation`): not handled. */
    val legacyGamelists: Boolean = false,
) {
    val dataPath: String get() = data.displayPath("")
    val mediaPath: String? get() = media?.displayPath(mediaBase)
}

/** One run of "export for the games already there". */
data class CatchUpProgress(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val last: CatchUpResult? = null)

data class CatchUpResult(val games: Int, val media: Int, val added: Int, val completed: Int, val errors: Int, val unmatched: Int)

private data class GameResult(val media: Int, val entries: List<GamelistEntry>, val system: String)

/**
 * Writes the media and the `gamelist.xml` entry ES-DE would otherwise have to scrape, from what
 * RomM already knows about the game. Only while the launcher is ES-DE and the export is on.
 *
 * Never overwrites: a media file that exists under any extension ES-DE reads is left alone, and a
 * gamelist entry that exists only gets the fields it lacks ([GamelistXml]). A failure here never
 * fails the download, it is logged and the game stays installed.
 *
 * About a running ES-DE: it re-reads `gamelist.xml` before saving and only rewrites the games it
 * changed, so an entry for a game it does not know yet (a fresh download) survives. For a game it
 * already lists (catch-up while it is open) its own copy wins the next time it saves that game,
 * e.g. after playing it. Android does not let an app see whether ES-DE is running, so the settings
 * ask to close it before a catch-up; ES-DE shows new entries after a restart.
 */
@Singleton
class EsdeExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: EsdePrefs,
    private val settings: SettingsStore,
    private val mapping: MappingRepository,
    private val roms: RomRepository,
    private val platforms: PlatformRepository,
    private val localLibrary: LocalLibraryRepository,
    private val api: RommApi,
    @ImageClient private val client: OkHttpClient,
    private val log: FileLogger,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val gamelistLock = Mutex()
    private val catchUpLock = Mutex()
    private val _progress = MutableStateFlow(CatchUpProgress())
    val progress: StateFlow<CatchUpProgress> = _progress.asStateFlow()

    /* ---------------- where ---------------- */

    suspend fun status(manualDir: String? = null): EsdeStatus = withContext(Dispatchers.IO) {
        val manual = manualDir ?: prefs.snapshot().manualDir
        val allFiles = StorageLocations.hasAllFilesAccess(context)
        val root: StorageRoot = when {
            !manual.isNullOrBlank() -> StorageRoot.parse(manual) ?: StorageRoot.Direct(manual.trimEnd('/'))
            !allFiles -> return@withContext EsdeStatus.NoAccess
            else -> StorageLocations.volumes(context).map { File(it.first, "ES-DE") }
                .firstOrNull { File(it, "settings/es_settings.xml").isFile }
                ?.let { StorageRoot.Direct(it.absolutePath) }
                ?: return@withContext EsdeStatus.NotFound
        }
        if (root is StorageRoot.Direct && !allFiles) return@withContext EsdeStatus.NoAccess
        val data = FileGatewayFactory.create(context, root)
        if (!data.exists("settings", "es_settings.xml") && !data.exists("", "gamelists")) return@withContext EsdeStatus.NotFound
        val esSettings = runCatching { data.openInput("settings", "es_settings.xml")?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull().orEmpty()
        val mediaSetting = EsdeSettingsFile.string(esSettings, "MediaDirectory").orEmpty()
        val legacy = EsdeSettingsFile.bool(esSettings, "LegacyGamelistFileLocation") == true
        val dataRootPath = when (root) {
            is StorageRoot.Direct -> root.path
            is StorageRoot.Saf -> StorageLocations.treeUriToPath(Uri.parse(root.treeUri))
        }?.trimEnd('/')
        val target = when {
            mediaSetting.isBlank() -> EsdeTarget(data, data, "downloaded_media", legacyGamelists = legacy)
            !mediaSetting.startsWith("/") -> EsdeTarget(data, null, "", mediaProblem = mediaSetting, legacyGamelists = legacy)
            else -> {
                val m = mediaSetting.trimEnd('/')
                when {
                    dataRootPath != null && m.startsWith("$dataRootPath/", ignoreCase = true) ->
                        EsdeTarget(data, data, m.substring(dataRootPath.length + 1), legacyGamelists = legacy)
                    allFiles -> EsdeTarget(data, DirectFileGateway(StorageRoot.Direct(m)), "", legacyGamelists = legacy)
                    else -> EsdeTarget(data, null, "", mediaProblem = mediaSetting, legacyGamelists = legacy)
                }
            }
        }
        EsdeStatus.Ready(target)
    }

    private suspend fun active(): Boolean = settings.launcher.first() == Launcher.ESDE && prefs.snapshot().anything

    /* ---------------- after a download ---------------- */

    /**
     * Called by the download task once the game's files are in place, [names] being what it put in
     * [relDir] (the file itself, or what came out of the archive). Returns at once; the export runs
     * on its own and its failures stay in the log.
     */
    fun onPlaced(entity: DownloadEntity, relDir: String, names: List<String>) {
        if (entity.kind != DownloadKind.ROM || relDir.isEmpty() || names.isEmpty()) return
        if (relDir.substringAfterLast('/').lowercase() in LocalLibraryRepository.EXTRA_DIRS) return
        scope.launch(Dispatchers.IO) {
            runCatching {
                if (!active()) return@launch
                val target = (status() as? EsdeStatus.Ready)?.target ?: run { log.i(TAG, "ES-DE folder not found, nothing exported"); return@launch }
                val gw = mapping.gateway() ?: return@launch
                val rom = roms.detail(entity.romId).getOrNull() ?: return@launch
                val placed = locateGame(gw, relDir, names, EsdeMeta.stem(entity.fileName)) ?: return@launch
                val opts = prefs.snapshot()
                val r = exportGame(target, rom, placed, opts)
                val gl = if (opts.metadata) writeGamelist(target, r.system, r.entries) else null
                log.i(TAG, "${placed.romRelPath}: ${r.media} media, gamelist +${gl?.added ?: 0}/~${gl?.completed ?: 0}")
            }.onFailure { log.w(TAG, "export failed for ${entity.fileName}", it) }
        }
    }

    /** Where a game sits under the ROM root: its main file, and its own folder if it has one. */
    private data class Placed(val romRelPath: String, val folderRelPath: String?)

    private fun locateGame(gw: FileGateway, relDir: String, names: List<String>, romStem: String?): Placed? {
        val listing = runCatching { gw.list(relDir) }.getOrDefault(emptyList()).filter { it.name in names }
        val dirs = listing.filter { it.isDirectory }
        val files = listing.filterNot { it.isDirectory }
        // An archive holding one folder: the game is that folder.
        if (files.isEmpty() && dirs.size == 1) {
            val inner = join(relDir, dirs.first().name)
            val content = runCatching { gw.list(inner) }.getOrDefault(emptyList()).filterNot { it.isDirectory }
            val main = EsdeMeta.primaryFile(content.associate { it.name to it.sizeBytes }, romStem) ?: return null
            return Placed(join(inner, main), inner)
        }
        val main = EsdeMeta.primaryFile(files.associate { it.name to it.sizeBytes }, romStem) ?: return null
        // A game laid out in a folder of its own (`psx/Final Fantasy VII/`): ES-DE lists that folder.
        return Placed(join(relDir, main), relDir.takeIf { it.contains('/') })
    }

    /* ---------------- one game ---------------- */

    private suspend fun exportGame(target: EsdeTarget, rom: Rom, placed: Placed, opts: EsdeExportSettings): GameResult {
        val system = placed.romRelPath.substringBefore('/').lowercase()
        val inner = placed.romRelPath.substringAfter('/')
        val entries = ArrayList<GamelistEntry>(2)
        var media = 0
        val sources = buildList {
            if (opts.cover) add(Media("covers", rom.coverCandidates(preferLarge = true), video = false))
            if (opts.screenshot) add(Media("screenshots", rom.screenshots.take(2), video = false))
            if (opts.marquee) add(Media("marquees", listOfNotNull(rom.logo), video = false))
            if (opts.video) add(Media("videos", rom.videos.take(1), video = true))
        }
        val mediaGw = target.media
        if (mediaGw != null) {
            for (m in sources) if (writeMedia(mediaGw, target.mediaBase, system, inner, m)) media++
            // The game's own folder shows in ES-DE's list too: same pictures, no clip.
            placed.folderRelPath?.substringAfter('/')?.takeIf { it.isNotEmpty() && placed.folderRelPath.contains('/') }?.let { folderInner ->
                for (m in sources.filterNot { it.video }) if (writeMedia(mediaGw, target.mediaBase, system, folderInner, m)) media++
            }
        }
        if (opts.metadata) {
            entries += GamelistEntry(inner, fieldsFor(rom))
            placed.folderRelPath?.substringAfter('/')?.takeIf { it.isNotEmpty() && placed.folderRelPath.contains('/') }?.let { folderInner ->
                entries += GamelistEntry(folderInner, fieldsFor(rom).filterKeys { it in FOLDER_FIELDS }, tag = "folder")
            }
        }
        return GameResult(media, entries, system)
    }

    private data class Media(val type: String, val urls: List<String>, val video: Boolean)

    /** @return true when a file was written (false: already there, nothing to fetch, or failed). */
    private fun writeMedia(gw: FileGateway, base: String, system: String, innerPath: String, m: Media): Boolean {
        if (m.urls.isEmpty()) return false
        val innerDir = innerPath.substringBeforeLast('/', "")
        val stem = EsdeMeta.stem(innerPath.substringAfterLast('/'))
        val dir = listOf(base, system, m.type, innerDir).filter { it.isNotEmpty() }.joinToString("/")
        val exts = if (m.video) VIDEO_EXTS else IMAGE_EXTS
        if (exts.any { gw.exists(dir, stem + it) }) return false
        for (url in m.urls) {
            val tmp = File(context.cacheDir, "esde").apply { mkdirs() }.let { File.createTempFile("media", ".tmp", it) }
            try {
                val ext = fetch(url, tmp, m.video) ?: continue
                if (!gw.ensureDir(dir)) return false
                if (gw.moveIn(tmp, dir, stem + ext)) return true
            } catch (t: Throwable) {
                log.w(TAG, "media ${m.type} for $innerPath from $url failed: ${t.message}")
            } finally {
                tmp.delete()
            }
        }
        return false
    }

    /** Downloads [url] into [dest]; returns the extension ES-DE will read it under, or null. */
    private fun fetch(url: String, dest: File, video: Boolean): String? {
        client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val ext = extensionFor(r.header("Content-Type"), url, video) ?: return null
            val limit = if (video) MAX_VIDEO_BYTES else MAX_IMAGE_BYTES
            if (r.body.contentLength() > limit) return null
            var total = 0L
            FileOutputStream(dest).use { out ->
                r.body.byteStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > limit) return null
                        out.write(buf, 0, n)
                    }
                }
            }
            return if (total > 0) ext else null
        }
    }

    /* ---------------- gamelist.xml ---------------- */

    /**
     * Merges [entries] into `gamelists/<system>/gamelist.xml`. The new content goes to a hidden temp
     * file, is read back and checked (it parses, it lost no entry), then takes the file's place in
     * one rename. A file that cannot be parsed is never replaced.
     */
    private suspend fun writeGamelist(target: EsdeTarget, system: String, entries: List<GamelistEntry>): MergeResult? {
        if (entries.isEmpty()) return null
        if (target.legacyGamelists) { log.i(TAG, "LegacyGamelistFileLocation is on: gamelist.xml left alone"); return null }
        return gamelistLock.withLock {
            withContext(Dispatchers.IO) {
                val gw = target.data
                val dir = "gamelists/$system"
                val existing = gw.openInput(dir, GAMELIST)?.use { it.readBytes().toString(Charsets.UTF_8) }
                val before = existing?.takeIf { it.isNotBlank() }?.let { GamelistXml.entryCount(it) } ?: 0
                val result = GamelistXml.merge(existing, entries)
                val xml = result.xml ?: return@withContext result
                val bytes = xml.toByteArray(Charsets.UTF_8)
                val after = GamelistXml.entryCount(xml)
                check(after >= before) { "merge would drop entries ($before -> $after)" }
                if (!gw.ensureDir(dir)) throw IllegalStateException("cannot create $dir")
                gw.openOutput(dir, TMP).use { out ->
                    out.write(bytes)
                    out.flush()
                    (out as? FileOutputStream)?.fd?.sync()
                }
                val back = gw.openInput(dir, TMP)?.use { it.readBytes().toString(Charsets.UTF_8) }
                if (back == null || back.length != xml.length || GamelistXml.entryCount(back) != after) {
                    gw.delete(dir, TMP)
                    throw IllegalStateException("gamelist temp file did not read back")
                }
                if (!gw.replaceFile(dir, TMP, GAMELIST)) {
                    gw.delete(dir, TMP)
                    throw IllegalStateException("could not replace $dir/$GAMELIST")
                }
                result
            }
        }
    }

    /* ---------------- games already on the device ---------------- */

    /** Exports every game the local index knows about. One run at a time; progress in [progress]. */
    fun exportExisting() {
        if (catchUpLock.isLocked) return
        scope.launch(Dispatchers.IO) {
            catchUpLock.withLock {
                _progress.value = CatchUpProgress(running = true)
                val result = runCatching { runCatchUp() }.onFailure { log.w(TAG, "catch-up failed", it) }.getOrNull()
                _progress.value = CatchUpProgress(running = false, last = result ?: CatchUpResult(0, 0, 0, 0, 1, 0))
            }
        }
    }

    private suspend fun runCatchUp(): CatchUpResult {
        val target = (status() as? EsdeStatus.Ready)?.target ?: return CatchUpResult(0, 0, 0, 0, 1, 0)
        val gw = mapping.gateway() ?: return CatchUpResult(0, 0, 0, 0, 1, 0)
        val opts = prefs.snapshot()
        val local = localLibrary.allFiles().first().filterNot { it.relPath.substringBefore('/') == it.relPath }
        val bySlug = local.groupBy { it.platformSlug }
        var games = 0; var media = 0; var added = 0; var completed = 0; var errors = 0; var unmatched = 0
        _progress.value = CatchUpProgress(running = true, total = local.size)
        var done = 0
        for ((slug, entries) in bySlug) {
            val platform = platforms.bySlug(slug)
            val ids = platform?.let { runCatching { romIdsByKey(it.id) }.getOrElse { e -> log.w(TAG, "listing $slug failed", e); errors++; null } }
            val perSystem = LinkedHashMap<String, MutableList<GamelistEntry>>()
            // Several files of one game (a cue and its bins) share a key: one export per game.
            for ((key, group) in entries.groupBy { it.nameKey }) {
                val id = ids?.get(key)
                if (id == null) { unmatched += group.size; done += group.size; _progress.value = _progress.value.copy(done = done); continue }
                runCatching {
                    val rom = roms.detail(id).getOrThrow()
                    val first = group.first()
                    val dir = first.relPath.substringBeforeLast('/', "")
                    val placed = locateGame(gw, dir, group.map { it.fileName }, EsdeMeta.stem(rom.fsName))
                    if (placed != null) {
                        val r = exportGame(target, rom, placed, opts)
                        media += r.media
                        perSystem.getOrPut(r.system) { ArrayList() } += r.entries
                        games++
                    }
                }.onFailure { e -> errors++; log.w(TAG, "catch-up ${group.first().relPath} failed: ${e.message}") }
                done += group.size
                _progress.value = _progress.value.copy(done = done)
            }
            if (opts.metadata) for ((system, list) in perSystem) {
                runCatching { writeGamelist(target, system, list) }
                    .onSuccess { r -> added += r?.added ?: 0; completed += r?.completed ?: 0 }
                    .onFailure { e -> errors++; log.w(TAG, "gamelist $system failed", e) }
            }
        }
        log.i(TAG, "catch-up: $games games, $media media, gamelist +$added/~$completed, $unmatched unmatched, $errors errors")
        return CatchUpResult(games, media, added, completed, errors, unmatched)
    }

    /** name key -> ROM id for one platform, every variant (no grouping by game). */
    private suspend fun romIdsByKey(platformId: Int): Map<String, Int> {
        val out = HashMap<String, Int>()
        var offset = 0
        while (true) {
            val page = api.roms(platformIds = listOf(platformId), limit = 250, offset = offset, groupByMetaId = false, withCharIndex = false, withTotal = offset == 0)
            for (dto in page.items) {
                val fs = dto.fsName ?: continue
                out.putIfAbsent(Rom.nameKeyOf(fs), dto.id)
            }
            offset += page.items.size
            if (page.items.isEmpty() || (page.total != null && offset >= page.total)) break
        }
        return out
    }

    /* ---------------- helpers ---------------- */

    private fun fieldsFor(rom: Rom): Map<String, String> = buildMap {
        put("name", rom.name)
        rom.summary?.let { put("desc", it) }
        EsdeMeta.rating(rom.rating)?.let { put("rating", it) }
        EsdeMeta.releaseDate(rom.firstReleaseDate)?.let { put("releasedate", it) }
        // RomM keeps one list of companies, without roles: the first is usually the developer,
        // the second the publisher (IGDB order). [à vérifier] on a few games.
        rom.companies.getOrNull(0)?.let { put("developer", it) }
        rom.companies.getOrNull(1)?.let { put("publisher", it) }
        rom.genres.takeIf { it.isNotEmpty() }?.let { put("genre", it.joinToString(", ")) }
        EsdeMeta.players(rom.players)?.let { put("players", it) }
    }.filterValues { it.isNotBlank() }

    private fun join(a: String, b: String) = if (a.isEmpty()) b else "$a/$b"

    companion object {
        private const val TAG = "EsdeExport"
        private const val GAMELIST = "gamelist.xml"
        private const val TMP = ".gamelist.xml.rommtmp"
        private val FOLDER_FIELDS = setOf("name", "desc", "rating", "releasedate", "developer", "publisher", "genre", "players")
        private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        private const val MAX_VIDEO_BYTES = 300L * 1024 * 1024
        /** Same order as ES-DE's `sImageExtensions` / `sVideoExtensions` (FileData.h). */
        val IMAGE_EXTS = listOf(".png", ".jpg", ".webp")
        val VIDEO_EXTS = listOf(".mp4", ".mkv", ".avi", ".wmv", ".mov", ".webm", ".m4v")

        /** Extension from the server's Content-Type, else from the URL; null when ES-DE cannot read it. */
        fun extensionFor(contentType: String?, url: String, video: Boolean): String? {
            val ct = contentType?.substringBefore(';')?.trim()?.lowercase()
            val byType = when (ct) {
                "image/png" -> ".png"
                "image/jpeg", "image/jpg" -> ".jpg"
                "image/webp" -> ".webp"
                "video/mp4" -> ".mp4"
                "video/webm" -> ".webm"
                "video/x-matroska" -> ".mkv"
                "video/quicktime" -> ".mov"
                "video/x-m4v" -> ".m4v"
                else -> null
            }
            val allowed = if (video) VIDEO_EXTS else IMAGE_EXTS
            if (byType != null) return byType.takeIf { it in allowed }
            val path = url.substringBefore('?').substringBefore('#')
            val ext = "." + path.substringAfterLast('/').substringAfterLast('.', "").lowercase().let { if (it == "jpeg") "jpg" else it }
            return ext.takeIf { it in allowed }
        }
    }
}

/** Reads values out of ES-DE's `settings/es_settings.xml` (`<string name="X" value="..." />`). */
object EsdeSettingsFile {
    fun string(xml: String, name: String): String? =
        Regex("<string\\s+name=\"${Regex.escape(name)}\"\\s+value=\"([^\"]*)\"").find(xml)?.groupValues?.get(1)?.let(::unescape)

    fun bool(xml: String, name: String): Boolean? =
        Regex("<bool\\s+name=\"${Regex.escape(name)}\"\\s+value=\"([^\"]*)\"").find(xml)?.groupValues?.get(1)?.let { it == "true" }

    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
