package com.lladlam.melox.core.provider.melody

import android.content.Context
import com.lladlam.melox.BuildConfig
import com.lladlam.melox.core.account.SecureSessionPreferences

/**
 * Lewis Melody 的服务器地址与 API 令牌。
 *
 * 存的是「地址 + 令牌」而不是口令：口令只在登录那一次用过，落盘的东西等价于
 * 一枚可随时在面板里吊销的 api 令牌。地址跟着令牌一起存，是因为自建后端没有
 * 固定域名，换地址时清掉令牌比留着旧令牌打错服务器更安全。
 */
object LewisMelodySessionStore {
    private const val FileName = "melox_lewis_melody_session"
    private const val KeyBaseUrl = "baseUrl"
    private const val KeyToken = "token"
    private const val KeyAccountId = "accountId"
    private const val KeyDisplayName = "displayName"
    private const val KeyAvatarUrl = "avatarUrl"

    data class Session(
        val baseUrl: String = "",
        val token: String = "",
        val accountId: String = "",
        val displayName: String = "",
        val avatarUrl: String = "",
    ) {
        val isLoggedIn: Boolean get() = token.isNotBlank()

        /** 会话里没填地址时退回构建期默认值；两边都空就是真的没配。 */
        val effectiveBaseUrl: String
            get() = baseUrl.trim().trimEnd('/').ifBlank {
                BuildConfig.MELODY_BASE_URL.trim().trimEnd('/')
            }
    }

    fun read(context: Context): Session {
        val preferences = preferences(context)
        return Session(
            baseUrl = preferences.getString(KeyBaseUrl, "").orEmpty(),
            token = preferences.getString(KeyToken, "").orEmpty(),
            accountId = preferences.getString(KeyAccountId, "").orEmpty(),
            displayName = preferences.getString(KeyDisplayName, "").orEmpty(),
            avatarUrl = preferences.getString(KeyAvatarUrl, "").orEmpty(),
        )
    }

    fun save(context: Context, session: Session) {
        require(session.token.isNotBlank()) { "melody token must not be blank" }
        require(session.token.none { it.isWhitespace() }) { "melody token must not contain whitespace" }
        preferences(context).edit()
            .putString(KeyBaseUrl, session.baseUrl.trim().trimEnd('/'))
            .putString(KeyToken, session.token)
            .putString(KeyAccountId, session.accountId)
            .putString(KeyDisplayName, session.displayName)
            .putString(KeyAvatarUrl, session.avatarUrl)
            .apply()
    }

    /** 登录页在换服务器时先落地址：令牌随后才拿到，不能等 save 一起写。 */
    fun updateBaseUrl(context: Context, baseUrl: String) {
        preferences(context).edit().putString(KeyBaseUrl, baseUrl.trim().trimEnd('/')).apply()
    }

    fun clear(context: Context) {
        preferences(context).edit().clear().apply()
    }

    private fun preferences(context: Context) =
        SecureSessionPreferences.openEncrypted(context.applicationContext, FileName)
}
