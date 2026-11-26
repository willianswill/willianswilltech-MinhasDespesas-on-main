package com.willianswill.novatv.core.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class UserAvatar(
    val id: Int,
    val emoji: String,
    val label: String,
    val bgColorHex: String
)

data class UserProfile(
    val id: Long,
    val name: String,
    val avatarId: Int,
    val pin: String = ""
)

object UserProfileManager {

    private const val PREFS_NAME = "novatv_users_registry"
    private const val KEY_USERS = "users_json"
    private const val KEY_ACTIVE_USER_ID = "active_user_id"

    val avatars = listOf(
        UserAvatar(0, "😎", "Clássico", "#2563EB"),
        UserAvatar(1, "🦁", "Leão", "#D97706"),
        UserAvatar(2, "🦊", "Raposa", "#EA580C"),
        UserAvatar(3, "🐼", "Panda", "#059669"),
        UserAvatar(4, "🤖", "Robô", "#0891B2"),
        UserAvatar(5, "👾", "Gamer", "#7C3AED"),
        UserAvatar(6, "🐱", "Gato", "#DB2777"),
        UserAvatar(7, "🦸", "Herói", "#DC2626")
    )

    fun getAvatar(avatarId: Int): UserAvatar =
        avatars.find { it.id == avatarId } ?: avatars.first()

    fun getUsers(context: Context): List<UserProfile> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_USERS, null)
        if (raw.isNullOrBlank()) {
            val defaultUser = UserProfile(id = 1L, name = "Principal", avatarId = 0)
            saveUsers(context, listOf(defaultUser))
            setActiveUserId(context, 1L)
            return listOf(defaultUser)
        }
        val arr = JSONArray(raw)
        val list = mutableListOf<UserProfile>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                UserProfile(
                    id = obj.getLong("id"),
                    name = obj.getString("name"),
                    avatarId = obj.optInt("avatarId", 0),
                    pin = obj.optString("pin", "")
                )
            )
        }
        return list
    }

    private fun saveUsers(context: Context, list: List<UserProfile>) {
        val arr = JSONArray()
        list.forEach { u ->
            arr.put(
                JSONObject().apply {
                    put("id", u.id)
                    put("name", u.name)
                    put("avatarId", u.avatarId)
                    put("pin", u.pin)
                }
            )
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USERS, arr.toString())
            .apply()
    }

    fun getActiveUserId(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val id = prefs.getLong(KEY_ACTIVE_USER_ID, 1L)
        val users = getUsers(context)
        return if (users.any { it.id == id }) id else users.first().id
    }

    fun getActiveUser(context: Context): UserProfile {
        val id = getActiveUserId(context)
        return getUsers(context).find { it.id == id } ?: getUsers(context).first()
    }

    fun setActiveUserId(context: Context, userId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_ACTIVE_USER_ID, userId)
            .apply()
    }

    fun createUser(context: Context, name: String, avatarId: Int, pin: String = ""): UserProfile {
        val current = getUsers(context).toMutableList()
        val nextId = (current.maxOfOrNull { it.id } ?: 0L) + 1L
        val created = UserProfile(
            id = nextId,
            name = name.trim().ifEmpty { "Perfil $nextId" },
            avatarId = avatarId,
            pin = pin.trim()
        )
        current.add(created)
        saveUsers(context, current)
        return created
    }

    fun updateUser(context: Context, updated: UserProfile) {
        val current = getUsers(context).map { if (it.id == updated.id) updated else it }
        saveUsers(context, current)
    }

    fun deleteUser(context: Context, userId: Long): Boolean {
        val current = getUsers(context)
        if (current.size <= 1) return false
        val remaining = current.filterNot { it.id == userId }
        saveUsers(context, remaining)
        if (getActiveUserId(context) == userId) {
            setActiveUserId(context, remaining.first().id)
        }
        return true
    }
}
