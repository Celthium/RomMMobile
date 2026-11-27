package com.rommmobile.app.data.api

import kotlinx.serialization.json.JsonElement
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * RomM 5.x contract, verified against the user's server `/openapi.json` (5.2.0).
 * Paths are relative to the placeholder base; [com.rommmobile.app.core.network.HostSelectionInterceptor]
 * swaps in the real host.
 */
interface RommApi {

    @GET("api/heartbeat")
    suspend fun heartbeat(): HeartbeatDto

    /** Returns JSON `null` when no session: parsed by hand for that reason. */
    @GET("api/users/me")
    suspend fun me(): JsonElement

    @FormUrlEncoded
    @POST("api/token")
    suspend fun token(
        @Field("grant_type") grantType: String,
        @Field("username") username: String? = null,
        @Field("password") password: String? = null,
        @Field("scope") scope: String? = null,
        @Field("refresh_token") refreshToken: String? = null,
    ): TokenDto

    @POST("api/client-tokens/exchange")
    suspend fun exchangePairCode(@Body body: ExchangeBody): ClientTokenCreateDto

    @POST("api/auth/device/init")
    suspend fun deviceInit(@Body body: DeviceInitBody): DeviceAuthInitDto

    @POST("api/auth/device/token")
    suspend fun deviceToken(@Body body: DeviceTokenBody): DeviceAuthTokenDto

    @GET("api/platforms")
    suspend fun platforms(): List<PlatformDto>

    /**
     * `with_rom_id_index` defaults to true server-side and would ship the id of every ROM of the
     * platform with each page: always off. `char_index` and `total` only on the first page.
     */
    @GET("api/roms")
    suspend fun roms(
        @Query("platform_ids") platformIds: List<Int>? = null,
        @Query("collection_id") collectionId: Int? = null,
        @Query("virtual_collection_id") virtualCollectionId: String? = null,
        @Query("smart_collection_id") smartCollectionId: Int? = null,
        @Query("search_term") searchTerm: String? = null,
        @Query("regions") regions: List<String>? = null,
        @Query("order_by") orderBy: String? = "name",
        @Query("order_dir") orderDir: String = "asc",
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("group_by_meta_id") groupByMetaId: Boolean = true,
        @Query("with_char_index") withCharIndex: Boolean,
        @Query("with_total") withTotal: Boolean,
        @Query("with_filter_values") withFilterValues: Boolean = false,
        @Query("with_rom_id_index") withRomIdIndex: Boolean = false,
        @Query("with_files") withFiles: Boolean = false,
    ): RomPageDto

    @GET("api/roms/{id}")
    suspend fun rom(@Path("id") id: Int): RomDto

    @GET("api/collections")
    suspend fun collections(): List<CollectionDto>

    @GET("api/collections/virtual")
    suspend fun virtualCollections(@Query("type") type: String, @Query("limit") limit: Int? = null): List<VirtualCollectionDto>

    @GET("api/collections/smart")
    suspend fun smartCollections(): List<SmartCollectionDto>

    @GET("api/firmware")
    suspend fun firmware(@Query("platform_id") platformId: Int? = null): List<FirmwareDto>

    /* ---------------- saves, states and sync (RomM 5.4, docs/04 section 9) ---------------- */

    /** The user's devices; read only to learn whether the session has `devices.read`. */
    @GET("api/devices")
    suspend fun devices(): JsonElement

    @POST("api/devices")
    suspend fun createDevice(@Body body: DeviceCreateBody): DeviceCreateDto

    @GET("api/saves")
    suspend fun saves(@Query("rom_id") romId: Int? = null, @Query("device_id") deviceId: String? = null): List<SaveDto>

    @Multipart
    @POST("api/saves")
    suspend fun uploadSave(
        @Query("rom_id") romId: Int,
        @Query("emulator") emulator: String?,
        @Query("slot") slot: String?,
        @Query("device_id") deviceId: String?,
        @Query("content_hash") contentHash: String?,
        @Query("session_id") sessionId: Int?,
        @Query("overwrite") overwrite: Boolean,
        @Query("autocleanup") autocleanup: Boolean,
        @Query("autocleanup_limit") autocleanupLimit: Int,
        @Part saveFile: MultipartBody.Part,
    ): SaveDto

    @Streaming
    @GET("api/saves/{id}/content")
    suspend fun saveContent(
        @Path("id") id: Int,
        @Query("device_id") deviceId: String?,
        @Query("session_id") sessionId: Int?,
        @Query("optimistic") optimistic: Boolean,
    ): ResponseBody

    @POST("api/saves/{id}/downloaded")
    suspend fun confirmSaveDownloaded(@Path("id") id: Int, @Body body: ConfirmDownloadBody): SaveDto

    @POST("api/sync/negotiate")
    suspend fun negotiate(@Body body: NegotiateBody): NegotiateDto

    @POST("api/sync/sessions/{id}/complete")
    suspend fun completeSync(@Path("id") id: Int, @Body body: SyncCompleteBody): JsonElement

    @GET("api/states")
    suspend fun states(@Query("rom_id") romId: Int): List<StateDto>

    @Multipart
    @POST("api/states")
    suspend fun uploadState(@Query("rom_id") romId: Int, @Query("emulator") emulator: String?, @Part stateFile: MultipartBody.Part): StateDto

    @Multipart
    @PUT("api/states/{id}")
    suspend fun replaceState(@Path("id") id: Int, @Part stateFile: MultipartBody.Part): StateDto

    @Streaming
    @GET("api/states/{id}/content")
    suspend fun stateContent(@Path("id") id: Int): ResponseBody

    /* ---------------- game page extras: achievements and collections ---------------- */

    @GET("api/roms/{id}")
    suspend fun romExtras(@Path("id") id: Int): RomExtrasDto

    @GET("api/users/me")
    suspend fun meRa(): MeRaDto

    @POST("api/users/{id}/ra/refresh")
    suspend fun raRefresh(@Path("id") userId: Int, @Body body: RaRefreshBody): JsonElement

    @POST("api/collections/{id}/roms")
    suspend fun addToCollection(@Path("id") id: Int, @Body body: CollectionRomsBody): JsonElement

    @HTTP(method = "DELETE", path = "api/collections/{id}/roms", hasBody = true)
    suspend fun removeFromCollection(@Path("id") id: Int, @Body body: CollectionRomsBody): JsonElement

    /** Creates the user's Favourites collection the first time a game is starred, as the web app does. */
    @Multipart
    @POST("api/collections")
    suspend fun createCollection(@Query("is_favorite") isFavorite: Boolean, @Part("name") name: okhttp3.RequestBody): CollectionDto

    /** Library totals; `include_platform_stats` left off (per-platform numbers come from /platforms). */
    @GET("api/stats")
    suspend fun stats(): StatsDto
}
