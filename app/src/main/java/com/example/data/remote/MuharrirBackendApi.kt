package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

@JsonClass(generateAdapter = true)
data class CreateUploadRequestDto(
    @Json(name = "client_document_id") val clientDocumentId: String,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "file_size_bytes") val fileSizeBytes: Long,
    @Json(name = "page_count") val pageCount: Int?,
)

@JsonClass(generateAdapter = true)
data class CreateUploadResponseDto(
    @Json(name = "upload_id") val uploadId: String,
    @Json(name = "remote_document_id") val remoteDocumentId: String,
    @Json(name = "chunk_size_bytes") val chunkSizeBytes: Long,
)

@JsonClass(generateAdapter = true)
data class UploadStatusResponseDto(
    @Json(name = "upload_id") val uploadId: String,
    @Json(name = "bytes_received") val bytesReceived: Long,
    @Json(name = "completed") val completed: Boolean,
)

@JsonClass(generateAdapter = true)
data class RemoteDocumentMetadataDto(
    @Json(name = "remote_document_id") val remoteDocumentId: String,
    @Json(name = "file_name") val fileName: String,
    @Json(name = "file_size_bytes") val fileSizeBytes: Long,
    @Json(name = "page_count") val pageCount: Int?,
    @Json(name = "created_at_iso") val createdAtIso: String,
)

@JsonClass(generateAdapter = true)
data class RemoteProcessingStatusDto(
    @Json(name = "remote_document_id") val remoteDocumentId: String,
    @Json(name = "stage") val stage: String,
    @Json(name = "processed_pages") val processedPages: Int,
    @Json(name = "total_pages") val totalPages: Int,
    @Json(name = "error_message") val errorMessage: String?,
)

/**
 * Clean Retrofit backend API contract prepared for future server integration.
 * No backend service is deployed in the current environment; calls are not invoked until configured.
 */
interface MuharrirBackendApi {
    @POST("api/v1/documents/uploads")
    suspend fun createDocumentUpload(
        @Body request: CreateUploadRequestDto,
    ): Response<CreateUploadResponseDto>

    @PUT("api/v1/documents/uploads/{uploadId}/file")
    suspend fun uploadPdfContent(
        @Path("uploadId") uploadId: String,
        @Header("Content-Range") contentRange: String?,
        @Body pdfStreamBody: RequestBody,
    ): Response<UploadStatusResponseDto>

    @GET("api/v1/documents/uploads/{uploadId}/status")
    suspend fun getResumableUploadStatus(
        @Path("uploadId") uploadId: String,
    ): Response<UploadStatusResponseDto>

    @GET("api/v1/documents/{documentId}")
    suspend fun getDocumentMetadata(
        @Path("documentId") remoteDocumentId: String,
    ): Response<RemoteDocumentMetadataDto>

    @GET("api/v1/documents/{documentId}/status")
    suspend fun getProcessingStatus(
        @Path("documentId") remoteDocumentId: String,
    ): Response<RemoteProcessingStatusDto>
}
