package com.willianswill.iptv.model

import java.io.Serializable

data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = "Geral",
    val tvgId: String = "",
    val userAgent: String? = null,
    val referrer: String? = null,
    var isFavorite: Boolean = false
) : Serializable
