package com.lladlam.melox.core.provider.melody

import android.content.Context
import com.lladlam.melox.core.lyrics.LyricLine
import com.lladlam.melox.core.lyrics.LyricQuality
import com.lladlam.melox.core.lyrics.LyricsDocument
import com.lladlam.melox.core.music.model.AudioQualityTier
import com.lladlam.melox.core.music.model.MusicAlbumRef
import com.lladlam.melox.core.music.model.MusicArtistRef
import com.lladlam.melox.core.music.model.MusicPage
import com.lladlam.melox.core.music.model.MusicResourceId
import com.lladlam.melox.core.music.model.MusicTrack
import com.lladlam.melox.core.music.model.PlaybackResolution
import com.lladlam.melox.core.music.model.ProviderTrackMetadata
import com.lladlam.melox.core.music.model.SourceId
import com.lladlam.melox.core.music.model.TrackAvailability
import com.lladlam.melox.core.music.provider.DownloadCapability
import com.lladlam.melox.core.music.provider.LyricsCapability
import com.lladlam.melox.core.music.provider.MusicCapability
import com.lladlam.melox.core.music.provider.MusicProvider
import com.lladlam.melox.core.music.provider.PlaybackCapability
import com.lladlam.melox.core.music.provider.SearchCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 自建 melody 后端作为原生音源。
 *
 * 走「动态音源」这条通道（`plugin:` 前缀），而不是往 [com.lladlam.melox.core.music.model.MusicSource]
 * 里加枚举：枚举被 76 个文件引用、十几处 `when` 穷举，加一项就要跟着改 UI 和账号页；
 * 动态通道已经是插件在用的成熟路径，搜索页会把它渲染成独立的音源 chip。
 */
class LewisMelodyProvider(
    context: Context,
    httpClient: OkHttpClient,
) : MusicProvider, SearchCapability, LyricsCapability, PlaybackCapability, DownloadCapability {
    companion object {
        const val SourceValue = "plugin:local.lewis-melody"
        private const val DisplayName = "Lewis Melody"
    }

    override val sourceId: SourceId = requireNotNull(SourceId.parse(SourceValue)) {
        "$SourceValue 不是合法的来源标识"
    }
    override val displayName: String = DisplayName
    override val capabilities: Set<MusicCapability> = setOf(
        MusicCapability.Search,
        MusicCapability.Playback,
        MusicCapability.Lyrics,
        MusicCapability.Download,
    )

    private val client = LewisMelodyClient(context.applicationContext, httpClient)

    override suspend fun searchSongs(query: String, page: Int, pageSize: Int): MusicPage<MusicTrack> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext MusicPage(emptyList(), page.coerceAtLeast(1), pageSize)
            val size = pageSize.coerceIn(1, 100)
            val data = client.search(query, page.coerceAtLeast(1), size)
            val songs = data.optJSONArray("songs") ?: JSONArray()
            val items = buildList {
                for (index in 0 until songs.length()) {
                    songs.optJSONObject(index)?.let(::toTrack)?.let(::add)
                }
            }
            val total = data.optLong("total").takeIf { it > 0L }
            MusicPage(items, page.coerceAtLeast(1), size, total)
        }

    override suspend fun lyrics(track: MusicTrack): LyricsDocument = withContext(Dispatchers.IO) {
        require(track.id.sourceId == sourceId) { "曲目不属于当前音源" }
        val data = runCatching { client.lyric(track.id.value) }.getOrNull()
            ?: return@withContext LyricsDocument(emptyList(), isUnsynchronized = true)
        val lrc = data.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val translation = data.optJSONObject("tlyric")?.optString("lyric").orEmpty()
        val lines = parseLrc(lrc, translation)
        LyricsDocument(
            lines = lines,
            quality = if (lines.isEmpty()) LyricQuality.Fallback else LyricQuality.LineSynchronized,
            // 逐字时间轴要 YRC；手里只有行级 LRC，就别让渲染层去做假逐字。
            pseudoTimingAllowed = false,
            isUnsynchronized = lines.isEmpty(),
        )
    }

    override suspend fun resolvePlayback(track: MusicTrack, quality: AudioQualityTier): PlaybackResolution =
        withContext(Dispatchers.IO) {
            require(track.id.sourceId == sourceId) { "曲目不属于当前音源" }
            resolve(track, quality)
        }

    override suspend fun resolveDownload(track: MusicTrack, quality: AudioQualityTier): PlaybackResolution =
        withContext(Dispatchers.IO) {
            require(track.id.sourceId == sourceId) { "曲目不属于当前音源" }
            resolve(track, quality)
        }

    private fun resolve(track: MusicTrack, quality: AudioQualityTier): PlaybackResolution {
        val entries = runCatching { client.playback(track.id.value, level(quality)) }
            .getOrElse { return PlaybackResolution.Unavailable(it.message?.take(200) ?: "播放地址请求失败") }
        val first = entries.optJSONObject(0) ?: return PlaybackResolution.Unavailable("后端没有返回播放地址")
        val url = first.optString("url").trim()
        if (url.isEmpty()) return PlaybackResolution.Unavailable("播放地址为空")
        return PlaybackResolution.Playable(
            url = url,
            requestedQuality = quality,
            actualQuality = quality,
            bitrate = first.optInt("br").takeIf { it > 0 },
            format = first.optString("type").trim().takeIf { it.isNotEmpty() && it.length <= 16 },
        )
    }

    /** 后端 level 用的是网易那套字符串，不是本地的枚举名。 */
    private fun level(quality: AudioQualityTier): String = when (quality) {
        AudioQualityTier.Standard -> "standard"
        AudioQualityTier.High -> "higher"
        AudioQualityTier.Lossless -> "lossless"
        AudioQualityTier.HiResolution -> "hires"
        AudioQualityTier.Immersive -> "hires"
        AudioQualityTier.Master -> "hires"
    }

    private fun toTrack(song: JSONObject): MusicTrack? {
        val numericId = song.optLong("id").takeIf { it > 0L } ?: return null
        val album = song.optJSONObject("album")
        val artwork = album?.optString("picUrl")?.trim().orEmpty().takeIf { it.isNotEmpty() }
        val artists = buildList {
            val array = song.optJSONArray("artist") ?: song.optJSONArray("artists")
            if (array != null) {
                for (index in 0 until array.length()) {
                    val name = array.optJSONObject(index)?.optString("name")?.trim().orEmpty()
                    if (name.isNotEmpty()) add(MusicArtistRef(name = name))
                }
            }
        }
        val albumName = album?.optString("name")?.trim().orEmpty()
        return MusicTrack(
            id = MusicResourceId(sourceId, numericId.toString()),
            title = song.optString("name").trim().ifEmpty { "未知曲目" },
            artists = artists.ifEmpty { listOf(MusicArtistRef(name = "未知歌手")) },
            album = albumName.takeIf { it.isNotEmpty() }?.let { MusicAlbumRef(name = it, artworkUrl = artwork) },
            artworkUrl = artwork,
            durationMs = song.optLong("duration").takeIf { it > 0L },
            availability = TrackAvailability.Playable,
            // 后端只按数值 ID 索引，没有平台私有字段需要跟着曲目走。
            providerMetadata = ProviderTrackMetadata.Empty,
        )
    }
}

/**
 * 行级 LRC 解析：一个时间标签一行，同一行可以带多个标签（副歌复用）。
 * 翻译按毫秒时间戳对齐；对不上的翻译宁可丢掉，也不要错位显示。
 */
private val TimeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

private fun parseLrc(lrc: String, translation: String): List<LyricLine> {
    if (lrc.isBlank()) return emptyList()
    val translated = parseTimedText(translation).toMap()
    val lines = parseTimedText(lrc)
    val result = ArrayList<LyricLine>(lines.size)
    var previous = -1L
    for ((timeMs, text) in lines) {
        if (timeMs < previous) continue
        previous = timeMs
        result += LyricLine(
            timeMs = timeMs,
            text = text,
            translation = translated[timeMs]?.takeIf { it.isNotEmpty() },
        )
    }
    return result
}

private fun parseTimedText(raw: String): List<Pair<Long, String>> {
    if (raw.isBlank()) return emptyList()
    val result = ArrayList<Pair<Long, String>>()
    for (line in raw.lineSequence()) {
        val matches = TimeTag.findAll(line).toList()
        if (matches.isEmpty()) continue
        val text = line.substring(matches.last().range.last + 1).trim()
        if (text.isEmpty()) continue
        for (match in matches) {
            val minutes = match.groupValues[1].toLongOrNull() ?: continue
            val seconds = match.groupValues[2].toLongOrNull() ?: continue
            if (seconds >= 60L) continue
            val fraction = match.groupValues[3]
            val millis = when (fraction.length) {
                0 -> 0L
                1 -> fraction.toLongOrNull()?.times(100) ?: 0L
                2 -> fraction.toLongOrNull()?.times(10) ?: 0L
                else -> fraction.take(3).toLongOrNull() ?: 0L
            }
            result += (minutes * 60_000L + seconds * 1_000L + millis) to text
        }
    }
    return result.sortedBy { it.first }
}
