package com.willianswill.novatv.core.network

import okhttp3.Request

/**
 * Estrutura base preparada para expansão completa do protocolo Stalker Portal / MAC Address.
 */
interface StalkerPortalGateway {
    suspend fun handshake(portalUrl: String, macAddress: String): String
    suspend fun getAccountInfo(portalUrl: String, macAddress: String, token: String): Map<String, String>
}

class StalkerClient : StalkerPortalGateway {

    override suspend fun handshake(portalUrl: String, macAddress: String): String {
        val normalized = portalUrl.trim().trimEnd('/')
        val url = "$normalized/portal.php?type=stb&action=handshake&token=&JsHttpRequest=1-xml"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux; C) MAG200 stbapp")
            .header("Cookie", "mac=$macAddress; stb_lang=en; timezone=America/Sao_Paulo")
            .build()

        HttpClientProvider.sharedClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Erro HTTP ${response.code} no handshake Stalker")
            }
            return response.body?.string().orEmpty()
        }
    }

    override suspend fun getAccountInfo(portalUrl: String, macAddress: String, token: String): Map<String, String> {
        return mapOf(
            "portal" to portalUrl,
            "mac" to macAddress,
            "status" to if (token.isNotBlank()) "connected" else "pending"
        )
    }
}
