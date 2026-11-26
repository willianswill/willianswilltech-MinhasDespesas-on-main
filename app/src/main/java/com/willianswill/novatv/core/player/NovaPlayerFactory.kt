package com.willianswill.novatv.core.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.willianswill.novatv.core.network.HttpClientProvider

object NovaPlayerFactory {

    @OptIn(UnstableApi::class)
    fun createPlayer(
        context: Context,
        bufferProfile: BufferProfile,
        userAgent: String? = null,
        referrer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        forMultiView: Boolean = false
    ): ExoPlayer {
        val effectiveProfile = if (forMultiView) BufferProfile.LOW else bufferProfile

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                effectiveProfile.minBufferMs,
                effectiveProfile.maxBufferMs,
                effectiveProfile.bufferForPlaybackMs,
                effectiveProfile.bufferForPlaybackAfterRebufferMs
            )
            .setBackBuffer(if (forMultiView) 0 else 5_000, !forMultiView)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val headers = HashMap<String, String>(extraHeaders)
        val ua = userAgent?.takeIf { it.isNotBlank() }
            ?: AppPreferences(context).customUserAgent
        referrer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }

        val dataSourceFactory = OkHttpDataSource.Factory(HttpClientProvider.sharedClient)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    fun buildMediaItem(
        streamUrl: String,
        externalSubtitleUri: Uri? = null,
        externalSubtitleMime: String = MimeTypes.APPLICATION_SUBRIP
    ): MediaItem {
        val builder = MediaItem.Builder().setUri(streamUrl)

        val lower = streamUrl.lowercase().substringBefore('?')
        when {
            lower.endsWith(".m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            lower.endsWith(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
        }

        if (externalSubtitleUri != null) {
            val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(externalSubtitleUri)
                .setMimeType(externalSubtitleMime)
                .setLanguage("pt")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            builder.setSubtitleConfigurations(listOf(subtitleConfig))
        }

        return builder.build()
    }
}
