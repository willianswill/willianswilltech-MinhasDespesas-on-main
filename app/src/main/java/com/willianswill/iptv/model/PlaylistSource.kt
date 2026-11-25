package com.willianswill.iptv.model

data class PlaylistSource(
    val id: String,
    val name: String,
    val sourceUrl: String,
    val isLocal: Boolean,
    val channelCount: Int,
    val updatedAt: Long
)
