package com.willianswill.novatv.core.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.willianswill.novatv.core.network.HttpClientProvider

object NovaPlayerFactory {

    @OptIn(UnstableApi::class)
    fun getBandwidthMeter(context: Context): DefaultBandwidthMeter =
        DefaultBandwidthMeter.getSingletonInstance(context.applicationContext)

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
            .setBackBuffer(if (forMultiView) 0 else 30_000, !forMultiView)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val headers = HashMap<String, String>(extraHeaders)
        val ua = userAgent?.takeIf { it.isNotBlank() }
            ?: AppPreferences(context).customUserAgent
        referrer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }

        val httpDataSourceFactory = OkHttpDataSource.Factory(HttpClientProvider.playerClient)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)
        val dataSourceFactory = DefaultDataSource.Factory(context.applicationContext, httpDataSourceFactory)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setBandwidthMeter(getBandwidthMeter(context))
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().apply {
                // Seek rápido para o keyframe mais próximo sem travar a UI durante múltiplos seeks
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
            }
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
