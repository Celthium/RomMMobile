# 05 — Launchers, folders and platform mapping

This is the heart of the project: **it is the problem the app exists to solve**.

## 1. The problem

RomM organises the library as `library/roms/<slug>/`, where the slug follows the IGDB naming (`sms`, `genesis`, `neo-geo-pocket-color`, `sega32`, `zxs`…). Every Android frontend uses its own conventions instead: ES-DE expects `mastersystem`, `megadrive`, `ngpc`, `sega32x`, `zxspectrum`; Daijishō, Beacon and iiSU let the user pick one folder per platform, so the convention is whatever the user has built up over time.

The result with any generic client: on every download the app asks where to put the file, or it puts it in a folder the frontend does not watch. This must **disappear completely**.

## 2. Solution: cascading resolution

For each platform, the destination folder is resolved in this order and the result is stored in `FolderMappingEntity`:

1. **User override** (`source = USER`) — always wins, never rewritten automatically.
2. **Folder already present on the device** (`source = DISCOVERED`) — the subfolders of the ROM root folder are listed and compared with the platform aliases, case-insensitively and ignoring spaces, hyphens and underscores. This is the most frequent case: anyone with a frontend already configured already has the right folders.
3. **Preset of the selected launcher** (`source = PRESET`) — from `platform_map.json`, the field of the launcher selected during onboarding.
4. **Creation** (`source = CREATED`) — if nothing exists, the preset folder is proposed with a single-confirmation dialog. This is the only place where the user sees a question, and only once per platform.

Additional rules:

- A match against multiple candidates (e.g. both `megadrive` and `genesis` exist) is resolved by asking once, showing how many ROMs each folder contains: the most populated one is preselected.
- The mapping is **editable at any time** from Settings → Platform folders, with a table `platform → folder → number of files found`.
- Switching launcher later **does not erase** user overrides: it only re-runs phases 2 and 3.

### Pseudocode

```kotlin
fun resolveFolder(platform: Platform): FolderMapping? {
    userOverride(platform.slug)?.let { return it }
    val entry = platformMap[platform.slug]              // may be null
    val aliases = buildSet {
        add(platform.slug)
        entry?.let { addAll(listOfNotNull(it.esde) + it.esdeAlt + it.aliases) }
    }.map(::normalize)                                   // lowercase, strip spaces/-/_
    val existing = fileGateway.list(root).filter { it.isDirectory }
    val hits = existing.filter { normalize(it.name) in aliases }
    when {
        hits.size == 1 -> return save(platform.slug, hits.first(), DISCOVERED)
        hits.size > 1  -> return askUser(hits)            // preselection: most files inside
    }
    val preset = entry?.folderFor(selectedLauncher) ?: platform.slug
    return proposeCreate(preset)                          // single confirmation
}
```

## 3. Supported launchers

| Launcher | Typical root folder | Folder convention | Metadata | Preset reliability |
|---|---|---|---|---|
| **ES-DE** | `/storage/emulated/0/ROMs` | **fixed and known** (`snes`, `megadrive`, `gc`…), lowercase | `ES-DE/gamelists/<sys>/gamelist.xml` + `ES-DE/downloaded_media/<sys>/` | high: mapped from the official `es_systems.xml` |
| **Cocoon** | same as ES-DE | natively imports the ES-DE structure (ES-DE Migration/Link) | ES-DE format | high: inherits the ES-DE preset |
| **Daijishō** | `/storage/emulated/0/Roms` (varies) | **user-defined**: each platform has one or more "sync paths" | internal database, own scraping | medium: the common short name is proposed, relying on automatic discovery |
| **Beacon** | variable | user-defined, one folder per platform | internal | medium |
| **iiSU** | variable; assets in `/storage/emulated/0/Android/media/com.iisulauncher/iiSULauncher/assets/` | user-defined; can import ES-DE metadata | assets managed by hand by the user | medium |
| **Pegasus** | variable | user-defined | `metadata.pegasus.txt` per folder | medium |
| **Dig** | variable | user-defined | internal | low (project inactive) |
| **Custom / RetroArch only** | free choice | RomM slug unchanged | none | — |

For launchers with a free convention the preset uses the **common short name** (the `generic` column of `platform_map.json`, which almost always matches the RomM slug): in practice the user already has the folders, so automatic discovery wins.

**To verify on the device at first launch, do not take it for granted:** ES-DE on Android asks for the ROM folder at first launch and the user may have put it on the SD card (`/storage/XXXX-XXXX/ROMs`). The app must also look for the root folder on the SD card and propose both.

## 4. `platform_map.json`

File in `app/src/main/assets/platform_map.json` (copy in `assets/` of this repo). Structure:

```json
{
  "schemaVersion": 1,
  "platforms": [
    {
      "slug": "sms",
      "label": "Sega Master System",
      "esde": "mastersystem",
      "esdeAlt": ["mark3"],
      "generic": "sms",
      "aliases": ["sega master system", "sms", "mastersystem"]
    }
  ]
}
```

- `slug`: RomM/IGDB slug. The entries cover the roughly 80 platforms actually present in a real library plus the most common ones not present yet.
- `esde`: official ES-DE folder, extracted from `resources/systems/android/es_systems.xml` in the ES-DE repo.
- `esdeAlt`: alternatives accepted by ES-DE (regional variants such as `genesis`/`megadrive`, `segacd`/`megacd`, `tg16`/`pcengine`): they count as aliases during discovery.
- `esde: null` means ES-DE does not have that system: the app uses `generic`, warns the user that the frontend might not show it and does not treat it as an error.
- The file **is not authoritative on the list of platforms**: that comes from `GET /api/platforms`. An unknown slug is not an error; the app falls back to `slug` and to automatic discovery.
- The file must be updatable without recompiling: an override in `Android/data/<pkg>/files/platform_map.json` is planned, read if present.

## 5. Rules for downloaded files

1. **Unchanged name**: the server's `fs_name` is used. It is already No-Intro/Redump and every frontend scrapes on top of it.
2. **Archives**: `.zip` is extracted by default; `.7z` is left intact by default (ES-DE and RetroArch read it; extracting it only makes things worse). Both behaviours are configurable, also per platform.
3. **Multi-file / multi-disc games**: RomM serves them as an on-the-fly zip containing an `.m3u`. Destination: **one folder per game** inside the platform folder (`ROMs/psx/Final Fantasy VII/` containing the `.chd`/`.bin`/`.cue` files and the `.m3u`). ES-DE correctly handles both the `.m3u` in the system folder and the subfolder; the `.m3u` must in any case be left where the frontend sees it. Behaviour configurable per platform (`flat` or `folder-per-game`), default `folder-per-game` for psx, ps2, saturn, segacd, dreamcast, pcenginecd, 3do, neogeocd.
4. **No partial files** in the frontend folder: write to the private cache, verify, move.
5. **Duplicates**: if a file with the same name (or the extracted or compressed version of the same name) already exists, the download is marked "already on the device" and skipped, with the option to force it.
6. **BIOS/firmware**: separate section. Source `GET /api/firmware`, destination **`RetroArch/system/`** (configurable path), not the platform folders. Users typically already have a complete BIOS pack for RetroArch: the feature only serves to fill the gaps, so never overwrite an existing file without asking.

## 6. Metadata for the frontend (ES-DE export)

No Android frontend exposes an API to register a game. The universal contract is: **a file with the right extension in the watched folder, plus a new scan by the frontend**. ES-DE rescans at every launch.

When the launcher is **ES-DE or Cocoon**, each finished ROM download also writes what ES-DE would otherwise scrape, from what RomM already has (`feature/esde/`). Settings > Launcher and folders turns it on or off per media (video off by default) and offers **Export for games already on the device**. Formats checked against ES-DE's source (`FileData.cpp`, `GamelistFileParser.cpp`, `MetaData.cpp`, master, 2026-11):

- ES-DE's data folder: `<volume>/ES-DE` holding `settings/es_settings.xml`, or chosen by hand (path or SAF tree). Media go to `MediaDirectory` from `es_settings.xml` when set, else `ES-DE/downloaded_media`.
- Media: `downloaded_media/<system>/{covers,screenshots,marquees,videos}/<ROM sub-folders>/<ROM name without extension>.<ext>`. Images `.png`/`.jpg`/`.webp`, videos `.mp4`/`.mkv`/`.avi`/`.wmv`/`.mov`/`.webm`/`.m4v`, the extension taken from the file's first bytes (RomM serves some PNG screenshots as `.jpg`). `<system>` is the platform folder's name (ES-DE system name = folder in `%ROMPATH%`). Sources: `path_cover_large` → `url_cover`, first `merged_screenshots`, `ss_metadata.logo_path` (marquee), `path_video`. A game in its own folder (`psx/Game/`) also gets the folder's pictures.
- `gamelists/<system>/gamelist.xml`: `<game>` with `<path>./file</path>`, `name`, `desc`, `rating` (0-1, from RomM's 0-10), `releasedate` (`YYYYMMDDT000000`), `publisher` (RomM's companies, no roles: no `developer` is guessed), `genre`, `players`. A `<folder>` entry for a game in its own folder.
- **Never overwrite**: a media file present under any extension ES-DE reads is skipped; an existing entry only gets the fields it lacks (absent or blank); nothing is removed. ES-DE writes `<alternativeEmulator>` before `<gameList>` (two top-level elements, valid for pugixml only): both are read and written back in place. A file that does not parse is left alone.
- **Atomic write**: hidden temp file `.gamelist.xml.rommtmp`, read back and checked (parses, no entry lost), then renamed over `gamelist.xml` (SAF: old file set aside, renamed back on failure).
- **ES-DE running**: ES-DE re-reads `gamelist.xml` before saving and only rewrites the games it changed itself (`SaveGamelistsMode` = `always` by default). An entry for a freshly downloaded game, unknown to ES-DE, therefore survives. A game ES-DE already lists (catch-up while it is open) is rewritten from ES-DE's own copy the next time ES-DE saves that game (after playing it, say), losing the added fields. Android does not let the app see whether ES-DE is running, so the catch-up dialog asks to quit ES-DE first. ES-DE shows new entries after a restart.
- Not handled: `LegacyGamelistFileLocation` (gamelists in the ROM folders) — the settings say so and gamelists are not written; `MediaDirectory` with `~` or `%ESPATH%`; miximages, 3D boxes, title screens.

## Extras next to a game

RomM files a game's extras by category (`dlc`, `update`, `mod`, `patch`, …). The game page lists them under **Files** and downloads each one into `<platform folder>/<game>/<category>/`, the layout emulators that read DLC and updates from the game's folder expect (Yuzu-style). An extra is never registered as the game itself, and a game folder that contains only extras is not reported as "on the device".

## 7. Save sync (RetroArch saves and states)

Code: `feature/sync/`. API: doc 04, section 9. Off until the user turns it on in Settings › Save sync.

### Where the files are

Resolved in this order (`RetroArchConfig.detect`), separately for saves and states:

1. **Folder set by hand** in the settings.
2. **`retroarch.cfg`** of the installed RetroArch (`com.retroarch.aarch64`, `com.retroarch`, `com.retroarch.ra32`), read from `Android/data/<package>/files/` then `RetroArch/`: `savefile_directory`, `savestate_directory`, `sort_savefiles_enable` / `sort_savestates_enable` (one folder per core), `sort_*_by_content_enable` (one folder per content folder), `savefiles_in_content_dir` / `savestates_in_content_dir` (next to the ROM). `:/…` is relative to RetroArch's own folder.
3. **RetroArch's Android defaults**: `<internal>/RetroArch/saves` and `/states`, one folder per core.

ES-DE adds the emulator chosen per system: `ES-DE/settings/es_settings.xml` (`ROMDirectory`) and the `<alternativeEmulator><label>RetroArch (mGBA)</label>` at the top of each `ES-DE/gamelists/<system>/gamelist.xml`. The core named there is the folder a save that exists only on the server goes into when the server does not name its emulator.

Observed on the AYN Thor (Android 13, 2026-11-27): `retroarch.cfg` in `Android/data/com.retroarch.aarch64/files/`, saves in `RetroArch/saves/<core>/` (`mGBA`, `Snes9x`, `Flycast`), states in `RetroArch/states/<core>/`, `autosave_interval = 10`.

### Matching a file to a game

RetroArch names a save after the content it loaded: `<ROM file without extension>.srm`, `.state`, `.state1`… `.state.auto`. The games are the ROMs this app downloaded (`downloads`, with their RomM id) and the files of the local library, matched to RomM on the ROM cache and, for files that have a save, by a server search (cached in `sync/state.json`).

### Rules

- **Saves**: `/api/sync/negotiate` with this device's id, slot `autosave` (`autosave-<ext>` for side files such as `.rtc`), `emulator` = RomM's core id (`mGBA` → `mgba`). Uploads keep 10 versions per slot on the server (`autocleanup`).
- **States**: no server tracking, so the hash of each file at the last sync and the server row's id and `updated_at` are kept in `sync/state.json`. Changed on one side → sent or received; changed on both → conflict; first meeting with a file on both sides → the server copy is downloaded and compared byte for byte.
- **Conflicts** are never settled automatically: nothing is written until the user picks "keep this device's copy" or "keep RomM's copy" (Settings › Save sync, or the game page).
- **Restores** (files from the server) are written only while RetroArch is closed. The app knows that through the usage-event log (Android "Usage access"): the last event of each RetroArch activity is `ACTIVITY_DESTROYED` → closed, `PAUSED`/`STOPPED` → in the background (it may still hold the game in memory), `RESUMED` → open. Without that access, or with RetroArch in the background, the file waits (notification) until RetroArch is closed or the user confirms it is. If the target file changed while waiting, the restore becomes a conflict.
- Before a file on the device is replaced, it is copied to `files/sync/backups/<date>/` (last 40 runs kept).
- **Game page, Save data tab** (laid out like RomM web's): sub-tabs Saves (n) / States (n), the device's sync status, saves grouped by slot ("AUTOSAVE · N versions") with every version kept on RomM, states one row each; the copy on the device is marked "On this device". Any other version can be restored (A): same rules as a restore (backup, RetroArch closed); an older save version is then uploaded again as the slot's newest, else the next sync would bring the newer one back.
- **Game page**: a Sync button next to Download (same height as the main action; a dot on its icon for an open conflict or waiting restore) and a **Saves** tab after Media, one row per kind (status, last sync, files on the device and on RomM); a row with a conflict or a waiting restore opens it. Shown only while the sync is on.
- **Triggers**: manual (Settings, game page); automatic for saves (app start, return to the app at most every 10 min, hourly WorkManager job); states only by hand unless turned on.
- Standalone emulators (DuckStation, PPSSPP, Dolphin, melonDS…) are not covered.
