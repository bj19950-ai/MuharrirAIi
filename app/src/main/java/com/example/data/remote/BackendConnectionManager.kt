package com.example.data.remote

import com.example.domain.model.BackendConnectionState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Manages backend endpoint validation and Retrofit client instantiation.
 * In Module 02, no remote backend is deployed by default, so local document management
 * remains fully autonomous and no data leaves the device.
 */
class BackendConnectionManager(
    private val configuredBaseUrl: String = "",
) {
    val connectionState: BackendConnectionState
        get() = if (isValidHttpsEndpoint(configuredBaseUrl)) {
            BackendConnectionState.Configured(configuredBaseUrl)
        } else {
            BackendConnectionState.NotConfigured(
                reasonAr = "لم يتم ضبط خادم معالجة خلفي بعد. تعمل إدارة المستندات وتخزينها محلياً بشكل مستقل في التخزين الخاص للتطبيق.",
            )
        }

    fun isConfigured(): Boolean = connectionState is BackendConnectionState.Configured

    fun createApiOrNull(): MuharrirBackendApi? {
        if (!isValidHttpsEndpoint(configuredBaseUrl)) return null

        val logging = HttpLoggingInterceptor().apply {
            // Never log document payload bodies or sensitive headers.
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val okHttp = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()

        val normalizedUrl = if (configuredBaseUrl.endsWith("/")) {
            configuredBaseUrl
        } else {
            "$configuredBaseUrl/"
        }

        return Retrofit.Builder()
            .baseUrl(normalizedUrl)
            .client(okHttp)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(MuharrirBackendApi::class.java)
    }

    private fun isValidHttpsEndpoint(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("https://") && trimmed.length > "https://".length
    }
}
