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
import com.willianswill.novatv.core.database.entity.ProfileEntity
import com.willianswill.novatv.core.database.entity.ProfileType
import com.willianswill.novatv.core.database.entity.SeriesEntity
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
import com.willianswill.novatv.feature.downloads.DownloadsActivity
import com.willianswill.novatv.feature.livetv.LiveTvActivity
import com.willianswill.novatv.feature.search.GlobalSearchActivity
import com.willianswill.novatv.feature.playlists.PlaylistsActivity
import com.willianswill.novatv.feature.profiles.ProfilePickerActivity
import com.willianswill.novatv.feature.settings.SettingsActivity
import com.willianswill.novatv.feature.vod.MovieDetailActivity
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
    private lateinit var topLaunchAdapter: HomeCarouselAdapter
    private lateinit var topNetflixAdapter: HomeCarouselAdapter
    private lateinit var topPrimeAdapter: HomeCarouselAdapter
    private lateinit var topHboAdapter: HomeCarouselAdapter
    private lateinit var topDisneyAdapter: HomeCarouselAdapter

    private val top10SeriesCache = HashMap<String, SeriesEntity>()
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
        setupBackExitConfirmation()
        binding.root.postDelayed({ checkAppUpdateOnStartup() }, 4_000L)
    }

    private fun setupBackExitConfirmation() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val dialog = AlertDialog.Builder(this@HomeActivity)
                    .setTitle("Sair do Wtv Canais Online?")
                    .setMessage("Deseja realmente fechar o aplicativo?")
                    .setPositiveButton("Sair") { _, _ ->
                        finishAffinity()
                    }
                    .setNegativeButton("Cancelar", null)
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { cancelBtn ->
                        cancelBtn.requestFocus()
                        cancelBtn.post { cancelBtn.requestFocus() }
                    }
                }
                dialog.show()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        prefs = AppPreferences(this)
        val user = UserProfileManager.getActiveUser(this)
        val avatar = UserProfileManager.getAvatar(user.avatarId)
        binding.btnSwitchProfile.text = "${avatar.emoji} ${user.name}"
        checkTelemetryHeartbeat()
        checkAndApplyMacPlaylist(manual = false)
        bannerHandler.removeCallbacks(autoRotateBanner)
        bannerHandler.postDelayed(autoRotateBanner, 6000L)
        if (binding.bannerNoProfile.visibility != View.VISIBLE) {
            (binding.root as? android.widget.ScrollView)?.scrollTo(0, 0)
            binding.cardLiveTv.post { binding.cardLiveTv.requestFocus() }
        }
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

        val openTop10Item: (List<PlayableItem>, Int) -> Unit = { list, idx ->
            val item = list[idx]
            if (item.mediaType == MediaType.SERIES && item.seriesId.isNotBlank()) {
                val s = top10SeriesCache[item.seriesId]
                if (s != null) {
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
                } else launchPlayer(list, idx)
            } else if (item.mediaType == MediaType.MOVIE) {
                startActivity(
                    Intent(this, MovieDetailActivity::class.java).apply {
                        putExtra(MovieDetailActivity.EXTRA_DB_ID, item.streamDbId)
                        putExtra(MovieDetailActivity.EXTRA_STREAM_ID, item.streamId)
                        putExtra(MovieDetailActivity.EXTRA_NAME, item.title)
                        putExtra(MovieDetailActivity.EXTRA_COVER, item.iconUrl)
                        putExtra(MovieDetailActivity.EXTRA_CATEGORY, item.subtitle)
                        putExtra(MovieDetailActivity.EXTRA_STREAM_URL, item.streamUrl)
                        putExtra(MovieDetailActivity.EXTRA_USER_AGENT, item.userAgent)
                        putExtra(MovieDetailActivity.EXTRA_REFERRER, item.referrer)
                        putExtra(MovieDetailActivity.EXTRA_IS_FAV, item.isFavorite)
                    }
                )
            } else {
                launchPlayer(list, idx)
            }
        }

        topLaunchAdapter = HomeCarouselAdapter(openTop10Item)
        topNetflixAdapter = HomeCarouselAdapter(openTop10Item)
        topPrimeAdapter = HomeCarouselAdapter(openTop10Item)
        topHboAdapter = HomeCarouselAdapter(openTop10Item)
        topDisneyAdapter = HomeCarouselAdapter(openTop10Item)

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

        listOf(
            binding.rvTopLaunch to topLaunchAdapter,
            binding.rvTopNetflix to topNetflixAdapter,
            binding.rvTopPrime to topPrimeAdapter,
            binding.rvTopHbo to topHboAdapter,
            binding.rvTopDisney to topDisneyAdapter
        ).forEach { (rv, adp) ->
            rv.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            rv.adapter = adp
        }
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
        binding.cardLiveTv.enableCinemaFocusZoom(1.06f)
        binding.cardMovies.enableCinemaFocusZoom(1.06f)
        binding.cardSeries.enableCinemaFocusZoom(1.06f)
        binding.cardMultiview.enableCinemaFocusZoom(1.06f)

        listOf(
            binding.btnGlobalSearch, binding.btnSwitchProfile, binding.btnPlaylists, binding.btnDownloads, binding.btnSettings,
            binding.btnAddFirstPlaylist, binding.btnCheckMacPlaylist,
            binding.btnBannerPrev, binding.btnBannerNext
        ).forEach { btn ->
            btn.backgroundTintList = null
            btn.enableCinemaFocusZoom(1.10f)
        }

        // Navegação previsível entre a barra superior e os cards principais (sem pular para o rodapé)
        binding.btnGlobalSearch.nextFocusDownId = com.willianswill.novatv.R.id.card_live_tv
        binding.btnGlobalSearch.nextFocusRightId = com.willianswill.novatv.R.id.btn_switch_profile
        binding.btnSwitchProfile.nextFocusLeftId = com.willianswill.novatv.R.id.btn_global_search
        binding.btnSwitchProfile.nextFocusDownId = com.willianswill.novatv.R.id.card_live_tv
        binding.btnPlaylists.nextFocusDownId = com.willianswill.novatv.R.id.card_movies
        binding.btnDownloads.nextFocusDownId = com.willianswill.novatv.R.id.card_series
        binding.btnSettings.nextFocusDownId = com.willianswill.novatv.R.id.card_multiview
        binding.btnSwitchProfile.nextFocusRightId = com.willianswill.novatv.R.id.btn_playlists
        binding.btnPlaylists.nextFocusLeftId = com.willianswill.novatv.R.id.btn_switch_profile
        binding.btnPlaylists.nextFocusRightId = com.willianswill.novatv.R.id.btn_downloads
        binding.btnDownloads.nextFocusLeftId = com.willianswill.novatv.R.id.btn_playlists
        binding.btnDownloads.nextFocusRightId = com.willianswill.novatv.R.id.btn_settings
        binding.btnSettings.nextFocusLeftId = com.willianswill.novatv.R.id.btn_downloads

        binding.cardLiveTv.nextFocusUpId = com.willianswill.novatv.R.id.btn_switch_profile
        binding.cardMovies.nextFocusUpId = com.willianswill.novatv.R.id.btn_playlists
        binding.cardSeries.nextFocusUpId = com.willianswill.novatv.R.id.btn_settings
        binding.cardMultiview.nextFocusUpId = com.willianswill.novatv.R.id.btn_settings

        binding.btnBannerPrev.nextFocusUpId = com.willianswill.novatv.R.id.btn_playlists
        binding.btnBannerPrev.nextFocusDownId = com.willianswill.novatv.R.id.card_live_tv
        binding.btnBannerNext.nextFocusUpId = com.willianswill.novatv.R.id.btn_settings
        binding.btnBannerNext.nextFocusDownId = com.willianswill.novatv.R.id.card_live_tv
        binding.vpHeroBanners.getChildAt(0)?.isFocusable = false

        binding.tvHomeDeviceMac.text = "📡 MAC do Aparelho: ${TelemetryManager.getDeviceMac(this)}"
        binding.btnCheckMacPlaylist.setOnClickListener {
            checkAndApplyMacPlaylist(manual = true)
        }

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

        binding.btnGlobalSearch.setOnClickListener {
            startActivity(Intent(this, GlobalSearchActivity::class.java))
        }
        binding.btnSwitchProfile.setOnClickListener {
            startActivity(Intent(this, ProfilePickerActivity::class.java).putExtra(ProfilePickerActivity.EXTRA_FORCE_PICKER, true))
            finish()
        }

        binding.btnPlaylists.setOnClickListener {
            openServerSwitcherOrPlaylists()
        }
        binding.btnAddFirstPlaylist.setOnClickListener {
            startActivity(Intent(this, PlaylistsActivity::class.java))
        }
        binding.btnDownloads.setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
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

    private var macPlaylistSyncRunning = false

    private fun checkAndApplyMacPlaylist(manual: Boolean) {
        if (macPlaylistSyncRunning) return
        macPlaylistSyncRunning = true
        lifecycleScope.launch {
            try {
                val remote = TelemetryManager.fetchRemoteMacPlaylist(this@HomeActivity)
                if (remote == null) {
                    if (manual) {
                        Toast.makeText(
                            this@HomeActivity,
                            "Nenhuma lista enviada ainda para o MAC ${TelemetryManager.getDeviceMac(this@HomeActivity)}.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                val lastTs = TelemetryManager.getLastAppliedMacPlaylistTs(this@HomeActivity)
                if (!manual && remote.updatedAt <= lastTs) return@launch

                Toast.makeText(
                    this@HomeActivity,
                    "Lista recebida via MAC (${remote.name})! Carregando canais...",
                    Toast.LENGTH_LONG
                ).show()

                val parsedUri = Uri.parse(remote.m3uUrl)
                val xtreamUser = parsedUri.getQueryParameter("username")?.trim().orEmpty()
                val xtreamPass = parsedUri.getQueryParameter("password")?.trim().orEmpty()
                val baseServer = if (parsedUri.scheme != null && parsedUri.host != null) {
                    val portPart = if (parsedUri.port != -1) ":${parsedUri.port}" else ""
                    "${parsedUri.scheme}://${parsedUri.host}$portPart"
                } else ""

                var synced: ProfileEntity? = null
                var savedId = 0L

                if (xtreamUser.isNotBlank() && xtreamPass.isNotBlank() && baseServer.isNotBlank()) {
                    val xtreamProfile = ProfileEntity(
                        name = remote.name,
                        type = ProfileType.XTREAM.name,
                        serverUrl = baseServer,
                        username = xtreamUser,
                        password = xtreamPass
                    )
                    savedId = repository.saveProfile(xtreamProfile, activate = true)
                    synced = runCatching { repository.syncActiveLiveFirst() }.getOrNull()
                    if (synced == null || (synced.totalChannels == 0 && synced.totalMovies == 0 && synced.totalSeries == 0)) {
                        repository.deleteProfile(savedId)
                        savedId = 0L
                        synced = null
                    }
                }

                if (synced == null) {
                    val m3uProfile = ProfileEntity(
                        name = remote.name,
                        type = ProfileType.M3U_URL.name,
                        serverUrl = remote.m3uUrl
                    )
                    savedId = repository.saveProfile(m3uProfile, activate = true)
                    try {
                        synced = repository.syncActiveLiveFirst()
                    } catch (syncErr: Exception) {
                        repository.deleteProfile(savedId)
                        throw syncErr
                    }
                }

                TelemetryManager.markMacPlaylistApplied(this@HomeActivity, remote.updatedAt)
                Toast.makeText(
                    this@HomeActivity,
                    "Lista ${remote.name} pronta (${synced?.totalChannels ?: 0} canais)! Filmes e EPG em segundo plano.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "Erro lista MAC: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                macPlaylistSyncRunning = false
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
                    val mac = TelemetryManager.getDeviceMac(this@HomeActivity)
                    binding.tvActiveProfile.text = "Nenhum servidor • MAC: $mac"
                    binding.tvHomeDeviceMac.text = "📡 MAC do Aparelho: $mac"
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

                if (!startupSyncTriggered &&
                    (profile.lastSyncAt == 0L || System.currentTimeMillis() - profile.lastSyncAt > 12L * 60L * 60L * 1000L)
                ) {
                    startupSyncTriggered = true
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(6_000L)
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

                    val top10Specs = listOf(
                        listOf("lançamento", "lancamento", "novidade") to (binding.tvSectionTopLaunch to (binding.rvTopLaunch to topLaunchAdapter)),
                        listOf("netflix", "netflix", "") to (binding.tvSectionTopNetflix to (binding.rvTopNetflix to topNetflixAdapter)),
                        listOf("prime", "amazon", "") to (binding.tvSectionTopPrime to (binding.rvTopPrime to topPrimeAdapter)),
                        listOf("hbo", "max", "") to (binding.tvSectionTopHbo to (binding.rvTopHbo to topHboAdapter)),
                        listOf("disney", "disney", "") to (binding.tvSectionTopDisney to (binding.rvTopDisney to topDisneyAdapter))
                    )
                    top10Specs.forEach { (kws, uiPair) ->
                        val titleView = uiPair.first
                        val rv = uiPair.second.first
                        val adapter = uiPair.second.second
                        launch {
                            repository.observeTop10ByKeywords(
                                profileId = profile.id,
                                kw1 = kws[0],
                                kw2 = kws[1],
                                kw3 = kws[2],
                                includeAdult = includeAdult
                            ).collectLatest { (movies, series) ->
                                val movieItems = movies.map { s ->
                                    HomeCarouselItem(
                                        PlayableItem(
                                            contentKey = s.streamUrl,
                                            title = s.name,
                                            subtitle = s.categoryName,
                                            streamUrl = s.streamUrl,
                                            iconUrl = s.iconUrl,
                                            mediaType = MediaType.MOVIE,
                                            streamId = s.streamId,
                                            userAgent = s.userAgent,
                                            referrer = s.referrer,
                                            streamDbId = s.id,
                                            isFavorite = s.isFavorite
                                        )
                                    )
                                }
                                val seriesItems = series.map { sr ->
                                    top10SeriesCache[sr.seriesId] = sr
                                    HomeCarouselItem(
                                        PlayableItem(
                                            contentKey = "series_${sr.seriesId}",
                                            title = sr.name,
                                            subtitle = sr.categoryName,
                                            streamUrl = "",
                                            iconUrl = sr.coverUrl,
                                            mediaType = MediaType.SERIES,
                                            seriesId = sr.seriesId,
                                            isFavorite = sr.isFavorite
                                        )
                                    )
                                }
                                val combinedTop10 = (movieItems + seriesItems).take(10)
                                adapter.submitList(combinedTop10)
                                toggleSection(titleView, rv, combinedTop10.isNotEmpty())
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
