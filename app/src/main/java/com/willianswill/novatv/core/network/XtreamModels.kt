package com.willianswill.novatv.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class XtreamAuthResponse(
    @SerialName("user_info") val userInfo: XtreamUserInfo? = null,
    @SerialName("server_info") val serverInfo: XtreamServerInfo? = null
)

@Serializable
data class XtreamUserInfo(
    val username: String = "",
    val status: String = "",
    @SerialName("exp_date") val expDate: String? = null,
    @SerialName("max_connections") val maxConnections: String? = null,
    @SerialName("active_cons") val activeCons: String? = null
)

@Serializable
data class XtreamServerInfo(
    val url: String = "",
    val port: String = "",
    @SerialName("https_port") val httpsPort: String = "",
    @SerialName("server_protocol") val serverProtocol: String = "http"
)

@Serializable
data class XtreamCategoryDto(
    @SerialName("category_id") val categoryId: String = "",
    @SerialName("category_name") val categoryName: String = "Geral"
)

@Serializable
data class XtreamLiveDto(
    @SerialName("stream_id") val streamId: Long = 0L,
    val name: String = "",
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("epg_channel_id") val epgChannelId: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("tv_archive") val tvArchive: Int = 0,
    @SerialName("tv_archive_duration") val tvArchiveDuration: Int = 0,
    val added: String? = null
)

@Serializable
data class XtreamVodDto(
    @SerialName("stream_id") val streamId: Long = 0L,
    val name: String = "",
    @SerialName("stream_icon") val streamIcon: String? = null,
    @SerialName("category_id") val categoryId: String? = null,
    @SerialName("container_extension") val containerExtension: String? = "mp4",
    val rating: JsonElement? = null,
    val added: String? = null
)

@Serializable
data class XtreamSeriesDto(
    @SerialName("series_id") val seriesId: kotlinx.serialization.json.JsonPrimitive? = null,
    val name: String = "",
    val cover: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    @SerialName("last_modified") val lastModified: String? = null,
    val rating: JsonElement? = null,
    @SerialName("category_id") val categoryId: String? = null
)

@Serializable
data class XtreamSeriesInfoResponse(
    val info: XtreamSeriesDetailDto? = null,
    val episodes: Map<String, List<XtreamEpisodeDto>> = emptyMap()
)

@Serializable
data class XtreamSeriesDetailDto(
    val name: String = "",
    val cover: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val rating: JsonElement? = null
)

@Serializable
data class XtreamEpisodeDto(
    val id: String = "",
    @SerialName("episode_num") val episodeNum: Int = 0,
    val title: String = "",
    @SerialName("container_extension") val containerExtension: String? = "mp4",
    val season: Int = 1,
    val info: XtreamEpisodeInfoDto? = null
)

@Serializable
data class XtreamEpisodeInfoDto(
    @SerialName("movie_image") val movieImage: String? = null,
    val plot: String? = null,
    val duration: String? = null
)
