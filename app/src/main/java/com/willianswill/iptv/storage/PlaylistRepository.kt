package com.willianswill.iptv.storage

import android.content.Context
import com.willianswill.iptv.model.Channel
import com.willianswill.iptv.model.PlaylistSource
import com.willianswill.iptv.parser.M3uParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class PlaylistRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("minhas_tv_prefs", Context.MODE_PRIVATE)
    private val playlistDir = File(context.filesDir, "playlists").apply { mkdirs() }

    companion object {
        private const val KEY_PLAYLISTS = "playlists_json"
        private const val KEY_ACTIVE_PLAYLIST_ID = "active_playlist_id"
        private const val KEY_FAVORITES = "favorite_urls"
        private const val KEY_RECENTS = "recent_urls"
        private const val MAX_RECENTS = 25
    }

    fun getPlaylists(): List<PlaylistSource> {
        val raw = prefs.getString(KEY_PLAYLISTS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<PlaylistSource>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            list.add(
                PlaylistSource(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    sourceUrl = obj.optString("sourceUrl", ""),
                    isLocal = obj.optBoolean("isLocal", false),
                    channelCount = obj.optInt("channelCount", 0),
                    updatedAt = obj.optLong("updatedAt", 0L)
                )
            )
        }
        return list
    }

    private fun savePlaylists(list: List<PlaylistSource>) {
        val arr = JSONArray()
        for (p in list) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("sourceUrl", p.sourceUrl)
                put("isLocal", p.isLocal)
                put("channelCount", p.channelCount)
                put("updatedAt", p.updatedAt)
            }
            arr.put(obj)
        }
        prefs.edit().putString(KEY_PLAYLISTS, arr.toString()).apply()
    }

    fun getActivePlaylistId(): String? {
        return prefs.getString(KEY_ACTIVE_PLAYLIST_ID, null)
    }

    fun setActivePlaylistId(id: String?) {
        prefs.edit().putString(KEY_ACTIVE_PLAYLIST_ID, id).apply()
    }

    fun addOrUpdatePlaylist(name: String, url: String, content: String, isLocal: Boolean, existingId: String? = null): PlaylistSource {
        val favs = getFavoriteUrls()
        val channels = M3uParser.parseStream(content.byteInputStream(Charsets.UTF_8), favs)
        if (channels.isEmpty()) {
            throw IllegalArgumentException("Nenhum canal válido encontrado nessa lista M3U.")
        }

        val id = existingId ?: UUID.randomUUID().toString()
        val file = File(playlistDir, "$id.m3u")
        file.writeText(content, Charsets.UTF_8)

        val item = PlaylistSource(
            id = id,
            name = name.ifBlank { "Minha Lista IPTV" },
            sourceUrl = url,
            isLocal = isLocal,
            channelCount = channels.size,
            updatedAt = System.currentTimeMillis()
        )

        val current = getPlaylists().filterNot { it.id == id }.toMutableList()
        current.add(0, item)
        savePlaylists(current)
        setActivePlaylistId(id)
        return item
    }

    fun deletePlaylist(id: String) {
        val file = File(playlistDir, "$id.m3u")
        if (file.exists()) file.delete()

        val updated = getPlaylists().filterNot { it.id == id }
        savePlaylists(updated)

        if (getActivePlaylistId() == id) {
            setActivePlaylistId(updated.firstOrNull()?.id)
        }
    }

    fun loadChannels(playlistId: String): List<Channel> {
        val file = File(playlistDir, "$playlistId.m3u")
        if (!file.exists()) return emptyList()
        val favs = getFavoriteUrls()
        return file.inputStream().use { M3uParser.parseStream(it, favs) }
    }

    fun getFavoriteUrls(): Set<String> {
        return prefs.getStringSet(KEY_FAVORITES, emptySet())?.toSet() ?: emptySet()
    }

    fun toggleFavorite(url: String): Boolean {
        val current = getFavoriteUrls().toMutableSet()
        val added = if (current.contains(url)) {
            current.remove(url)
            false
        } else {
            current.add(url)
            true
        }
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
        return added
    }

    fun recordRecent(url: String) {
        val raw = prefs.getString(KEY_RECENTS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<String>()
        list.add(url)
        for (i in 0 until arr.length()) {
            val item = arr.getString(i)
            if (item != url && list.size < MAX_RECENTS) {
                list.add(item)
            }
        }
        prefs.edit().putString(KEY_RECENTS, JSONArray(list).toString()).apply()
    }

    fun getRecentUrls(): List<String> {
        val raw = prefs.getString(KEY_RECENTS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return List(arr.length()) { arr.getString(it) }
    }
}
