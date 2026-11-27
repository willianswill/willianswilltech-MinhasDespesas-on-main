package com.willianswill.novatv.feature.search

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.willianswill.novatv.R
import com.willianswill.novatv.core.database.IptvRepository
import com.willianswill.novatv.core.database.entity.MediaType
import com.willianswill.novatv.core.database.entity.SeriesEntity
import com.willianswill.novatv.core.database.entity.StreamEntity
import com.willianswill.novatv.core.player.AppPreferences
import com.willianswill.novatv.core.player.PlayableItem
import com.willianswill.novatv.core.player.PlayerActivity
import com.willianswill.novatv.core.ui.enableCinemaFocusZoom
import com.willianswill.novatv.databinding.ActivityGlobalSearchBinding
import com.willianswill.novatv.databinding.ItemSearchResultBinding
import com.willianswill.novatv.feature.vod.MovieDetailActivity
import com.willianswill.novatv.feature.vod.SeriesDetailActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

sealed class GlobalSearchEntry {
    data class StreamHit(val stream: StreamEntity) : GlobalSearchEntry()
    data class SeriesHit(val series: SeriesEntity) : GlobalSearchEntry()
}

class GlobalSearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGlobalSearchBinding
    private lateinit var repository: IptvRepository
    private lateinit var prefs: AppPreferences
    private lateinit var adapter: SearchResultAdapter

    private var profileId = -1L
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGlobalSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = IptvRepository(this)
        prefs = AppPreferences(this)

        binding.btnBack.backgroundTintList = null
        binding.btnBack.enableCinemaFocusZoom(1.06f)
        binding.btnBack.setOnClickListener { finish() }

        adapter = SearchResultAdapter { entry -> openEntry(entry) }
        binding.rvSearchResults.layoutManager = LinearLayoutManager(this)
        binding.rvSearchResults.adapter = adapter

        lifecycleScope.launch {
            profileId = repository.observeActiveProfile().firstOrNull()?.id ?: -1L
        }

        binding.etGlobalQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim().orEmpty()
                runSearch(q)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.etGlobalQuery.post { binding.etGlobalQuery.requestFocus() }
    }

    private fun runSearch(query: String) {
        searchJob?.cancel()
        if (query.length < 2) {
            binding.rvSearchResults.visibility = View.GONE
            binding.tvSearchHint.visibility = View.VISIBLE
            binding.tvSearchHint.text = "Digite pelo menos 2 letras para buscar em Canais, Filmes e Séries."
            return
        }
        searchJob = lifecycleScope.launch {
            delay(180L)
            if (profileId <= 0L) {
                profileId = repository.observeActiveProfile().firstOrNull()?.id ?: -1L
            }
            if (profileId <= 0L) return@launch

            val (streams, series) = repository.searchGlobal(profileId, query, prefs.showAdultContent)
            val combined = streams.map { GlobalSearchEntry.StreamHit(it) } +
                series.map { GlobalSearchEntry.SeriesHit(it) }

            adapter.submitList(combined)
            if (combined.isEmpty()) {
                binding.rvSearchResults.visibility = View.GONE
                binding.tvSearchHint.visibility = View.VISIBLE
                binding.tvSearchHint.text = "Nenhum resultado encontrado para \"$query\"."
            } else {
                binding.tvSearchHint.visibility = View.GONE
                binding.rvSearchResults.visibility = View.VISIBLE
            }
        }
    }

    private fun openEntry(entry: GlobalSearchEntry) {
        when (entry) {
            is GlobalSearchEntry.StreamHit -> {
                val s = entry.stream
                if (s.mediaType == MediaType.LIVE) {
                    val playable = PlayableItem(
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
                        isFavorite = s.isFavorite
                    )
                    startActivity(
                        Intent(this, PlayerActivity::class.java).apply {
                            putExtra(PlayerActivity.EXTRA_ITEMS, arrayListOf(playable))
                            putExtra(PlayerActivity.EXTRA_INDEX, 0)
                        }
                    )
                } else {
                    startActivity(
                        Intent(this, MovieDetailActivity::class.java).apply {
                            putExtra(MovieDetailActivity.EXTRA_DB_ID, s.id)
                            putExtra(MovieDetailActivity.EXTRA_STREAM_ID, s.streamId)
                            putExtra(MovieDetailActivity.EXTRA_NAME, s.name)
                            putExtra(MovieDetailActivity.EXTRA_COVER, s.iconUrl)
                            putExtra(MovieDetailActivity.EXTRA_CATEGORY, s.categoryName)
                            putExtra(MovieDetailActivity.EXTRA_PLOT, s.plot)
                            putExtra(MovieDetailActivity.EXTRA_RATING, s.rating)
                            putExtra(MovieDetailActivity.EXTRA_STREAM_URL, s.streamUrl)
                            putExtra(MovieDetailActivity.EXTRA_USER_AGENT, s.userAgent)
                            putExtra(MovieDetailActivity.EXTRA_REFERRER, s.referrer)
                            putExtra(MovieDetailActivity.EXTRA_IS_FAV, s.isFavorite)
                        }
                    )
                }
            }
            is GlobalSearchEntry.SeriesHit -> {
                val s = entry.series
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

    private class SearchResultAdapter(
        private val onClick: (GlobalSearchEntry) -> Unit
    ) : RecyclerView.Adapter<SearchResultAdapter.VH>() {

        private var items: List<GlobalSearchEntry> = emptyList()

        fun submitList(list: List<GlobalSearchEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            b.root.enableCinemaFocusZoom(1.03f)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class VH(private val b: ItemSearchResultBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(entry: GlobalSearchEntry) {
                when (entry) {
                    is GlobalSearchEntry.StreamHit -> {
                        val s = entry.stream
                        b.tvSrBadge.text = if (s.mediaType == MediaType.LIVE) "📺 CANAL AO VIVO" else "🎬 FILME"
                        b.tvSrTitle.text = if (s.mediaType == MediaType.LIVE) s.baseName else s.name
                        b.tvSrSub.text = s.categoryName
                        loadIcon(s.iconUrl)
                    }
                    is GlobalSearchEntry.SeriesHit -> {
                        val s = entry.series
                        b.tvSrBadge.text = "🍿 SÉRIE"
                        b.tvSrTitle.text = s.name
                        b.tvSrSub.text = s.categoryName
                        loadIcon(s.coverUrl)
                    }
                }
                b.root.setOnClickListener { onClick(entry) }
            }

            private fun loadIcon(url: String) {
                if (url.isNotBlank()) {
                    Glide.with(b.ivSrIcon.context)
                        .load(url)
                        .placeholder(R.drawable.ic_tv_logo)
                        .error(R.drawable.ic_tv_logo)
                        .into(b.ivSrIcon)
                } else {
                    b.ivSrIcon.setImageResource(R.drawable.ic_tv_logo)
                }
            }
        }
    }
}
