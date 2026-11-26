package com.willianswill.novatv.feature.home

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.willianswill.novatv.core.database.IptvRepository
import com.willianswill.novatv.core.database.entity.MediaType
import com.willianswill.novatv.core.network.CatalogSyncWorker
import com.willianswill.novatv.core.player.AppPreferences
import com.willianswill.novatv.core.player.MultiViewActivity
import com.willianswill.novatv.core.player.PlayableItem
import com.willianswill.novatv.core.player.PlayerActivity
import com.willianswill.novatv.databinding.ActivityHomeBinding
import com.willianswill.novatv.feature.livetv.LiveTvActivity
import com.willianswill.novatv.feature.playlists.PlaylistsActivity
import com.willianswill.novatv.feature.settings.SettingsActivity
import com.willianswill.novatv.feature.vod.VodCatalogActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var repository: IptvRepository
    private lateinit var prefs: AppPreferences

    private lateinit var continueAdapter: HomeCarouselAdapter
    private lateinit var favAdapter: HomeCarouselAdapter
    private lateinit var recentAdapter: HomeCarouselAdapter

    private var favPlayables: List<PlayableItem> = emptyList()
    private var profileDataJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = IptvRepository(this)
        prefs = AppPreferences(this)
        CatalogSyncWorker.schedulePeriodicSync(this)

        setupCarousels()
        setupNavigation()
        observeActiveProfile()
    }

    private fun setupCarousels() {
        val openPlayer: (List<PlayableItem>, Int) -> Unit = { list, idx ->
            startActivity(
                Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_ITEMS, ArrayList(list))
                    putExtra(PlayerActivity.EXTRA_INDEX, idx)
                }
            )
        }

        continueAdapter = HomeCarouselAdapter(openPlayer)
        favAdapter = HomeCarouselAdapter(openPlayer)
        recentAdapter = HomeCarouselAdapter(openPlayer)

        binding.rvContinue.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvContinue.adapter = continueAdapter

        binding.rvFavorites.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvFavorites.adapter = favAdapter

        binding.rvRecent.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecent.adapter = recentAdapter
    }

    private fun setupNavigation() {
        binding.btnPlaylists.setOnClickListener {
            startActivity(Intent(this, PlaylistsActivity::class.java))
        }
        binding.btnAddFirstPlaylist.setOnClickListener {
            startActivity(Intent(this, PlaylistsActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.cardLiveTv.setOnClickListener {
            startActivity(Intent(this, LiveTvActivity::class.java))
        }
        binding.cardMovies.setOnClickListener {
            startActivity(
                Intent(this, VodCatalogActivity::class.java).putExtra(
                    VodCatalogActivity.EXTRA_MODE,
                    MediaType.MOVIE
                )
            )
        }
        binding.cardSeries.setOnClickListener {
            startActivity(
                Intent(this, VodCatalogActivity::class.java).putExtra(
                    VodCatalogActivity.EXTRA_MODE,
                    MediaType.SERIES
                )
            )
        }
        binding.cardMultiview.setOnClickListener {
            if (favPlayables.isEmpty()) {
                Toast.makeText(this, "Favorite alguns canais ou abra pela TV ao Vivo para usar o Multi-View.", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(this, MultiViewActivity::class.java).apply {
                        putExtra(MultiViewActivity.EXTRA_ITEMS, ArrayList(favPlayables))
                        putExtra(MultiViewActivity.EXTRA_INITIAL_INDEX, 0)
                    }
                )
            }
        }
    }

    private fun observeActiveProfile() {
        lifecycleScope.launch {
            repository.observeActiveProfile().collectLatest { profile ->
                profileDataJob?.cancel()
                if (profile == null) {
                    binding.tvActiveProfile.text = "Nenhum servidor conectado"
                    binding.bannerNoProfile.visibility = View.VISIBLE
                    toggleSection(binding.tvSectionContinue, binding.rvContinue, false)
                    toggleSection(binding.tvSectionFavorites, binding.rvFavorites, false)
                    toggleSection(binding.tvSectionRecent, binding.rvRecent, false)
                    return@collectLatest
                }

                binding.bannerNoProfile.visibility = View.GONE
                binding.tvActiveProfile.text =
                    "${profile.name} • ${profile.totalChannels} canais • ${profile.totalMovies} filmes"

                val includeAdult = prefs.showAdultContent
                profileDataJob = lifecycleScope.launch {
                    launch {
                        repository.observeContinueWatching(profile.id).collectLatest { list ->
                            val mapped = list.map { h ->
                                val pct = if (h.durationMs > 0) ((h.positionMs * 100) / h.durationMs).toInt().coerceIn(1, 99) else 0
                                HomeCarouselItem(
                                    playable = PlayableItem(
                                        contentKey = h.contentKey,
                                        title = h.title,
                                        subtitle = h.mediaType,
                                        streamUrl = h.streamUrl,
                                        iconUrl = h.iconUrl,
                                        mediaType = h.mediaType,
                                        resumePositionMs = h.positionMs
                                    ),
                                    progressPercent = pct
                                )
                            }
                            continueAdapter.submitList(mapped)
                            toggleSection(binding.tvSectionContinue, binding.rvContinue, mapped.isNotEmpty())
                        }
                    }

                    launch {
                        repository.observeFavorites(profile.id, includeAdult).collectLatest { list ->
                            val mapped = list.map { s ->
                                val p = PlayableItem(
                                    contentKey = s.streamUrl,
                                    title = s.name,
                                    subtitle = s.categoryName,
                                    streamUrl = s.streamUrl,
                                    iconUrl = s.iconUrl,
                                    mediaType = s.mediaType,
                                    streamId = s.streamId,
                                    epgChannelId = s.epgChannelId,
                                    hasCatchup = s.hasCatchup,
                                    catchupDays = s.catchupDays,
                                    userAgent = s.userAgent,
                                    referrer = s.referrer,
                                    streamDbId = s.id,
                                    isFavorite = true
                                )
                                HomeCarouselItem(p)
                            }
                            favPlayables = mapped.map { it.playable }
                            favAdapter.submitList(mapped)
                            toggleSection(binding.tvSectionFavorites, binding.rvFavorites, mapped.isNotEmpty())
                        }
                    }

                    launch {
                        repository.observeRecentlyAdded(profile.id, MediaType.MOVIE, includeAdult).collectLatest { list ->
                            val mapped = list.map { s ->
                                HomeCarouselItem(
                                    PlayableItem(
                                        contentKey = s.streamUrl,
                                        title = s.name,
                                        subtitle = s.categoryName,
                                        streamUrl = s.streamUrl,
                                        iconUrl = s.iconUrl,
                                        mediaType = s.mediaType,
                                        streamId = s.streamId,
                                        userAgent = s.userAgent,
                                        referrer = s.referrer,
                                        streamDbId = s.id,
                                        isFavorite = s.isFavorite
                                    )
                                )
                            }
                            recentAdapter.submitList(mapped)
                            toggleSection(binding.tvSectionRecent, binding.rvRecent, mapped.isNotEmpty())
                        }
                    }
                }
            }
        }
    }

    private fun toggleSection(header: View, list: View, visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        header.visibility = v
        list.visibility = v
    }
}
