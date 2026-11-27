package com.willianswill.novatv.feature.vod

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.willianswill.novatv.R
import com.willianswill.novatv.core.database.IptvRepository
import com.willianswill.novatv.core.database.entity.MediaType
import com.willianswill.novatv.core.database.entity.ProfileType
import com.willianswill.novatv.core.downloads.DownloadStatus
import com.willianswill.novatv.core.downloads.OfflineDownloadManager
import com.willianswill.novatv.core.network.XtreamApiClient
import com.willianswill.novatv.core.player.PlayableItem
import com.willianswill.novatv.core.player.PlayerActivity
import com.willianswill.novatv.databinding.ActivitySeriesDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SeriesDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERIES_DB_ID = "extra_series_db_id"
        const val EXTRA_SERIES_ID = "extra_series_id"
        const val EXTRA_NAME = "extra_series_name"
        const val EXTRA_COVER = "extra_series_cover"
        const val EXTRA_CATEGORY = "extra_series_cat"
        const val EXTRA_PLOT = "extra_series_plot"
        const val EXTRA_RATING = "extra_series_rating"
        const val EXTRA_IS_FAV = "extra_series_fav"
    }

    private lateinit var binding: ActivitySeriesDetailBinding
    private lateinit var repository: IptvRepository

    private var seriesDbId = 0L
    private var seriesId = ""
    private var seriesName = ""
    private var seriesCover = ""
    private var isFav = false

    private var episodesBySeason: Map<Int, List<EpisodeUiEntry>> = emptyMap()
    private var allEntriesOrdered: List<EpisodeUiEntry> = emptyList()
    private var selectedSeason = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = IptvRepository(this)

        seriesDbId = intent.getLongExtra(EXTRA_SERIES_DB_ID, 0L)
        seriesId = intent.getStringExtra(EXTRA_SERIES_ID).orEmpty()
        seriesName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        seriesCover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        val cat = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
        val plot = intent.getStringExtra(EXTRA_PLOT).orEmpty()
        val rating = intent.getStringExtra(EXTRA_RATING).orEmpty()
        isFav = intent.getBooleanExtra(EXTRA_IS_FAV, false)

        binding.tvSeriesTitle.text = seriesName
        binding.tvSeriesMeta.text = if (rating.isNotBlank() && rating != "0") "$cat • ★ $rating" else cat
        binding.tvSeriesPlot.text = plot.ifBlank { "Selecione uma temporada e episódio abaixo." }
        updateFavUi()

        if (seriesCover.isNotBlank()) {
            Glide.with(this).load(seriesCover).placeholder(R.drawable.ic_tv_logo).into(binding.ivSeriesPoster)
            Glide.with(this).load(seriesCover).placeholder(R.drawable.ic_tv_logo).into(binding.ivSeriesBackdrop)
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnFavSeries.setOnClickListener {
            isFav = !isFav
            updateFavUi()
            lifecycleScope.launch { repository.toggleSeriesFavorite(seriesDbId, isFav) }
        }
        binding.btnResumeSeries.setOnClickListener {
            if (allEntriesOrdered.isEmpty()) return@setOnClickListener
            val lastIdx = allEntriesOrdered.indexOfLast { it.playable.resumePositionMs > 5_000L }
                .takeIf { it >= 0 } ?: 0
            launchWithResumeChoice(allEntriesOrdered.map { it.playable }, lastIdx)
        }

        binding.rvSeasons.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvEpisodes.layoutManager = LinearLayoutManager(this)
    }

    override fun onResume() {
        super.onResume()
        loadEpisodes()
    }

    private fun updateFavUi() {
        binding.btnFavSeries.text = if (isFav) "★ Favorita" else "☆ Favoritar"
    }

    private fun loadEpisodes() {
        binding.progressLoading.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val profile = repository.observeActiveProfile().firstOrNull() ?: return@launch
                val historyMap = repository.getSeriesHistory(profile.id, seriesId)
                    .associateBy { it.contentKey }

                val rawEntries: List<Pair<Int, EpisodeUiEntry>> =
                    if (profile.type == ProfileType.XTREAM.name && !seriesId.startsWith("m3u_")) {
                        val client = XtreamApiClient()
                        val res = client.getSeriesInfo(
                            profile.serverUrl,
                            profile.username,
                            profile.password,
                            seriesId,
                            profile.userAgent.takeIf { it.isNotBlank() }
                        )

                        res.episodes.entries
                            .sortedBy { it.key.toIntOrNull() ?: 999 }
                            .flatMap { (seasonKey, epList) ->
                                val sNum = seasonKey.toIntOrNull() ?: 1
                                epList.sortedBy { it.episodeNum }.map { ep ->
                                    val ext = ep.containerExtension.orEmpty().ifBlank { "mp4" }
                                    val epUrl = client.buildSeriesEpisodeUrl(profile.serverUrl, profile.username, profile.password, ep.id, ext)
                                    val saved = historyMap[epUrl]
                                    val pct = if (saved != null && saved.durationMs > 0L) {
                                        ((saved.positionMs * 100) / saved.durationMs).toInt().coerceIn(1, 100)
                                    } else 0

                                    val entry = EpisodeUiEntry(
                                        playable = PlayableItem(
                                            contentKey = epUrl,
                                            title = "$seriesName • T${ep.season}E${ep.episodeNum}",
                                            subtitle = ep.title,
                                            streamUrl = epUrl,
                                            iconUrl = ep.info?.movieImage ?: seriesCover,
                                            mediaType = MediaType.SERIES,
                                            streamId = ep.id,
                                            userAgent = profile.userAgent.takeIf { it.isNotBlank() },
                                            resumePositionMs = saved?.positionMs ?: 0L,
                                            seriesId = seriesId,
                                            seasonNum = ep.season,
                                            episodeNum = ep.episodeNum
                                        ),
                                        progressPercent = pct
                                    )
                                    sNum to entry
                                }
                            }
                    } else {
                        repository.getM3uSeriesEpisodes(profile.id, seriesId).map { ep ->
                            val saved = historyMap[ep.streamUrl]
                            val pct = if (saved != null && saved.durationMs > 0L) {
                                ((saved.positionMs * 100) / saved.durationMs).toInt().coerceIn(1, 100)
                            } else 0
                            val parts = ep.epgChannelId.split(":")
                            val sNum = parts.getOrNull(0)?.toIntOrNull() ?: 1
                            val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 1

                            sNum to EpisodeUiEntry(
                                playable = PlayableItem(
                                    contentKey = ep.streamUrl,
                                    title = "$seriesName • ${ep.qualityLabel}",
                                    subtitle = ep.name,
                                    streamUrl = ep.streamUrl,
                                    iconUrl = ep.iconUrl.ifBlank { seriesCover },
                                    mediaType = MediaType.SERIES,
                                    streamId = ep.streamId,
                                    userAgent = ep.userAgent,
                                    referrer = ep.referrer,
                                    resumePositionMs = saved?.positionMs ?: 0L,
                                    seriesId = seriesId,
                                    seasonNum = sNum,
                                    episodeNum = epNum
                                ),
                                progressPercent = pct
                            )
                        }
                    }

                withContext(Dispatchers.Main) {
                    binding.progressLoading.visibility = View.GONE
                    allEntriesOrdered = rawEntries.map { it.second }
                    episodesBySeason = rawEntries.groupBy({ it.first }, { it.second })

                    val seasons = episodesBySeason.keys.sorted()
                    if (seasons.isEmpty()) {
                        Toast.makeText(this@SeriesDetailActivity, "Nenhum episódio encontrado.", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    if (!seasons.contains(selectedSeason)) {
                        selectedSeason = seasons.first()
                    }
                    binding.rvSeasons.adapter = SeasonTabAdapter(seasons, selectedSeason) { s ->
                        selectedSeason = s
                        renderSeasonEpisodes(s)
                    }
                    renderSeasonEpisodes(selectedSeason)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressLoading.visibility = View.GONE
                    Toast.makeText(this@SeriesDetailActivity, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renderSeasonEpisodes(season: Int) {
        val list = episodesBySeason[season].orEmpty()
        binding.rvEpisodes.adapter = EpisodeProgressAdapter(
            items = list,
            onSelect = { idxInSeason ->
                val clicked = list[idxInSeason]
                val globalIdx = allEntriesOrdered.indexOfFirst { it.playable.contentKey == clicked.playable.contentKey }
                    .coerceAtLeast(0)
                launchWithResumeChoice(allEntriesOrdered.map { it.playable }, globalIdx)
            },
            onLongSelect = { idxInSeason ->
                val clicked = list[idxInSeason].playable
                val existing = OfflineDownloadManager.findByUrl(this, clicked.streamUrl)
                when (existing?.status) {
                    DownloadStatus.COMPLETED -> {
                        AlertDialog.Builder(this)
                            .setTitle(clicked.title)
                            .setMessage("Este episódio já está baixado para assistir offline.")
                            .setPositiveButton("▶ Assistir Offline") { _, _ -> openPlayer(listOf(clicked), 0) }
                            .setNegativeButton("🗑 Excluir Download") { _, _ ->
                                OfflineDownloadManager.deleteDownload(this, existing.id)
                                Toast.makeText(this, "Download removido.", Toast.LENGTH_SHORT).show()
                            }
                            .show()
                    }
                    DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                        Toast.makeText(this, "Episódio já está baixando (veja em ⬇ Offline).", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        AlertDialog.Builder(this)
                            .setTitle(clicked.title)
                            .setMessage("Deseja baixar este episódio para assistir offline?")
                            .setPositiveButton("⬇ Baixar Offline") { _, _ ->
                                OfflineDownloadManager.enqueueDownload(this, clicked)
                                Toast.makeText(this, "⬇ Baixando ${clicked.title}...", Toast.LENGTH_LONG).show()
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                }
            }
        )
    }

    private fun launchWithResumeChoice(list: List<PlayableItem>, idx: Int) {
        val item = list[idx]
        if (item.resumePositionMs > 5_000L) {
            val timeStr = formatTime(item.resumePositionMs)
            AlertDialog.Builder(this)
                .setTitle(item.title)
                .setMessage("Você já assistiu até $timeStr. Como deseja continuar?")
                .setPositiveButton("▶ Continuar ($timeStr)") { _, _ -> openPlayer(list, idx) }
                .setNeutralButton("↺ Do início") { _, _ ->
                    val resetList = list.toMutableList()
                    resetList[idx] = item.copy(resumePositionMs = 0L)
                    openPlayer(resetList, idx)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            openPlayer(list, idx)
        }
    }

    private fun openPlayer(list: List<PlayableItem>, idx: Int) {
        val adjusted = list.mapIndexed { i, item ->
            if (i == idx) {
                val localUri = OfflineDownloadManager.findCompletedLocalUri(this, item.streamUrl)
                if (localUri != null) item.copy(streamUrl = localUri) else item
            } else item
        }
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_ITEMS, ArrayList(adjusted))
                putExtra(PlayerActivity.EXTRA_INDEX, idx)
            }
        )
    }

    private fun formatTime(ms: Long): String {
        val sec = (ms / 1000L).coerceAtLeast(0L)
        val h = sec / 3600L
        val m = (sec % 3600L) / 60L
        val s = sec % 60L
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        else String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }

    private class SeasonTabAdapter(
        private val seasons: List<Int>,
        private var selected: Int,
        private val onSelect: (Int) -> Unit
    ) : RecyclerView.Adapter<SeasonTabAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = LayoutInflater.from(parent.context).inflate(R.layout.item_category_tab, parent, false) as TextView
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = seasons[position]
            holder.tv.text = "Temporada $s"
            holder.tv.isSelected = s == selected
            holder.tv.setOnClickListener {
                selected = s
                notifyDataSetChanged()
                onSelect(s)
            }
        }

        override fun getItemCount(): Int = seasons.size
        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
    }
}
