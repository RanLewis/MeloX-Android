package com.lladlam.melox.core.provider.melody

import android.content.Context
import com.lladlam.melox.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 后端 `/api/melody/**` 的最小客户端。
 *
 * 三条约定决定了这里的写法：
 *   1. 所有出口都在鉴权面上，必须带 `Authorization: Bearer <token>`；令牌过期（401）就
 *      用构建时注入的口令重新换一枚，换不到才让调用方看到失败。
 *   2. 信封是 `{ code, data, message }`，业务码不是 200 一律当失败 —— 不能只看 HTTP 状态。
 *   3. 后端返回的是结构化 JSON（SongVO / LyricVO / SongUrlVO），不做二次猜测。
 */
internal class LewisMelodyClient(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** `/api/melody/search`，type=1 是歌曲。 */
    fun search(query: String, page: Int, pageSize: Int): JSONObject {
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val url = endpoint("/api/melody/search")
            .addQueryParameter("keyword", query)
            .addQueryParameter("type", "1")
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("total", "true")
            .build()
        return dataObject(Request.Builder().url(url).get())
    }

    /** `/api/melody/lyric`，返回 LRC / 翻译（YRC 逐字先不用）。 */
    fun lyric(songId: String): JSONObject {
        val url = endpoint("/api/melody/lyric")
            .addQueryParameter("id", songId)
            .build()
        return dataObject(Request.Builder().url(url).get())
    }

    /** `/api/melody/song/batch/player/url/v1`，返回 SongUrlVO 数组。 */
    fun playback(songId: String, level: String): JSONArray {
        val body = JSONObject()
            .put("ids", JSONArray().put(songId.toLongOrNull() ?: throw IOException("非法歌曲 ID：$songId")))
            .put("level", level)
        val request = Request.Builder()
            .url(endpoint("/api/melody/song/batch/player/url/v1").build())
            .post(body.toString().toRequestBody(jsonMediaType))
        return execute(request)?.optJSONArray("data") ?: JSONArray()
    }

    private fun endpoint(path: String) = baseUrl().toHttpUrl().newBuilder().addPathSegments(path.trimStart('/'))

    private fun baseUrl(): String = BuildConfig.MELODY_BASE_URL.trim().trimEnd('/').ifBlank {
        error("MELODY_BASE_URL 未配置")
    }

    private fun dataObject(builder: Request.Builder): JSONObject =
        execute(builder)?.optJSONObject("data") ?: JSONObject()

    /**
     * 发请求并拆信封。401 只重试一次：第二次还失败说明口令本身不对，
     * 再重试就是拿错误口令刷登录接口。
     */
    private fun execute(builder: Request.Builder, allowRelogin: Boolean = true): JSONObject? {
        val request = builder.header("Authorization", "Bearer ${token(forceLogin = false)}").build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 && allowRelogin) {
                LewisMelodySessionStore.clear(context)
                return execute(builder, allowRelogin = false)
            }
            val envelope = runCatching { JSONObject(text) }
                .getOrElse { throw IOException("后端返回的不是 JSON（HTTP ${response.code}）") }
            val code = envelope.optInt("code", -1)
            if (code != 200) {
                val message = envelope.optString("message").ifBlank { "接口返回 $code" }
                throw IOException(message)
            }
            return envelope
        }
    }

    /** 读缓存的令牌；没有就用构建期口令换一枚。 */
    private fun token(forceLogin: Boolean): String {
        if (!forceLogin) {
            val cached = LewisMelodySessionStore.token(context)
            if (cached.isNotBlank()) return cached
        }
        return login()
    }

    private fun login(): String {
        val password = BuildConfig.MELODY_PASSWORD
        if (password.isBlank()) {
            throw IOException("未配置 MELODY_PASSWORD：构建时需要 -PmelodyPassword=<面板密码>")
        }
        val body = JSONObject()
            .put("password", password)
            .put("kind", "api")
            .put("label", "melox-native")
            .toString()
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(endpoint("/api/auth/login").build())
            .post(body)
            .build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val envelope = runCatching { JSONObject(text) }
                .getOrElse { throw IOException("登录返回的不是 JSON（HTTP ${response.code}）") }
            if (envelope.optInt("code") != 200) {
                throw IOException(envelope.optString("message").ifBlank { "登录失败" })
            }
            val issued = envelope.optJSONObject("data")?.optString("token").orEmpty()
            if (issued.isBlank()) throw IOException("登录响应缺少 token")
            LewisMelodySessionStore.save(context, issued)
            return issued
        }
    }
}
