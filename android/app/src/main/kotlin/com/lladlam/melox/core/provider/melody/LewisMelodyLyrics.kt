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
    /**
     * 翻译/罗马音是另一条独立的 LRC 轨，行首时间戳经常和主歌词差几百毫秒（甚至一秒以上），
     * 按毫秒精确匹配会把翻译整片丢掉。官方 `NeteaseLyricParser` 用的也是容差对齐。
     */
    private const val AnnotationToleranceMs = 1_500L

    fun parse(data: JSONObject): LyricsDocument {
        val lrc = data.obj("lrc")?.str("lyric").orEmpty()
        val translation = data.obj("tlyric")?.str("lyric").orEmpty()
        val romanization = data.obj("romalrc")?.str("lyric").orEmpty()
        val yrc = data.obj("yrc")?.str("lyric").orEmpty()

        val wordLevel = parseYrc(yrc)
        if (wordLevel.isNotEmpty()) {
            return LyricsDocument(
                lines = annotate(wordLevel, translation, romanization),
                source = LyricSource.Lewis,
                quality = LyricQuality.WordSynchronized,
                pseudoTimingAllowed = false,
                isUnsynchronized = false,
            )
        }

        val lineLevel = parseTimedText(lrc).map { (timeMs, text) ->
            LyricLine(
                timeMs = timeMs,
                text = text,
                timingKind = LyricTimingKind.LineSynchronized,
            )
        }
        return LyricsDocument(
            lines = annotate(lineLevel, translation, romanization),
            source = LyricSource.Lewis,
            quality = if (lineLevel.isEmpty()) LyricQuality.Fallback else LyricQuality.LineSynchronized,
            // 只有行级 LRC 时不要让渲染层去做假逐字。
            pseudoTimingAllowed = false,
            isUnsynchronized = lineLevel.isEmpty(),
        )
    }

    private fun parseYrc(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val result = ArrayList<LyricLine>()
        for (line in raw.lineSequence()) {
            // 开头几行是 `{"t":-1000,"c":[...]}` 形式的制作人名单，不是逐字行，直接跳过。
            val header = YrcLine.matchEntire(line.trim()) ?: continue
            val start = header.groupValues[1].toLongOrNull() ?: continue
            val duration = header.groupValues[2].toLongOrNull()?.takeIf { it > 0L }
            val payload = header.groupValues[3]

            val syllables = ArrayList<LyricSyllable>()
            val text = StringBuilder()
            for (match in YrcSyllable.findAll(payload)) {
                val syllableStart = match.groupValues[1].toLongOrNull() ?: continue
                val syllableDuration = match.groupValues[2].toLongOrNull() ?: 0L
                // 括号里是 `(起点,时长,0)`；分组 3 是那个恒为 0 的标志位，文字在分组 4。
                // 取错分组会让每个字都变成 "0"（五个字就是 00000，时间还对得上）。
                val syllableText = match.groupValues[4]
                if (syllableText.isEmpty()) continue
                syllables += LyricSyllable(
                    text = syllableText,
                    startTimeMs = syllableStart,
                    endTimeMs = if (syllableDuration > 0L) syllableStart + syllableDuration else syllableStart,
                )
                text.append(syllableText)
            }
            val lineText = text.toString().trim()
            if (lineText.isBlank()) continue
            result += LyricLine(
                timeMs = start,
                durationMs = duration,
                text = lineText,
                syllables = syllables,
                timingKind = LyricTimingKind.Precise,
            )
        }
        return result.sortedBy { it.timeMs }
    }

    private fun annotate(
        lines: List<LyricLine>,
        translation: String,
        romanization: String,
    ): List<LyricLine> {
        if (lines.isEmpty()) return lines
        val translations = alignAnnotations(lines, parseTimedText(translation))
        val romanizations = alignAnnotations(lines, parseTimedText(romanization))
        if (translations.isEmpty() && romanizations.isEmpty()) return lines
        return lines.map { line ->
            line.copy(
                translation = translations[line.timeMs]?.takeIf { it.isNotBlank() },
                romanization = romanizations[line.timeMs]?.takeIf { it.isNotBlank() },
            )
        }
    }

    /** 双指针容差匹配：主歌词和注释轨都是按时间排好序的，跳过任何一边没对上的行。 */
    private fun alignAnnotations(
        lines: List<LyricLine>,
        annotations: List<Pair<Long, String>>,
    ): Map<Long, String> {
        if (annotations.isEmpty()) return emptyMap()
        val result = HashMap<Long, String>(lines.size)
        var cursor = 0
        for (line in lines) {
            while (cursor < annotations.size && annotations[cursor].first < line.timeMs - AnnotationToleranceMs) {
                cursor++
            }
            val candidate = annotations.getOrNull(cursor) ?: break
            if (candidate.first > line.timeMs + AnnotationToleranceMs) continue
            if (candidate.second.isNotBlank()) result[line.timeMs] = candidate.second
            cursor++
        }
        return result
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
}

/** `[16200,3260](...)我(16620,380,0)们` —— 行首是整行的起点与时长。 */
private val YrcLine = Regex("^\\[(\\d+),(\\d+)](.*)$")

/** `(16200,420,0)我` —— 括号里依次是起点、时长、未知标志位，文字在右括号之后。 */
private val YrcSyllable = Regex("\\((\\d+),(\\d+),(\\d+)\\)([^(]*)")

private val LrcTimeTag = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
