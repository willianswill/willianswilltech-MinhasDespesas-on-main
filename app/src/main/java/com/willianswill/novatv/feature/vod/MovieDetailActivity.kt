package com.willianswill.novatv.feature.vod

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.willianswill.novatv.R
import com.willianswill.novatv.core.database.IptvRepository
import com.willianswill.novatv.core.database.entity.MediaType
import com.willianswill.novatv.core.downloads.DownloadStatus
import com.willianswill.novatv.core.downloads.OfflineDownloadManager
import com.willianswill.novatv.core.player.PlayableItem
import com.willianswill.novatv.core.player.PlayerActivity
import com.willianswill.novatv.core.ui.enableCinemaFocusZoom
import com.willianswill.novatv.databinding.ActivityMovieDetailBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.Locale

class MovieDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DB_ID = "extra_movie_db_id"
        const val EXTRA_STREAM_ID = "extra_movie_stream_id"
        const val EXTRA_NAME = "extra_movie_name"
        const val EXTRA_COVER = "extra_movie_cover"
        const val EXTRA_CATEGORY = "extra_movie_cat"
        const val EXTRA_PLOT = "extra_movie_plot"
        const val EXTRA_RATING = "extra_movie_rating"
        const val EXTRA_STREAM_URL = "extra_movie_url"
        const val EXTRA_USER_AGENT = "extra_movie_ua"
        const val EXTRA_REFERRER = "extra_movie_ref"
        const val EXTRA_IS_FAV = "extra_movie_fav"
    }

    private lateinit var binding: ActivityMovieDetailBinding
    private lateinit var repository: IptvRepository

    private var dbId = 0L
    private var streamId = ""
    private var movieName = ""
    private var movieCover = ""
    private var movieCat = ""
    private var streamUrl = ""
    private var userAgent: String? = null
    private var referrer: String? = null
    private var isFav = false
    private var resumeMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMovieDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = IptvRepository(this)
        OfflineDownloadManager.ensureLoaded(this)

        dbId = intent.getLongExtra(EXTRA_DB_ID, 0L)
        streamId = intent.getStringExtra(EXTRA_STREAM_ID).orEmpty()
        movieName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        movieCover = intent.getStringExtra(EXTRA_COVER).orEmpty()
        movieCat = intent.getStringExtra(EXTRA_CATEGORY).orEmpty()
        val plot = intent.getStringExtra(EXTRA_PLOT).orEmpty()
        val rating = intent.getStringExtra(EXTRA_RATING).orEmpty()
        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        referrer = intent.getStringExtra(EXTRA_REFERRER)
        isFav = intent.getBooleanExtra(EXTRA_IS_FAV, false)

        binding.tvMovieTitle.text = movieName
        binding.tvMovieMeta.text = if (rating.isNotBlank() && rating != "0") "$movieCat • ★ $rating" else movieCat
        binding.tvMoviePlot.text = plot.ifBlank { "Toque em Assistir para iniciar a reprodução." }

        if (movieCover.isNotBlank()) {
            Glide.with(this).load(movieCover).placeholder(R.drawable.ic_tv_logo).into(binding.ivMoviePoster)
            Glide.with(this).load(movieCover).placeholder(R.drawable.ic_tv_logo).into(binding.ivMovieBackdrop)
        }

        listOf(
            binding.btnBack, binding.btnMoviePlay, binding.btnMovieRestart,
            binding.btnMovieDownload, binding.btnMovieFav
        ).forEach {
            it.backgroundTintList = null
            it.enableCinemaFocusZoom(1.08f)
        }

        updateFavUi()
        setupListeners()
        observeDownloadState()

        binding.btnMoviePlay.post { binding.btnMoviePlay.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val profile = repository.observeActiveProfile().firstOrNull() ?: return@launch
            val saved = repository.getWatchProgress(profile.id, streamUrl)
            resumeMs = saved?.positionMs ?: 0L
            if (resumeMs > 5_000L) {
                val timeStr = formatTime(resumeMs)
                binding.btnMoviePlay.text = "▶ Continuar ($timeStr)"
                binding.tvMovieProgress.visibility = View.VISIBLE
                binding.tvMovieProgress.text = "Progresso salvo: $timeStr"
                binding.btnMovieRestart.visibility = View.VISIBLE
            } else {
                binding.btnMoviePlay.text = "▶ Assistir"
                binding.tvMovieProgress.visibility = View.GONE
                binding.btnMovieRestart.visibility = View.GONE
            }
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnMoviePlay.setOnClickListener { launchMovie(resumePosition = resumeMs) }
        binding.btnMovieRestart.setOnClickListener { launchMovie(resumePosition = 0L) }

        binding.btnMovieFav.setOnClickListener {
            if (dbId <= 0L) return@setOnClickListener
            isFav = !isFav
            updateFavUi()
            lifecycleScope.launch {
                repository.toggleStreamFavorite(dbId, isFav)
                Toast.makeText(
                    this@MovieDetailActivity,
                    if (isFav) "★ Adicionado aos Favoritos" else "☆ Removido dos Favoritos",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnMovieDownload.setOnClickListener {
            val existing = OfflineDownloadManager.findByUrl(this, streamUrl)
            when (existing?.status) {
                DownloadStatus.COMPLETED -> {
                    OfflineDownloadManager.deleteDownload(this, existing.id)
                    Toast.makeText(this, "Download removido.", Toast.LENGTH_SHORT).show()
                }
                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                    Toast.makeText(this, "Download em andamento (veja em ⬇ Offline).", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    OfflineDownloadManager.enqueueDownload(this, buildPlayable(resumeMs))
                    Toast.makeText(this, "⬇ Baixando $movieName em segundo plano...", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun observeDownloadState() {
        lifecycleScope.launch {
            OfflineDownloadManager.downloadsFlow.collectLatest { list ->
                val item = list.firstOrNull { it.streamUrl == streamUrl }
                when (item?.status) {
                    DownloadStatus.COMPLETED -> binding.btnMovieDownload.text = "✓ Offline (Excluir)"
                    DownloadStatus.DOWNLOADING -> {
                        val pct = if (item.totalBytes > 0L) ((item.downloadedBytes * 100L) / item.totalBytes).toInt() else 0
                        binding.btnMovieDownload.text = "⏳ Baixando $pct%"
                    }
                    DownloadStatus.QUEUED -> binding.btnMovieDownload.text = "⏳ Na fila..."
                    else -> binding.btnMovieDownload.text = "⬇ Baixar Offline"
                }
            }
        }
    }

    private fun updateFavUi() {
        binding.btnMovieFav.text = if (isFav) "★ Favorito" else "☆ Favoritar"
    }

    private fun buildPlayable(posMs: Long) = PlayableItem(
        contentKey = streamUrl,
        title = movieName,
        subtitle = movieCat,
        streamUrl = streamUrl,
        iconUrl = movieCover,
        mediaType = MediaType.MOVIE,
        streamId = streamId,
        userAgent = userAgent,
        referrer = referrer,
        resumePositionMs = posMs,
        streamDbId = dbId,
        isFavorite = isFav
    )

    private fun launchMovie(resumePosition: Long) {
        val localUri = OfflineDownloadManager.findCompletedLocalUri(this, streamUrl)
        val item = buildPlayable(resumePosition).let {
            if (localUri != null) it.copy(streamUrl = localUri) else it
        }
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_ITEMS, arrayListOf(item))
                putExtra(PlayerActivity.EXTRA_INDEX, 0)
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
