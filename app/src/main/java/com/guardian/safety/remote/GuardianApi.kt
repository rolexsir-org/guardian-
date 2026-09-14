package com.guardian.safety.remote

import com.squareup.moshi.JsonObject
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Guardian Worker HTTP surface.
 *
 * Endpoints return the raw `{data}` / `{error}` envelope; [ApiClient] decodes it
 * into typed models so a server-side contract change surfaces as a structured
 * error instead of a silent empty state.
 */
interface GuardianApi {

    // ------------------------------------------------------------------- auth

    @POST("v1/auth/register")
    suspend fun register(@Body body: RequestBody): Response<JsonObject>

    @POST("v1/auth/login")
    suspend fun login(@Body body: RequestBody): Response<JsonObject>

    @POST("v1/auth/refresh")
    suspend fun refresh(@Body body: RequestBody): Response<JsonObject>

    @POST("v1/auth/logout")
    suspend fun logout(): Response<JsonObject>

    @POST("v1/auth/logout-all")
    suspend fun logoutAll(): Response<JsonObject>

    @GET("v1/auth/me")
    suspend fun me(): Response<JsonObject>

    @GET("v1/auth/sessions")
    suspend fun sessions(): Response<JsonObject>

    @DELETE("v1/auth/sessions/{sessionId}")
    suspend fun revokeSession(@Path("sessionId") sessionId: String): Response<JsonObject>

    // ---------------------------------------------------------------- profile

    @GET("v1/me/profile")
    suspend fun profile(): Response<JsonObject>

    @PUT("v1/me/profile")
    suspend fun updateProfile(@Body body: RequestBody): Response<JsonObject>

    @POST("v1/me/presence")
    suspend fun recordPresence(@Body body: RequestBody): Response<JsonObject>

    @GET("v1/me/emergency-contacts")
    suspend fun emergencyContacts(): Response<JsonObject>

    @POST("v1/me/emergency-contacts")
    suspend fun addEmergencyContact(@Body body: RequestBody): Response<JsonObject>

    @PATCH("v1/me/emergency-contacts/{contactId}")
    suspend fun updateEmergencyContact(
        @Path("contactId") contactId: String,
        @Body body: RequestBody,
    ): Response<JsonObject>

    @DELETE("v1/me/emergency-contacts/{contactId}")
    suspend fun deleteEmergencyContact(@Path("contactId") contactId: String): Response<JsonObject>

    // ----------------------------------------------------------------- family

    @GET("v1/family/groups")
    suspend fun groups(): Response<JsonObject>

    @POST("v1/family/groups")
    suspend fun createGroup(@Body body: RequestBody): Response<JsonObject>

    @POST("v1/family/groups/{groupId}/invites")
    suspend fun createInvite(
        @Path("groupId") groupId: String,
        @Body body: RequestBody,
    ): Response<JsonObject>

    @POST("v1/family/join")
    suspend fun joinGroup(@Body body: RequestBody): Response<JsonObject>

    @GET("v1/family/groups/{groupId}/members")
    suspend fun members(@Path("groupId") groupId: String): Response<JsonObject>

    @PATCH("v1/family/groups/{groupId}/members/{memberId}")
    suspend fun updateMember(
        @Path("groupId") groupId: String,
        @Path("memberId") memberId: String,
        @Body body: RequestBody,
    ): Response<JsonObject>

    @DELETE("v1/family/groups/{groupId}/members/{memberId}")
    suspend fun removeMember(
        @Path("groupId") groupId: String,
        @Path("memberId") memberId: String,
    ): Response<JsonObject>

    @GET("v1/family/groups/{groupId}/messages")
    suspend fun messages(
        @Path("groupId") groupId: String,
        @Query("since") since: Long?,
    ): Response<JsonObject>

    @POST("v1/family/groups/{groupId}/messages")
    suspend fun sendMessage(
        @Path("groupId") groupId: String,
        @Body body: RequestBody,
    ): Response<JsonObject>

    @GET("v1/family/groups/{groupId}/locations")
    suspend fun groupLocations(@Path("groupId") groupId: String): Response<JsonObject>

    @POST("v1/family/groups/{groupId}/locations")
    suspend fun shareLocation(
        @Path("groupId") groupId: String,
        @Body body: RequestBody,
    ): Response<JsonObject>

    @GET("v1/family/realtime-scopes")
    suspend fun realtimeScopes(): Response<JsonObject>

    // --------------------------------------------------------------- check-ins

    @GET("v1/me/checkins")
    suspend fun checkins(): Response<JsonObject>

    @POST("v1/me/checkins")
    suspend fun startCheckin(@Body body: RequestBody): Response<JsonObject>

    @PATCH("v1/me/checkins/{checkinId}")
    suspend fun completeCheckin(
        @Path("checkinId") checkinId: String,
        @Body body: RequestBody,
    ): Response<JsonObject>

    // -------------------------------------------------------------------- SOS

    @GET("v1/sos")
    suspend fun sosHistory(): Response<JsonObject>

    @POST("v1/sos")
    suspend fun createSos(@Body body: RequestBody): Response<JsonObject>

    @GET("v1/sos/{sosId}")
    suspend fun sosEvent(@Path("sosId") sosId: String): Response<JsonObject>

    @POST("v1/sos/{sosId}/acknowledge")
    suspend fun acknowledgeSos(@Path("sosId") sosId: String): Response<JsonObject>

    @POST("v1/sos/{sosId}/resolve")
    suspend fun resolveSos(
        @Path("sosId") sosId: String,
        @Body body: RequestBody,
    ): Response<JsonObject>

    @GET("v1/family/groups/{groupId}/sos/active")
    suspend fun activeSos(@Path("groupId") groupId: String): Response<JsonObject>

    // ----------------------------------------------------------- safety events

    @GET("v1/safety-events")
    suspend fun safetyEvents(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("radiusMeters") radiusMeters: Int,
        @Query("kind") kind: String? = null,
        @Query("limit") limit: Int = 100,
    ): Response<JsonObject>

    @POST("v1/safety-events")
    suspend fun reportSafetyEvent(@Body body: RequestBody): Response<JsonObject>

    @POST("v1/safety-events/{eventId}/votes")
    suspend fun confirmSafetyEvent(@Path("eventId") eventId: String): Response<JsonObject>

    @DELETE("v1/safety-events/{eventId}/votes")
    suspend fun withdrawSafetyEventVote(@Path("eventId") eventId: String): Response<JsonObject>

    // ---------------------------------------------------------------- evidence

    @GET("v1/evidence")
    suspend fun evidenceFiles(): Response<JsonObject>

    @POST("v1/evidence")
    suspend fun uploadEvidence(
        @Body body: RequestBody,
        @Header("Content-Type") contentType: String,
        @Header("X-File-Name") fileName: String,
        @Header("X-Sos-Event-Id") sosEventId: String?,
    ): Response<JsonObject>

    @DELETE("v1/evidence/{evidenceId}")
    suspend fun deleteEvidence(@Path("evidenceId") evidenceId: String): Response<JsonObject>
}
