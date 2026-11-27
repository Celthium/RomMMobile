package com.rommmobile.app.feature.sync

import android.content.Context
import android.os.Environment
import com.rommmobile.app.core.storage.StorageLocations
import java.io.File

/** The two kinds RomM keeps apart, and so does the sync. */
enum class SaveKind { SAVE, STATE }

/** Where a folder came from, shown next to it in the settings. */
enum class PathSource { MANUAL, RETROARCH_CFG, DEFAULT }

data class KindDirs(
    val dir: File,
    val source: PathSource,
    /** RetroArch files each core's saves in its own folder (`saves/mGBA/…`). */
    val sortByCore: Boolean,
    /** …or each content folder's saves in its own folder (`saves/gba/…`). */
    val sortByContent: Boolean,
    /** Saves next to the ROM instead of a common folder. */
    val inContentDir: Boolean,
)

data class RetroArchSetup(
    /** Installed RetroArch package, null when none is. */
    val packageName: String?,
    /** retroarch.cfg that was read, null when none could be. */
    val configFile: File?,
    val saves: KindDirs,
    val states: KindDirs,
    val esde: EsDeConfig?,
) {
    fun dirs(kind: SaveKind) = if (kind == SaveKind.SAVE) saves else states
}

/** What ES-DE's own files say: the ROM folder and the emulator picked per system. */
data class EsDeConfig(
    val dataDir: File,
    val romDirectory: String?,
    /** System folder (`gba`) -> label of the alternative emulator (`RetroArch (mGBA)`). */
    val alternativeEmulators: Map<String, String>,
) {
    /** RetroArch core folder named by the system's emulator label, e.g. `mGBA`; null if not RetroArch. */
    fun coreDirFor(system: String): String? {
        val label = alternativeEmulators[system] ?: return null
        if (!label.contains("RetroArch", ignoreCase = true)) return null
        return Regex("\\(([^)]+)\\)").find(label)?.groupValues?.get(1)?.trim()
    }
}

/**
 * Finds where RetroArch keeps saves and states on this device:
 * 1. the folder set by hand in the settings;
 * 2. `retroarch.cfg` of the installed RetroArch (`savefile_directory`, `savestate_directory`, the
 *    sort options), read from `Android/data/<package>/files/` or `RetroArch/`;
 * 3. RetroArch's Android defaults, `<internal>/RetroArch/saves` and `/states`, with core folders.
 * ES-DE's settings and gamelists add the emulator chosen per system, which names the core folder
 * a save that exists only on the server goes into.
 */
object RetroArchConfig {

    val PACKAGES = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")

    fun installedPackage(context: Context): String? = PACKAGES.firstOrNull { pkg ->
        // Visible thanks to the <queries> entries in the manifest (package visibility, Android 11+).
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    fun detect(context: Context, manualSaves: String?, manualStates: String?): RetroArchSetup {
        val pkg = installedPackage(context)
        val internal = Environment.getExternalStorageDirectory()
        val cfgFile = configCandidates(internal, pkg).firstOrNull { runCatching { it.canRead() && it.isFile }.getOrDefault(false) }
        val cfg = cfgFile?.let { runCatching { parse(it.readLines()) }.getOrNull() }.orEmpty()
        val appDir = pkg?.let { File(internal, "Android/data/$it/files") }

        fun resolve(key: String, fallback: String): Pair<File, PathSource> {
            val raw = cfg[key]?.takeIf { it.isNotBlank() && it != "default" }
            val f = raw?.let { expand(it, internal, appDir) }
            return if (f != null) f to PathSource.RETROARCH_CFG else File(internal, fallback) to PathSource.DEFAULT
        }

        fun kind(manual: String?, dirKey: String, fallback: String, sortCore: String, sortContent: String, inContent: String): KindDirs {
            val (auto, src) = resolve(dirKey, fallback)
            val dir = manual?.takeIf { it.isNotBlank() }?.let(::File)
            return KindDirs(
                dir = dir ?: auto,
                source = if (dir != null) PathSource.MANUAL else src,
                // RetroArch's defaults on Android: sorted by core, not by content.
                sortByCore = cfg[sortCore]?.toBooleanStrictOrNull() ?: true,
                sortByContent = cfg[sortContent]?.toBooleanStrictOrNull() ?: false,
                inContentDir = cfg[inContent]?.toBooleanStrictOrNull() ?: false,
            )
        }

        return RetroArchSetup(
            packageName = pkg,
            configFile = cfgFile.takeIf { cfg.isNotEmpty() },
            saves = kind(manualSaves, "savefile_directory", "RetroArch/saves", "sort_savefiles_enable", "sort_savefiles_by_content_enable", "savefiles_in_content_dir"),
            states = kind(manualStates, "savestate_directory", "RetroArch/states", "sort_savestates_enable", "sort_savestates_by_content_enable", "savestates_in_content_dir"),
            esde = readEsDe(context),
        )
    }

    private fun configCandidates(internal: File, pkg: String?): List<File> = buildList {
        (listOfNotNull(pkg) + PACKAGES).distinct().forEach { add(File(internal, "Android/data/$it/files/retroarch.cfg")) }
        add(File(internal, "RetroArch/retroarch.cfg"))
        add(File(internal, "RetroArch/config/retroarch.cfg"))
    }

    /** `key = "value"` lines; comments and blank lines skipped. */
    fun parse(lines: List<String>): Map<String, String> = buildMap {
        for (line in lines) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val i = t.indexOf('=')
            if (i <= 0) continue
            put(t.substring(0, i).trim(), t.substring(i + 1).trim().removeSurrounding("\""))
        }
    }

    /** `:/saves` is relative to RetroArch's own folder, `~` to the internal storage. */
    private fun expand(raw: String, internal: File, appDir: File?): File? = when {
        raw.startsWith(":") -> appDir?.let { File(it, raw.removePrefix(":").trimStart('/', '\\')) }
        raw.startsWith("~") -> File(internal, raw.removePrefix("~").trimStart('/'))
        raw.startsWith("/") -> File(raw)
        else -> null
    }

    /** ES-DE's data folder is `ES-DE/` at the root of a volume unless the user moved it. */
    fun readEsDe(context: Context): EsDeConfig? {
        val roots = StorageLocations.volumes(context).map { it.first }
        val dataDir = roots.map { File(it, "ES-DE") }.firstOrNull { File(it, "settings/es_settings.xml").isFile } ?: return null
        val settings = runCatching { File(dataDir, "settings/es_settings.xml").readText() }.getOrNull().orEmpty()
        val romDir = Regex("<string name=\"ROMDirectory\" value=\"([^\"]*)\"").find(settings)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val alt = HashMap<String, String>()
        File(dataDir, "gamelists").listFiles()?.filter { it.isDirectory }?.forEach { sys ->
            val gl = File(sys, "gamelist.xml")
            if (!gl.isFile) return@forEach
            // Only the head of the file: <alternativeEmulator> comes before the games.
            val head = runCatching { gl.bufferedReader().use { r -> CharArray(4096).let { b -> String(b, 0, r.read(b).coerceAtLeast(0)) } } }.getOrNull().orEmpty()
            Regex("<alternativeEmulator>\\s*<label>([^<]+)</label>").find(head)?.groupValues?.get(1)?.let { alt[sys.name] = it.trim() }
        }
        return EsDeConfig(dataDir, romDir, alt)
    }
}

/**
 * RetroArch core folder names and the `emulator` ids RomM's web player and RetroArch sync use for
 * them (`backend/handler/sync/retroarch/emulator_names.py`, RomM 5.4.1).
 */
object CoreNames {
    private val DIR_BY_ID = linkedMapOf(
        "snes9x" to "Snes9x", "bsnes" to "bsnes", "mesen-s" to "Mesen-S", "nestopia" to "Nestopia",
        "fceumm" to "FCEUmm", "mesen" to "Mesen", "mednafen_psx" to "Beetle PSX", "mednafen_psx_hw" to "Beetle PSX HW",
        "beetle_psx" to "Beetle PSX", "beetle_psx_hw" to "Beetle PSX HW", "pcsx_rearmed" to "PCSX-ReARMed",
        "swanstation" to "SwanStation", "gambatte" to "Gambatte", "sameboy" to "SameBoy", "tgbdual" to "TGB Dual",
        "mgba" to "mGBA", "vba_next" to "VBA Next", "vbam" to "VBA-M", "genesis_plus_gx" to "Genesis Plus GX",
        "blastem" to "BlastEm", "picodrive" to "PicoDrive", "mupen64plus_next" to "Mupen64Plus-Next",
        "parallel_n64" to "ParaLLEl N64", "mednafen_saturn" to "Beetle Saturn", "beetle_saturn" to "Beetle Saturn",
        "kronos" to "Kronos", "mame" to "MAME", "fbneo" to "FBNeo", "fbalpha" to "FB Alpha", "pcsx2" to "PCSX2",
        "play" to "Play!", "dolphin" to "Dolphin", "flycast" to "Flycast", "redream" to "Redream", "stella" to "Stella",
        "mednafen_pce" to "Beetle PCE", "mednafen_pce_fast" to "Beetle PCE Fast", "beetle_pce" to "Beetle PCE",
        "beetle_pce_fast" to "Beetle PCE Fast", "dosbox_pure" to "DOSBox-Pure", "scummvm" to "ScummVM",
        "ppsspp" to "PPSSPP", "desmume" to "DeSmuME", "melonds" to "melonDS", "citra" to "Citra",
    )
    private val ID_BY_DIR: Map<String, String> = buildMap { DIR_BY_ID.forEach { (id, dir) -> putIfAbsent(dir, id) } }

    /** RomM's id for a core folder, the folder name itself when unknown (RomM does the same). */
    fun emulatorFor(coreDir: String?): String? = coreDir?.let { ID_BY_DIR[it] ?: it }?.take(50)

    /** Core folder for a RomM emulator id, unchanged when unknown. */
    fun dirFor(emulator: String?): String? = emulator?.let { DIR_BY_ID[it] ?: it }
}
