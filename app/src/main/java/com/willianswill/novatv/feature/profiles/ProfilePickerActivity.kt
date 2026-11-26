package com.willianswill.novatv.feature.profiles

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.willianswill.novatv.R
import com.willianswill.novatv.core.network.TelemetryManager
import kotlinx.coroutines.launch
import com.willianswill.novatv.core.player.UserAvatar
import com.willianswill.novatv.core.player.UserProfile
import com.willianswill.novatv.core.player.UserProfileManager
import com.willianswill.novatv.core.ui.enableCinemaFocusZoom
import com.willianswill.novatv.databinding.ActivityProfilePickerBinding
import com.willianswill.novatv.databinding.DialogUserProfileBinding
import com.willianswill.novatv.databinding.ItemUserProfileBinding
import com.willianswill.novatv.feature.home.HomeActivity

class ProfilePickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfilePickerBinding
    private var isManageMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfilePickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnNewProfile.enableCinemaFocusZoom(1.06f)
        binding.btnManageMode.enableCinemaFocusZoom(1.06f)

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.rvUserProfiles.layoutManager = GridLayoutManager(this, if (isLandscape) 4 else 2)

        binding.btnNewProfile.setOnClickListener {
            showCreateOrEditDialog(existing = null)
        }

        binding.btnManageMode.setOnClickListener {
            isManageMode = !isManageMode
            binding.btnManageMode.text = if (isManageMode) "✓ Concluir" else "✎ Gerenciar Perfis"
            binding.tvPickerHint.text = if (isManageMode) {
                "Clique em um perfil para editar ou excluir"
            } else {
                "Cada perfil mantém seu histórico, favoritos e configurações"
            }
            refreshProfiles()
        }

        refreshProfiles()
        verifyRemoteBlockStatus()
    }

    private fun verifyRemoteBlockStatus() {
        lifecycleScope.launch {
            val cmd = TelemetryManager.checkCommandNow(this@ProfilePickerActivity) ?: return@launch
            if (cmd.action == "BLOCK") {
                TelemetryManager.handleCommandOnActivity(this@ProfilePickerActivity, cmd) {
                    verifyRemoteBlockStatus()
                }
            } else if (cmd.action == "KICK") {
                TelemetryManager.markKickHandled(this@ProfilePickerActivity, cmd.issuedAt)
            }
        }
    }

    private fun refreshProfiles() {
        val users = UserProfileManager.getUsers(this)
        binding.rvUserProfiles.adapter = UserProfileAdapter(users) { user ->
            if (isManageMode) {
                showCreateOrEditDialog(user)
            } else {
                enterProfile(user)
            }
        }
    }

    private fun enterProfile(user: UserProfile) {
        if (user.pin.isNotBlank()) {
            val input = EditText(this).apply {
                hint = "Digite o PIN de ${user.name}"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            AlertDialog.Builder(this)
                .setTitle("Perfil Protegido")
                .setView(input)
                .setPositiveButton("Entrar") { _, _ ->
                    if (input.text.toString().trim() == user.pin) {
                        openHomeForUser(user)
                    } else {
                        Toast.makeText(this, "PIN incorreto!", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } else {
            openHomeForUser(user)
        }
    }

    private fun openHomeForUser(user: UserProfile) {
        UserProfileManager.setActiveUserId(this, user.id)
        startActivity(
            Intent(this, HomeActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            )
        )
        finish()
    }

    private fun showCreateOrEditDialog(existing: UserProfile?) {
        val dlgBinding = DialogUserProfileBinding.inflate(layoutInflater)
        var selectedAvatarId = existing?.avatarId ?: 0
        dlgBinding.etUserName.setText(existing?.name.orEmpty())
        dlgBinding.etUserPin.setText(existing?.pin.orEmpty())

        dlgBinding.rvAvatars.layoutManager = GridLayoutManager(this, 4)
        dlgBinding.rvAvatars.adapter = AvatarChoiceAdapter(
            UserProfileManager.avatars,
            selectedAvatarId
        ) { chosen -> selectedAvatarId = chosen.id }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Novo Perfil" else "Editar Perfil")
            .setView(dlgBinding.root)
            .setPositiveButton("Salvar") { _, _ ->
                val name = dlgBinding.etUserName.text.toString().trim()
                val pin = dlgBinding.etUserPin.text.toString().trim()
                if (existing == null) {
                    val created = UserProfileManager.createUser(this, name, selectedAvatarId, pin)
                    Toast.makeText(this, "Perfil ${created.name} criado!", Toast.LENGTH_SHORT).show()
                } else {
                    UserProfileManager.updateUser(
                        this,
                        existing.copy(
                            name = name.ifEmpty { existing.name },
                            avatarId = selectedAvatarId,
                            pin = pin
                        )
                    )
                    Toast.makeText(this, "Perfil atualizado!", Toast.LENGTH_SHORT).show()
                }
                refreshProfiles()
            }
            .setNegativeButton("Cancelar", null)

        if (existing != null) {
            builder.setNeutralButton("Excluir") { _, _ ->
                if (!UserProfileManager.deleteUser(this, existing.id)) {
                    Toast.makeText(this, "É necessário manter pelo menos 1 perfil.", Toast.LENGTH_SHORT).show()
                } else {
                    refreshProfiles()
                }
            }
        }
        builder.show()
    }

    private inner class UserProfileAdapter(
        private val items: List<UserProfile>,
        private val onClick: (UserProfile) -> Unit
    ) : RecyclerView.Adapter<UserProfileAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemUserProfileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            b.root.enableCinemaFocusZoom(1.08f)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val user = items[position]
            val avatar = UserProfileManager.getAvatar(user.avatarId)
            holder.b.tvAvatarEmoji.text = avatar.emoji
            holder.b.tvProfileTitle.text = if (isManageMode) "✎ ${user.name}" else user.name
            holder.b.tvLockBadge.visibility = if (user.pin.isNotBlank()) View.VISIBLE else View.GONE

            val bg = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor(avatar.bgColorHex))
            }
            holder.b.avatarBox.background = bg
            holder.b.root.setOnClickListener { onClick(user) }
        }

        override fun getItemCount(): Int = items.size
        inner class VH(val b: ItemUserProfileBinding) : RecyclerView.ViewHolder(b.root)
    }

    private class AvatarChoiceAdapter(
        private val items: List<UserAvatar>,
        private var selectedId: Int,
        private val onChoose: (UserAvatar) -> Unit
    ) : RecyclerView.Adapter<AvatarChoiceAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    120
                ).apply { setMargins(6, 6, 6, 6) }
                gravity = android.view.Gravity.CENTER
                textSize = 30f
                isFocusable = true
                setBackgroundResource(R.drawable.selector_cinema_card)
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tv.text = item.emoji
            holder.tv.alpha = if (item.id == selectedId) 1.0f else 0.55f
            holder.tv.setOnClickListener {
                selectedId = item.id
                notifyDataSetChanged()
                onChoose(item)
            }
        }

        override fun getItemCount(): Int = items.size
        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)
    }
}
