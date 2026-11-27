# 04 — RomM API Contract

Reference server: **RomM 5.2** at `http://192.168.1.10:8080`.

> **Golden rule:** the source of truth is `{base}/openapi.json` (**not** `/api/openapi.json`) of the server in use, plus the Swagger UI at `{base}/api/docs`. RomM's public documentation lags behind on some endpoints. Before implementing, download the OpenAPI spec and generate/verify the DTOs.

## 1. Authentication

| Method | Endpoint | Notes |
|---|---|---|
| User login | `POST /api/login` | **caution: the docs say `/api/auth/login`, which is a 404 on 5.x** |
| Current session | `GET /api/users/me` | used at startup to find out whether the user is logged in |
| Client API token | `Authorization` header with an `rmm_…` token | via QR pairing: `POST /api/client-tokens/{id}/pair` plus exchange |
| Basic | `Authorization: Basic base64(user:pass)` | fallback, Base64 over **UTF-8** bytes |
| Heartbeat | `GET /api/heartbeat` | no auth, returns the version: use it for the reachability test |

Order of preference for the app: **client API token via QR** (convenient on a handheld) → login with credentials → Basic.

## 2. Platforms

`GET /api/platforms` → list with `id`, `slug`, `name`, `display_name`/`custom_name`, `rom_count`, logo data.

- Show only those with `rom_count > 0`.
- Label from `display_name`/`custom_name`, never from `name` alone.
- Icon: `{base}/assets/platforms/{slug}.ico`.
- **This list is the source of truth for slugs**: `platform_map.json` is only a mapping seed, not an authoritative list.

## 3. ROMs

`GET /api/roms` with the parameters:

| Parameter | Value |
|---|---|
| `platform_ids` | plural; the old singular `platform_id` is ignored by recent servers |
| `collection_id`, `virtual_collection_id`, `smart_collection_id` | collection filters |
| `search_term` | server-side search (500 ms debounce, minimum 2 characters) |
| `order_by` | `name`, `fs_size_bytes`, `created_at`, `first_release_date`, `average_rating` |
| `order_dir` | `asc` / `desc` |
| `limit`, `offset` | pagination, recommended page size 20–40 |
| `group_by_meta_id=1` | **essential**: groups the releases of the same game into one card with `siblings[]` |

Response: `{ items, total, limit, offset, char_index }`.

- **`char_index`** is the letter → offset map: it is what feeds the alphabet rail. `romm-mobile` ignored it; we build the fast-scroll on top of it.
- Map it directly onto Paging 3 (`limit`/`offset`).

Useful ROM fields: `id`, `name`, `fs_name` (the real file name, **to be used unchanged**), `fs_size_bytes`, `platform_id`, `platform_slug`, `regions`, `revision`, `md5_hash`/`sha1_hash`/`crc_hash`, `path_cover_small`/`path_cover_large`, `url_cover`, `siblings`, `files[]`, `first_release_date`, `summary`, `genres`.

Detail: `GET /api/roms/{id}`.

## 4. Covers

Priority: **`{base}{path_cover_small|large}`** (server cache, includes covers uploaded manually by the user) and only as a fallback `url_cover` (external CDN). The reverse order is bug #21 of `romm-mobile`: custom covers never showed up.

In Coil, the authentication header must be added **only** for requests to the host of the user's own server, never to the CDN.

## 5. Download

| Case | Endpoint |
|---|---|
| Whole game | `GET /api/roms/{id}/content/{fs_name}` |
| Only some files | `GET /api/roms/{id}/content/{fs_name}?file_ids=1,2,3` |
| Single file of a multi-file game | `GET /api/roms/{id}/files/content/{file_name}` |
| Firmware / BIOS | `GET /api/firmware/{id}/content/{file_name}` |

Verified notes:

- `Accept-Ranges` supported, **206** responses on resume.
- The `content` endpoint **without** `file_ids` always returns the whole game: asking for a single 90-byte `.cue` there downloads everything (their bug #20). For a single file use `files/content/`.
- Multi-disc: the server produces a **zip on the fly containing an `.m3u`**. It must be extracted (see doc 05 for the destination).
- Build URLs with normal `/`: their bug with backslashes in firmware URLs is real.

## 6. Collections

| Type | Endpoint |
|---|---|
| User collections | `GET /api/collections` |
| Virtual collections | `GET /api/collections/virtual?type=...` with the 5 types: `collection`, `franchise`, `genre`, `company`, `mode` |
| Smart collections | dedicated resource in RomM 5.x, ROMs filterable with `smart_collection_id` |

Caution: **virtual collection ids are strings**, not integers.

## 7. Startup behaviour

In parallel: `GET /api/heartbeat` (server version, no auth) and `GET /api/users/me` (valid session?). The first data request **must not depend** on the outcome of the version detection.

## 8. Errors

| Code | Behaviour |
|---|---|
| 401 | clear the session, go back to login |
| 403 | explicit message (token without permissions or Cloudflare Access) |
| 404 on known endpoints | likely version mismatch: show the server version and the endpoint attempted |
| non-JSON response | reverse proxy or login page: dedicated message, not a parsing crash |
| timeout/IO | retry with backoff, offline banner, content from cache |

## 9. Saves and save states (verified on RomM 5.4.1)

Source: `{base}/openapi.json` of the test server (5.4.1) and `backend/endpoints/{saves,states}.py`, `backend/endpoints/sync/`, `backend/handler/sync/comparison.py` at tag `5.4.1` of `rommapp/romm`.

RomM stores the two kinds separately and the app keeps them separate too:

| | Saves (SRAM, `.srm`, `.sav`…) | States (`.state`, `.state1`, `.state.auto`…) |
|---|---|---|
| List | `GET /api/saves?rom_id=` (also `rom_ids=` repeated, max 500, `platform_id`, `slot`, `device_id`) | `GET /api/states?rom_id=` (also `rom_ids=`, `platform_id`) |
| Upload | `POST /api/saves?rom_id=&emulator=&slot=&device_id=&content_hash=&overwrite=&autocleanup=&autocleanup_limit=` multipart `saveFile` (+ optional `screenshotFile`) | `POST /api/states?rom_id=&emulator=` multipart `stateFile` (+ optional `screenshotFile`) |
| Replace | `PUT /api/saves/{id}?device_id=&content_hash=` multipart `saveFile` | `PUT /api/states/{id}` multipart `stateFile` |
| Download | `GET /api/saves/{id}/content?device_id=&optimistic=` | `GET /api/states/{id}/content` |
| Confirm download | `POST /api/saves/{id}/downloaded` JSON `{device_id, content_hash}` | — |
| Per-slot summary | `GET /api/saves/summary?rom_id=` → `{total_count, slots:[{slot, count, latest}]}` | — |
| Delete | `POST /api/saves/delete` JSON `{saves:[ids]}` | `POST /api/states/delete` |
| Device tracking | yes (`device_syncs[]`, `track`/`untrack`) | **no** |
| Scopes | read `assets.read` (+ `devices.read` with `device_id`); write `assets.write` (+ `devices.write` with `device_id`) | `assets.read` / `assets.write` |

`SaveSchema` / `StateSchema` (common fields): `id`, `rom_id`, `user_id`, `file_name`, `file_name_no_tags`, `file_name_no_ext`, `file_extension`, `file_size_bytes`, `emulator`, `created_at`, `updated_at` (ISO 8601, UTC), `missing_from_fs`, `screenshot`, `is_public`, `is_favorite`, `labels`. Saves add `slot`, `content_hash`, `origin_device_id`, `device_syncs[]` (`device_id`, `device_name`, `last_synced_at`, `is_untracked`, `is_current`). States add `core`.

### Observed on the test server (2026-11-27)

- `updated_at` comes **with microseconds** in upload replies (`2026-11-27T13:24:22.754301+00:00`) and **without** them in lists (`2026-11-27T13:24:22+00:00`): compare instants to the second, never strings.
- For states, `file_name_no_tags` **drops the extension** (`Game.state9` → `Game`): match files on `file_name`.
- A state uploaded again under the same `file_name` replaces the row (same `id`); states have no versions.

### Content hash

`content_hash` is the **MD5 hex digest of the file bytes** (32 chars). For a zip it is the MD5 of the lines `"<entry name>:<entry md5>"` joined with `\n` (entries sorted). States have no hash in the API: compare size + `updated_at`, or download.

### Slots and versions (saves only)

- Saves are paired on **(rom_id, slot)**. RomM's own RetroArch sync and web player use the slot **`autosave`**. A save uploaded **with** a slot gets a version tag appended to its stored name (about 22 bytes, e.g. `Game [2026-11-27_10-00-00].srm`) and the slot keeps several versions (`autocleanup=true&autocleanup_limit=N`, capped by the server's `MAX_SAVES_PER_SLOT`). An upload never destroys the previous version of a slot.
- A save **without** a slot is "archival / manual": same `file_name` + `emulator` overwrites the row.
- With `device_id` and a slot, an upload is refused with **409** "Slot has a newer save since your last sync" if the slot head changed since this device's last sync, unless `overwrite=true`. Re-uploading bytes identical to an existing version (same hash) just promotes that version to the slot head.
- `emulator` is RomM's core id (`mgba`, `snes9x`, `genesis_plus_gx`…). RomM maps RetroArch's core folder names (`mGBA`, `Snes9x`, `Genesis Plus GX`) to these ids in `backend/handler/sync/retroarch/emulator_names.py` (table from `Covin90/romm-retroarch-sync`). Max 50 chars.

### Devices and negotiation (saves only)

- `POST /api/devices` registers a device (`name`, `platform`, `client`, `client_version`, `sync_mode`, `allow_existing=true`) → `{device_id}`. The device-code login (`/api/auth/device/token`) already returns a `device_id` bound to the token.
- `POST /api/sync/negotiate` body `{device_id?, saves:[{rom_id, file_name, slot, emulator, content_hash, updated_at, file_size_bytes}], rom_ids?, emulators?, restore_unlisted}` → `{session_id, operations:[{action, rom_id, save_id, file_name, slot, emulator, reason, server_updated_at, server_content_hash}], total_upload, total_download, total_conflict, total_no_op, total_delete}`. `action` ∈ `upload`, `download`, `conflict`, `no_op`, `delete`. `device_id` may be omitted with a device-bound token.
- Server rule (`compare_save_state`): same hash → `no_op`; with a sync baseline for this device, client changed **and** server changed since the last sync → `conflict`, only one changed → `upload`/`download`; without a baseline, the newer `updated_at` wins and equal timestamps with different content → `conflict`.
- `rom_ids` scopes downloads to the ROMs installed on the device (read-only: omitting a ROM never deletes anything).
- `POST /api/sync/sessions/{id}/complete` `{operations_completed, operations_failed}` closes the session.

### RetroArch Cloud Sync (WebDAV)

RomM 5.4 also exposes a WebDAV server for RetroArch's own **Cloud Sync** at `{base}/api/sync/retroarch/` (verified: `OPTIONS` → 401, auth required). It maps `saves/<core>/<file>` and `states/<core>/<file>` onto the same per-ROM assets (slot `autosave`). RetroArch then syncs itself when content starts and closes. This is an alternative to the app's sync, not something the app calls.

## 10. Game page extras: RetroAchievements and collections (verified on RomM 5.4.1)

| Need | Call |
|---|---|
| Achievement set | `GET /api/roms/{id}`: `ra_id`, `merged_ra_metadata.achievements[]` (`ra_id`, `title`, `description`, `points`, `num_awarded`, `num_awarded_hardcore`, `badge_path`/`badge_url`, `badge_path_lock`/`badge_url_lock`, `display_order`, `type`) |
| User progress | `GET /api/users/me`: `ra_username`, `ra_progression.results[]` (`rom_ra_id`, `num_awarded`, `num_awarded_hardcore`, `earned_achievements[]` = `{id, date, date_hardcore}`, `id` being the achievement's `ra_id` as a string) |
| Refresh progress | `POST /api/users/{id}/ra/refresh` JSON `{incremental: true}` |
| Collections of the game | `GET /api/roms/{id}`: `user_collections[]` = `{id, name, is_smart}` |
| User's collections | `GET /api/collections` (`user_id`, `owner_username`, `is_favorite`): only one's own can be changed |
| Add / remove the game | `POST` / `DELETE /api/collections/{id}/roms` JSON `{rom_ids: [...]}` (atomic, no full list) |
| Create Favourites | `POST /api/collections?is_favorite=true` multipart `name=Favourites` |

Badges: `badge_path` is relative to RomM's resources (`{base}/assets/romm/resources/{path}`), `badge_url` is RetroAchievements' CDN. Collection changes need the `collections.write` scope (the Viewer group has it for its own collections).

## 11. Server totals

`GET /api/stats` (no auth needed on 5.4.1) -> `{PLATFORMS, ROMS, SAVES, STATES, SCREENSHOTS, TOTAL_FILESIZE_BYTES}` (+ `METADATA_COVERAGE`, `REGION_BREAKDOWN`; `include_platform_stats` adds per-platform numbers). Shown on the second screen while nothing is selected.
