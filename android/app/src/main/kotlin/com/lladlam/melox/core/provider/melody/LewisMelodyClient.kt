package com.lladlam.melox.core.provider.melody

import android.content.Context
import com.lladlam.melox.core.music.model.MusicAccountSummary
import com.lladlam.melox.core.music.model.MusicSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** 令牌缺失或已失效。调用方据此把账号页切回「未登录」，而不是当成网络错误重试。 */
internal class LewisMelodyAuthException(message: String) : IOException(message)

/**
 * 后端 melody 接口（搜索 / 歌曲 / 歌词 / 歌单 / 专辑 / 歌手 / 账号）与 /api/auth/login 的最小同步客户端。
 *
 * 三条约定决定这里的写法：
 *   1. 信封是 `{ code, data, message }`，且 `code` 就是 HTTP 状态码（后端 result.ts），
 *      所以两个都要看：代理吞掉状态码时信封里的 code 仍然是权威值。
 *   2. 除登录外全部在鉴权面上，必须带 `Authorization: Bearer <token>`。
 *   3. 令牌只在登录那一次拿到，之后所有请求都从加密偏好里读 —— 客户端的构造函数
 *      不接受令牌参数，避免有人把令牌顺手写进日志或 Intent。
 */
internal class LewisMelodyClient(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ---- 搜索 ----------------------------------------------------------------

    /** 后端 type：1=歌曲、10=歌手、100=专辑、1000=歌单（以 search.repo.ts 的 switch 为准）。 */
    fun search(keyword: String, type: Int, limit: Int, offset: Int): JSONObject {
        val url = endpoint("api/melody/search")
            .addQueryParameter("keyword", keyword)
            .addQueryParameter("type", type.toString())
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("total", "true")
            .build()
        return dataObject(Request.Builder().url(url).get())
    }

    // ---- 歌曲 ----------------------------------------------------------------

    fun songDetail(ids: List<Long>): JSONArray {
        if (ids.isEmpty()) return JSONArray()
        val body = JSONObject().put("songIds", JSONArray(ids)).toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(endpoint("api/melody/song/batch/detail").build())
            .post(body)
            .build()
        return dataArray(request)
    }

    fun playbackUrls(songId: Long, level: String): JSONArray {
        val body = JSONObject()
            .put("ids", JSONArray().put(songId))
            .put("level", level)
            .toString()
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(endpoint("api/melody/song/batch/player/url/v1").build())
            .post(body)
            .build()
        return dataArray(request)
    }

    fun artistSongs(artistId: Long, limit: Int, offset: Int): JSONObject {
        val body = JSONObject()
            .put("artistId", artistId)
            .put("limit", limit)
            .put("offset", offset)
            .toString()
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(endpoint("api/melody/song/v1/artist").build())
            .post(body)
            .build()
        return dataObject(request)
    }

    // ---- 收藏 ----------------------------------------------------------------

    /** `POST /api/melody/song/like` 返回的是歌曲 ID 数组，不是歌曲对象。 */
    fun likedIds(): List<Long> = dataArray(
        Request.Builder().url(endpoint("api/melody/song/like").build()).post(emptyBody()).build(),
    ).longs()

    fun setLiked(songId: Long, liked: Boolean) {
        val body = JSONObject()
            .put("trackId", songId)
            .put("like", liked)
            .toString()
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(endpoint("api/melody/song/radio/like").build())
            .post(body)
            .build()
        execute(request, authorized = true)
    }

    // ---- 歌单 ----------------------------------------------------------------

    fun userPlaylists(): JSONArray =
        dataArray(Request.Builder().url(endpoint("api/melody/playlist/user").build()).get())

    fun playlistDetail(playlistId: Long): JSONObject {
        val url = endpoint("api/melody/playlist/detail")
            .addQueryParameter("playlistId", playlistId.toString())
            .build()
        return dataObject(Request.Builder().url(url).get())
    }

    fun addTracksToPlaylist(playlistId: Long, trackIds: List<Long>) {
        val body = JSONObject()
            .put("op", "add")
            .put("pid", playlistId)
            .put("trackIds", JSONArray(trackIds))
            .toString()
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(endpoint("api/melody/playlist/manipulate/tracks").build())
            .post(body)
            .build()
        execute(request, authorized = true)
    }

    // ---- 专辑 / 歌手 ----------------------------------------------------------

    fun albumDetail(albumId: Long): JSONObject =
        dataObject(Request.Builder().url(endpoint("api/melody/album/$albumId").build()).get())

    fun artistAlbums(artistId: Long, limit: Int, offset: Int): JSONObject {
        val url = endpoint("api/melody/album/artist/$artistId")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        return dataObject(Request.Builder().url(url).get())
    }

    fun artistDetail(artistId: Long): JSONObject =
        dataObject(Request.Builder().url(endpoint("api/melody/artist/v1/$artistId").build()).get())

    // ---- 歌词 ----------------------------------------------------------------

    fun lyric(songId: Long): JSONObject {
        val url = endpoint("api/melody/lyric")
            .addQueryParameter("id", songId.toString())
            .build()
        return dataObject(Request.Builder().url(url).get())
    }

    // ---- 账号 / 登录 ---------------------------------------------------------

    /** `GET /api/melody/auth/nuser/account/get` → `{ account, profile }`。 */
    fun account(): JSONObject = dataObject(
        Request.Builder().url(endpoint("api/melody/auth/nuser/account/get").build()).get(),
    )

    fun accountSummary(): MusicAccountSummary? {
        val data = account()
        val profile = data.obj("profile") ?: return null
        val id = profile.str("userId").ifBlank { data.obj("account")?.str("id").orEmpty() }
        return MusicAccountSummary(
            MusicSource.Lewis,
            id.ifBlank { "melody" },
            profile.str("nickname").ifBlank { "Melody" },
            profile.str("avatarUrl").takeIf { it.isNotBlank() },
        )
    }

    /**
     * 用面板口令换一枚 `kind=api` 的令牌。
     *
     * 口令本身不落盘：后端只在签发那一刻比对，之后库里的行只存令牌摘要。
     * 换到令牌后立刻读一次账号资料，把昵称/头像写进会话 —— 账号页要靠它显示。
     */
    fun login(password: String, baseUrl: String): LewisMelodySessionStore.Session {
        val normalized = normalizeBaseUrl(baseUrl)
        val body = JSONObject()
            .put("password", password)
            .put("kind", "api")
            .put("label", "melox-android")
            .toString()
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(normalized.toHttpUrl().newBuilder().addPathSegments("api/auth/login").build())
            .post(body)
            .build()
        val issued = try {
            execute(request, authorized = false).obj("data")?.str("token").orEmpty()
        } catch (auth: LewisMelodyAuthException) {
            // 登录失败不能把一次 401 解释成「会话过期」，也不该清掉与本次无关的旧会话。
            throw IOException(auth.message ?: "口令不正确")
        }
        if (issued.isBlank()) throw IOException("登录响应里没有令牌")
        val provisional = LewisMelodySessionStore.Session(baseUrl = normalized, token = issued)
        LewisMelodySessionStore.save(context, provisional)
        val account = runCatching { accountSummary() }.getOrNull() ?: return provisional
        val session = provisional.copy(
            accountId = account.id,
            displayName = account.displayName,
            avatarUrl = account.avatarUrl.orEmpty(),
        )
        LewisMelodySessionStore.save(context, session)
        return session
    }

    // ---- 传输层 --------------------------------------------------------------

    /**
     * 直接用一枚已经签发好的 api 令牌登录（面板里复制出来的那种）。
     *
     * 存下来之前必须验证一次：否则一个拼错的地址或过期令牌会静静躺在加密偏好里，
     * 直到用户下一次搜索才变成一条看不懂的失败。验证不过就清干净。
     */
    fun useToken(token: String, baseUrl: String): LewisMelodySessionStore.Session {
        val normalized = normalizeBaseUrl(baseUrl)
        val trimmed = token.trim()
        if (trimmed.isEmpty()) throw IOException("请粘贴 API 令牌")
        val session = LewisMelodySessionStore.Session(baseUrl = normalized, token = trimmed)
        LewisMelodySessionStore.save(context, session)
        val account = try {
            accountSummary()
        } catch (error: Throwable) {
            LewisMelodySessionStore.clear(context)
            throw if (error is LewisMelodyAuthException) IOException("令牌无效或已失效") else error
        }
        if (account == null) {
            LewisMelodySessionStore.clear(context)
            throw IOException("令牌无效：服务器没有返回账号信息")
        }
        val verified = session.copy(
            accountId = account.id,
            displayName = account.displayName,
            avatarUrl = account.avatarUrl.orEmpty(),
        )
        LewisMelodySessionStore.save(context, verified)
        return verified
    }

    private fun emptyBody() = "".toRequestBody(jsonMediaType)

    private fun endpoint(path: String) = baseUrl().toHttpUrl().newBuilder().addPathSegments(path)

    private fun baseUrl(): String {
        val configured = LewisMelodySessionStore.read(context).effectiveBaseUrl
        if (configured.isBlank()) error("没有配置 Melody 服务器地址，请先在设置里登录")
        // 明文 http 会被系统拦截，早失败比让用户看到一条莫名的 IO 异常好。
        val parsed = configured.toHttpUrlOrNull() ?: error("Melody 服务器地址不合法：$configured")
        require(parsed.scheme == "https") { "Melody 服务器地址必须是 https" }
        return configured
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) throw IOException("请填写服务器地址")
        val parsed = trimmed.toHttpUrlOrNull() ?: throw IOException("服务器地址不合法：$raw")
        if (parsed.scheme != "https") throw IOException("服务器地址必须是 https")
        return trimmed
    }

    private fun dataObject(request: Request): JSONObject =
        execute(request, authorized = true).obj("data") ?: JSONObject()

    private fun dataArray(request: Request): JSONArray =
        execute(request, authorized = true).optJSONArray("data") ?: JSONArray()

    private fun execute(request: Request, authorized: Boolean): JSONObject {
        val builder = request.newBuilder()
        if (authorized) {
            val token = LewisMelodySessionStore.read(context).token
            if (token.isBlank()) throw LewisMelodyAuthException("尚未登录 Melody")
            builder.header("Authorization", "Bearer $token")
        }
        httpClient.newCall(builder.build()).execute().use { response ->
            val text = response.body.string()
            val envelope = runCatching { JSONObject(text) }.getOrElse {
                if (response.code == 401 && authorized) {
                    LewisMelodySessionStore.clear(context)
                    throw LewisMelodyAuthException("登录状态已失效，请重新登录")
                }
                throw IOException("服务器返回的不是 JSON（HTTP ${response.code}）")
            }
            val code = envelope.optInt("code", response.code)
            if (response.code == 401 || code == 401) {
                // 登录请求本身的 401 是「口令不对」，不能顺手把别的会话清掉。
                if (authorized) LewisMelodySessionStore.clear(context)
                throw LewisMelodyAuthException(envelope.str("message").ifBlank { "登录状态已失效，请重新登录" })
            }
            if (code != 200 || !response.isSuccessful) {
                val message = envelope.str("message").ifBlank { "请求失败（$code）" }
                throw IOException(message)
            }
            return envelope
        }
    }
}
