package com.willianswill.novatv.core.network

import android.annotation.SuppressLint
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object HttpClientProvider {
    const val DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val permissiveTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    // Prioriza IPv4 e usa Google DoH como fallback se o DNS da operadora bloquear/falhar
    private val resilientDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val systemResult = runCatching {
                Dns.SYSTEM.lookup(hostname)
                    .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                    .sortedBy { it !is Inet4Address }
            }.getOrNull()

            if (!systemResult.isNullOrEmpty()) {
                return systemResult
            }

            val dohIps = resolveViaGoogleDoh(hostname)
            if (dohIps.isNotEmpty()) return dohIps

            return Dns.SYSTEM.lookup(hostname)
        }

        private fun resolveViaGoogleDoh(hostname: String): List<InetAddress> {
            return runCatching {
                val req = Request.Builder()
                    .url("https://dns.google/resolve?name=$hostname&type=A")
                    .header("Accept", "application/dns-json")
                    .build()
                dohClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return emptyList()
                    val body = resp.body?.string().orEmpty()
                    val answers = JSONObject(body).optJSONArray("Answer") ?: return emptyList()
                    val ips = mutableListOf<InetAddress>()
                    for (i in 0 until answers.length()) {
                        val item = answers.getJSONObject(i)
                        if (item.optInt("type") == 1) {
                            val ip = item.optString("data")
                            if (ip.isNotBlank()) ips.add(InetAddress.getByName(ip))
                        }
                    }
                    ips
                }
            }.getOrDefault(emptyList())
        }
    }

    val sharedClient: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .dns(resilientDns)
            .connectionPool(ConnectionPool(8, 60, TimeUnit.SECONDS))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)

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
