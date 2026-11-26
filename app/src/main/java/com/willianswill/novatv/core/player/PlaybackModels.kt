package com.willianswill.novatv.core.player

import java.io.Serializable

data class PlayableItem(
    val contentKey: String,
    val title: String,
    val subtitle: String = "",
    val streamUrl: String,
    val iconUrl: String = "",
    val mediaType: String = "LIVE",
    val streamId: String = "",
    val epgChannelId: String = "",
    val hasCatchup: Boolean = false,
    val catchupDays: Int = 0,
    val userAgent: String? = null,
    val referrer: String? = null,
    val resumePositionMs: Long = 0L,
    val streamDbId: Long = 0L,
    val isFavorite: Boolean = false
) : Serializable
