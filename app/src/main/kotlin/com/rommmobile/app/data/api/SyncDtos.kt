package com.rommmobile.app.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Saves, states and sync negotiation, verified against RomM 5.4.1 (`/openapi.json` and
 * `backend/endpoints/{saves,states,sync}`). See docs/04-ROMM-API.md section 9.
 * Request bodies carry no defaults: the app's Json has `encodeDefaults = false`.
 */

/** Scopes the save sync needs on top of [READ_SCOPES]. */
val SYNC_SCOPES = listOf("assets.write", "devices.read", "devices.write")

@Serializable
data class SaveDto(
    val id: Int,
    @SerialName("rom_id") val romId: Int = 0,
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_name_no_tags") val fileNameNoTags: String? = null,
    @SerialName("file_extension") val fileExtension: String? = null,
    @SerialName("file_size_bytes") val fileSizeBytes: Long = 0,
    val emulator: String? = null,
    val slot: String? = null,
    @SerialName("content_hash") val contentHash: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("missing_from_fs") val missingFromFs: Boolean = false,
)

@Serializable
data class StateDto(
    val id: Int,
    @SerialName("rom_id") val romId: Int = 0,
    @SerialName("file_name") val fileName: String = "",
    @SerialName("file_name_no_tags") val fileNameNoTags: String? = null,
    @SerialName("file_extension") val fileExtension: String? = null,
    @SerialName("file_size_bytes") val fileSizeBytes: Long = 0,
    val emulator: String? = null,
    val core: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("missing_from_fs") val missingFromFs: Boolean = false,
)

@Serializable
data class DeviceCreateBody(
    val name: String,
    val platform: String,
    val client: String,
    @SerialName("client_version") val clientVersion: String,
    val hostname: String?,
    @SerialName("allow_existing") val allowExisting: Boolean,
)

@Serializable
data class DeviceCreateDto(@SerialName("device_id") val deviceId: String)

@Serializable
data class ClientSaveDto(
    @SerialName("rom_id") val romId: Int,
    @SerialName("file_name") val fileName: String,
    val slot: String?,
    val emulator: String?,
    @SerialName("content_hash") val contentHash: String?,
    /** ISO 8601 UTC. */
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("file_size_bytes") val fileSizeBytes: Long,
)

@Serializable
data class NegotiateBody(
    @SerialName("device_id") val deviceId: String?,
    val saves: List<ClientSaveDto>,
    @SerialName("rom_ids") val romIds: List<Int>?,
)

@Serializable
data class SyncOperationDto(
    /** upload, download, conflict, no_op, delete. */
    val action: String,
    @SerialName("rom_id") val romId: Int,
    @SerialName("save_id") val saveId: Int? = null,
    @SerialName("file_name") val fileName: String = "",
    val slot: String? = null,
    val emulator: String? = null,
    val reason: String? = null,
    @SerialName("server_updated_at") val serverUpdatedAt: String? = null,
    @SerialName("server_content_hash") val serverContentHash: String? = null,
)

@Serializable
data class NegotiateDto(
    @SerialName("session_id") val sessionId: Int,
    val operations: List<SyncOperationDto> = emptyList(),
)

@Serializable
data class SyncCompleteBody(
    @SerialName("operations_completed") val completed: Int,
    @SerialName("operations_failed") val failed: Int,
)

@Serializable
data class ConfirmDownloadBody(
    @SerialName("device_id") val deviceId: String,
    @SerialName("content_hash") val contentHash: String?,
)
