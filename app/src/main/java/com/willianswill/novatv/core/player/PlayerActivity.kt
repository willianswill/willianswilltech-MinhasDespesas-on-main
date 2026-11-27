package com.willianswill.novatv.core.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.bumptech.glide.Glide
import com.willianswill.novatv.R
import com.willianswill.novatv.core.database.IptvRepository
import com.willianswill.novatv.core.database.entity.MediaType
import com.willianswill.novatv.core.database.entity.ProfileType
import com.willianswill.novatv.core.database.entity.WatchHistoryEntity
import com.willianswill.novatv.core.network.TelemetryManager
import com.willianswill.novatv.core.network.XtreamApiClient
import com.willianswill.novatv.core.ui.enableCinemaFocusZoom
import com.willianswill.novatv.databinding.ActivityPlayerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayerActivity : AppCompatActivity(), TelemetryManager.PlaybackStopper {

    companion object {
        const val EXTRA_ITEMS = "extra_items"
        const val EXTRA_INDEX = "extra_index"
        private const val MAX_RETRY = 5
        private const val AUTO_HIDE_MS = 4000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefs: AppPreferences
    private lateinit var repository: IptvRepository
    private var player: ExoPlayer? = null

    private var items: List<PlayableItem> = emptyList()
    private var currentIndex = 0
    private var previousIndex = -1
    private var retryCount = 0
    private var resizeIdx = 0
    private var isSeeking = false
    private var overrideCatchupUrl: String? = null
    private var externalSubUri: Uri? = null

    private var activeVariants: List<QualityVariant> = emptyList()
    private var currentVariant: QualityVariant? = null
    private var isAutoQuality = true
    private var rebufferCountInWindow = 0
    private var lastReadyAtMs = 0L
    private val failedVariantUrls = HashSet<String>()
    private val stallWatchdogRunnable = Runnable { onPlaybackStalled() }
    private var overlayAdapter: PlayerChannelOverlayAdapter? = null
    private val hideGestureRunnable = Runnable { binding.tvGestureFeedback.visibility = View.GONE }
    private var pendingSeekTargetMs = -1L
    private var progressiveSeekDeltaMs = 0L
    private var seekLockUntilMs = 0L
    private val commitSeekRunnable = Runnable {
        if (pendingSeekTargetMs >= 0L) {
            seekLockUntilMs = System.currentTimeMillis() + 2000L
            player?.seekTo(pendingSeekTargetMs)
            pendingSeekTargetMs = -1L
            progressiveSeekDeltaMs = 0L
            handler.removeCallbacks(hideGestureRunnable)
            handler.postDelayed(hideGestureRunnable, 1200L)
        }
    }
    private var saveTickCount = 0
    private var heartbeatTickCount = 0
    private val autoNextRunnable = Runnable {
        if (currentIndex + 1 < items.size) {
            switchToIndex(currentIndex + 1)
        }
    }

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Ajustar",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Esticar",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom"
    )

    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private val retryRunnable = Runnable { startPlayback(isRetry = true) }
    private val abrCheckRunnable = object : Runnable {
        override fun run() {
            evaluateAdaptiveUpgrade()
            handler.postDelayed(this, 20_000L)
        }
    }
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateSeekProgress()
            handler.postDelayed(this, 1000L)
        }
    }

    private val pickSubtitleLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            externalSubUri = uri
            startPlayback(isRetry = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemBars()
        prefs = AppPreferences(this)
        repository = IptvRepository(this)

        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        items = (intent.getSerializableExtra(EXTRA_ITEMS) as? ArrayList<PlayableItem>) ?: emptyList()
        currentIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))

        if (items.isEmpty()) {
            finish()
            return
        }

        setupUiActions()
        setupChannelDrawer()
        setupPlayerGestures()
        startPlayback(isRetry = false)
    }

    private fun setupChannelDrawer() {
        if (items.size <= 1) {
            binding.btnChannelList.visibility = View.GONE
            return
        }
        binding.rvPlayerChannels.layoutManager = LinearLayoutManager(this)
        overlayAdapter = PlayerChannelOverlayAdapter(items, currentIndex) { selectedIdx ->
            closeChannelDrawer()
            if (selectedIdx != currentIndex) {
                switchToIndex(selectedIdx)
            }
        }
        binding.rvPlayerChannels.adapter = overlayAdapter
    }

    private fun openChannelDrawer() {
        hideControls()
        overlayAdapter?.updateSelected(currentIndex)
        binding.channelDrawer.visibility = View.VISIBLE
        binding.rvPlayerChannels.scrollToPosition(currentIndex)
        binding.rvPlayerChannels.requestFocus()
    }

    private fun closeChannelDrawer() {
        binding.channelDrawer.visibility = View.GONE
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupPlayerGestures() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (binding.channelDrawer.visibility == View.VISIBLE) {
                    closeChannelDrawer()
                } else {
                    toggleControls()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val exo = player ?: return true
                val item = items.getOrNull(currentIndex) ?: return true
                if (item.mediaType == MediaType.LIVE && overrideCatchupUrl == null) {
                    return true
                }
                val width = binding.playerView.width.coerceAtLeast(1)
                val isRight = e.x > width / 2f
                val delta = if (isRight) 10_000L else -10_000L
                val target = (exo.currentPosition + delta).coerceIn(0L, exo.duration.coerceAtLeast(0L))
                seekLockUntilMs = System.currentTimeMillis() + 1800L
                exo.seekTo(target)
                updateSeekUiImmediate(target, exo.duration)
                showControls(focusSeekBar = true)
                showGestureFeedback(if (isRight) "+10s ⏩" else "⏪ -10s")
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (kotlin.math.abs(distanceY) <= kotlin.math.abs(distanceX)) return false
                val width = binding.playerView.width.coerceAtLeast(1)
                val height = binding.playerView.height.coerceAtLeast(1)
                val isLeft = (e1?.x ?: e2.x) < width / 2f
                val deltaRatio = distanceY / height

                if (isLeft) {
                    val lp = window.attributes
                    val cur = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
                    val next = (cur + deltaRatio * 1.4f).coerceIn(0.05f, 1.0f)
                    lp.screenBrightness = next
                    window.attributes = lp
                    showGestureFeedback("☀ Brilho: ${(next * 100).toInt()}%")
                } else {
                    val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val step = if (distanceY > 0) 1 else -1
                    val nextVol = (curVol + step).coerceIn(0, maxVol)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nextVol, 0)
                    val pct = (nextVol * 100) / maxVol
                    showGestureFeedback("🔊 Volume: $pct%")
                }
                return true
            }
        })

        binding.playerView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            true
        }
    }

    private fun showGestureFeedback(text: String) {
        binding.tvGestureFeedback.text = text
        binding.tvGestureFeedback.visibility = View.VISIBLE
        handler.removeCallbacks(hideGestureRunnable)
        handler.postDelayed(hideGestureRunnable, 1200L)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupUiActions() {
        listOf(
            binding.btnBack, binding.btnPrev, binding.btnPlayPause, binding.btnNext,
            binding.btnTracks, binding.btnQuality, binding.btnAspect, binding.btnReload,
            binding.btnChannelList, binding.btnRecall, binding.btnNextEpisode,
            binding.btnCatchup, binding.btnMultiview, binding.btnPip,
            binding.btnQpAudio, binding.btnQpSubtitles, binding.btnQpEpisodes, binding.btnQpClose,
            binding.cardRecentLive1, binding.cardRecentLive2
        ).forEach { it.enableCinemaFocusZoom(1.06f) }

        binding.btnQpAudio.setOnClickListener {
            closeQuickPanel()
            showAudioOnlyDialog()
        }
        binding.btnQpSubtitles.setOnClickListener {
            closeQuickPanel()
            showSubtitlesOnlyDialog()
        }
        binding.btnQpEpisodes.setOnClickListener {
            closeQuickPanel()
            openChannelDrawer()
        }
        binding.btnQpClose.setOnClickListener { closeQuickPanel() }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnChannelList.setOnClickListener { openChannelDrawer() }
        binding.btnCloseDrawer.setOnClickListener { closeChannelDrawer() }
        binding.btnRecall.setOnClickListener {
            if (previousIndex in items.indices && previousIndex != currentIndex) {
                switchToIndex(previousIndex)
            }
        }
        binding.btnNextEpisode.setOnClickListener {
            if (currentIndex + 1 < items.size) {
                switchToIndex(currentIndex + 1)
            }
        }

        binding.btnPlayPause.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
                binding.btnPlayPause.text = "▶ Retomar"
            } else {
                p.play()
                binding.btnPlayPause.text = "⏸ Pausar"
            }
            scheduleHideControls()
        }

        binding.btnPrev.setOnClickListener { zap(-1) }
        binding.btnNext.setOnClickListener { zap(1) }

        binding.btnAspect.setOnClickListener {
            resizeIdx = (resizeIdx + 1) % resizeModes.size
            val (mode, label) = resizeModes[resizeIdx]
            binding.playerView.resizeMode = mode
            binding.btnAspect.text = label
            scheduleHideControls()
        }

        binding.btnReload.setOnClickListener {
            retryCount = 0
            overrideCatchupUrl = null
            startPlayback(isRetry = false)
            scheduleHideControls()
        }

        binding.btnTracks.setOnClickListener {
            showTracksDialog()
            scheduleHideControls()
        }

        binding.btnQuality.setOnClickListener {
            showQualityDialog()
            scheduleHideControls()
        }

        binding.btnCatchup.setOnClickListener {
            showCatchupDialog()
            scheduleHideControls()
        }

        binding.btnMultiview.setOnClickListener {
            val intent = Intent(this, MultiViewActivity::class.java).apply {
                putExtra(MultiViewActivity.EXTRA_ITEMS, ArrayList(items.take(60)))
                putExtra(MultiViewActivity.EXTRA_INITIAL_INDEX, currentIndex)
            }
            startActivity(intent)
            finish()
        }

        binding.btnPip.setOnClickListener {
            enterPipMode()
        }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.tvPosTime.text = formatDuration(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
                handler.removeCallbacks(hideControlsRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeeking = false
                val target = (seekBar?.progress ?: 0).toLong()
                seekLockUntilMs = System.currentTimeMillis() + 1800L
                player?.seekTo(target)
                scheduleHideControls()
            }
        })
    }

    private fun switchToIndex(newIndex: Int) {
        saveCurrentWatchProgress()
        previousIndex = currentIndex
        currentIndex = newIndex
        retryCount = 0
        overrideCatchupUrl = null
        externalSubUri = null
        currentVariant = null
        activeVariants = emptyList()
        failedVariantUrls.clear()
        binding.btnNextEpisode.visibility = View.GONE
        binding.btnRecall.visibility = if (previousIndex in items.indices) View.VISIBLE else View.GONE
        startPlayback(isRetry = false)
    }

    private fun zap(delta: Int) {
        if (items.size <= 1) return
        val next = (currentIndex + delta + items.size) % items.size
        switchToIndex(next)
        showControls()
    }

    private fun startPlayback(isRetry: Boolean) {
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(abrCheckRunnable)
        val item = items.getOrNull(currentIndex) ?: return

        if (activeVariants.isEmpty()) {
            activeVariants = item.variants.sortedByDescending { it.rank }
            if (activeVariants.size > 1 && isAutoQuality) {
                currentVariant = pickBestVariantForSpeed(activeVariants, getEstimatedSpeedMbps())
            } else {
                currentVariant = activeVariants.firstOrNull()
            }
        }
        updateQualityButtonUi()

        binding.tvTitle.text = item.title
        binding.tvSubtitle.text = item.subtitle
        binding.btnCatchup.visibility = if (item.hasCatchup) View.VISIBLE else View.GONE

        val isVod = item.mediaType != MediaType.LIVE || overrideCatchupUrl != null
        binding.seekContainer.visibility = if (isVod) View.VISIBLE else View.GONE

        val chosen = currentVariant
        val qualitySuffix = if (chosen != null && activeVariants.size > 1) " (${chosen.label})" else ""
        showLoading(if (isRetry) "Reconectando ($retryCount/$MAX_RETRY)..." else "Carregando ${item.title}$qualitySuffix...")

        releasePlayer()

        val exo = NovaPlayerFactory.createPlayer(
            context = this,
            bufferProfile = prefs.bufferProfile,
            userAgent = chosen?.userAgent ?: item.userAgent,
            referrer = chosen?.referrer ?: item.referrer
        )

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        showLoading("Buffering...")
                        if (!isVod && overrideCatchupUrl == null) {
                            handler.removeCallbacks(stallWatchdogRunnable)
                            handler.postDelayed(stallWatchdogRunnable, 4_500L)
                        }
                        if (lastReadyAtMs > 0L && System.currentTimeMillis() - lastReadyAtMs < 20_000L) {
                            rebufferCountInWindow++
                            if (rebufferCountInWindow >= 2 && isAutoQuality && activeVariants.size > 1) {
                                trySwitchToAnotherSource("Rede instável")
                            }
                        }
                    }
                    Player.STATE_READY -> {
                        handler.removeCallbacks(stallWatchdogRunnable)
                        retryCount = 0
                        seekLockUntilMs = 0L
                        lastReadyAtMs = System.currentTimeMillis()
                        hideLoading()
                        binding.btnPlayPause.text = if (exo.isPlaying) "⏸ Pausar" else "▶ Retomar"
                        if (isVod && exo.duration > 0) {
                            binding.seekBar.max = exo.duration.toInt()
                            binding.tvDurTime.text = formatDuration(exo.duration)
                        }
                    }
                    Player.STATE_ENDED -> {
                        hideLoading()
                        if (item.mediaType == MediaType.SERIES && currentIndex + 1 < items.size) {
                            binding.btnNextEpisode.text = "▶ Próximo Episódio (3s)"
                            binding.btnNextEpisode.visibility = View.VISIBLE
                            binding.btnNextEpisode.requestFocus()
                            handler.removeCallbacks(autoNextRunnable)
                            handler.postDelayed(autoNextRunnable, 3000L)
                        } else if (item.mediaType == MediaType.LIVE && overrideCatchupUrl == null && prefs.autoReconnect) {
                            scheduleRetry()
                        }
                    }
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                handler.removeCallbacks(stallWatchdogRunnable)
                val reason = extractErrorReason(error)
                if (activeVariants.size > 1 && trySwitchToAnotherSource("Fonte falhou")) {
                    return
                }
                if (prefs.autoReconnect) {
                    scheduleRetry(reason)
                } else {
                    hideLoading()
                    Toast.makeText(this@PlayerActivity, "Erro ($reason)", Toast.LENGTH_LONG).show()
                }
            }
        })

        val playUrl = overrideCatchupUrl ?: chosen?.streamUrl ?: item.streamUrl
        val subMime = if (externalSubUri?.toString()?.lowercase()?.endsWith(".vtt") == true) {
            MimeTypes.TEXT_VTT
        } else {
            MimeTypes.APPLICATION_SUBRIP
        }

        val mediaItem = NovaPlayerFactory.buildMediaItem(playUrl, externalSubUri, subMime)
        binding.playerView.player = exo
        exo.setMediaItem(mediaItem)
        if (!isRetry && isVod && item.resumePositionMs > 5_000L) {
            exo.seekTo(item.resumePositionMs)
        }
        exo.prepare()
        exo.playWhenReady = true
        player = exo

        loadLiveEpgSubtitle(item)
        sendPlaybackHeartbeat(item)
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
        if (isAutoQuality && activeVariants.size > 1 && overrideCatchupUrl == null) {
            handler.postDelayed(abrCheckRunnable, 20_000L)
        }
        scheduleHideControls()
    }

    private fun loadLiveEpgSubtitle(item: PlayableItem) {
        if (item.mediaType != MediaType.LIVE || item.epgChannelId.isBlank()) return
        lifecycleScope.launch {
            val profile = repository.observeActiveProfile().firstOrNull() ?: return@launch
            val prog = repository.getCurrentEpg(profile.id, item.epgChannelId)
            if (prog != null) {
                val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                binding.tvSubtitle.text = "Agora: ${prog.title} (${fmt.format(Date(prog.startMs))} - ${fmt.format(Date(prog.endMs))})"
            }
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun extractErrorReason(error: PlaybackException): String {
        val cause = error.cause
        if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
            return "HTTP ${cause.responseCode}"
        }
        val root = cause?.cause ?: cause
        val rootName = root?.javaClass?.simpleName ?: error.errorCodeName
        val rootMsg = root?.message?.take(60).orEmpty()
        return if (rootMsg.isNotBlank()) "$rootName: $rootMsg" else rootName
    }

    private fun scheduleRetry(reason: String = "") {
        // Libera a conexão imediatamente para não prender o slot no servidor IPTV
        releasePlayer()
        if (retryCount < MAX_RETRY) {
            retryCount++
            val delay = when (retryCount) {
                1 -> 300L
                2 -> 1000L
                else -> 2000L
            }
            val detail = if (reason.isNotBlank()) " [$reason]" else ""
            showLoading("Sinal instável$detail. Reconectando ($retryCount/$MAX_RETRY)...")
            handler.removeCallbacks(retryRunnable)
            handler.postDelayed(retryRunnable, delay)
        } else {
            hideLoading()
            val detail = if (reason.isNotBlank()) " ($reason)" else ""
            Toast.makeText(this, "Não foi possível conectar ao canal$detail.", Toast.LENGTH_LONG).show()
            showControls()
        }
    }

    private fun onPlaybackStalled() {
        if (isVodPlayback() || overrideCatchupUrl != null) return
        if (activeVariants.size > 1 && trySwitchToAnotherSource("Canal travou")) {
            return
        }
        if (prefs.autoReconnect) {
            scheduleRetry("Sinal travado")
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun getEstimatedSpeedMbps(): Double {
        val meterEstimate = NovaPlayerFactory.getBandwidthMeter(this).bitrateEstimate
        if (meterEstimate > 0L && meterEstimate != Long.MAX_VALUE) {
            return meterEstimate / 1_000_000.0
        }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val kbps = caps?.linkDownstreamBandwidthKbps ?: 0
        return if (kbps > 0) kbps / 1000.0 else 5.0
    }

    private fun pickBestVariantForSpeed(variants: List<QualityVariant>, mbps: Double): QualityVariant {
        val targetMaxRank = when {
            mbps < 2.5 -> 1 // SD
            mbps < 6.5 -> 2 // HD
            mbps < 16.0 -> 3 // FHD
            else -> 4 // 4K
        }
        return variants.firstOrNull { it.rank <= targetMaxRank } ?: variants.last()
    }

    private fun trySwitchToAnotherSource(reasonPrefix: String = "Rede lenta"): Boolean {
        val cur = currentVariant ?: return false
        if (activeVariants.size <= 1) return false
        failedVariantUrls.add(cur.streamUrl)

        var candidates = activeVariants.filter { it.streamUrl != cur.streamUrl && !failedVariantUrls.contains(it.streamUrl) }
        if (candidates.isEmpty()) {
            failedVariantUrls.clear()
            failedVariantUrls.add(cur.streamUrl)
            candidates = activeVariants.filter { it.streamUrl != cur.streamUrl }
        }
        val next = candidates.firstOrNull { it.rank <= cur.rank } ?: candidates.firstOrNull() ?: return false

        rebufferCountInWindow = 0
        retryCount = 0
        currentVariant = next
        Toast.makeText(this, "⚡ $reasonPrefix: trocando para ${next.label}", Toast.LENGTH_SHORT).show()
        startPlayback(isRetry = false)
        return true
    }

    private fun evaluateAdaptiveUpgrade() {
        if (!isAutoQuality || activeVariants.size <= 1 || overrideCatchupUrl != null) return
        if (rebufferCountInWindow > 0) {
            rebufferCountInWindow = 0
            return
        }
        val cur = currentVariant ?: return
        val mbps = getEstimatedSpeedMbps()
        val bestForSpeed = pickBestVariantForSpeed(activeVariants, mbps)
        if (bestForSpeed.rank > cur.rank) {
            currentVariant = bestForSpeed
            val speedStr = String.format(Locale.getDefault(), "%.1f", mbps)
            Toast.makeText(this, "⚡ Internet estável ($speedStr Mbps): subindo para ${bestForSpeed.label}", Toast.LENGTH_SHORT).show()
            startPlayback(isRetry = false)
        }
    }

    private fun updateQualityButtonUi() {
        if (activeVariants.size <= 1) {
            binding.btnQuality.visibility = View.GONE
            return
        }
        binding.btnQuality.visibility = View.VISIBLE
        val label = currentVariant?.label ?: "Padrão"
        binding.btnQuality.text = if (isAutoQuality) "⚡ Auto ($label)" else "⚙ $label"
    }

    private fun showQualityDialog() {
        if (activeVariants.isEmpty()) return
        val mbps = String.format(Locale.getDefault(), "%.1f", getEstimatedSpeedMbps())
        val labels = mutableListOf<String>()
        val autoMark = if (isAutoQuality) "✓ " else ""
        labels.add("${autoMark}⚡ Automático (Rede: $mbps Mbps)")
        activeVariants.forEach { v ->
            val mark = if (!isAutoQuality && currentVariant?.streamUrl == v.streamUrl) "✓ " else ""
            labels.add("$mark${v.label}")
        }

        AlertDialog.Builder(this)
            .setTitle("Qualidade do Canal")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    isAutoQuality = true
                    currentVariant = pickBestVariantForSpeed(activeVariants, getEstimatedSpeedMbps())
                } else {
                    isAutoQuality = false
                    currentVariant = activeVariants[which - 1]
                }
                rebufferCountInWindow = 0
                startPlayback(isRetry = false)
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun isVodPlayback(): Boolean {
        val item = items.getOrNull(currentIndex) ?: return false
        return item.mediaType != MediaType.LIVE || overrideCatchupUrl != null
    }

    private fun openQuickPanel() {
        hideControls()
        val item = items.getOrNull(currentIndex)
        binding.btnQpEpisodes.visibility = if (item?.mediaType == MediaType.SERIES && items.size > 1) View.VISIBLE else View.GONE
        binding.vodQuickPanel.visibility = View.VISIBLE
        binding.btnQpAudio.requestFocus()
    }

    private fun closeQuickPanel() {
        binding.vodQuickPanel.visibility = View.GONE
    }

    private fun showAudioOnlyDialog() {
        val exo = player ?: return
        val audioTracks = TrackManager.getAudioTracks(exo)
        if (audioTracks.isEmpty()) {
            Toast.makeText(this, "Nenhuma faixa de áudio adicional encontrada.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = audioTracks.map { (if (it.isSelected) "✓ " else "") + it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Selecionar Áudio")
            .setItems(labels) { _, which ->
                TrackManager.selectTrack(exo, audioTracks[which], C.TRACK_TYPE_AUDIO)
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showSubtitlesOnlyDialog() {
        val exo = player ?: return
        val subTracks = TrackManager.getSubtitleTracks(exo)
        val labels = mutableListOf("Desativar Legendas")
        val actions = mutableListOf<() -> Unit>({ TrackManager.disableSubtitles(exo) })
        subTracks.forEach { tr ->
            labels.add((if (tr.isSelected) "✓ " else "") + tr.label)
            actions.add { TrackManager.selectTrack(exo, tr, C.TRACK_TYPE_TEXT) }
        }
        labels.add("+ Carregar Legenda Externa (.srt / .vtt)")
        actions.add { pickSubtitleLauncher.launch(arrayOf("*/*")) }

        AlertDialog.Builder(this)
            .setTitle("Selecionar Legenda")
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun togglePlayPauseAndShowOsd() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.pause()
            binding.btnPlayPause.text = "▶ Retomar"
            showGestureFeedback("⏸ Pausado")
        } else {
            exo.play()
            binding.btnPlayPause.text = "⏸ Pausar"
            showGestureFeedback("▶ Reproduzindo")
        }
        showControls(focusSeekBar = false)
    }

    private fun updateSeekUiImmediate(posMs: Long, durMs: Long) {
        if (durMs > 0L) {
            binding.seekBar.max = durMs.toInt()
            binding.seekBar.progress = posMs.toInt()
            binding.tvPosTime.text = formatDuration(posMs)
            binding.tvDurTime.text = "-${formatDuration((durMs - posMs).coerceAtLeast(0L))}"
        }
    }

    private fun seekRelative(deltaMs: Long) {
        val exo = player ?: return
        if (!isVodPlayback() || exo.duration <= 0L) return
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, exo.duration)
        seekLockUntilMs = System.currentTimeMillis() + 1800L
        exo.seekTo(target)
        updateSeekUiImmediate(target, exo.duration)
        val sec = (deltaMs / 1000L)
        val sign = if (sec >= 0) "+${sec}s ⏩" else "⏪ ${sec}s"
        showGestureFeedback("$sign  (${formatDuration(target)})")
        showControls(focusSeekBar = true)
    }

    private fun showTracksDialog() {
        val exo = player ?: return
        val audioTracks = TrackManager.getAudioTracks(exo)
        val subTracks = TrackManager.getSubtitleTracks(exo)

        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        audioTracks.forEach { tr ->
            val mark = if (tr.isSelected) "✓ " else ""
            labels.add("${mark}Áudio: ${tr.label}")
            actions.add { TrackManager.selectTrack(exo, tr, C.TRACK_TYPE_AUDIO) }
        }

        labels.add("Desativar Legendas")
        actions.add { TrackManager.disableSubtitles(exo) }

        subTracks.forEach { tr ->
            val mark = if (tr.isSelected) "✓ " else ""
            labels.add("${mark}Legenda: ${tr.label}")
            actions.add { TrackManager.selectTrack(exo, tr, C.TRACK_TYPE_TEXT) }
        }

        labels.add("+ Carregar Legenda Externa (.srt / .vtt)")
        actions.add { pickSubtitleLauncher.launch(arrayOf("*/*")) }

        AlertDialog.Builder(this)
            .setTitle("Faixas de Áudio e Legendas")
            .setItems(labels.toTypedArray()) { _, which ->
                actions[which].invoke()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showCatchupDialog() {
        val item = items.getOrNull(currentIndex) ?: return
        lifecycleScope.launch {
            val profile = repository.observeActiveProfile().firstOrNull() ?: return@launch
            if (profile.type != ProfileType.XTREAM.name) {
                Toast.makeText(this@PlayerActivity, "Catch-up disponível apenas para listas Xtream.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val now = System.currentTimeMillis()
            val from = now - (item.catchupDays.coerceAtLeast(1) * 24L * 60L * 60L * 1000L)
            val programs = repository.getEpgInRange(profile.id, item.epgChannelId, from, now)
                .filter { it.endMs <= now }
                .sortedByDescending { it.startMs }
                .take(25)

            if (programs.isEmpty()) {
                Toast.makeText(this@PlayerActivity, "Nenhum programa anterior no EPG para Catch-up.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val fmtLabel = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
            val fmtParam = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
            val titles = programs.map { "${fmtLabel.format(Date(it.startMs))} - ${it.title}" }.toTypedArray()

            AlertDialog.Builder(this@PlayerActivity)
                .setTitle("Catch-up TV (Voltar Programação)")
                .setItems(titles) { _, idx ->
                    val chosen = programs[idx]
                    val durationMin = ((chosen.endMs - chosen.startMs) / 60_000L).toInt().coerceAtLeast(15)
                    val startStr = fmtParam.format(Date(chosen.startMs))
                    overrideCatchupUrl = XtreamApiClient().buildCatchupUrl(
                        profile.serverUrl,
                        profile.username,
                        profile.password,
                        item.streamId,
                        startStr,
                        durationMin
                    )
                    binding.tvSubtitle.text = "Catch-up: ${chosen.title}"
                    startPlayback(isRetry = false)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            hideControls()
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        } else {
            Toast.makeText(this, "PiP requer Android 8.0 ou superior.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            hideControls()
        }
    }

    private fun sendPlaybackHeartbeat(item: PlayableItem) {
        val statusLabel = when (item.mediaType) {
            MediaType.MOVIE -> "Assistindo Filme"
            MediaType.SERIES -> "Assistindo Série"
            else -> "Assistindo TV"
        }
        TelemetryManager.sendHeartbeat(
            context = this,
            status = statusLabel,
            watchingTitle = item.title,
            watchingType = item.mediaType
        ) { cmd ->
            releasePlayer()
            TelemetryManager.handleCommandOnActivity(this, cmd) {
                startPlayback(isRetry = false)
            }
        }
    }

    private fun updateSeekProgress() {
        val exo = player ?: return
        val currentItem = items.getOrNull(currentIndex)
        if (currentItem != null && exo.isPlaying) {
            heartbeatTickCount++
            if (heartbeatTickCount % 30 == 0) {
                sendPlaybackHeartbeat(currentItem)
            }
        }
        if (!isSeeking && pendingSeekTargetMs < 0L && System.currentTimeMillis() > seekLockUntilMs && exo.duration > 0) {
            val pos = exo.currentPosition
            val dur = exo.duration
            val remaining = (dur - pos).coerceAtLeast(0L)
            binding.seekBar.progress = pos.toInt()
            binding.seekBar.secondaryProgress = ((dur * exo.bufferedPercentage) / 100L).toInt()
            binding.tvPosTime.text = formatDuration(pos)
            binding.tvDurTime.text = "-${formatDuration(remaining)}"
            binding.tvBufferPercent.text = "Buffer: ${exo.bufferedPercentage}%"

            val item = items.getOrNull(currentIndex)
            if (item != null && item.mediaType != MediaType.LIVE && exo.isPlaying) {
                saveTickCount++
                if (saveTickCount % 5 == 0) {
                    saveCurrentWatchProgress()
                }
            }
            if (item?.mediaType == MediaType.SERIES && currentIndex + 1 < items.size) {
                if (remaining in 1L..30_000L) {
                    binding.btnNextEpisode.text = "▶ Próximo Episódio (${remaining / 1000L}s)"
                    binding.btnNextEpisode.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun saveCurrentWatchProgress() {
        val exo = player ?: return
        val item = items.getOrNull(currentIndex) ?: return
        val pos = exo.currentPosition
        val dur = exo.duration.coerceAtLeast(0L)
        if (item.mediaType != MediaType.LIVE && pos < 5_000L) return

        val activeUserId = UserProfileManager.getActiveUserId(this)
        CoroutineScope(Dispatchers.IO).launch {
            val profile = repository.observeActiveProfile().firstOrNull() ?: return@launch
            repository.upsertWatchProgress(
                WatchHistoryEntity(
                    profileId = profile.id,
                    userProfileId = activeUserId,
                    contentKey = item.contentKey,
                    mediaType = item.mediaType,
                    title = item.title,
                    iconUrl = item.iconUrl,
                    streamUrl = item.streamUrl,
                    positionMs = if (item.mediaType == MediaType.LIVE) 0L else pos,
                    durationMs = dur,
                    seriesId = item.seriesId,
                    seasonNum = item.seasonNum,
                    episodeNum = item.episodeNum
                )
            )
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = (ms / 1000L).coerceAtLeast(0L)
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
        }
    }

    private fun showLoading(msg: String) {
        binding.loadingText.text = msg
        binding.loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        binding.loadingOverlay.visibility = View.GONE
    }

    private fun toggleControls() {
        if (binding.controlsOverlay.visibility == View.VISIBLE) hideControls() else showControls()
    }

    private fun showControls(focusSeekBar: Boolean = false) {
        binding.controlsOverlay.visibility = View.VISIBLE
        if (!isVodPlayback()) {
            loadRecentLiveThumbnails()
            val targetBtn = when {
                binding.btnQuality.visibility == View.VISIBLE -> binding.btnQuality
                else -> binding.btnChannelList
            }
            targetBtn.requestFocus()
        } else {
            binding.recentLiveStrip.visibility = View.GONE
            if (focusSeekBar && binding.seekContainer.visibility == View.VISIBLE) {
                binding.seekBar.requestFocus()
            } else {
                binding.btnPlayPause.requestFocus()
            }
        }
        scheduleHideControls()
    }

    private fun loadRecentLiveThumbnails() {
        val current = items.getOrNull(currentIndex) ?: return
        lifecycleScope.launch {
            val profile = repository.observeActiveProfile().firstOrNull() ?: run {
                binding.recentLiveStrip.visibility = View.GONE
                return@launch
            }
            val recent = repository.observeHistoryByType(profile.id, MediaType.LIVE)
                .firstOrNull()
                .orEmpty()
                .filter { it.contentKey != current.contentKey && it.streamUrl != current.streamUrl }
                .take(2)

            if (recent.isEmpty()) {
                binding.recentLiveStrip.visibility = View.GONE
                return@launch
            }
            binding.recentLiveStrip.visibility = View.VISIBLE
            bindRecentLiveCard(recent.getOrNull(0), binding.cardRecentLive1, binding.ivRecentLive1, binding.tvRecentLive1)
            bindRecentLiveCard(recent.getOrNull(1), binding.cardRecentLive2, binding.ivRecentLive2, binding.tvRecentLive2)
        }
    }

    private fun bindRecentLiveCard(
        entry: WatchHistoryEntity?,
        card: View,
        iconView: android.widget.ImageView,
        titleView: android.widget.TextView
    ) {
        if (entry == null) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        titleView.text = entry.title
        if (entry.iconUrl.isNotBlank()) {
            Glide.with(this).load(entry.iconUrl).placeholder(R.drawable.ic_tv_logo).into(iconView)
        } else {
            iconView.setImageResource(R.drawable.ic_tv_logo)
        }
        card.setOnClickListener {
            val idx = items.indexOfFirst { it.streamUrl == entry.streamUrl || it.contentKey == entry.contentKey }
            if (idx >= 0) {
                hideControls()
                switchToIndex(idx)
            } else {
                val fallbackItem = PlayableItem(
                    contentKey = entry.contentKey,
                    title = entry.title,
                    subtitle = "Último canal",
                    streamUrl = entry.streamUrl,
                    iconUrl = entry.iconUrl,
                    mediaType = MediaType.LIVE
                )
                items = listOf(fallbackItem) + items
                hideControls()
                switchToIndex(0)
            }
        }
    }

    private fun hideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        binding.controlsOverlay.visibility = View.GONE
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, AUTO_HIDE_MS)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && binding.controlsOverlay.visibility == View.VISIBLE) {
            scheduleHideControls()
        }
        val isDpadHoriz = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (isDpadHoriz &&
            isVodPlayback() &&
            binding.vodQuickPanel.visibility != View.VISIBLE &&
            binding.channelDrawer.visibility != View.VISIBLE &&
            (binding.controlsOverlay.visibility != View.VISIBLE || binding.seekBar.hasFocus())
        ) {
            if (handleVodDpadSeek(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleVodDpadSeek(event: KeyEvent): Boolean {
        val exo = player ?: return true
        if (exo.duration <= 0L) return true
        if (event.action == KeyEvent.ACTION_DOWN) {
            handler.removeCallbacks(commitSeekRunnable)
            val isRight = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            val stepMs = when {
                event.repeatCount == 0 -> 10_000L
                event.repeatCount <= 4 -> 30_000L
                event.repeatCount <= 10 -> 60_000L
                else -> 120_000L
            }
            val signedStep = if (isRight) stepMs else -stepMs
            val basePos = if (pendingSeekTargetMs >= 0L) pendingSeekTargetMs else exo.currentPosition
            val target = (basePos + signedStep).coerceIn(0L, exo.duration)
            progressiveSeekDeltaMs += signedStep
            pendingSeekTargetMs = target
            updateSeekUiImmediate(target, exo.duration)
            showControls(focusSeekBar = true)
            val deltaSec = progressiveSeekDeltaMs / 1000L
            val arrow = if (deltaSec >= 0) "⏩ +${deltaSec}s" else "⏪ ${deltaSec}s"
            binding.tvGestureFeedback.text = "$arrow  •  ${formatDuration(target)}"
            binding.tvGestureFeedback.visibility = View.VISIBLE
            handler.removeCallbacks(hideGestureRunnable)
            return true
        }
        if (event.action == KeyEvent.ACTION_UP && pendingSeekTargetMs >= 0L) {
            handler.removeCallbacks(commitSeekRunnable)
            handler.postDelayed(commitSeekRunnable, 350L)
            return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val repeatCount = event?.repeatCount ?: 0
        val vod = isVodPlayback()

        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                togglePlayPauseAndShowOsd()
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekRelative(30_000L)
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekRelative(-30_000L)
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (currentIndex + 1 < items.size) {
                    switchToIndex(currentIndex + 1)
                    true
                } else super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (binding.vodQuickPanel.visibility != View.VISIBLE &&
                    binding.channelDrawer.visibility != View.VISIBLE &&
                    (binding.controlsOverlay.visibility != View.VISIBLE || binding.seekBar.hasFocus())
                ) {
                    if (!vod) {
                        showControls(focusSeekBar = false)
                    } else {
                        togglePlayPauseAndShowOsd()
                    }
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!vod && binding.controlsOverlay.visibility != View.VISIBLE) {
                    zap(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (binding.controlsOverlay.visibility != View.VISIBLE &&
                    binding.channelDrawer.visibility != View.VISIBLE &&
                    binding.vodQuickPanel.visibility != View.VISIBLE
                ) {
                    showControls(focusSeekBar = vod)
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.controlsOverlay.visibility != View.VISIBLE &&
                    binding.channelDrawer.visibility != View.VISIBLE &&
                    binding.vodQuickPanel.visibility != View.VISIBLE
                ) {
                    if (vod) {
                        openQuickPanel()
                    } else if (items.size > 1) {
                        openChannelDrawer()
                    }
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                handler.removeCallbacks(autoNextRunnable)
                when {
                    binding.vodQuickPanel.visibility == View.VISIBLE -> {
                        closeQuickPanel()
                        true
                    }
                    binding.channelDrawer.visibility == View.VISIBLE -> {
                        closeChannelDrawer()
                        true
                    }
                    binding.controlsOverlay.visibility == View.VISIBLE -> {
                        hideControls()
                        true
                    }
                    else -> {
                        player?.pause()
                        saveCurrentWatchProgress()
                        finish()
                        true
                    }
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return super.onKeyUp(keyCode, event)
    }

    override fun stopPlaybackForRemoteCommand() {
        releasePlayer()
    }

    private fun releasePlayer() {
        handler.removeCallbacks(stallWatchdogRunnable)
        handler.removeCallbacks(progressRunnable)
        player?.release()
        player = null
    }

    override fun onStop() {
        super.onStop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            return
        }
        saveCurrentWatchProgress()
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
    }
}
