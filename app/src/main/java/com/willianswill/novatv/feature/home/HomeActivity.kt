package com.willianswill.novatv.feature.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
import com.willianswill.novatv.core.database.IptvRepository
import com.willianswill.novatv.core.database.entity.MediaType
import com.willianswill.novatv.core.database.entity.WatchHistoryEntity
import com.willianswill.novatv.core.network.AppUpdateChecker
import com.willianswill.novatv.core.network.AppUpdateInfo
import com.willianswill.novatv.core.network.CatalogSyncWorker
import com.willianswill.novatv.core.network.TelemetryManager
import com.willianswill.novatv.core.player.AppPreferences
import com.willianswill.novatv.core.player.MultiViewActivity
import com.willianswill.novatv.core.player.PlayableItem
import com.willianswill.novatv.core.player.PlayerActivity
import com.willianswill.novatv.core.player.UserProfileManager
import com.willianswill.novatv.core.ui.enableCinemaFocusZoom
import com.willianswill.novatv.databinding.ActivityHomeBinding
import com.willianswill.novatv.feature.livetv.LiveTvActivity
import com.willianswill.novatv.feature.playlists.PlaylistsActivity
import com.willianswill.novatv.feature.profiles.ProfilePickerActivity
import com.willianswill.novatv.feature.settings.SettingsActivity
import com.willianswill.novatv.feature.vod.SeriesDetailActivity
import com.willianswill.novatv.feature.vod.VodCatalogActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var repository: IptvRepository
    private lateinit var prefs: AppPreferences

    private lateinit var heroBannerAdapter: HeroBannerAdapter
    private lateinit var recentChannelsAdapter: HomeCarouselAdapter
    private lateinit var recentSeriesAdapter: HomeCarouselAdapter
    private lateinit var recentMoviesAdapter: HomeCarouselAdapter
    private lateinit var favAdapter: HomeCarouselAdapter
    private lateinit var recentAddedAdapter: HomeCarouselAdapter

    private var favPlayables: List<PlayableItem> = emptyList()
    private var bannerCount = 0
    private var profileDataJob: Job? = null
    private var startupSyncTriggered = false

    private val bannerHandler = Handler(Looper.getMainLooper())
    private val autoRotateBanner = object : Runnable {
        override fun run() {
            if (bannerCount > 1) {
                val next = (binding.vpHeroBanners.currentItem + 1) % bannerCount
                binding.vpHeroBanners.setCurrentItem(next, true)
            }
            bannerHandler.postDelayed(this, 6000L)
        }
    }

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
        checkAppUpdateOnStartup()
    }

    override fun onResume() {
        super.onResume()
        prefs = AppPreferences(this)
        val user = UserProfileManager.getActiveUser(this)
        val avatar = UserProfileManager.getAvatar(user.avatarId)
        binding.btnSwitchProfile.text = "${avatar.emoji} ${user.name}"
        checkTelemetryHeartbeat()
        bannerHandler.removeCallbacks(autoRotateBanner)
        bannerHandler.postDelayed(autoRotateBanner, 6000L)
    }

    override fun onPause() {
        super.onPause()
        bannerHandler.removeCallbacks(autoRotateBanner)
    }

    private fun setupCarousels() {
        heroBannerAdapter = HeroBannerAdapter { banner ->
            when (banner) {
                is FeaturedBannerItem.MovieBanner -> launchPlayer(listOf(banner.playable), 0)
                is FeaturedBannerItem.SeriesBanner -> {
                    val s = banner.series
                    startActivity(
                        Intent(this, SeriesDetailActivity::class.java).apply {
                            putExtra(SeriesDetailActivity.EXTRA_SERIES_DB_ID, s.id)
                            putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, s.seriesId)
                            putExtra(SeriesDetailActivity.EXTRA_NAME, s.name)
                            putExtra(SeriesDetailActivity.EXTRA_COVER, s.coverUrl)
                            putExtra(SeriesDetailActivity.EXTRA_CATEGORY, s.categoryName)
                            putExtra(SeriesDetailActivity.EXTRA_PLOT, s.plot)
                            putExtra(SeriesDetailActivity.EXTRA_RATING, s.rating)
                            putExtra(SeriesDetailActivity.EXTRA_IS_FAV, s.isFavorite)
                        }
                    )
                }
            }
        }
        binding.vpHeroBanners.adapter = heroBannerAdapter
        binding.vpHeroBanners.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.tvBannerCounter.text = "${position + 1}/${bannerCount.coerceAtLeast(1)}"
            }
        })
        val openDirect: (List<PlayableItem>, Int) -> Unit = { list, idx ->
            launchPlayer(list, idx)
        }

        val openWithResumePrompt: (List<PlayableItem>, Int) -> Unit = { list, idx ->
            val item = list[idx]
            if (item.mediaType != MediaType.LIVE && item.resumePositionMs > 5_000L) {
                val timeStr = formatTime(item.resumePositionMs)
                val dialog = AlertDialog.Builder(this)
                    .setTitle(item.title)
                    .setMessage("Você já assistiu até $timeStr. Como deseja reproduzir?")
                    .setPositiveButton("▶ Continuar ($timeStr)") { _, _ ->
                        launchPlayer(list, idx)
                    }
                    .setNeutralButton("↺ Do início") { _, _ ->
                        val resetList = list.toMutableList()
                        resetList[idx] = item.copy(resumePositionMs = 0L)
                        launchPlayer(resetList, idx)
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
                launchPlayer(list, idx)
            }
        }

        recentChannelsAdapter = HomeCarouselAdapter(openDirect)
        recentSeriesAdapter = HomeCarouselAdapter(openWithResumePrompt)
        recentMoviesAdapter = HomeCarouselAdapter(openWithResumePrompt)
        favAdapter = HomeCarouselAdapter(openDirect)
        recentAddedAdapter = HomeCarouselAdapter(openDirect)

        binding.rvRecentChannels.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecentChannels.adapter = recentChannelsAdapter

        binding.rvRecentSeries.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecentSeries.adapter = recentSeriesAdapter

        binding.rvRecentMovies.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecentMovies.adapter = recentMoviesAdapter

        binding.rvFavorites.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvFavorites.adapter = favAdapter

        binding.rvRecent.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvRecent.adapter = recentAddedAdapter
    }

    private fun launchPlayer(list: List<PlayableItem>, idx: Int) {
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_ITEMS, ArrayList(list))
                putExtra(PlayerActivity.EXTRA_INDEX, idx)
            }
        )
    }

    private fun setupNavigation() {
        binding.cardLiveTv.enableCinemaFocusZoom(1.05f)
        binding.cardMovies.enableCinemaFocusZoom(1.05f)
        binding.cardSeries.enableCinemaFocusZoom(1.05f)
        binding.cardMultiview.enableCinemaFocusZoom(1.05f)
        binding.btnSwitchProfile.enableCinemaFocusZoom(1.06f)
        binding.btnPlaylists.enableCinemaFocusZoom(1.06f)
        binding.btnSettings.enableCinemaFocusZoom(1.06f)
        binding.btnAddFirstPlaylist.enableCinemaFocusZoom(1.05f)
        binding.btnBannerPrev.enableCinemaFocusZoom(1.06f)
        binding.btnBannerNext.enableCinemaFocusZoom(1.06f)

        binding.btnBannerPrev.setOnClickListener {
            if (bannerCount > 1) {
                val prev = (binding.vpHeroBanners.currentItem - 1 + bannerCount) % bannerCount
                binding.vpHeroBanners.setCurrentItem(prev, true)
            }
        }
        binding.btnBannerNext.setOnClickListener {
            if (bannerCount > 1) {
                val next = (binding.vpHeroBanners.currentItem + 1) % bannerCount
                binding.vpHeroBanners.setCurrentItem(next, true)
            }
        }

        binding.btnSwitchProfile.setOnClickListener {
            startActivity(Intent(this, ProfilePickerActivity::class.java))
            finish()
        }

        binding.btnPlaylists.setOnClickListener {
            openServerSwitcherOrPlaylists()
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

    private fun openServerSwitcherOrPlaylists() {
        lifecycleScope.launch {
            val profiles = repository.observeProfiles().firstOrNull().orEmpty()
            if (profiles.size <= 1) {
                startActivity(Intent(this@HomeActivity, PlaylistsActivity::class.java))
                return@launch
            }
            val labels = profiles.map { p ->
                val mark = if (p.isActive) "✓ [ATIVO] " else ""
                "$mark${p.name} (${p.totalChannels} canais)"
            }.toTypedArray()
            val activeIdx = profiles.indexOfFirst { it.isActive }.coerceAtLeast(0)

            AlertDialog.Builder(this@HomeActivity)
                .setTitle("Trocar Servidor / Playlist")
                .setSingleChoiceItems(labels, activeIdx) { dialog, which ->
                    val chosen = profiles[which]
                    dialog.dismiss()
                    if (!chosen.isActive) {
                        lifecycleScope.launch {
                            repository.setActiveProfile(chosen.id)
                            Toast.makeText(
                                this@HomeActivity,
                                "Servidor alterado para: ${chosen.name}",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                .setPositiveButton("➕ Adicionar / Gerenciar") { _, _ ->
                    startActivity(Intent(this@HomeActivity, PlaylistsActivity::class.java))
                }
                .setNegativeButton("Fechar", null)
                .show()
        }
    }

    private fun observeActiveProfile() {
        lifecycleScope.launch {
            repository.observeActiveProfile().collectLatest { profile ->
                profileDataJob?.cancel()
                if (profile == null) {
                    binding.tvActiveProfile.text = "Nenhum servidor conectado"
                    binding.bannerNoProfile.visibility = View.VISIBLE
                    binding.heroCarouselContainer.visibility = View.GONE
                    binding.btnAddFirstPlaylist.post { binding.btnAddFirstPlaylist.requestFocus() }
                    toggleSection(binding.tvSectionRecentChannels, binding.rvRecentChannels, false)
                    toggleSection(binding.tvSectionRecentSeries, binding.rvRecentSeries, false)
                    toggleSection(binding.tvSectionRecentMovies, binding.rvRecentMovies, false)
                    toggleSection(binding.tvSectionFavorites, binding.rvFavorites, false)
                    toggleSection(binding.tvSectionRecent, binding.rvRecent, false)
                    return@collectLatest
                }

                binding.bannerNoProfile.visibility = View.GONE
                binding.tvActiveProfile.text =
                    "${profile.name} • ${profile.totalChannels} canais • ${profile.totalMovies} filmes"
                if (currentFocus == null) {
                    binding.cardLiveTv.post { binding.cardLiveTv.requestFocus() }
                }

                if (!startupSyncTriggered && System.currentTimeMillis() - profile.lastSyncAt > 30L * 60L * 1000L) {
                    startupSyncTriggered = true
                    lifecycleScope.launch {
                        runCatching {
                            val updated = repository.syncActiveProfile()
                            if (updated != null && updated.epgUrl.isNotBlank()) {
                                repository.syncEpgForProfile(updated)
                            }
                        }
                    }
                }

                val includeAdult = prefs.showAdultContent
                profileDataJob = lifecycleScope.launch {
                    launch {
                        repository.observeHistoryByType(profile.id, MediaType.LIVE).collectLatest { list ->
                            val mapped = list.map { it.toCarouselItem() }
                            recentChannelsAdapter.submitList(mapped)
                            toggleSection(binding.tvSectionRecentChannels, binding.rvRecentChannels, mapped.isNotEmpty())
                        }
                    }

                    launch {
                        repository.observeHistoryByType(profile.id, MediaType.SERIES).collectLatest { list ->
                            val mapped = list.map { it.toCarouselItem() }
                            recentSeriesAdapter.submitList(mapped)
                            toggleSection(binding.tvSectionRecentSeries, binding.rvRecentSeries, mapped.isNotEmpty())
                        }
                    }

                    launch {
                        repository.observeHistoryByType(profile.id, MediaType.MOVIE).collectLatest { list ->
                            val mapped = list.map { it.toCarouselItem() }
                            recentMoviesAdapter.submitList(mapped)
                            toggleSection(binding.tvSectionRecentMovies, binding.rvRecentMovies, mapped.isNotEmpty())
                        }
                    }

                    launch {
                        repository.observeFavorites(profile.id, includeAdult).collectLatest { list ->
                            val mapped = list.map { s ->
                                val p = PlayableItem(
                                    contentKey = s.streamUrl,
                                    title = s.baseName,
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
                        combine(
                            repository.observeRecentlyAdded(profile.id, MediaType.MOVIE, includeAdult),
                            repository.observeRecentSeries(profile.id, includeAdult)
                        ) { movies, series ->
                            val moviePlayables = movies.map { s ->
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

                            val movieBanners = movies.take(6).map { s ->
                                FeaturedBannerItem.MovieBanner(
                                    playable = PlayableItem(
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
                                    ),
                                    rating = s.rating,
                                    timestamp = s.addedAt
                                )
                            }
                            val seriesBanners = series.take(6).map { sr ->
                                FeaturedBannerItem.SeriesBanner(
                                    series = sr,
                                    timestamp = sr.lastModified
                                )
                            }
                            val combinedBanners = (movieBanners + seriesBanners)
                                .sortedByDescending {
                                    when (it) {
                                        is FeaturedBannerItem.MovieBanner -> it.timestamp
                                        is FeaturedBannerItem.SeriesBanner -> it.timestamp
                                    }
                                }
                                .take(10)

                            moviePlayables to combinedBanners
                        }.collectLatest { (moviePlayables, combinedBanners) ->
                            recentAddedAdapter.submitList(moviePlayables)
                            toggleSection(binding.tvSectionRecent, binding.rvRecent, moviePlayables.isNotEmpty())

                            bannerCount = combinedBanners.size
                            heroBannerAdapter.submitList(combinedBanners)
                            binding.heroCarouselContainer.visibility = if (bannerCount > 0) View.VISIBLE else View.GONE
                            if (bannerCount > 0) {
                                binding.tvBannerCounter.text = "${binding.vpHeroBanners.currentItem + 1}/$bannerCount"
                            }
                        }
                    }
                }
            }
        }
    }

    private fun WatchHistoryEntity.toCarouselItem(): HomeCarouselItem {
        val pct = if (durationMs > 0) ((positionMs * 100) / durationMs).toInt().coerceIn(1, 100) else 0
        return HomeCarouselItem(
            playable = PlayableItem(
                contentKey = contentKey,
                title = title,
                subtitle = if (mediaType == MediaType.LIVE) "Ao Vivo" else if (pct > 0) "$pct% assistido" else mediaType,
                streamUrl = streamUrl,
                iconUrl = iconUrl,
                mediaType = mediaType,
                resumePositionMs = positionMs,
                seriesId = seriesId,
                seasonNum = seasonNum,
                episodeNum = episodeNum
            ),
            progressPercent = pct
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

    private fun checkTelemetryHeartbeat() {
        TelemetryManager.sendHeartbeat(this, "Online no Menu") { cmd ->
            TelemetryManager.handleCommandOnActivity(this, cmd) {
                checkTelemetryHeartbeat()
            }
        }
    }

    private fun checkAppUpdateOnStartup() {
        lifecycleScope.launch {
            val info = AppUpdateChecker.checkForUpdate(this@HomeActivity) ?: return@launch
            if (isFinishing || isDestroyed) return@launch
            showUpdateDialog(info)
        }
    }

    private fun showUpdateDialog(info: AppUpdateInfo) {
        AlertDialog.Builder(this)
            .setTitle("Nova versão disponível (${info.versionName})")
            .setMessage(info.notes.ifBlank { "Há uma nova atualização do NovaTV IPTV pronta para instalar." })
            .setPositiveButton("⬇ Atualizar Agora") { _, _ ->
                if (info.apkDownloadUrl.isBlank()) {
                    if (info.releasePageUrl.isNotBlank()) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.releasePageUrl)))
                    }
                    return@setPositiveButton
                }
                val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    setPadding(48, 24, 48, 24)
                }
                val dlDialog = AlertDialog.Builder(this)
                    .setTitle("Baixando ${info.versionName}...")
                    .setView(pb)
                    .setCancelable(false)
                    .show()

                lifecycleScope.launch {
                    try {
                        AppUpdateChecker.downloadAndInstallApk(this@HomeActivity, info.apkDownloadUrl) { pct ->
                            pb.progress = pct
                        }
                        dlDialog.dismiss()
                    } catch (e: Exception) {
                        dlDialog.dismiss()
                        Toast.makeText(this@HomeActivity, "Falha ao atualizar: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Depois", null)
            .show()
    }

    private fun toggleSection(header: View, list: View, visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        header.visibility = v
        list.visibility = v
    }
}
