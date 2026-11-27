package com.rommmobile.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Game page extras: RetroAchievements and the user's collections (favourites included), verified
 * against RomM 5.4.1 (`DetailedRomSchema.merged_ra_metadata` / `user_collections`,
 * `UserSchema.ra_progression`, `/api/collections/{id}/roms`).
 */

/** Scope the favourite and collection buttons need on top of [READ_SCOPES]. */
const val COLLECTIONS_WRITE = "collections.write"

/** The parts of a ROM's detail the extras need; read with the same `GET /api/roms/{id}`. */
@Serializable
data class RomExtrasDto(
    val id: Int,
    @SerialName("ra_id") val raId: Int? = null,
    @SerialName("merged_ra_metadata") val raMetadata: RaMetadataDto? = null,
    @SerialName("user_collections") val userCollections: List<UserCollectionRefDto> = emptyList(),
)

@Serializable
data class RaMetadataDto(val achievements: List<RaAchievementDto> = emptyList())

@Serializable
data class RaAchievementDto(
    @SerialName("ra_id") val raId: Int? = null,
    val title: String = "",
    val description: String = "",
    val points: Int = 0,
    @SerialName("num_awarded") val numAwarded: Int = 0,
    @SerialName("num_awarded_hardcore") val numAwardedHardcore: Int = 0,
    @SerialName("badge_url") val badgeUrl: String? = null,
    @SerialName("badge_url_lock") val badgeUrlLock: String? = null,
    @SerialName("badge_path") val badgePath: String? = null,
    @SerialName("badge_path_lock") val badgePathLock: String? = null,
    @SerialName("display_order") val displayOrder: Int = 0,
    /** RA's kind: progression, win_condition, missable… or null. */
    val type: String? = null,
)

@Serializable
data class UserCollectionRefDto(
    val id: Int,
    val name: String = "",
    @SerialName("is_smart") val isSmart: Boolean = false,
)

/** `GET /api/users/me`, read for its RetroAchievements fields. */
@Serializable
data class MeRaDto(
    val id: Int = 0,
    val username: String = "",
    @SerialName("ra_username") val raUsername: String? = null,
    @SerialName("ra_progression") val raProgression: RaProgressionDto? = null,
)

@Serializable
data class RaProgressionDto(val results: List<RaGameProgressDto> = emptyList())

@Serializable
data class RaGameProgressDto(
    @SerialName("rom_ra_id") val romRaId: Int? = null,
    @SerialName("max_possible") val maxPossible: Int? = null,
    @SerialName("num_awarded") val numAwarded: Int? = null,
    @SerialName("num_awarded_hardcore") val numAwardedHardcore: Int? = null,
    @SerialName("earned_achievements") val earned: List<RaEarnedDto> = emptyList(),
)

@Serializable
data class RaEarnedDto(
    /** The achievement's RA id, as a string. */
    val id: String = "",
    val date: String? = null,
    @SerialName("date_hardcore") val dateHardcore: String? = null,
)

@Serializable
data class RaRefreshBody(val incremental: Boolean)

@Serializable
data class CollectionRomsBody(@SerialName("rom_ids") val romIds: List<Int>)

/** `GET /api/stats`: the server's totals, shown on the second screen when nothing is selected. */
@Serializable
data class StatsDto(
    @SerialName("PLATFORMS") val platforms: Int = 0,
    @SerialName("ROMS") val roms: Int = 0,
    @SerialName("SAVES") val saves: Int = 0,
    @SerialName("STATES") val states: Int = 0,
    @SerialName("SCREENSHOTS") val screenshots: Int = 0,
    @SerialName("TOTAL_FILESIZE_BYTES") val totalBytes: Long = 0,
)
