package com.lladlam.melox.core.provider.melody

import android.content.Context
import com.lladlam.melox.core.account.SecureSessionPreferences

/**
 * Lewis Melody 的 API 令牌（后端 `/api/auth/login` 用 `kind=api` 换来的那枚）。
 *
 * 与其它音源一样单独一个加密偏好文件：这个令牌等价于后台全部权限，
 * 不能和别的音源共用一个 Cookie 罐子或偏好文件。
 */
object LewisMelodySessionStore {
    private const val FileName = "melox_lewis_melody_session"
    private const val KeyToken = "token"

    fun token(context: Context): String =
        preferences(context).getString(KeyToken, "").orEmpty()

    fun save(context: Context, token: String) {
        require(token.isNotBlank()) { "melody token must not be blank" }
        require(token.none { it.isWhitespace() }) { "melody token must not contain whitespace" }
        preferences(context).edit().putString(KeyToken, token).apply()
    }

    fun clear(context: Context) {
        preferences(context).edit().remove(KeyToken).apply()
    }

    private fun preferences(context: Context) =
        SecureSessionPreferences.openEncrypted(context.applicationContext, FileName)
}
