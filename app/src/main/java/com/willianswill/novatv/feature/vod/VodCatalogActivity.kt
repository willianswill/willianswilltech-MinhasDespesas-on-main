package com.willianswill.novatv.feature.vod

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.willianswill.novatv.core.database.IptvRepository
import com.willianswill.novatv.core.database.entity.MediaType
import com.willianswill.novatv.core.database.entity.ProfileEntity
import com.willianswill.novatv.core.database.entity.ProfileType
import com.willianswill.novatv.core.database.entity.SeriesEntity
import com.willianswill.novatv.core.database.entity.StreamEntity
import com.willianswill.novatv.core.downloads.DownloadStatus
import com.willianswill.novatv.core.downloads.OfflineDownloadManager
import com.willianswill.novatv.core.network.XtreamApiClient
import com.willianswill.novatv.core.player.AppPreferences
import com.willianswill.novatv.core.player.PlayableItem
import com.willianswill.novatv.core.player.PlayerActivity
import com.willianswill.novatv.core.ui.NavTab
import com.willianswill.novatv.core.ui.QuickNavHelper
import com.willianswill.novatv.databinding.ActivityVodCatalogBinding
import com.willianswill.novatv.feature.livetv.CategoryTabAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class VodCatalogActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MODE = "extra_vod_mode"
    }

    private lateinit var binding: ActivityVodCatalogBinding
    private lateinit var repository: IptvRepository
    private lateinit var prefs: AppPreferences
    private lateinit var categoryAdapter: CategoryTabAdapter
    private lateinit var vodAdapter: VodPagingAdapter
    private lateinit var seriesAdapter: SeriesPagingAdapter

    private var mode: String = MediaType.MOVIE
    private var activeProfile: ProfileEntity? = null
    private var selectedCategoryId: String? = null
    private var onlyFavorites: Boolean = false
    private var searchQuery: String = ""
    private var pagingJob: Job? = null
    private var autoSelectedLaunchCategory = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodCatalogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mode = intent.getStringExtra(EXTRA_MODE) ?: MediaType.MOVIE
        binding.tvVodTitle.text = if (mode == MediaType.SERIES) "Séries" else "Filmes"

        repository = IptvRepository(this)
        prefs = AppPreferences(this)

        setupLists()
        setupActions()
        QuickNavHelper.bind(
            this,
            binding.quickNav.root,
            if (mode == MediaType.SERIES) NavTab.SERIES else NavTab.MOVIES
        )
        observeProfile()
    }

    private fun setupLists() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        categoryAdapter = CategoryTabAdapter { cat ->
            when {
                cat == null -> {
                    selectedCategoryId = null
                    onlyFavorites = false
                }
                cat.categoryId == "__FAV__" -> {
                    selectedCategoryId = null
                    onlyFavorites = true
                }
                else -> {
                    selectedCategoryId = cat.categoryId
                    onlyFavorites = false
                }
            }
            loadPagedCatalog()
        }
        binding.rvCategories.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCategories.adapter = categoryAdapter

        vodAdapter = VodPagingAdapter { movie -> showMovieDetails(movie) }
        seriesAdapter = SeriesPagingAdapter { series ->
            startActivity(
                Intent(this, SeriesDetailActivity::class.java).apply {
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_DB_ID, series.id)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, series.seriesId)
                    putExtra(SeriesDetailActivity.EXTRA_NAME, series.name)
                    putExtra(SeriesDetailActivity.EXTRA_COVER, series.coverUrl)
                    putExtra(SeriesDetailActivity.EXTRA_CATEGORY, series.categoryName)
                    putExtra(SeriesDetailActivity.EXTRA_PLOT, series.plot)
                    putExtra(SeriesDetailActivity.EXTRA_RATING, series.rating)
                    putExtra(SeriesDetailActivity.EXTRA_IS_FAV, series.isFavorite)
                }
            )
        }

        val span = if (isLandscape) 5 else 2
        binding.rvVodGrid.layoutManager = GridLayoutManager(this, span)
        binding.rvVodGrid.adapter = if (mode == MediaType.SERIES) seriesAdapter else vodAdapter

        val updateEmptyState: (Int) -> Unit = { count ->
            binding.emptyVodBox.visibility = if (count == 0) View.VISIBLE else View.GONE
            binding.rvVodGrid.visibility = if (count == 0) View.GONE else View.VISIBLE
        }
        vodAdapter.addLoadStateListener { ls ->
            if (mode != MediaType.SERIES && ls.refresh is LoadState.NotLoading) {
                updateEmptyState(vodAdapter.itemCount)
            }
        }
        seriesAdapter.addLoadStateListener { ls ->
            if (mode == MediaType.SERIES && ls.refresh is LoadState.NotLoading) {
                updateEmptyState(seriesAdapter.itemCount)
            }
        }
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnVodSync.setOnClickListener {
            binding.btnVodSync.isEnabled = false
            binding.btnVodSync.text = "Sincronizando..."
            lifecycleScope.launch {
                try {
                    repository.syncActiveProfile()
                    Toast.makeText(this@VodCatalogActivity, "Catálogo atualizado!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@VodCatalogActivity, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    binding.btnVodSync.isEnabled = true
                    binding.btnVodSync.text = "↻ Sincronizar Lista Agora"
                }
            }
        }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                loadPagedCatalog()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun observeProfile() {
        lifecycleScope.launch {
            repository.observeActiveProfile().collectLatest { profile ->
                if (profile == null) {
                    Toast.makeText(this@VodCatalogActivity, "Adicione uma lista primeiro.", Toast.LENGTH_SHORT).show()
                    finish()
                    return@collectLatest
                }
                activeProfile = profile
                launch {
                    repository.observeCategories(profile.id, mode, prefs.showAdultContent).collectLatest { cats ->
                        categoryAdapter.submitCategories(cats)
                        if (mode == MediaType.MOVIE && !autoSelectedLaunchCategory && cats.isNotEmpty()) {
                            autoSelectedLaunchCategory = true
                            val launchIdx = cats.indexOfFirst { c ->
                                val n = c.name.lowercase(Locale.getDefault())
                                n.contains("lançamento") || n.contains("lancamento") || n.contains("lancamentos")
                            }.takeIf { it >= 0 } ?: cats.indexOfFirst { c ->
                                val n = c.name.lowercase(Locale.getDefault())
                                n.contains("novidade") || n.contains("2025") || n.contains("2026")
                            }
                            if (launchIdx >= 0) {
                                val launchCat = cats[launchIdx]
                                selectedCategoryId = launchCat.categoryId
                                onlyFavorites = false
                                categoryAdapter.setSelectedCategory(launchCat.categoryId)
                                binding.rvCategories.scrollToPosition(launchIdx + 2)
                                loadPagedCatalog()
                            }
                        }
                    }
                }
                loadPagedCatalog()
            }
        }
    }

    private fun loadPagedCatalog() {
        val profile = activeProfile ?: return
        pagingJob?.cancel()
        pagingJob = lifecycleScope.launch {
            if (mode == MediaType.SERIES) {
                repository.pagedSeries(
                    profileId = profile.id,
                    categoryId = selectedCategoryId,
                    onlyFavorites = onlyFavorites,
                    includeAdult = prefs.showAdultContent,
                    query = searchQuery
                ).collectLatest { seriesAdapter.submitData(it) }
            } else {
                repository.pagedStreams(
                    profileId = profile.id,
                    mediaType = MediaType.MOVIE,
                    categoryId = selectedCategoryId,
                    onlyFavorites = onlyFavorites,
                    includeAdult = prefs.showAdultContent,
                    query = searchQuery
                ).collectLatest { vodAdapter.submitData(it) }
            }
        }
    }

    private fun showMovieDetails(movie: StreamEntity) {
        val profile = activeProfile ?: return
        lifecycleScope.launch {
            val saved = repository.getWatchProgress(profile.id, movie.streamUrl)
            val resumeMs = saved?.positionMs ?: 0L
            val favLabel = if (movie.isFavorite) "Remover Favorito" else "★ Favoritar"
            val info = buildString {
                append("Categoria: ${movie.categoryName}\n")
                if (movie.rating.isNotBlank()) append("Nota: ★ ${movie.rating}\n")
                if (resumeMs > 5_000L) append("Progresso salvo: ${formatTime(resumeMs)}\n")
                if (movie.plot.isNotBlank()) append("\n${movie.plot}")
            }

            val baseItem = PlayableItem(
                contentKey = movie.streamUrl,
                title = movie.name,
                subtitle = movie.categoryName,
                streamUrl = movie.streamUrl,
                iconUrl = movie.iconUrl,
                mediaType = MediaType.MOVIE,
                streamId = movie.streamId,
                userAgent = movie.userAgent,
                referrer = movie.referrer,
                resumePositionMs = resumeMs,
                streamDbId = movie.id,
                isFavorite = movie.isFavorite
            )

            val existingDl = OfflineDownloadManager.findByUrl(this@VodCatalogActivity, movie.streamUrl)
            val dlLabel = when (existingDl?.status) {
                DownloadStatus.COMPLETED -> "✓ Offline (Excluir)"
                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> "⏳ Baixando..."
                else -> "⬇ Baixar Offline"
            }

            val favBtn = android.widget.Button(this@VodCatalogActivity).apply {
                text = favLabel
                isFocusable = true
                setOnClickListener {
                    lifecycleScope.launch {
                        repository.toggleStreamFavorite(movie.id, !movie.isFavorite)
                        Toast.makeText(
                            this@VodCatalogActivity,
                            if (!movie.isFavorite) "★ Adicionado aos Favoritos" else "☆ Removido dos Favoritos",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            val msgView = android.widget.TextView(this@VodCatalogActivity).apply {
                text = info
                setPadding(48, 24, 48, 12)
                textSize = 14f
            }
            val container = android.widget.LinearLayout(this@VodCatalogActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(msgView)
                addView(favBtn)
            }

            val dialog = AlertDialog.Builder(this@VodCatalogActivity)
                .setTitle(movie.name)
                .setView(container)
                .setPositiveButton("▶ Assistir") { _, _ ->
                    launchWithResumeChoice(listOf(baseItem), 0)
                }
                .setNeutralButton(dlLabel) { _, _ ->
                    when (existingDl?.status) {
                        DownloadStatus.COMPLETED -> {
                            OfflineDownloadManager.deleteDownload(this@VodCatalogActivity, existingDl.id)
                            Toast.makeText(this@VodCatalogActivity, "Download removido.", Toast.LENGTH_SHORT).show()
                        }
                        DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                            Toast.makeText(this@VodCatalogActivity, "Download em andamento (veja em ⬇ Offline).", Toast.LENGTH_SHORT).show()
                        }
                        else -> {
                            OfflineDownloadManager.enqueueDownload(this@VodCatalogActivity, baseItem)
                            Toast.makeText(this@VodCatalogActivity, "⬇ Baixando ${movie.name} em segundo plano...", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                .setNegativeButton("Fechar", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { playBtn ->
                    playBtn.requestFocus()
                    playBtn.post { playBtn.requestFocus() }
                }
            }
            dialog.show()
        }
    }

    private fun showSeriesEpisodes(series: SeriesEntity) {
        val profile = activeProfile ?: return
        Toast.makeText(this, "Carregando episódios de ${series.name}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val historyMap = repository.getSeriesHistory(profile.id, series.seriesId)
                    .associateBy { it.contentKey }

                val entries: List<EpisodeUiEntry> = if (profile.type == ProfileType.XTREAM.name && !series.seriesId.startsWith("m3u_")) {
                    val client = XtreamApiClient()
                    val res = client.getSeriesInfo(
                        profile.serverUrl,
                        profile.username,
                        profile.password,
                        series.seriesId,
                        profile.userAgent.takeIf { it.isNotBlank() }
                    )

                    val episodes = res.episodes.entries
                        .sortedBy { it.key.toIntOrNull() ?: 999 }
                        .flatMap { (_, epList) -> epList.sortedBy { it.episodeNum } }

                    episodes.map { ep ->
                        val ext = ep.containerExtension.orEmpty().ifBlank { "mp4" }
                        val epUrl = client.buildSeriesEpisodeUrl(profile.serverUrl, profile.username, profile.password, ep.id, ext)
                        val saved = historyMap[epUrl]
                        val pct = if (saved != null && saved.durationMs > 0L) {
                            ((saved.positionMs * 100) / saved.durationMs).toInt().coerceIn(1, 100)
                        } else 0

                        EpisodeUiEntry(
                            playable = PlayableItem(
                                contentKey = epUrl,
                                title = "${series.name} • T${ep.season}E${ep.episodeNum}",
                                subtitle = ep.title,
                                streamUrl = epUrl,
                                iconUrl = ep.info?.movieImage ?: series.coverUrl,
                                mediaType = MediaType.SERIES,
                                streamId = ep.id,
                                userAgent = profile.userAgent.takeIf { it.isNotBlank() },
                                resumePositionMs = saved?.positionMs ?: 0L,
                                seriesId = series.seriesId,
                                seasonNum = ep.season,
                                episodeNum = ep.episodeNum
                            ),
                            progressPercent = pct
                        )
                    }
                } else {
                    val m3uEpisodes = repository.getM3uSeriesEpisodes(profile.id, series.seriesId)
                    m3uEpisodes.map { ep ->
                        val saved = historyMap[ep.streamUrl]
                        val pct = if (saved != null && saved.durationMs > 0L) {
                            ((saved.positionMs * 100) / saved.durationMs).toInt().coerceIn(1, 100)
                        } else 0
                        val parts = ep.epgChannelId.split(":")
                        val season = parts.getOrNull(0)?.toIntOrNull() ?: 1
                        val epNum = parts.getOrNull(1)?.toIntOrNull() ?: 1

                        EpisodeUiEntry(
                            playable = PlayableItem(
                                contentKey = ep.streamUrl,
                                title = "${series.name} • ${ep.qualityLabel}",
                                subtitle = ep.name,
                                streamUrl = ep.streamUrl,
                                iconUrl = ep.iconUrl.ifBlank { series.coverUrl },
                                mediaType = MediaType.SERIES,
                                streamId = ep.streamId,
                                userAgent = ep.userAgent,
                                referrer = ep.referrer,
                                resumePositionMs = saved?.positionMs ?: 0L,
                                seriesId = series.seriesId,
                                seasonNum = season,
                                episodeNum = epNum
                            ),
                            progressPercent = pct
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    if (entries.isEmpty()) {
                        Toast.makeText(this@VodCatalogActivity, "Nenhum episódio encontrado.", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }

                    val recycler = RecyclerView(this@VodCatalogActivity).apply {
                        layoutManager = LinearLayoutManager(this@VodCatalogActivity)
                        setPadding(24, 16, 24, 16)
                    }
                    val dialog = AlertDialog.Builder(this@VodCatalogActivity)
                        .setTitle(series.name)
                        .setView(recycler)
                        .setNegativeButton("Fechar", null)
                        .create()

                    recycler.adapter = EpisodeProgressAdapter(
                        items = entries,
                        onSelect = { idx ->
                            dialog.dismiss()
                            launchWithResumeChoice(entries.map { it.playable }, idx)
                        }
                    )
                    dialog.show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VodCatalogActivity, "Erro ao carregar episódios: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchWithResumeChoice(list: List<PlayableItem>, idx: Int) {
        val item = list[idx]
        if (item.resumePositionMs > 5_000L) {
            val timeStr = formatTime(item.resumePositionMs)
            val dialog = AlertDialog.Builder(this)
                .setTitle(item.title)
                .setMessage("Você já assistiu até $timeStr. Como deseja continuar?")
                .setPositiveButton("▶ Continuar ($timeStr)") { _, _ ->
                    openPlayer(list, idx)
                }
                .setNeutralButton("↺ Do início") { _, _ ->
                    val resetList = list.toMutableList()
                    resetList[idx] = item.copy(resumePositionMs = 0L)
                    openPlayer(resetList, idx)
                }
                .setNegativeButton("Cancelar", null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { continueBtn ->
                    continueBtn.requestFocus()
                    continueBtn.post { continueBtn.requestFocus() }
                }
            }
            dialog.show()
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
}
