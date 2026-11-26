package com.willianswill.novatv.core.player

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.willianswill.novatv.databinding.ActivityMultiviewBinding

class MultiViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ITEMS = "extra_mv_items"
        const val EXTRA_INITIAL_INDEX = "extra_mv_index"
    }

    private lateinit var binding: ActivityMultiviewBinding
    private lateinit var prefs: AppPreferences

    private var availableItems: List<PlayableItem> = emptyList()
    private var isFourScreens = true
    private var activeSlot = 0

    private val players = arrayOfNulls<ExoPlayer>(4)
    private val slotItems = arrayOfNulls<PlayableItem>(4)
    private lateinit var slotContainers: List<FrameLayout>
    private lateinit var playerViews: List<PlayerView>
    private lateinit var slotLabels: List<TextView>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).hide(WindowInsetsCompat.Type.systemBars())

        prefs = AppPreferences(this)

        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        availableItems = (intent.getSerializableExtra(EXTRA_ITEMS) as? ArrayList<PlayableItem>) ?: emptyList()
        val initialIdx = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0).coerceIn(0, (availableItems.size - 1).coerceAtLeast(0))

        slotContainers = listOf(binding.slot0, binding.slot1, binding.slot2, binding.slot3)
        playerViews = listOf(binding.pv0, binding.pv1, binding.pv2, binding.pv3)
        slotLabels = listOf(binding.tvSlot0, binding.tvSlot1, binding.tvSlot2, binding.tvSlot3)

        binding.btnMvBack.setOnClickListener { finish() }
        binding.btnMvToggleGrid.setOnClickListener { toggleGridMode() }
        binding.btnMvSwapChannel.setOnClickListener { showChannelPickerForActiveSlot() }

        slotContainers.forEachIndexed { idx, frame ->
            frame.setOnClickListener { selectAudioSlot(idx) }
            frame.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) selectAudioSlot(idx)
            }
        }

        if (availableItems.isNotEmpty()) {
            for (slot in 0..3) {
                val item = availableItems[(initialIdx + slot) % availableItems.size]
                loadChannelIntoSlot(slot, item)
            }
        }
        selectAudioSlot(0)
    }

    private fun loadChannelIntoSlot(slot: Int, item: PlayableItem) {
        players[slot]?.release()
        slotItems[slot] = item
        slotLabels[slot].text = item.title

        val exo = NovaPlayerFactory.createPlayer(
            context = this,
            bufferProfile = prefs.bufferProfile,
            userAgent = item.userAgent,
            referrer = item.referrer,
            forMultiView = true
        )
        exo.volume = if (slot == activeSlot) 1f else 0f
        playerViews[slot].player = exo
        exo.setMediaItem(NovaPlayerFactory.buildMediaItem(item.streamUrl))
        exo.prepare()
        exo.playWhenReady = true
        players[slot] = exo
    }

    private fun selectAudioSlot(slot: Int) {
        activeSlot = slot
        for (i in 0..3) {
            val isActive = i == slot
            slotContainers[i].isSelected = isActive
            players[i]?.volume = if (isActive) 1f else 0f
        }
        val activeName = slotItems[slot]?.title ?: "Tela ${slot + 1}"
        binding.tvMvInfo.text = "Áudio ativo: $activeName"
    }

    private fun toggleGridMode() {
        isFourScreens = !isFourScreens
        binding.row2.visibility = if (isFourScreens) View.VISIBLE else View.GONE
        if (!isFourScreens) {
            players[2]?.pause()
            players[3]?.pause()
            if (activeSlot >= 2) selectAudioSlot(0)
        } else {
            players[2]?.play()
            players[3]?.play()
        }
    }

    private fun showChannelPickerForActiveSlot() {
        if (availableItems.isEmpty()) return
        val titles = availableItems.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Escolher canal para Tela ${activeSlot + 1}")
            .setItems(titles) { _, which ->
                loadChannelIntoSlot(activeSlot, availableItems[which])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onStop() {
        super.onStop()
        for (i in 0..3) {
            players[i]?.release()
            players[i] = null
        }
    }
}
