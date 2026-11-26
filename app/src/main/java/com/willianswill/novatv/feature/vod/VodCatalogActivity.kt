package com.willianswill.novatv.feature.vod

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.willianswill.novatv.core.database.IptvRepository
import com.willianswill.novatv.core.database.entity.MediaType
import com.willianswill.novatv.core.database.entity.ProfileEntity
import com.willianswill.novatv.core.database.entity.SeriesEntity
import com.willianswill.novatv.core.database.entity.StreamEntity
import com.willianswill.novatv.core.network.XtreamApiClient
import com.willianswill.novatv.core.player.AppPreferences
import com.willianswill.novatv.core.player.PlayableItem
import com.willianswill.novatv.core.player.PlayerActivity
import com.willianswill.novatv.databinding.ActivityVodCatalogBinding
import com.willianswill.novatv.feature.livetv.CategoryTabAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        seriesAdapter = SeriesPagingAdapter { series -> showSeriesEpisodes(series) }

        val span = if (isLandscape) 5 else 2
        binding.rvVodGrid.layoutManager = GridLayoutManager(this, span)
        binding.rvVodGrid.adapter = if (mode == MediaType.SERIES) seriesAdapter else vodAdapter
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { finish() }
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
        val favLabel = if (movie.isFavorite) "Remover dos Favoritos" else "★ Favoritar"
        val info = buildString {
            append("Categoria: ${movie.categoryName}\n")
            if (movie.rating.isNotBlank()) append("Nota: ★ ${movie.rating}\n")
            if (movie.plot.isNotBlank()) append("\n${movie.plot}")
        }

        AlertDialog.Builder(this)
            .setTitle(movie.name)
            .setMessage(info)
            .setPositiveButton("▶ Assistir") { _, _ ->
                val item = PlayableItem(
                    contentKey = movie.streamUrl,
                    title = movie.name,
                    subtitle = movie.categoryName,
                    streamUrl = movie.streamUrl,
                    iconUrl = movie.iconUrl,
                    mediaType = MediaType.MOVIE,
                    streamId = movie.streamId,
                    userAgent = movie.userAgent,
                    referrer = movie.referrer,
                    streamDbId = movie.id,
                    isFavorite = movie.isFavorite
                )
                startActivity(
                    Intent(this, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_ITEMS, arrayListOf(item))
                        putExtra(PlayerActivity.EXTRA_INDEX, 0)
                    }
                )
            }
            .setNeutralButton(favLabel) { _, _ ->
                lifecycleScope.launch {
                    repository.toggleStreamFavorite(movie.id, !movie.isFavorite)
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showSeriesEpisodes(series: SeriesEntity) {
        val profile = activeProfile ?: return
        Toast.makeText(this, "Carregando episódios de ${series.name}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
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

                val playables = episodes.map { ep ->
                    val ext = ep.containerExtension.orEmpty().ifBlank { "mp4" }
                    val epUrl = client.buildSeriesEpisodeUrl(profile.serverUrl, profile.username, profile.password, ep.id, ext)
                    PlayableItem(
                        contentKey = epUrl,
                        title = "${series.name} • T${ep.season}E${ep.episodeNum}",
                        subtitle = ep.title,
                        streamUrl = epUrl,
                        iconUrl = ep.info?.movieImage ?: series.coverUrl,
                        mediaType = MediaType.SERIES,
                        streamId = ep.id,
                        userAgent = profile.userAgent.takeIf { it.isNotBlank() }
                    )
                }

                withContext(Dispatchers.Main) {
                    if (playables.isEmpty()) {
                        Toast.makeText(this@VodCatalogActivity, "Nenhum episódio encontrado.", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    val labels = playables.map { "${it.title} - ${it.subtitle}" }.toTypedArray()
                    AlertDialog.Builder(this@VodCatalogActivity)
                        .setTitle(series.name)
                        .setItems(labels) { _, idx ->
                            startActivity(
                                Intent(this@VodCatalogActivity, PlayerActivity::class.java).apply {
                                    putExtra(PlayerActivity.EXTRA_ITEMS, ArrayList(playables))
                                    putExtra(PlayerActivity.EXTRA_INDEX, idx)
                                }
                            )
                        }
                        .setNegativeButton("Fechar", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@VodCatalogActivity, "Erro ao carregar episódios: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
