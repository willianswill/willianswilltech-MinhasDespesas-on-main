package com.willianswill.novatv.core.network

import android.annotation.SuppressLint
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object HttpClientProvider {
    const val DEFAULT_USER_AGENT = "NovaTV/1.0 (Linux; Android TV) VLC/3.0.20"

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val permissiveTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val sharedClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 60, TimeUnit.SECONDS))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)

        // Permite conexão com servidores IPTV que usam certificados SSL auto-assinados ou cadeia incompleta
        try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(permissiveTrustManager), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, permissiveTrustManager)
            builder.hostnameVerifier { _, _ -> true }
        } catch (_: Exception) {
        }

        builder.build()
    }
}
