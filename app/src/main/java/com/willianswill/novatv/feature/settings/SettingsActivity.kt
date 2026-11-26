package com.willianswill.novatv.feature.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.willianswill.novatv.core.database.IptvRepository
import com.willianswill.novatv.core.database.entity.ProfileEntity
import com.willianswill.novatv.core.network.TelemetryManager
import com.willianswill.novatv.core.player.AppPreferences
import com.willianswill.novatv.core.player.BufferProfile
import com.willianswill.novatv.databinding.ActivitySettingsBinding
import com.willianswill.novatv.feature.admin.ControlPanelActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences
    private lateinit var repository: IptvRepository

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) exportBackup(uri)
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importBackup(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        repository = IptvRepository(this)

        setupUi()
        setupListeners()
    }

    private fun setupUi() {
        updateBufferButtons(prefs.bufferProfile)
        binding.switchReconnect.isChecked = prefs.autoReconnect
        binding.etUserAgent.setText(prefs.customUserAgent)
        binding.etFirebaseUrl.setText(TelemetryManager.getFirebaseUrl(this))
        updateAdultLabel()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnBufLow.setOnClickListener { setBuffer(BufferProfile.LOW) }
        binding.btnBufStd.setOnClickListener { setBuffer(BufferProfile.STANDARD) }
        binding.btnBufHigh.setOnClickListener { setBuffer(BufferProfile.HIGH) }

        binding.switchReconnect.setOnCheckedChangeListener { _, isChecked ->
            prefs.autoReconnect = isChecked
        }

        binding.btnSaveUa.setOnClickListener {
            val ua = binding.etUserAgent.text.toString().trim()
            if (ua.isNotEmpty()) {
                prefs.customUserAgent = ua
                Toast.makeText(this, "User-Agent salvo!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSetPin.setOnClickListener { showSetPinDialog() }
        binding.btnToggleAdult.setOnClickListener { toggleAdultContent() }

        binding.btnExportBackup.setOnClickListener {
            exportLauncher.launch("novatv_backup.json")
        }
        binding.btnImportBackup.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "*/*"))
        }

        binding.btnSaveFirebase.setOnClickListener {
            val url = binding.etFirebaseUrl.text.toString().trim()
            TelemetryManager.setFirebaseUrl(this, url)
            Toast.makeText(this, "URL do Painel salva!", Toast.LENGTH_SHORT).show()
        }

        binding.btnOpenControlPanel.setOnClickListener {
            val pin = prefs.parentalPin
            if (pin.isNotEmpty()) {
                val input = EditText(this).apply {
                    hint = "Digite o PIN para abrir o Painel"
                    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                }
                AlertDialog.Builder(this)
                    .setTitle("Acesso ao Painel")
                    .setView(input)
                    .setPositiveButton("Entrar") { _, _ ->
                        if (input.text.toString().trim() == pin) {
                            startActivity(Intent(this, ControlPanelActivity::class.java))
                        } else {
                            Toast.makeText(this, "PIN incorreto!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            } else {
                startActivity(Intent(this, ControlPanelActivity::class.java))
            }
        }
    }

    private fun setBuffer(profile: BufferProfile) {
        prefs.bufferProfile = profile
        updateBufferButtons(profile)
        Toast.makeText(this, "Buffer: ${profile.label}", Toast.LENGTH_SHORT).show()
    }

    private fun updateBufferButtons(active: BufferProfile) {
        binding.btnBufLow.isSelected = active == BufferProfile.LOW
        binding.btnBufStd.isSelected = active == BufferProfile.STANDARD
        binding.btnBufHigh.isSelected = active == BufferProfile.HIGH
    }

    private fun updateAdultLabel() {
        binding.btnToggleAdult.text = if (prefs.showAdultContent) {
            "Conteúdo Adulto (+18): Visível"
        } else {
            "Conteúdo Adulto (+18): Oculto"
        }
    }

    private fun showSetPinDialog() {
        val input = EditText(this).apply {
            hint = "Digite 4 dígitos (vazio remove PIN)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("PIN Parental")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                prefs.parentalPin = input.text.toString().trim()
                Toast.makeText(this, "PIN atualizado!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toggleAdultContent() {
        val pin = prefs.parentalPin
        if (!prefs.showAdultContent && pin.isNotEmpty()) {
            val input = EditText(this).apply {
                hint = "Digite o PIN para desbloquear"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            AlertDialog.Builder(this)
                .setTitle("Verificação Parental")
                .setView(input)
                .setPositiveButton("Confirmar") { _, _ ->
                    if (input.text.toString().trim() == pin) {
                        prefs.showAdultContent = true
                        updateAdultLabel()
                    } else {
                        Toast.makeText(this, "PIN incorreto!", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            prefs.showAdultContent = !prefs.showAdultContent
            updateAdultLabel()
        }
    }

    private fun exportBackup(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val profiles = repository.observeProfiles().firstOrNull().orEmpty()
                val arr = JSONArray()
                profiles.forEach { p ->
                    arr.put(
                        JSONObject().apply {
                            put("name", p.name)
                            put("type", p.type)
                            put("serverUrl", p.serverUrl)
                            put("username", p.username)
                            put("password", p.password)
                            put("macAddress", p.macAddress)
                            put("epgUrl", p.epgUrl)
                            put("userAgent", p.userAgent)
                        }
                    )
                }
                val root = JSONObject().apply {
                    put("bufferProfile", prefs.bufferProfile.name)
                    put("customUserAgent", prefs.customUserAgent)
                    put("autoReconnect", prefs.autoReconnect)
                    put("profiles", arr)
                }
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(root.toString(2).toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Backup exportado com sucesso!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Erro ao exportar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun importBackup(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                    it.readText()
                } ?: throw IllegalStateException("Arquivo inválido")
                val root = JSONObject(text)
                root.optString("bufferProfile").takeIf { it.isNotBlank() }?.let { name ->
                    BufferProfile.entries.find { it.name == name }?.let { prefs.bufferProfile = it }
                }
                root.optString("customUserAgent").takeIf { it.isNotBlank() }?.let {
                    prefs.customUserAgent = it
                }
                prefs.autoReconnect = root.optBoolean("autoReconnect", true)

                val arr = root.optJSONArray("profiles") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    repository.saveProfile(
                        ProfileEntity(
                            name = obj.getString("name"),
                            type = obj.getString("type"),
                            serverUrl = obj.getString("serverUrl"),
                            username = obj.optString("username", ""),
                            password = obj.optString("password", ""),
                            macAddress = obj.optString("macAddress", ""),
                            epgUrl = obj.optString("epgUrl", ""),
                            userAgent = obj.optString("userAgent", "")
                        ),
                        activate = i == 0
                    )
                }
                withContext(Dispatchers.Main) {
                    setupUi()
                    Toast.makeText(this@SettingsActivity, "Backup restaurado! Sincronize seu perfil em Playlists.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SettingsActivity, "Erro ao restaurar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
