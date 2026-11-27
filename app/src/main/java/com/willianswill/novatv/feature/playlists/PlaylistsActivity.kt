package com.willianswill.novatv.feature.playlists

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.willianswill.novatv.core.database.IptvRepository
import com.willianswill.novatv.core.database.entity.ProfileEntity
import com.willianswill.novatv.core.database.entity.ProfileType
import com.willianswill.novatv.core.network.StalkerClient
import com.willianswill.novatv.core.ui.enableCinemaFocusZoom
import com.willianswill.novatv.databinding.ActivityPlaylistsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaylistsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistsBinding
    private lateinit var repository: IptvRepository
    private lateinit var adapter: ProfileAdapter

    private var selectedType: ProfileType = ProfileType.XTREAM

    private val pickM3uLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importLocalM3u(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = IptvRepository(this)
        setupTabs()
        setupList()
        setupActions()
        observeProfiles()
    }

    private fun setupTabs() {
        fun updateTabUi(type: ProfileType) {
            selectedType = type
            binding.tabXtream.isSelected = type == ProfileType.XTREAM
            binding.tabM3u.isSelected = type == ProfileType.M3U_URL
            binding.tabStalker.isSelected = type == ProfileType.STALKER

            binding.groupXtreamFields.visibility = if (type == ProfileType.XTREAM) View.VISIBLE else View.GONE
            binding.etEpgUrl.visibility = if (type == ProfileType.M3U_URL) View.VISIBLE else View.GONE
            binding.btnPickLocalM3u.visibility = if (type == ProfileType.M3U_URL) View.VISIBLE else View.GONE
            binding.etMacAddress.visibility = if (type == ProfileType.STALKER) View.VISIBLE else View.GONE

            binding.etServerUrl.hint = when (type) {
                ProfileType.XTREAM -> "http://servidor.com:8080"
                ProfileType.M3U_URL -> "https://servidor.com/lista.m3u8"
                ProfileType.STALKER -> "http://portal.servidor.com/c/"
                ProfileType.M3U_LOCAL -> ""
            }
        }

        updateTabUi(ProfileType.XTREAM)
        binding.tabXtream.setOnClickListener { updateTabUi(ProfileType.XTREAM) }
        binding.tabM3u.setOnClickListener { updateTabUi(ProfileType.M3U_URL) }
        binding.tabStalker.setOnClickListener { updateTabUi(ProfileType.STALKER) }
    }

    private fun setupList() {
        adapter = ProfileAdapter(
            onActivate = { profile ->
                lifecycleScope.launch {
                    repository.setActiveProfile(profile.id)
                    Toast.makeText(this@PlaylistsActivity, "Perfil ativado: ${profile.name}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            },
            onSync = { profile ->
                syncProfile(profile)
            },
            onDelete = { profile ->
                androidx.appcompat.app.AlertDialog.Builder(this@PlaylistsActivity)
                    .setTitle("Excluir servidor")
                    .setMessage("Deseja remover a lista \"${profile.name}\"?")
                    .setPositiveButton("Excluir") { _, _ ->
                        lifecycleScope.launch {
                            repository.deleteProfile(profile.id)
                            Toast.makeText(this@PlaylistsActivity, "Servidor removido.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        )
        binding.rvProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvProfiles.adapter = adapter
    }

    private fun setupActions() {
        listOf(
            binding.btnBack, binding.btnPullMacPlaylist, binding.tabXtream, binding.tabM3u,
            binding.tabStalker, binding.btnConnectSave, binding.btnPickLocalM3u
        ).forEach { it.enableCinemaFocusZoom(1.05f) }
        binding.tabXtream.post { binding.tabXtream.requestFocus() }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPullMacPlaylist.setOnClickListener { pullPlaylistByMac() }
        binding.btnPickLocalM3u.setOnClickListener { pickM3uLauncher.launch(arrayOf("*/*")) }

        binding.btnConnectSave.setOnClickListener {
            val name = binding.etProfileName.text.toString().trim().ifEmpty { "Minha Lista IPTV" }
            val url = binding.etServerUrl.text.toString().trim()

            if (url.isBlank()) {
                Toast.makeText(this, "Informe a URL do servidor ou lista.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            when (selectedType) {
                ProfileType.XTREAM -> {
                    val user = binding.etUsername.text.toString().trim()
                    val pass = binding.etPassword.text.toString().trim()
                    if (user.isBlank() || pass.isBlank()) {
                        Toast.makeText(this, "Preencha usuário e senha Xtream.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    createAndSync(
                        ProfileEntity(
                            name = name,
                            type = ProfileType.XTREAM.name,
                            serverUrl = url,
                            username = user,
                            password = pass
                        )
                    )
                }

                ProfileType.M3U_URL -> {
                    val epg = binding.etEpgUrl.text.toString().trim()
                    createAndSync(
                        ProfileEntity(
                            name = name,
                            type = ProfileType.M3U_URL.name,
                            serverUrl = url,
                            epgUrl = epg
                        )
                    )
                }

                ProfileType.STALKER -> {
                    val mac = binding.etMacAddress.text.toString().trim()
                    if (mac.isBlank()) {
                        Toast.makeText(this, "Informe o MAC Address.", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    testAndSaveStalker(name, url, mac)
                }

                ProfileType.M3U_LOCAL -> Unit
            }
        }
    }

    private fun observeProfiles() {
        lifecycleScope.launch {
            repository.observeProfiles().collectLatest { list ->
                adapter.submitList(list)
                val hasSaved = list.isNotEmpty()
                binding.tvSavedHeader.visibility = if (hasSaved) View.VISIBLE else View.GONE
                binding.rvProfiles.visibility = if (hasSaved) View.VISIBLE else View.GONE
                if (hasSaved) {
                    binding.rvProfiles.post {
                        binding.rvProfiles.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                    }
                }
            }
        }
    }

    private fun createAndSync(profile: ProfileEntity) {
        setBusy(true, "Conectando e carregando canais ao vivo...")
        lifecycleScope.launch {
            try {
                val id = repository.saveProfile(profile, activate = true)
                val saved = profile.copy(id = id, isActive = true)
                val synced = repository.syncActiveLiveFirst() ?: saved
                setBusy(false)
                Toast.makeText(
                    this@PlaylistsActivity,
                    "Canais prontos (${synced.totalChannels} canais)! Filmes, séries e EPG carregando em segundo plano.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } catch (e: Exception) {
                setBusy(false)
                Toast.makeText(this@PlaylistsActivity, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun syncProfile(profile: ProfileEntity) {
        setBusy(true, "Atualizando ${profile.name}...")
        lifecycleScope.launch {
            try {
                repository.setActiveProfile(profile.id)
                val synced = repository.syncActiveProfile()
                var epgCount = 0
                if (synced != null && synced.epgUrl.isNotBlank()) {
                    setBusy(true, "Atualizando EPG...")
                    epgCount = runCatching { repository.syncEpgForProfile(synced) }.getOrDefault(0)
                }
                setBusy(false)
                Toast.makeText(this@PlaylistsActivity, "Catálogo atualizado ($epgCount programas EPG)!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                setBusy(false)
                Toast.makeText(this@PlaylistsActivity, "Falha ao atualizar: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importLocalM3u(uri: Uri) {
        val name = binding.etProfileName.text.toString().trim().ifEmpty { "Lista Local M3U" }
        setBusy(true, "Importando arquivo M3U em streaming...")
        lifecycleScope.launch {
            try {
                val profile = ProfileEntity(
                    name = name,
                    type = ProfileType.M3U_LOCAL.name,
                    serverUrl = uri.toString(),
                    epgUrl = binding.etEpgUrl.text.toString().trim()
                )
                val id = repository.saveProfile(profile, activate = true)
                val saved = profile.copy(id = id, isActive = true)
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        repository.syncM3uStream(saved, stream)
                    } ?: throw IllegalStateException("Não foi possível abrir o arquivo.")
                }
                setBusy(false)
                Toast.makeText(this@PlaylistsActivity, "Lista M3U local importada!", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                setBusy(false)
                Toast.makeText(this@PlaylistsActivity, "Erro ao importar: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun testAndSaveStalker(name: String, url: String, mac: String) {
        setBusy(true, "Testando handshake Stalker Portal...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                StalkerClient().handshake(url, mac)
                repository.saveProfile(
                    ProfileEntity(
                        name = name,
                        type = ProfileType.STALKER.name,
                        serverUrl = url,
                        macAddress = mac
                    ),
                    activate = true
                )
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    Toast.makeText(this@PlaylistsActivity, "Portal Stalker salvo!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    setBusy(false)
                    Toast.makeText(this@PlaylistsActivity, "Erro Stalker: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pullPlaylistByMac() {
        val mac = com.willianswill.novatv.core.network.TelemetryManager.getDeviceMac(this)
        setBusy(true, "Buscando lista para MAC $mac...")
        lifecycleScope.launch {
            try {
                val remote = com.willianswill.novatv.core.network.TelemetryManager.fetchRemoteMacPlaylist(this@PlaylistsActivity)
                if (remote == null) {
                    setBusy(false)
                    Toast.makeText(
                        this@PlaylistsActivity,
                        "Nenhuma lista enviada para o MAC $mac.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                val profile = ProfileEntity(
                    name = remote.name,
                    type = ProfileType.M3U_URL.name,
                    serverUrl = remote.m3uUrl
                )
                val savedId = repository.saveProfile(profile, activate = true)
                val synced = try {
                    repository.syncActiveProfile()
                } catch (e: Exception) {
                    repository.deleteProfile(savedId)
                    throw e
                }
                com.willianswill.novatv.core.network.TelemetryManager.markMacPlaylistApplied(
                    this@PlaylistsActivity,
                    remote.updatedAt
                )
                setBusy(false)
                Toast.makeText(
                    this@PlaylistsActivity,
                    "Lista ${remote.name} carregada (${synced?.totalChannels ?: 0} canais)!",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } catch (e: Exception) {
                setBusy(false)
                Toast.makeText(this@PlaylistsActivity, "Erro lista MAC: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setBusy(busy: Boolean, text: String = "") {
        binding.syncStatusBox.visibility = if (busy) View.VISIBLE else View.GONE
        binding.tvSyncStatus.text = text
        binding.btnConnectSave.isEnabled = !busy
        binding.btnPickLocalM3u.isEnabled = !busy
    }
}
