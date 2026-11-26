package com.willianswill.novatv.core.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
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
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.willianswill.novatv.core.database.IptvRepository
import com.willianswill.novatv.core.database.entity.MediaType
import com.willianswill.novatv.core.database.entity.ProfileType
import com.willianswill.novatv.core.database.entity.WatchHistoryEntity
import com.willianswill.novatv.core.network.XtreamApiClient
import com.willianswill.novatv.databinding.ActivityPlayerBinding
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ITEMS = "extra_items"
        const val EXTRA_INDEX = "extra_index"
        private const val MAX_RETRY = 5
        private const val AUTO_HIDE_MS = 5000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var prefs: AppPreferences
    private lateinit var repository: IptvRepository
    private var player: ExoPlayer? = null

    private var items: List<PlayableItem> = emptyList()
    private var currentIndex = 0
    private var retryCount = 0
    private var resizeIdx = 0
    private var isSeeking = false
    private var overrideCatchupUrl: String? = null
    private var externalSubUri: Uri? = null

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Ajustar",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Esticar",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom"
    )

    private val handler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private val retryRunnable = Runnable { startPlayback(isRetry = true) }
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
        startPlayback(isRetry = false)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupUiActions() {
        binding.playerView.setOnClickListener { toggleControls() }
        binding.btnBack.setOnClickListener { finish() }

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
                player?.seekTo((seekBar?.progress ?: 0).toLong())
                scheduleHideControls()
            }
        })
    }

    private fun zap(delta: Int) {
        if (items.size <= 1) return
        saveCurrentWatchProgress()
        currentIndex = (currentIndex + delta + items.size) % items.size
        retryCount = 0
        overrideCatchupUrl = null
        externalSubUri = null
        startPlayback(isRetry = false)
        showControls()
    }

    private fun startPlayback(isRetry: Boolean) {
        handler.removeCallbacks(retryRunnable)
        val item = items.getOrNull(currentIndex) ?: return

        binding.tvTitle.text = item.title
        binding.tvSubtitle.text = item.subtitle
        binding.btnCatchup.visibility = if (item.hasCatchup) View.VISIBLE else View.GONE

        val isVod = item.mediaType != MediaType.LIVE || overrideCatchupUrl != null
        binding.seekContainer.visibility = if (isVod) View.VISIBLE else View.GONE

        showLoading(if (isRetry) "Reconectando ($retryCount/$MAX_RETRY)..." else "Carregando ${item.title}...")

        releasePlayer()

        val exo = NovaPlayerFactory.createPlayer(
            context = this,
            bufferProfile = prefs.bufferProfile,
            userAgent = item.userAgent,
            referrer = item.referrer
        )

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> showLoading("Buffering...")
                    Player.STATE_READY -> {
                        retryCount = 0
                        hideLoading()
                        binding.btnPlayPause.text = if (exo.isPlaying) "⏸ Pausar" else "▶ Retomar"
                        if (isVod && exo.duration > 0) {
                            binding.seekBar.max = exo.duration.toInt()
                            binding.tvDurTime.text = formatDuration(exo.duration)
                        }
                    }
                    Player.STATE_ENDED -> {
                        hideLoading()
                        if (item.mediaType == MediaType.LIVE && overrideCatchupUrl == null && prefs.autoReconnect) {
                            scheduleRetry()
                        }
                    }
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (prefs.autoReconnect) {
                    scheduleRetry()
                } else {
                    hideLoading()
                    Toast.makeText(this@PlayerActivity, "Erro de reprodução: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                }
            }
        })

        val playUrl = overrideCatchupUrl ?: item.streamUrl
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
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
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

    private fun scheduleRetry() {
        if (retryCount < MAX_RETRY) {
            retryCount++
            val delay = (retryCount * 1500L).coerceAtMost(6000L)
            showLoading("Sinal instável. Reconectando ($retryCount/$MAX_RETRY)...")
            handler.removeCallbacks(retryRunnable)
            handler.postDelayed(retryRunnable, delay)
        } else {
            hideLoading()
            Toast.makeText(this, "Não foi possível reconectar ao stream.", Toast.LENGTH_LONG).show()
            showControls()
        }
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

    private fun updateSeekProgress() {
        val exo = player ?: return
        if (!isSeeking && exo.duration > 0) {
            binding.seekBar.progress = exo.currentPosition.toInt()
            binding.tvPosTime.text = formatDuration(exo.currentPosition)
        }
    }

    private fun saveCurrentWatchProgress() {
        val exo = player ?: return
        val item = items.getOrNull(currentIndex) ?: return
        val pos = exo.currentPosition
        val dur = exo.duration.coerceAtLeast(0L)
        if (pos < 5_000L && item.mediaType != MediaType.LIVE) return

        lifecycleScope.launch {
            val profile = repository.observeActiveProfile().firstOrNull() ?: return@launch
            repository.upsertWatchProgress(
                WatchHistoryEntity(
                    profileId = profile.id,
                    contentKey = item.contentKey,
                    mediaType = item.mediaType,
                    title = item.title,
                    iconUrl = item.iconUrl,
                    streamUrl = item.streamUrl,
                    positionMs = if (item.mediaType == MediaType.LIVE) 0L else pos,
                    durationMs = dur
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

    private fun showControls() {
        binding.controlsOverlay.visibility = View.VISIBLE
        binding.btnPlayPause.requestFocus()
        scheduleHideControls()
    }

    private fun hideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        binding.controlsOverlay.visibility = View.GONE
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, AUTO_HIDE_MS)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> { zap(1); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (binding.controlsOverlay.visibility != View.VISIBLE) {
                    showControls()
                    true
                } else {
                    scheduleHideControls()
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.controlsOverlay.visibility != View.VISIBLE) {
                    zap(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                    true
                } else {
                    scheduleHideControls()
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (binding.controlsOverlay.visibility == View.VISIBLE) {
                    hideControls()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun releasePlayer() {
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
