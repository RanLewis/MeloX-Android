package com.lladlam.melox.core.provider.melody

import android.content.Context
import com.lladlam.melox.core.lyrics.LyricsDocument
import com.lladlam.melox.core.music.model.AudioQualityTier
import com.lladlam.melox.core.music.model.MusicAccountSummary
import com.lladlam.melox.core.music.model.MusicAlbumDetail
import com.lladlam.melox.core.music.model.MusicAlbumSummary
import com.lladlam.melox.core.music.model.MusicArtistDetail
import com.lladlam.melox.core.music.model.MusicArtistSummary
import com.lladlam.melox.core.music.model.MusicAuthenticationState
import com.lladlam.melox.core.music.model.MusicHomeFeed
import com.lladlam.melox.core.music.model.MusicPage
import com.lladlam.melox.core.music.model.MusicPlaylistDetail
import com.lladlam.melox.core.music.model.MusicPlaylistSummary
import com.lladlam.melox.core.music.model.MusicSource
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.PlaybackResolution
import com.lladlam.melox.core.music.model.SourceId
import com.lladlam.melox.core.music.provider.AlbumCapability
import com.lladlam.melox.core.music.provider.ArtistCapability
import com.lladlam.melox.core.music.provider.AuthenticationCapability
import com.lladlam.melox.core.music.provider.CatalogSearchCapability
import com.lladlam.melox.core.music.provider.DownloadCapability
import com.lladlam.melox.core.music.provider.FavoriteStateCapability
import com.lladlam.melox.core.music.provider.HomeFeedCapability
import com.lladlam.melox.core.music.provider.LyricsCapability
import com.lladlam.melox.core.music.provider.MusicCapability
import com.lladlam.melox.core.music.provider.MusicProvider
import com.lladlam.melox.core.music.provider.PlaybackCapability
import com.lladlam.melox.core.music.provider.PlaylistCapability
import com.lladlam.melox.core.music.provider.PlaylistWriteCapability
import com.lladlam.melox.core.music.provider.SearchCapability
import com.lladlam.melox.core.music.provider.UserLibraryCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * 自建 melody 后端作为 **原生音源**（[MusicSource.Lewis]）。
 *
 * 不经过插件沙箱：插件通道只做「第三方音源适配器」，拿不到加密偏好、也没有 64 KiB
 * 响应上限之外的空间，更重要的是登录只能靠用户手贴令牌。原生实现让口令登录、
 * 逐字歌词（yrc）、歌单写入这些都能按后端真实能力做。
 *
 * 能力边界（后端没有的出口就不声明，避免 UI 进到一个永远空的页面）：
 * 有搜索 / 歌曲详情 / 播放地址 / 歌词 / 歌单 / 收藏 / 专辑 / 歌手 / 账号；
 * 没有首页推荐、榜单、播客、评论、云盘。
 */
class LewisMelodyProvider(
    context: Context,
    httpClient: OkHttpClient,
) : MusicProvider,
    SearchCapability,
    CatalogSearchCapability,
    PlaybackCapability,
    DownloadCapability,
    LyricsCapability,
    PlaylistCapability,
    PlaylistWriteCapability,
    AlbumCapability,
    ArtistCapability,
    FavoriteStateCapability,
    UserLibraryCapability,
    HomeFeedCapability,
    AuthenticationCapability {

    override val source: MusicSource = MusicSource.Lewis
    override val displayName: String = source.displayName
    override val capabilities: Set<MusicCapability> = setOf(
        MusicCapability.Search,
        MusicCapability.Playback,
        MusicCapability.Lyrics,
        MusicCapability.Download,
        MusicCapability.Library,
        MusicCapability.Playlists,
        MusicCapability.PlaylistWrite,
        MusicCapability.Albums,
        MusicCapability.Artists,
        MusicCapability.Favorites,
        MusicCapability.HomeRecommendations,
        MusicCapability.Authentication,
    )

    private val appContext = context.applicationContext
    private val client = LewisMelodyClient(appContext, httpClient)

    // ---- 首页 ----------------------------------------------------------------

    /**
     * 后端没有推荐接口，所以首页只用两样真实存在的数据：
     *   - 「每日推荐」放**我的歌单**（`/playlist/user`），标题沿用宿主的固定文案；
     *   - 「为你推荐」放**我喜欢**里最近加入的若干首（`/song/like` + `/song/batch/detail`）。
     *
     * 两段都各自吞异常：没登录或后端抽风时首页只是少一块，不该整页报错。
     */
    override suspend fun homeFeed(
        playlistLimit: Int,
        newSongLimit: Int,
        rankingLimit: Int,
    ): MusicHomeFeed = io {
        val playlists = runCatching {
            LewisMelodyMapper.playlists(client.userPlaylists())
        }.getOrElse { emptyList() }

        val liked = runCatching {
            // 接口按「最近加入」倒序，取前几首就是最贴近当下的口味。
            val ids = client.likedIds().take(newSongLimit.coerceIn(1, 50))
            if (ids.isEmpty()) emptyList() else LewisMelodyMapper.tracks(client.songDetail(ids))
        }.getOrElse { emptyList() }

        MusicHomeFeed(
            recommendedPlaylists = playlists.take(playlistLimit.coerceIn(1, 30)),
            newSongs = liked,
        )
    }

    // ---- 搜索 ----------------------------------------------------------------

    override suspend fun searchSongs(query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> =
        io {
            if (query.isBlank()) return@io MusicPage(emptyList(), page.coerceAtLeast(1), pageSize.coerceAtLeast(1))
            val size = pageSize.coerceIn(1, 100)
            val current = page.coerceAtLeast(1)
            val data = client.search(query, SearchTypeSong, size, (current - 1) * size)
            MusicPage(
                items = LewisMelodyMapper.tracks(data.array("songs")),
                page = current,
                pageSize = size,
                total = data.longOrNull("total"),
            )
        }

    override suspend fun searchPlaylists(query: String, page: Int, pageSize: Int): MusicPage<MusicPlaylistSummary> =
        searchCatalog(query, page, pageSize, SearchTypePlaylist) { data ->
            LewisMelodyMapper.playlists(data.array("playlists"))
        }

    override suspend fun searchAlbums(query: String, page: Int, pageSize: Int): MusicPage<MusicAlbumSummary> =
        searchCatalog(query, page, pageSize, SearchTypeAlbum) { data ->
            LewisMelodyMapper.albums(data.array("albums"))
        }

    override suspend fun searchArtists(query: String, page: Int, pageSize: Int): MusicPage<MusicArtistSummary> =
        searchCatalog(query, page, pageSize, SearchTypeArtist) { data ->
            data.array("artists")?.objects().orEmpty().mapNotNull(LewisMelodyMapper::artist)
        }

    private suspend fun <T> searchCatalog(
        query: String,
        page: Int,
        pageSize: Int,
        type: Int,
        map: (JSONObject) -> List<T>,
    ): MusicPage<T> = io {
        if (query.isBlank()) return@io MusicPage(emptyList(), page.coerceAtLeast(1), pageSize.coerceAtLeast(1))
        val size = pageSize.coerceIn(1, 100)
        val current = page.coerceAtLeast(1)
        val data = client.search(query, type, size, (current - 1) * size)
        MusicPage(map(data), current, size, data.longOrNull("total"))
    }

    // ---- 播放 ----------------------------------------------------------------

    override suspend fun resolvePlayback(track: MusicTrack, quality: AudioQualityTier): PlaybackResolution =
        io { resolve(track, quality) }

    override suspend fun resolveDownload(track: MusicTrack, quality: AudioQualityTier): PlaybackResolution =
        io { resolve(track, quality) }

    private fun resolve(track: MusicTrack, quality: AudioQualityTier): PlaybackResolution {
        val songId = track.numericId() ?: return PlaybackResolution.Unavailable("曲目不属于 Melody 音源")
        val entries = runCatching { client.playbackUrls(songId, level(quality)) }
            .getOrElse { return PlaybackResolution.Unavailable(it.message?.take(200) ?: "播放地址请求失败") }
        val entry = entries.optJSONObject(0)
            ?: return fallbackResolution(songId, quality)
        val url = entry.str("url")
        if (url.isEmpty()) return fallbackResolution(songId, quality)
        return PlaybackResolution.Playable(
            url = url,
            requestedQuality = quality,
            actualQuality = quality,
            bitrate = entry.intOrNull("br")?.takeIf { it > 0 },
            format = entry.str("type").takeIf { it.isNotEmpty() && it.length <= 16 },
        )
    }

    /** 后端库里这一档没缓存地址时，退到 standard 再试一次；仍然没有才算不可播。 */
    private fun fallbackResolution(songId: Long, requested: AudioQualityTier): PlaybackResolution {
        if (requested == AudioQualityTier.Standard) return PlaybackResolution.Unavailable("后端没有这首歌的播放地址")
        val entries = runCatching { client.playbackUrls(songId, level(AudioQualityTier.Standard)) }.getOrNull()
        val entry = entries?.optJSONObject(0) ?: return PlaybackResolution.Unavailable("后端没有这首歌的播放地址")
        val url = entry.str("url")
        if (url.isEmpty()) return PlaybackResolution.Unavailable("后端没有这首歌的播放地址")
        return PlaybackResolution.Playable(
            url = url,
            requestedQuality = requested,
            actualQuality = AudioQualityTier.Standard,
            bitrate = entry.intOrNull("br")?.takeIf { it > 0 },
            format = entry.str("type").takeIf { it.isNotEmpty() && it.length <= 16 },
        )
    }

    /** 后端 level 用的是网易那套字符串（见面板 meta.ts 的 LEVEL_ITEMS），不是本地枚举名。 */
    private fun level(quality: AudioQualityTier): String = when (quality) {
        AudioQualityTier.Standard -> "standard"
        AudioQualityTier.High -> "exhigh"
        AudioQualityTier.Lossless -> "lossless"
        AudioQualityTier.HiResolution -> "hires"
        AudioQualityTier.Immersive -> "sky"
        AudioQualityTier.Master -> "jymaster"
    }

    // ---- 歌词 ----------------------------------------------------------------

    override suspend fun lyrics(track: MusicTrack): LyricsDocument = io {
        val songId = track.numericId()
        if (songId == null) {
            LyricsDocument(emptyList(), isUnsynchronized = true)
        } else {
            runCatching { client.lyric(songId) }
                .map(LewisMelodyLyrics::parse)
                .getOrElse { LyricsDocument(emptyList(), isUnsynchronized = true) }
        }
    }

    // ---- 歌单 ----------------------------------------------------------------

    override suspend fun playlistDetail(
        playlist: MusicPlaylistSummary,
        page: Int,
        pageSize: Int,
    ): MusicPlaylistDetail = io {
        val playlistId = playlist.id.value.toLongOrNull()
            ?: error("歌单不属于 Melody 音源")
        val data = client.playlistDetail(playlistId)
        val tracks = LewisMelodyMapper.tracks(data.array("tracks"))
        MusicPlaylistDetail(
            // 详情返回的封面/简介比列表更新，能覆盖就覆盖。
            summary = LewisMelodyMapper.playlist(data) ?: playlist,
            tracks = tracks,
            total = tracks.size.toLong(),
            hasMore = false,
        )
    }

    override suspend fun writablePlaylists(page: Int, pageSize: Int): MusicPage<MusicPlaylistSummary> =
        userPlaylists(page, pageSize)

    override suspend fun addTrackToPlaylist(track: MusicTrack, playlist: MusicPlaylistSummary) {
        io {
            val songId = track.numericId() ?: error("曲目不属于 Melody 音源")
            val playlistId = playlist.id.value.toLongOrNull() ?: error("歌单不属于 Melody 音源")
            client.addTracksToPlaylist(playlistId, listOf(songId))
        }
    }

    // ---- 专辑 / 歌手 ----------------------------------------------------------

    override suspend fun albumDetail(
        album: MusicAlbumSummary,
        page: Int,
        pageSize: Int,
    ): MusicAlbumDetail = io {
        val albumId = album.id.value.toLongOrNull() ?: error("专辑不属于 Melody 音源")
        val data = client.albumDetail(albumId)
        val tracks = LewisMelodyMapper.tracks(data.array("songs"))
        val albumObject = data.obj("album")
        MusicAlbumDetail(
            summary = albumObject?.let(LewisMelodyMapper::album) ?: album,
            tracks = tracks,
            totalTracks = tracks.size.toLong(),
            hasMore = false,
        )
    }

    override suspend fun artistDetail(
        artist: MusicArtistSummary,
        page: Int,
        pageSize: Int,
    ): MusicArtistDetail = io {
        val artistId = artist.id.value.toLongOrNull() ?: error("歌手不属于 Melody 音源")
        val data = client.artistDetail(artistId)
        val tracks = LewisMelodyMapper.tracks(data.array("hotSongs"))
        MusicArtistDetail(
            summary = data.obj("artist")?.let(LewisMelodyMapper::artist) ?: artist,
            tracks = tracks,
            totalTracks = artist.songCount,
            hasMore = data.optBoolean("more", false),
        )
    }

    // ---- 收藏 / 账号 ----------------------------------------------------------

    override suspend fun setFavorite(track: MusicTrack, favorite: Boolean) {
        io {
            val songId = track.numericId() ?: error("曲目不属于 Melody 音源")
            client.setLiked(songId, favorite)
        }
    }

    override suspend fun isFavorite(track: MusicTrack): Boolean = io {
        val songId = track.numericId() ?: return@io false
        client.likedIds().contains(songId)
    }

    override suspend fun accountSummary(): MusicAccountSummary? = io {
        runCatching { client.accountSummary() }.getOrNull()
    }

    override suspend fun userPlaylists(page: Int, pageSize: Int): MusicPage<MusicPlaylistSummary> = io {
        val all = LewisMelodyMapper.playlists(client.userPlaylists())
        // 后端 `/playlist/user` 一次给全量，分页在客户端切，避免为了翻页重复打同一个接口。
        pageOf(all, page, pageSize)
    }

    override suspend fun authenticationStatus(): MusicAuthenticationState = io {
        if (!LewisMelodySessionStore.read(appContext).isLoggedIn) return@io MusicAuthenticationState.SignedOut
        runCatching { client.accountSummary() }.fold(
            onSuccess = { account ->
                if (account == null) MusicAuthenticationState.SignedOut
                else MusicAuthenticationState.SignedIn(account)
            },
            onFailure = { error ->
                // 断网/后端 5xx 不是「已登出」：谎报会让用户莫名被踢回登录页。
                if (error is LewisMelodyAuthException) MusicAuthenticationState.SignedOut else throw error
            },
        )
    }

    // ---- 内部 ----------------------------------------------------------------

    private fun MusicTrack.numericId(): Long? =
        id.value.toLongOrNull()?.takeIf { id.sourceId == SourceId.fromBuiltIn(MusicSource.Lewis) }

    private fun <T> pageOf(all: List<T>, page: Int, pageSize: Int): MusicPage<T> {
        val current = page.coerceAtLeast(1)
        val size = pageSize.coerceIn(1, 500)
        val offset = (current - 1) * size
        val items = if (offset >= all.size) emptyList() else all.subList(offset, minOf(offset + size, all.size)).toList()
        return MusicPage(items, current, size, all.size.toLong())
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val SearchTypeSong = 1
        const val SearchTypeArtist = 10
        const val SearchTypeAlbum = 100
        const val SearchTypePlaylist = 1000
    }
}
