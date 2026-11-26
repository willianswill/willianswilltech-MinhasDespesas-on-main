package com.willianswill.novatv.core.player

import java.io.Serializable

data class QualityVariant(
    val label: String,
    val rank: Int, // 1=SD, 2=HD, 3=FHD, 4=4K
    val streamUrl: String,
    val userAgent: String? = null,
    val referrer: String? = null
) : Serializable

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
    val isFavorite: Boolean = false,
    val variants: List<QualityVariant> = emptyList(),
    val seriesId: String = "",
    val seasonNum: Int = 0,
    val episodeNum: Int = 0
) : Serializable
