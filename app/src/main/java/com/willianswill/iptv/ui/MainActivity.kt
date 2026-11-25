package com.willianswill.iptv.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.willianswill.iptv.R
import com.willianswill.iptv.databinding.ActivityMainBinding
import com.willianswill.iptv.databinding.DialogAddPlaylistBinding
import com.willianswill.iptv.model.Channel
import com.willianswill.iptv.parser.M3uParser
import com.willianswill.iptv.storage.PlaylistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    companion object {
        private const val CAT_ALL = "Todos"
        private const val CAT_FAV = "★ Favoritos"
        private const val CAT_RECENTS = "🕒 Recentes"
        private const val MAX_PLAYER_WINDOW = 150
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: PlaylistRepository
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private var allChannels: List<Channel> = emptyList()
    private var currentFilteredChannels: List<Channel> = emptyList()
    private var selectedCategory: String = CAT_ALL
    private var searchQuery: String = ""
    private var filterJob: Job? = null

    private var pendingPlaylistName: String = "Lista Local"
    private var activeDialog: AlertDialog? = null

    private val pickM3uLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importLocalM3u(uri, pendingPlaylistName)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = PlaylistRepository(this)
        setupRecyclerViews()
        setupListeners()
        loadActivePlaylist()
    }

    override fun onResume() {
        super.onResume()
        // Atualiza status de favoritos caso tenha mudado dentro do Player
        if (allChannels.isNotEmpty()) {
            val favs = repository.getFavoriteUrls()
            allChannels.forEach { it.isFavorite = favs.contains(it.url) }
            applyFilters()
        }
    }

    private fun setupRecyclerViews() {
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        categoryAdapter = CategoryAdapter { category ->
            selectedCategory = category
            applyFilters()
        }
        binding.rvCategories.layoutManager = LinearLayoutManager(
            this,
            if (isLandscape) LinearLayoutManager.VERTICAL else LinearLayoutManager.HORIZONTAL,
            false
        )
        binding.rvCategories.adapter = categoryAdapter

        channelAdapter = ChannelAdapter(
            onChannelClick = { _, position ->
                openPlayer(position)
            },
            onFavoriteToggle = { channel ->
                val isFav = repository.toggleFavorite(channel.url)
                channel.isFavorite = isFav
                applyFilters()
            }
        )

        val spanCount = if (isLandscape) 3 else 1
        binding.rvChannels.layoutManager = GridLayoutManager(this, spanCount)
        binding.rvChannels.adapter = channelAdapter
    }

    private fun setupListeners() {
        binding.btnManagePlaylists.setOnClickListener { showPlaylistsDialog() }
        binding.btnEmptyAdd.setOnClickListener { showPlaylistsDialog() }
        binding.btnRefreshPlaylist.setOnClickListener { refreshActivePlaylist() }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                applyFilters()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun loadActivePlaylist() {
        val playlists = repository.getPlaylists()
        val activeId = repository.getActivePlaylistId() ?: playlists.firstOrNull()?.id

        if (activeId == null) {
            allChannels = emptyList()
            binding.tvActivePlaylist.text = "Nenhuma lista carregada"
            showEmptyState(true)
            categoryAdapter.submitCategories(emptyList(), CAT_ALL)
            channelAdapter.submitList(emptyList())
            return
        }

        val activeMeta = playlists.find { it.id == activeId }
        binding.tvActivePlaylist.text = activeMeta?.let {
            "${it.name} • ${it.channelCount} canais"
        } ?: "Lista ativa"

        binding.progressLoading.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val loaded = repository.loadChannels(activeId)
            val groups = loaded.map { it.group }.distinct().sorted()
            val categories = listOf(CAT_ALL, CAT_FAV, CAT_RECENTS) + groups

            withContext(Dispatchers.Main) {
                binding.progressLoading.visibility = View.GONE
                allChannels = loaded
                if (!categories.contains(selectedCategory)) {
                    selectedCategory = CAT_ALL
                }
                categoryAdapter.submitCategories(categories, selectedCategory)
                applyFilters()
            }
        }
    }

    private fun applyFilters() {
        filterJob?.cancel()
        if (allChannels.isEmpty()) {
            showEmptyState(true)
            channelAdapter.submitList(emptyList())
            return
        }

        filterJob = lifecycleScope.launch(Dispatchers.Default) {
            val recentUrls = if (selectedCategory == CAT_RECENTS) repository.getRecentUrls() else emptyList()
            val query = searchQuery.lowercase()

            val base = when (selectedCategory) {
                CAT_ALL -> allChannels
                CAT_FAV -> allChannels.filter { it.isFavorite }
                CAT_RECENTS -> {
                    val map = allChannels.associateBy { it.url }
                    recentUrls.mapNotNull { map[it] }
                }
                else -> allChannels.filter { it.group == selectedCategory }
            }

            val filtered = if (query.isEmpty()) {
                base
            } else {
                base.filter {
                    it.name.lowercase().contains(query) || it.group.lowercase().contains(query)
                }
            }

            withContext(Dispatchers.Main) {
                currentFilteredChannels = filtered
                showEmptyState(filtered.isEmpty())
                if (filtered.isEmpty()) {
                    binding.tvEmptyTitle.text = "Nenhum canal encontrado"
                    binding.tvEmptySubtitle.text = "Tente mudar a categoria ou limpar a busca."
                }
                channelAdapter.submitList(filtered)
            }
        }
    }

    private fun showEmptyState(show: Boolean) {
        binding.emptyStateContainer.visibility = if (show) View.VISIBLE else View.GONE
        binding.rvChannels.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun openPlayer(clickedPosition: Int) {
        if (currentFilteredChannels.isEmpty()) return

        // Janela segura para evitar TransactionTooLargeException em listas gigantes (>10.000 canais)
        val half = MAX_PLAYER_WINDOW / 2
        val start = (clickedPosition - half).coerceAtLeast(0)
        val end = (start + MAX_PLAYER_WINDOW).coerceAtMost(currentFilteredChannels.size)
        val windowList = ArrayList(currentFilteredChannels.subList(start, end))
        val relativeIndex = clickedPosition - start

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_CHANNELS, windowList)
            putExtra(PlayerActivity.EXTRA_INDEX, relativeIndex)
        }
        startActivity(intent)
    }

    private fun refreshActivePlaylist() {
        val activeId = repository.getActivePlaylistId() ?: return
        val active = repository.getPlaylists().find { it.id == activeId } ?: return
        if (active.isLocal || active.sourceUrl.isBlank()) {
            Toast.makeText(this, "Listas locais devem ser reimportadas pelo botão + Listas M3U.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressLoading.visibility = View.VISIBLE
        Toast.makeText(this, "Atualizando ${active.name}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = M3uParser.downloadM3u(active.sourceUrl)
                repository.addOrUpdatePlaylist(active.name, active.sourceUrl, content, isLocal = false, existingId = active.id)
                withContext(Dispatchers.Main) {
                    binding.progressLoading.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Lista atualizada!", Toast.LENGTH_SHORT).show()
                    loadActivePlaylist()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressLoading.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Erro ao atualizar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showPlaylistsDialog() {
        val dialogBinding = DialogAddPlaylistBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setNegativeButton("Fechar", null)
            .create()
        activeDialog = dialog

        fun renderSavedPlaylists() {
            dialogBinding.savedPlaylistsContainer.removeAllViews()
            val saved = repository.getPlaylists()
            dialogBinding.tvSavedPlaylistsHeader.visibility = if (saved.isEmpty()) View.GONE else View.VISIBLE

            val activeId = repository.getActivePlaylistId()
            for (p in saved) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(16, 12, 16, 12)
                    setBackgroundResource(R.drawable.selector_focus_card)
                    isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 8 }
                }

                val info = TextView(this).apply {
                    val mark = if (p.id == activeId) "✓ " else ""
                    text = "$mark${p.name} (${p.channelCount} canais)"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val btnDel = Button(this).apply {
                    text = "Excluir"
                    textSize = 11f
                    setTextColor(0xFFF87171.toInt())
                    setBackgroundResource(R.drawable.selector_focus_button)
                    isFocusable = true
                    setOnClickListener {
                        repository.deletePlaylist(p.id)
                        renderSavedPlaylists()
                        loadActivePlaylist()
                    }
                }

                row.setOnClickListener {
                    repository.setActivePlaylistId(p.id)
                    dialog.dismiss()
                    loadActivePlaylist()
                }

                row.addView(info)
                row.addView(btnDel)
                dialogBinding.savedPlaylistsContainer.addView(row)
            }
        }

        renderSavedPlaylists()

        dialogBinding.btnSaveUrlPlaylist.setOnClickListener {
            val name = dialogBinding.etPlaylistName.text.toString().trim().ifEmpty { "Lista IPTV" }
            val url = dialogBinding.etPlaylistUrl.text.toString().trim()
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                Toast.makeText(this, "Informe uma URL válida iniciando com http:// ou https://", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialogBinding.btnSaveUrlPlaylist.isEnabled = false
            dialogBinding.btnSaveUrlPlaylist.text = "Baixando..."

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val content = M3uParser.downloadM3u(url)
                    repository.addOrUpdatePlaylist(name, url, content, isLocal = false)
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        Toast.makeText(this@MainActivity, "Lista carregada com sucesso!", Toast.LENGTH_SHORT).show()
                        loadActivePlaylist()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        dialogBinding.btnSaveUrlPlaylist.isEnabled = true
                        dialogBinding.btnSaveUrlPlaylist.text = "Carregar por URL"
                        Toast.makeText(this@MainActivity, "Falha: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        dialogBinding.btnPickFilePlaylist.setOnClickListener {
            pendingPlaylistName = dialogBinding.etPlaylistName.text.toString().trim().ifEmpty { "Lista Local" }
            pickM3uLauncher.launch(arrayOf("*/*"))
        }

        dialog.show()
    }

    private fun importLocalM3u(uri: Uri, name: String) {
        binding.progressLoading.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                    it.readText()
                } ?: throw IllegalStateException("Não foi possível ler o arquivo.")
                repository.addOrUpdatePlaylist(name, uri.toString(), content, isLocal = true)
                withContext(Dispatchers.Main) {
                    binding.progressLoading.visibility = View.GONE
                    activeDialog?.dismiss()
                    Toast.makeText(this@MainActivity, "Arquivo M3U importado!", Toast.LENGTH_SHORT).show()
                    loadActivePlaylist()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressLoading.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
