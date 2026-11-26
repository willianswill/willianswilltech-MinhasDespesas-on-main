package com.willianswill.novatv.feature.livetv

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import com.willianswill.novatv.core.database.IptvRepository
import com.willianswill.novatv.core.database.entity.MediaType
import com.willianswill.novatv.core.database.entity.StreamEntity
import com.willianswill.novatv.core.player.AppPreferences
import com.willianswill.novatv.core.player.BufferProfile
import com.willianswill.novatv.core.player.MultiViewActivity
import com.willianswill.novatv.core.player.NovaPlayerFactory
import com.willianswill.novatv.core.player.PlayableItem
import com.willianswill.novatv.core.player.PlayerActivity
import com.willianswill.novatv.databinding.ActivityLiveTvBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LiveTvActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveTvBinding
    private lateinit var repository: IptvRepository
    private lateinit var prefs: AppPreferences
    private lateinit var categoryAdapter: CategoryTabAdapter
    private lateinit var channelAdapter: LiveChannelPagingAdapter

    private var miniPlayer: ExoPlayer? = null
    private var activeProfileId: Long = -1L
    private var selectedCategoryId: String? = null
    private var onlyFavorites: Boolean = false
    private var searchQuery: String = ""
    private var currentPreviewStream: StreamEntity? = null
    private var pagingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveTvBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            loadPagedChannels()
        }
        binding.rvCategories.layoutManager = LinearLayoutManager(
            this,
            if (isLandscape) LinearLayoutManager.VERTICAL else LinearLayoutManager.HORIZONTAL,
            false
        )
        binding.rvCategories.adapter = categoryAdapter

        channelAdapter = LiveChannelPagingAdapter(
            onPreviewOrPlay = { stream, pos ->
                if (currentPreviewStream?.id == stream.id) {
                    openFullscreenPlayer(pos)
                } else {
                    previewChannel(stream)
                }
            },
            onToggleFav = { stream ->
                lifecycleScope.launch {
                    repository.toggleStreamFavorite(stream.id, !stream.isFavorite)
                }
            }
        )
        binding.rvChannels.layoutManager = LinearLayoutManager(this)
        binding.rvChannels.adapter = channelAdapter
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnFullscreen.setOnClickListener {
            val preview = currentPreviewStream
            if (preview == null) {
                Toast.makeText(this, "Selecione um canal primeiro.", Toast.LENGTH_SHORT).show()
            } else {
                val snapshot = channelAdapter.snapshot().items
                val idx = snapshot.indexOfFirst { it.id == preview.id }.coerceAtLeast(0)
                openFullscreenPlayer(idx)
            }
        }

        binding.btnMultiview.setOnClickListener {
            val items = channelAdapter.snapshot().items.take(60).map { it.toPlayable() }
            if (items.isEmpty()) {
                Toast.makeText(this, "Nenhum canal disponível para Multi-View.", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(this, MultiViewActivity::class.java).apply {
                        putExtra(MultiViewActivity.EXTRA_ITEMS, ArrayList(items))
                        putExtra(MultiViewActivity.EXTRA_INITIAL_INDEX, 0)
                    }
                )
            }
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                loadPagedChannels()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun observeProfile() {
        lifecycleScope.launch {
            repository.observeActiveProfile().collectLatest { profile ->
                if (profile == null) {
                    Toast.makeText(this@LiveTvActivity, "Adicione uma lista primeiro.", Toast.LENGTH_SHORT).show()
                    finish()
                    return@collectLatest
                }
                activeProfileId = profile.id
                launch {
                    repository.observeCategories(profile.id, MediaType.LIVE, prefs.showAdultContent).collectLatest { cats ->
                        categoryAdapter.submitCategories(cats)
                    }
                }
                loadPagedChannels()
            }
        }
    }

    private fun loadPagedChannels() {
        if (activeProfileId <= 0L) return
        pagingJob?.cancel()
        pagingJob = lifecycleScope.launch {
            repository.pagedStreams(
                profileId = activeProfileId,
                mediaType = MediaType.LIVE,
                categoryId = selectedCategoryId,
                onlyFavorites = onlyFavorites,
                includeAdult = prefs.showAdultContent,
                query = searchQuery
            ).collectLatest { pagingData ->
                channelAdapter.submitData(pagingData)
            }
        }
    }

    private fun previewChannel(stream: StreamEntity) {
        currentPreviewStream = stream
        binding.tvEpgNow.text = stream.name
        binding.tvEpgTimeline.text = "Carregando guia de programação..."

        miniPlayer?.release()
        val exo = NovaPlayerFactory.createPlayer(
            context = this,
            bufferProfile = BufferProfile.LOW,
            userAgent = stream.userAgent,
            referrer = stream.referrer,
            forMultiView = true
        )
        binding.miniPlayerView.player = exo
        exo.setMediaItem(NovaPlayerFactory.buildMediaItem(stream.streamUrl))
        exo.prepare()
        exo.playWhenReady = true
        miniPlayer = exo

        lifecycleScope.launch {
            if (stream.epgChannelId.isBlank()) {
                binding.tvEpgTimeline.text = "${stream.categoryName} • Toque novamente para Tela Cheia"
                return@launch
            }
            val now = System.currentTimeMillis()
            val windowEnd = now + (6L * 60L * 60L * 1000L)
            val programs = repository.getEpgInRange(activeProfileId, stream.epgChannelId, now, windowEnd)
            if (programs.isEmpty()) {
                binding.tvEpgTimeline.text = "${stream.categoryName} • Sem EPG disponível"
            } else {
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                val current = programs.firstOrNull { it.startMs <= now && it.endMs > now }
                if (current != null) {
                    binding.tvEpgNow.text = "${stream.name} • ${current.title}"
                }
                binding.tvEpgTimeline.text = programs.take(4).joinToString("  |  ") {
                    "${fmt.format(Date(it.startMs))} ${it.title}"
                }
            }
        }
    }

    private fun openFullscreenPlayer(clickedPos: Int) {
        val snapshot = channelAdapter.snapshot().items
        if (snapshot.isEmpty()) return

        val start = (clickedPos - 60).coerceAtLeast(0)
        val end = (start + 120).coerceAtMost(snapshot.size)
        val window = ArrayList(snapshot.subList(start, end).map { it.toPlayable() })
        val relIdx = (clickedPos - start).coerceIn(0, (window.size - 1).coerceAtLeast(0))

        miniPlayer?.release()
        miniPlayer = null

        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_ITEMS, window)
                putExtra(PlayerActivity.EXTRA_INDEX, relIdx)
            }
        )
    }

    private fun StreamEntity.toPlayable() = PlayableItem(
        contentKey = streamUrl,
        title = name,
        subtitle = categoryName,
        streamUrl = streamUrl,
        iconUrl = iconUrl,
        mediaType = mediaType,
        streamId = streamId,
        epgChannelId = epgChannelId,
        hasCatchup = hasCatchup,
        catchupDays = catchupDays,
        userAgent = userAgent,
        referrer = referrer,
        streamDbId = id,
        isFavorite = isFavorite
    )

    override fun onStop() {
        super.onStop()
        miniPlayer?.release()
        miniPlayer = null
    }
}
