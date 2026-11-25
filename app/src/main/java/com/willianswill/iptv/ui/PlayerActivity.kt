package com.willianswill.iptv.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import com.willianswill.iptv.R
import com.willianswill.iptv.databinding.ActivityPlayerBinding
import com.willianswill.iptv.model.Channel
import com.willianswill.iptv.storage.PlaylistRepository
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNELS = "extra_channels"
        const val EXTRA_INDEX = "extra_index"
        private const val MAX_RETRY_ATTEMPTS = 5
        private const val CONTROLS_AUTO_HIDE_MS = 5000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var repository: PlaylistRepository
    private var player: ExoPlayer? = null

    private var channels: List<Channel> = emptyList()
    private var currentIndex: Int = 0
    private var retryCount = 0
    private var resizeModeIndex = 0

    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Tela: Ajustar",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Tela: Esticar",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Tela: Zoom"
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { hideControls() }
    private val retryRunnable = Runnable { playCurrentChannel(isRetry = true) }

    private val sharedOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 30, TimeUnit.SECONDS))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemBars()
        repository = PlaylistRepository(this)

        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        channels = (intent.getSerializableExtra(EXTRA_CHANNELS) as? ArrayList<Channel>) ?: emptyList()
        currentIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (channels.size - 1).coerceAtLeast(0))

        if (channels.isEmpty()) {
            Toast.makeText(this, "Nenhum canal selecionado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setupControls()
        playCurrentChannel(isRetry = false)
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupControls() {
        binding.playerView.setOnClickListener {
            toggleControls()
        }

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnPlayPause.setOnClickListener {
            val p = player ?: return@setOnClickListener
            if (p.isPlaying) {
                p.pause()
                binding.btnPlayPause.setImageResource(R.drawable.ic_play)
            } else {
                p.play()
                binding.btnPlayPause.setImageResource(R.drawable.ic_pause)
            }
            scheduleHideControls()
        }

        binding.btnPrevChannel.setOnClickListener {
            zapChannel(-1)
        }

        binding.btnNextChannel.setOnClickListener {
            zapChannel(1)
        }

        binding.btnAspectRatio.setOnClickListener {
            resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
            val (mode, label) = resizeModes[resizeModeIndex]
            binding.playerView.resizeMode = mode
            binding.btnAspectRatio.text = label
            scheduleHideControls()
        }

        binding.btnReconnect.setOnClickListener {
            retryCount = 0
            playCurrentChannel(isRetry = false)
            scheduleHideControls()
        }

        binding.btnFavorite.setOnClickListener {
            val ch = channels.getOrNull(currentIndex) ?: return@setOnClickListener
            val isFav = repository.toggleFavorite(ch.url)
            ch.isFavorite = isFav
            updateFavoriteIcon(isFav)
            scheduleHideControls()
        }
    }

    private fun zapChannel(delta: Int) {
        if (channels.isEmpty()) return
        currentIndex = (currentIndex + delta + channels.size) % channels.size
        retryCount = 0
        playCurrentChannel(isRetry = false)
        showControls()
    }

    @OptIn(UnstableApi::class)
    private fun playCurrentChannel(isRetry: Boolean) {
        mainHandler.removeCallbacks(retryRunnable)
        val channel = channels.getOrNull(currentIndex) ?: return

        binding.tvChannelTitle.text = channel.name
        binding.tvChannelGroup.text = channel.group
        updateFavoriteIcon(channel.isFavorite)

        if (!isRetry) {
            repository.recordRecent(channel.url)
        }

        showLoading(if (isRetry) "Reconectando (${retryCount}/$MAX_RETRY_ATTEMPTS)..." else "Carregando ${channel.name}...")

        releasePlayer()

        // Buffer otimizado para IPTV ao vivo e VOD sem travas
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000, // minBufferMs
                50_000, // maxBufferMs
                2_500,  // bufferForPlaybackMs (início rápido)
                5_000   // bufferForPlaybackAfterRebufferMs (evita engasgos seguidos)
            )
            .setBackBuffer(5_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Decodificação por hardware com fallback automático para software em TV Box
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        val headers = mutableMapOf<String, String>()
        val ua = channel.userAgent?.takeIf { it.isNotBlank() } ?: "VLC/3.0.20 LibVLC/3.0.20"
        channel.referrer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }

        val dataSourceFactory = OkHttpDataSource.Factory(sharedOkHttpClient)
            .setUserAgent(ua)
            .setDefaultRequestProperties(headers)

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        showLoading("Buffering...")
                    }
                    Player.STATE_READY -> {
                        retryCount = 0
                        hideLoading()
                        binding.btnPlayPause.setImageResource(
                            if (exoPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                        )
                    }
                    Player.STATE_ENDED -> {
                        hideLoading()
                        // Em streams ao vivo que fecham conexão, tenta reconectar
                        scheduleAutoRetry()
                    }
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                scheduleAutoRetry()
            }
        })

        binding.playerView.player = exoPlayer
        exoPlayer.setMediaItem(MediaItem.fromUri(channel.url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        player = exoPlayer

        scheduleHideControls()
    }

    private fun scheduleAutoRetry() {
        if (retryCount < MAX_RETRY_ATTEMPTS) {
            retryCount++
            val delayMs = (retryCount * 1500L).coerceAtMost(6000L)
            showLoading("Sinal instável. Reconectando ($retryCount/$MAX_RETRY_ATTEMPTS)...")
            mainHandler.removeCallbacks(retryRunnable)
            mainHandler.postDelayed(retryRunnable, delayMs)
        } else {
            hideLoading()
            Toast.makeText(
                this,
                "Não foi possível reproduzir este canal no momento.",
                Toast.LENGTH_LONG
            ).show()
            showControls()
        }
    }

    private fun updateFavoriteIcon(isFav: Boolean) {
        binding.btnFavorite.setImageResource(
            if (isFav) R.drawable.ic_star_filled else R.drawable.ic_star_outline
        )
    }

    private fun showLoading(msg: String) {
        binding.loadingText.text = msg
        binding.loadingOverlay.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        binding.loadingOverlay.visibility = View.GONE
    }

    private fun toggleControls() {
        if (binding.controlsOverlay.visibility == View.VISIBLE) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        binding.controlsOverlay.visibility = View.VISIBLE
        binding.btnPlayPause.requestFocus()
        scheduleHideControls()
    }

    private fun hideControls() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        binding.controlsOverlay.visibility = View.GONE
    }

    private fun scheduleHideControls() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, CONTROLS_AUTO_HIDE_MS)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_CHANNEL_UP -> {
                zapChannel(1)
                true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                zapChannel(-1)
                true
            }
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
                    zapChannel(if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                    true
                } else {
                    scheduleHideControls()
                    super.onKeyDown(keyCode, event)
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (binding.controlsOverlay.visibility != View.VISIBLE) {
                    showControls()
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
        player?.release()
        player = null
    }

    override fun onStop() {
        super.onStop()
        mainHandler.removeCallbacksAndMessages(null)
        releasePlayer()
    }
}
