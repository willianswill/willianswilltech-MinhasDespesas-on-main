package com.willianswill.novatv.core.player

import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer

data class TrackOption(
    val group: TrackGroup,
    val trackIndex: Int,
    val label: String,
    val isSelected: Boolean
)

object TrackManager {

    fun getAudioTracks(player: ExoPlayer): List<TrackOption> =
        extractTracks(player, C.TRACK_TYPE_AUDIO, "Áudio")

    fun getSubtitleTracks(player: ExoPlayer): List<TrackOption> =
        extractTracks(player, C.TRACK_TYPE_TEXT, "Legenda")

    private fun extractTracks(player: ExoPlayer, trackType: Int, prefix: String): List<TrackOption> {
        val result = mutableListOf<TrackOption>()
        var counter = 1
        for (groupInfo in player.currentTracks.groups) {
            if (groupInfo.type != trackType) continue
            for (i in 0 until groupInfo.length) {
                if (!groupInfo.isTrackSupported(i)) continue
                val format = groupInfo.getTrackFormat(i)
                val lang = format.language?.takeIf { it.isNotBlank() && it != "und" } ?: ""
                val title = format.label?.takeIf { it.isNotBlank() }
                    ?: if (lang.isNotEmpty()) "$prefix $counter (${lang.uppercase()})" else "$prefix $counter"
                result.add(
                    TrackOption(
                        group = groupInfo.mediaTrackGroup,
                        trackIndex = i,
                        label = title,
                        isSelected = groupInfo.isTrackSelected(i)
                    )
                )
                counter++
            }
        }
        return result
    }

    fun selectTrack(player: ExoPlayer, option: TrackOption, trackType: Int) {
        val params = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(option.group, option.trackIndex))
            .build()
        player.trackSelectionParameters = params
    }

    fun disableSubtitles(player: ExoPlayer) {
        val params = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        player.trackSelectionParameters = params
    }
}
