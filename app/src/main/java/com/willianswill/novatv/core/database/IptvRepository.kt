package com.willianswill.novatv.core.database

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.willianswill.novatv.core.database.entity.CategoryEntity
import com.willianswill.novatv.core.database.entity.MediaType
import com.willianswill.novatv.core.database.entity.ProfileEntity
import com.willianswill.novatv.core.database.entity.ProfileType
import com.willianswill.novatv.core.database.entity.SeriesEntity
import com.willianswill.novatv.core.database.entity.StreamEntity
import com.willianswill.novatv.core.database.entity.WatchHistoryEntity
import com.willianswill.novatv.core.network.XtreamApiClient
import com.willianswill.novatv.core.parser.ChannelQualityParser
import com.willianswill.novatv.core.parser.StreamingM3uParser
import com.willianswill.novatv.core.parser.StreamingXmltvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.InputStream

class IptvRepository(context: Context) {

    private val db = NovaTvDatabase.getInstance(context)
    private val profileDao = db.profileDao()
    private val categoryDao = db.categoryDao()
    private val streamDao = db.streamDao()
    private val seriesDao = db.seriesDao()
    private val epgDao = db.epgDao()
    private val historyDao = db.watchHistoryDao()
    private val xtreamClient = XtreamApiClient()

    private val adultKeywords = listOf("adult", "xxx", "+18", "18+", "porn", "erotic")

    fun observeProfiles(): Flow<List<ProfileEntity>> = profileDao.observeProfiles()
    fun observeActiveProfile(): Flow<ProfileEntity?> = profileDao.observeActiveProfile()

    suspend fun setActiveProfile(profileId: Long) = withContext(Dispatchers.IO) {
        profileDao.setActiveProfile(profileId)
    }

    suspend fun deleteProfile(profileId: Long) = withContext(Dispatchers.IO) {
        streamDao.deleteByProfile(profileId)
        seriesDao.deleteByProfile(profileId)
        categoryDao.deleteByProfile(profileId)
        epgDao.deleteByProfile(profileId)
        historyDao.deleteByProfile(profileId)
        profileDao.deleteById(profileId)
    }

    suspend fun saveProfile(profile: ProfileEntity, activate: Boolean = true): Long = withContext(Dispatchers.IO) {
        val id = profileDao.insert(profile)
        if (activate) {
            profileDao.setActiveProfile(id)
        }
        id
    }

    fun pagedStreams(
        profileId: Long,
        mediaType: String,
        categoryId: String?,
        onlyFavorites: Boolean,
        includeAdult: Boolean,
        query: String
    ): Flow<PagingData<StreamEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = 60,
                prefetchDistance = 30,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                streamDao.pagingSource(
                    profileId = profileId,
                    mediaType = mediaType,
                    categoryId = categoryId,
                    onlyFavorites = onlyFavorites,
                    includeAdult = includeAdult,
                    query = query
                )
            }
        ).flow
    }

    fun pagedSeries(
        profileId: Long,
        categoryId: String?,
        onlyFavorites: Boolean,
        includeAdult: Boolean,
        query: String
    ): Flow<PagingData<SeriesEntity>> {
        return Pager(
            config = PagingConfig(
                pageSize = 60,
                prefetchDistance = 30,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                seriesDao.pagingSource(
                    profileId = profileId,
                    categoryId = categoryId,
                    onlyFavorites = onlyFavorites,
                    includeAdult = includeAdult,
                    query = query
                )
            }
        ).flow
    }

    fun observeCategories(profileId: Long, mediaType: String, includeAdult: Boolean) =
        categoryDao.observeCategories(profileId, mediaType, includeAdult)

    fun observeFavorites(profileId: Long, includeAdult: Boolean) =
        streamDao.observeFavorites(profileId, includeAdult)

    fun observeRecentlyAdded(profileId: Long, mediaType: String, includeAdult: Boolean) =
        streamDao.observeRecentlyAdded(profileId, mediaType, includeAdult)

    fun observeContinueWatching(profileId: Long) =
        historyDao.observeContinueWatching(profileId)

    suspend fun toggleStreamFavorite(id: Long, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        streamDao.updateFavorite(id, isFavorite)
    }

    suspend fun toggleSeriesFavorite(id: Long, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        seriesDao.updateFavorite(id, isFavorite)
    }

    suspend fun upsertWatchProgress(entry: WatchHistoryEntity) = withContext(Dispatchers.IO) {
        historyDao.upsert(entry)
    }

    suspend fun getCurrentEpg(profileId: Long, channelId: String) = withContext(Dispatchers.IO) {
        if (channelId.isBlank()) return@withContext null
        epgDao.getCurrentProgram(profileId, channelId, System.currentTimeMillis())
    }

    suspend fun getEpgInRange(profileId: Long, channelId: String, fromMs: Long, toMs: Long) =
        withContext(Dispatchers.IO) {
            if (channelId.isBlank()) return@withContext emptyList()
            epgDao.getProgramsInRange(profileId, channelId, fromMs, toMs)
        }

    suspend fun getChannelVariants(stream: StreamEntity): List<StreamEntity> =
        withContext(Dispatchers.IO) {
            streamDao.getVariants(
                profileId = stream.profileId,
                mediaType = stream.mediaType,
                categoryId = stream.categoryId,
                baseName = stream.baseName
            )
        }

    suspend fun syncActiveProfile(): ProfileEntity? = withContext(Dispatchers.IO) {
        val profile = profileDao.getActiveProfile() ?: return@withContext null
        when (profile.type) {
            ProfileType.XTREAM.name -> syncXtreamProfile(profile)
            ProfileType.M3U_URL.name -> {
                xtreamClient.openStream(profile.serverUrl, profile.userAgent).use { stream ->
                    syncM3uStream(profile, stream)
                }
            }
            else -> profile
        }
    }

    suspend fun syncXtreamProfile(profile: ProfileEntity): ProfileEntity = withContext(Dispatchers.IO) {
        val ua = profile.userAgent.takeIf { it.isNotBlank() }
        val auth = xtreamClient.authenticate(profile.serverUrl, profile.username, profile.password, ua)
        val status = auth.userInfo?.status.orEmpty()
        if (status.isNotEmpty() && !status.equals("Active", ignoreCase = true)) {
            throw IllegalStateException("Conta Xtream com status: $status")
        }

        val favStreamUrls = streamDao.getFavoriteUrls(profile.id).toHashSet()
        val favSeriesIds = seriesDao.getFavoriteSeriesIds(profile.id).toHashSet()

        val liveCats = runCatching {
            xtreamClient.getLiveCategories(profile.serverUrl, profile.username, profile.password, ua)
        }.getOrDefault(emptyList())
        val vodCats = runCatching {
            xtreamClient.getVodCategories(profile.serverUrl, profile.username, profile.password, ua)
        }.getOrDefault(emptyList())
        val seriesCats = runCatching {
            xtreamClient.getSeriesCategories(profile.serverUrl, profile.username, profile.password, ua)
        }.getOrDefault(emptyList())

        val catNameMap = HashMap<String, String>()
        val categoryEntities = ArrayList<CategoryEntity>()

        liveCats.forEachIndexed { idx, c ->
            catNameMap["L_${c.categoryId}"] = c.categoryName
            categoryEntities.add(
                CategoryEntity(
                    profileId = profile.id,
                    categoryId = c.categoryId,
                    name = c.categoryName,
                    mediaType = MediaType.LIVE,
                    isAdult = isAdult(c.categoryName),
                    sortOrder = idx
                )
            )
        }
        vodCats.forEachIndexed { idx, c ->
            catNameMap["M_${c.categoryId}"] = c.categoryName
            categoryEntities.add(
                CategoryEntity(
                    profileId = profile.id,
                    categoryId = c.categoryId,
                    name = c.categoryName,
                    mediaType = MediaType.MOVIE,
                    isAdult = isAdult(c.categoryName),
                    sortOrder = idx
                )
            )
        }
        seriesCats.forEachIndexed { idx, c ->
            catNameMap["S_${c.categoryId}"] = c.categoryName
            categoryEntities.add(
                CategoryEntity(
                    profileId = profile.id,
                    categoryId = c.categoryId,
                    name = c.categoryName,
                    mediaType = MediaType.SERIES,
                    isAdult = isAdult(c.categoryName),
                    sortOrder = idx
                )
            )
        }

        categoryDao.deleteByProfile(profile.id)
        streamDao.deleteByProfile(profile.id)
        seriesDao.deleteByProfile(profile.id)

        categoryEntities.chunked(500).forEach { categoryDao.insertAll(it) }

        val liveDtos = runCatching {
            xtreamClient.getLiveStreams(profile.serverUrl, profile.username, profile.password, ua)
        }.getOrDefault(emptyList())

        liveDtos.chunked(500).forEach { chunk ->
            val entities = chunk.map { dto ->
                val url = xtreamClient.buildLiveUrl(profile.serverUrl, profile.username, profile.password, dto.streamId, "ts")
                val catId = dto.categoryId.orEmpty()
                val catName = catNameMap["L_$catId"] ?: "Ao Vivo"
                val parsedQ = ChannelQualityParser.parse(dto.name)
                StreamEntity(
                    profileId = profile.id,
                    streamId = dto.streamId.toString(),
                    mediaType = MediaType.LIVE,
                    name = dto.name,
                    baseName = parsedQ.baseName,
                    qualityLabel = parsedQ.qualityLabel,
                    qualityRank = parsedQ.qualityRank,
                    streamUrl = url,
                    iconUrl = dto.streamIcon.orEmpty(),
                    categoryId = catId,
                    categoryName = catName,
                    epgChannelId = dto.epgChannelId.orEmpty(),
                    hasCatchup = dto.tvArchive == 1,
                    catchupDays = dto.tvArchiveDuration,
                    userAgent = ua,
                    isFavorite = favStreamUrls.contains(url),
                    isAdult = isAdult(catName) || isAdult(dto.name),
                    addedAt = dto.added?.toLongOrNull() ?: 0L
                )
            }
            streamDao.insertAll(entities)
        }

        val vodDtos = runCatching {
            xtreamClient.getVodStreams(profile.serverUrl, profile.username, profile.password, ua)
        }.getOrDefault(emptyList())

        vodDtos.chunked(500).forEach { chunk ->
            val entities = chunk.map { dto ->
                val ext = dto.containerExtension.orEmpty().ifBlank { "mp4" }
                val url = xtreamClient.buildMovieUrl(profile.serverUrl, profile.username, profile.password, dto.streamId, ext)
                val catId = dto.categoryId.orEmpty()
                val catName = catNameMap["M_$catId"] ?: "Filmes"
                val ratingStr = (dto.rating as? JsonPrimitive)?.contentOrNull.orEmpty()
                StreamEntity(
                    profileId = profile.id,
                    streamId = dto.streamId.toString(),
                    mediaType = MediaType.MOVIE,
                    name = dto.name,
                    streamUrl = url,
                    iconUrl = dto.streamIcon.orEmpty(),
                    categoryId = catId,
                    categoryName = catName,
                    containerExtension = ext,
                    rating = ratingStr,
                    userAgent = ua,
                    isFavorite = favStreamUrls.contains(url),
                    isAdult = isAdult(catName) || isAdult(dto.name),
                    addedAt = dto.added?.toLongOrNull() ?: 0L
                )
            }
            streamDao.insertAll(entities)
        }

        val seriesDtos = runCatching {
            xtreamClient.getSeries(profile.serverUrl, profile.username, profile.password, ua)
        }.getOrDefault(emptyList())

        seriesDtos.chunked(500).forEach { chunk ->
            val entities = chunk.map { dto ->
                val sid = dto.seriesId.toString()
                val catId = dto.categoryId.orEmpty()
                val catName = catNameMap["S_$catId"] ?: "Séries"
                val ratingStr = (dto.rating as? JsonPrimitive)?.contentOrNull.orEmpty()
                SeriesEntity(
                    profileId = profile.id,
                    seriesId = sid,
                    name = dto.name,
                    coverUrl = dto.cover.orEmpty(),
                    categoryId = catId,
                    categoryName = catName,
                    plot = dto.plot.orEmpty(),
                    cast = dto.cast.orEmpty(),
                    director = dto.director.orEmpty(),
                    genre = dto.genre.orEmpty(),
                    releaseDate = dto.releaseDate.orEmpty(),
                    rating = ratingStr,
                    isFavorite = favSeriesIds.contains(sid),
                    isAdult = isAdult(catName) || isAdult(dto.name),
                    lastModified = dto.lastModified?.toLongOrNull() ?: 0L
                )
            }
            seriesDao.insertAll(entities)
        }

        val epgUrl = profile.epgUrl.ifBlank {
            xtreamClient.buildXmltvEpgUrl(profile.serverUrl, profile.username, profile.password)
        }
        val updated = profile.copy(
            epgUrl = epgUrl,
            lastSyncAt = System.currentTimeMillis(),
            totalChannels = liveDtos.size,
            totalMovies = vodDtos.size,
            totalSeries = seriesDtos.size
        )
        profileDao.update(updated)
        updated
    }

    suspend fun syncM3uStream(profile: ProfileEntity, input: InputStream): ProfileEntity =
        withContext(Dispatchers.IO) {
            val favStreamUrls = streamDao.getFavoriteUrls(profile.id).toHashSet()
            categoryDao.deleteByProfile(profile.id)
            streamDao.deleteByProfile(profile.id)
            seriesDao.deleteByProfile(profile.id)

            val stats = StreamingM3uParser.parseInChunks(
                profileId = profile.id,
                input = input,
                favoriteUrls = favStreamUrls,
                onStreamsChunk = { chunk -> streamDao.insertAll(chunk) },
                onCategoriesCollected = { cats ->
                    cats.chunked(500).forEach { categoryDao.insertAll(it) }
                }
            )

            val updated = profile.copy(
                epgUrl = profile.epgUrl.ifBlank { stats.epgUrlHeader.orEmpty() },
                lastSyncAt = System.currentTimeMillis(),
                totalChannels = stats.totalChannels,
                totalMovies = stats.totalMovies,
                totalSeries = 0
            )
            profileDao.update(updated)
            updated
        }

    suspend fun syncEpgForProfile(profile: ProfileEntity): Int = withContext(Dispatchers.IO) {
        if (profile.epgUrl.isBlank()) return@withContext 0
        val cutoff = System.currentTimeMillis() - (24L * 60L * 60L * 1000L)
        epgDao.deleteExpired(profile.id, cutoff)

        xtreamClient.openStream(profile.epgUrl, profile.userAgent).use { stream ->
            StreamingXmltvParser.parseInChunks(profile.id, stream) { chunk ->
                epgDao.insertAll(chunk)
            }
        }
    }

    private fun isAdult(text: String): Boolean {
        val lower = text.lowercase()
        return adultKeywords.any { lower.contains(it) }
    }
}
