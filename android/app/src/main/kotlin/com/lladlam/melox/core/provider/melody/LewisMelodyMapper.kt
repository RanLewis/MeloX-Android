package com.lladlam.melox.core.provider.melody

import android.content.Context
import com.lladlam.melox.core.music.model.MusicAlbumRef
import com.lladlam.melox.core.music.model.MusicAlbumSummary
import com.lladlam.melox.core.music.model.MusicArtistRef
import com.lladlam.melox.core.music.model.MusicArtistSummary
import com.lladlam.melox.core.music.model.MusicPlaylistSummary
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.ProviderTrackMetadata
import com.lladlam.melox.core.music.model.TrackAvailability
import com.lladlam.melox.core.network.MeloXHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 后端 VO → MeloX 中立模型。
 *
 * 后端字段是网易云那套（`SongVO` / `ArtistInfo` / `AlbumVO` / `PlaylistVO`），
 * 这里只做一次翻译，UI 与播放链路不再看到平台字段。
 */
internal object LewisMelodyMapper {
    /** 歌曲在专辑里的曲目序号，用于同专辑多版本排序时区分。 */
    fun track(song: JSONObject): MusicTrack? {
        val numericId = song.longOrNull("id") ?: return null
        val albumObject = song.obj("album")
        val artwork = albumObject?.str("picUrl")?.takeIf { it.isNotBlank() }
        val albumName = albumObject?.str("name").orEmpty()

        val artistArray = song.array("artist") ?: song.array("artists")
        val artists = artistArray?.objects().orEmpty().mapNotNull { item ->
            val name = item.str("name")
            if (name.isBlank()) null else MusicArtistRef(
                id = item.longOrNull("id")?.let { MusicResourceId(MusicSource.Lewis, it.toString()) },
                name = name,
            )
        }

        return MusicTrack(
            id = MusicResourceId(MusicSource.Lewis, numericId.toString()),
            title = song.str("name").ifBlank { "未知曲目" },
            artists = artists.ifEmpty { listOf(MusicArtistRef(name = "未知歌手")) },
            album = albumName.takeIf { it.isNotBlank() }?.let { name ->
                MusicAlbumRef(
                    id = albumObject?.longOrNull("id")?.let { MusicResourceId(MusicSource.Lewis, it.toString()) },
                    name = name,
                    artworkUrl = artwork,
                )
            },
            artworkUrl = artwork,
            durationMs = song.longOrNull("duration")?.takeIf { it > 0L },
            availability = TrackAvailability.Playable,
            // 后端所有查询只按数值 ID 索引，没有平台私有字段需要随曲目持久化。
            providerMetadata = ProviderTrackMetadata.Empty,
        )
    }

    fun tracks(array: JSONArray?): List<MusicTrack> =
        array?.objects().orEmpty().mapNotNull(::track)

    fun album(album: JSONObject): MusicAlbumSummary? {
        val id = album.longOrNull("id") ?: return null
        val artists = when {
            album.array("artists") != null -> album.array("artists")!!.objects().mapNotNull { item ->
                val name = item.str("name")
                if (name.isBlank()) null else MusicArtistRef(
                    id = item.longOrNull("id")?.let { MusicResourceId(MusicSource.Lewis, it.toString()) },
                    name = name,
                )
            }
            album.obj("artist") != null -> listOfNotNull(
                album.obj("artist")!!.let { item ->
                    val name = item.str("name")
                    if (name.isBlank()) null else MusicArtistRef(
                        id = item.longOrNull("id")?.let { MusicResourceId(MusicSource.Lewis, it.toString()) },
                        name = name,
                    )
                },
            )
            else -> emptyList()
        }
        return MusicAlbumSummary(
            id = MusicResourceId(MusicSource.Lewis, id.toString()),
            title = album.str("name").ifBlank { "未知专辑" },
            artworkUrl = album.str("picUrl").takeIf { it.isNotBlank() },
            artists = artists,
            releaseDate = album.longOrNull("publishTime")?.let(::formatDate),
            trackCount = album.longOrNull("size"),
        )
    }

    fun artist(artist: JSONObject): MusicArtistSummary? {
        val id = artist.longOrNull("id") ?: return null
        return MusicArtistSummary(
            id = MusicResourceId(MusicSource.Lewis, id.toString()),
            name = artist.str("name").ifBlank { "未知歌手" },
            artworkUrl = artist.str("picUrl").ifBlank { artist.str("img1v1Url") }.takeIf { it.isNotBlank() },
            description = artist.str("briefDesc").takeIf { it.isNotBlank() },
            songCount = artist.longOrNull("musicSize"),
            albumCount = artist.longOrNull("albumSize"),
        )
    }

    fun playlist(playlist: JSONObject): MusicPlaylistSummary? {
        val id = playlist.longOrNull("id") ?: return null
        return MusicPlaylistSummary(
            id = MusicResourceId(MusicSource.Lewis, id.toString()),
            title = playlist.str("name").ifBlank { "未命名歌单" },
            artworkUrl = playlist.str("coverImgUrl").takeIf { it.isNotBlank() },
            creatorName = playlist.str("creator").takeIf { it.isNotBlank() },
            description = playlist.str("description").takeIf { it.isNotBlank() },
            trackCount = playlist.array("trackIds")?.length()?.takeIf { it > 0 }
                ?: playlist.array("tracks")?.length()?.takeIf { it > 0 },
        )
    }

    fun playlists(array: JSONArray?): List<MusicPlaylistSummary> =
        array?.objects().orEmpty().mapNotNull(::playlist)

    fun albums(array: JSONArray?): List<MusicAlbumSummary> =
        array?.objects().orEmpty().mapNotNull(::album)

    private fun formatDate(epochMs: Long): String? {
        if (epochMs <= 0L) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(epochMs))
        }.getOrNull()
    }
}

/**
 * 「我喜欢」歌单：后端把它存成独立的红心列表（`/song/like`），不是普通歌单，
 * 所以统一收藏入口不能靠「找一个名字像的歌单」来推断。
 */
internal suspend fun lewisLikedTracks(context: Context): List<MusicTrack> = withContext(Dispatchers.IO) {
    val client = LewisMelodyClient(context.applicationContext, MeloXHttpClient.shared)
    val ids = client.likedIds()
    if (ids.isEmpty()) return@withContext emptyList()
    ids.chunked(200)
        .flatMap { chunk -> LewisMelodyMapper.tracks(client.songDetail(chunk)) }
        .distinctBy { it.id.value }
}
