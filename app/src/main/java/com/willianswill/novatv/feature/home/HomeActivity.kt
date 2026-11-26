package com.willianswill.novatv.feature.home

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.willianswill.novatv.R
import com.willianswill.novatv.core.database.IptvRepository
import com.willianswill.novatv.core.database.entity.MediaType
import com.willianswill.novatv.core.database.entity.WatchHistoryEntity
import com.willianswill.novatv.core.network.AppUpdateChecker
import com.willianswill.novatv.core.network.AppUpdateInfo
import com.willianswill.novatv.core.network.CatalogSyncWorker
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
import com.willianswill.novatv.feature.vod.VodCatalogActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var repository: IptvRepository
    private lateinit var prefs: AppPreferences

    private lateinit var recentChannelsAdapter: HomeCarouselAdapter
    private lateinit var recentSeriesAdapter: HomeCarouselAdapter
    private lateinit var recentMoviesAdapter: HomeCarouselAdapter
    private lateinit var favAdapter: HomeCarouselAdapter
    private lateinit var recentAddedAdapter: HomeCarouselAdapter

    private var favPlayables: List<PlayableItem> = emptyList()
    private var heroPlayable: PlayableItem? = null
    private var profileDataJob: Job? = null
    private var startupSyncTriggered = false

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
    }

    private fun setupCarousels() {
        val openDirect: (List<PlayableItem>, Int) -> Unit = { list, idx ->
            launchPlayer(list, idx)
        }

        val openWithResumePrompt: (List<PlayableItem>, Int) -> Unit = { list, idx ->
            val item = list[idx]
            if (item.mediaType != MediaType.LIVE && item.resumePositionMs > 5_000L) {
                val timeStr = formatTime(item.resumePositionMs)
                AlertDialog.Builder(this)
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
                    .show()
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
        binding.btnHeroPlay.enableCinemaFocusZoom(1.05f)

        binding.btnHeroPlay.setOnClickListener {
            val item = heroPlayable ?: return@setOnClickListener
            launchPlayer(listOf(item), 0)
        }

        binding.btnSwitchProfile.setOnClickListener {
            startActivity(Intent(this, ProfilePickerActivity::class.java))
            finish()
        }

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
                    binding.heroBannerContainer.visibility = View.GONE
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
                            if (mapped.isNotEmpty()) {
                                updateHeroBanner(mapped.first().playable, "▶ CONTINUAR ASSISTINDO", "▶ Continuar")
                            }
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
                            recentAddedAdapter.submitList(mapped)
                            toggleSection(binding.tvSectionRecent, binding.rvRecent, mapped.isNotEmpty())
                            if (heroPlayable == null && mapped.isNotEmpty()) {
                                updateHeroBanner(mapped.first().playable, "✨ ADICIONADO RECENTEMENTE", "▶ Assistir Agora")
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

    private fun updateHeroBanner(item: PlayableItem, badge: String, buttonText: String) {
        heroPlayable = item
        binding.heroBannerContainer.visibility = View.VISIBLE
        binding.tvHeroBadge.text = badge
        binding.tvHeroTitle.text = item.title
        binding.tvHeroSub.text = item.subtitle
        binding.btnHeroPlay.text = buttonText
        if (item.iconUrl.isNotBlank()) {
            Glide.with(this)
                .load(item.iconUrl)
                .placeholder(R.drawable.ic_tv_logo)
                .error(R.drawable.ic_tv_logo)
                .into(binding.ivHeroBackdrop)
        }
    }

    private fun toggleSection(header: View, list: View, visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        header.visibility = v
        list.visibility = v
    }
}
