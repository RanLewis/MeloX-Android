package com.lladlam.melox.core.provider.melody

import com.lladlam.melox.core.lyrics.LyricLine
import com.lladlam.melox.core.lyrics.LyricQuality
import com.lladlam.melox.core.lyrics.LyricSyllable
import com.lladlam.melox.core.lyrics.LyricSource
import com.lladlam.melox.core.lyrics.LyricTimingKind
import com.lladlam.melox.core.lyrics.LyricsDocument
import org.json.JSONObject

/**
 * `LyricVO` → [LyricsDocument]。
 *
 * 后端同时给 lrc / tlyric / romalrc / yrc 四份文本（lyric.vo.ts），能不能逐字取决于
 * 库里有没有 yrc，而不是 `sgc` 那个字段（后端注释明说不能拿它判断）。所以这里先试 yrc，
 * 有就按逐字解析，没有才退回行级 LRC，并且**不允许**渲染层把行级歌词伪造出逐字时间轴。
 */
internal object LewisMelodyLyrics {
    fun parse(data: JSONObject): LyricsDocument {
        val lrc = data.obj("lrc")?.str("lyric").orEmpty()
        val translation = data.obj("tlyric")?.str("lyric").orEmpty()
        val romanization = data.obj("romalrc")?.str("lyric").orEmpty()
        val yrc = data.obj("yrc")?.str("lyric").orEmpty()

        val translations = parseTimedText(translation).toTimeMap()
        val romanizations = parseTimedText(romanization).toTimeMap()

        val wordLevel = parseYrc(yrc, translations, romanizations)
        if (wordLevel.isNotEmpty()) {
            return LyricsDocument(
                lines = wordLevel,
                source = LyricSource.Lewis,
                quality = LyricQuality.WordSynchronized,
                pseudoTimingAllowed = false,
                isUnsynchronized = false,
            )
        }

        val lines = parseTimedText(lrc).map { (timeMs, text) ->
            LyricLine(
                timeMs = timeMs,
                text = text,
                translation = translations[timeMs]?.takeIf { it.isNotBlank() },
                romanization = romanizations[timeMs]?.takeIf { it.isNotBlank() },
                timingKind = LyricTimingKind.LineSynchronized,
            )
        }
        return LyricsDocument(
            lines = lines,
            source = LyricSource.Lewis,
            quality = if (lines.isEmpty()) LyricQuality.Fallback else LyricQuality.LineSynchronized,
            // 只有行级 LRC 时不要让渲染层去做假逐字。
            pseudoTimingAllowed = false,
            isUnsynchronized = lines.isEmpty(),
        )
    }

    private fun parseYrc(
        raw: String,
        translations: Map<Long, String>,
        romanizations: Map<Long, String>,
    ): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val result = ArrayList<LyricLine>()
        for (line in raw.lineSequence()) {
            val header = YrcLine.matchEntire(line.trim()) ?: continue
            val start = header.groupValues[1].toLongOrNull() ?: continue
            val duration = header.groupValues[2].toLongOrNull()?.takeIf { it > 0L }
            val payload = header.groupValues[3]

            val syllables = ArrayList<LyricSyllable>()
            var text = ""
            for (match in YrcSyllable.findAll(payload)) {
                val syllableStart = match.groupValues[1].toLongOrNull() ?: continue
                val syllableDuration = match.groupValues[2].toLongOrNull() ?: 0L
                val syllableText = match.groupValues[3]
                if (syllableText.isBlank()) continue
                syllables += LyricSyllable(
                    text = syllableText,
                    startTimeMs = syllableStart,
                    endTimeMs = if (syllableDuration > 0L) syllableStart + syllableDuration else syllableStart,
                )
                text += syllableText
            }
            if (text.isBlank()) continue
            result += LyricLine(
                timeMs = start,
                durationMs = duration,
                text = text,
                syllables = syllables,
                translation = translations[start]?.takeIf { it.isNotBlank() },
                romanization = romanizations[start]?.takeIf { it.isNotBlank() },
                timingKind = LyricTimingKind.Precise,
            )
        }
        return result.sortedBy { it.timeMs }
    }

    private fun parseTimedText(raw: String): List<Pair<Long, String>> {
        if (raw.isBlank()) return emptyList()
        val result = ArrayList<Pair<Long, String>>()
        for (line in raw.lineSequence()) {
            val matches = LrcTimeTag.findAll(line).toList()
            if (matches.isEmpty()) continue
            // 同一行可以挂多个时间标签（副歌复用），文本取最后一个标签之后的部分。
            val text = line.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (match in matches) {
                val timeMs = toMillis(match) ?: continue
                result += timeMs to text
            }
        }
        return result.sortedBy { it.first }
    }

    private fun toMillis(match: MatchResult): Long? {
        val minutes = match.groupValues[1].toLongOrNull() ?: return null
        val seconds = match.groupValues[2].toLongOrNull() ?: return null
        if (seconds >= 60L) return null
        val fraction = match.groupValues[3]
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLongOrNull()?.times(100) ?: 0L
            2 -> fraction.toLongOrNull()?.times(10) ?: 0L
            else -> fraction.take(3).toLongOrNull() ?: 0L
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }

    /** 同一毫秒有多条时取第一条：翻译比歌词多出空行时不要覆盖已有内容。 */
    private fun List<Pair<Long, String>>.toTimeMap(): Map<Long, String> {
        if (isEmpty()) return emptyMap()
        val map = HashMap<Long, String>(size)
        for ((timeMs, text) in this) map.putIfAbsent(timeMs, text)
        return map
    }
}

/** `[16200,3260](...)我(16620,380,0)们` —— 行首是整行的起点与时长。 */
private val YrcLine = Regex("^\\[(\\d+),(\\d+)](.*)$")

/** `(16200,420,0)我` —— 括号里依次是起点、时长、未知标志位。 */
private val YrcSyllable = Regex("\\((\\d+),(\\d+),(\\d+)\\)([^(]*)")

private val LrcTimeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
