package com.lladlam.melox.ui.account

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.lladlam.melox.core.network.MeloXHttpClient
import com.lladlam.melox.core.provider.melody.LewisMelodyClient
import com.lladlam.melox.core.provider.melody.LewisMelodySessionStore
import com.lladlam.melox.ui.glass.MeloXGlassButton
import com.lladlam.melox.ui.glass.MeloXGlassButtonStyle
import com.lladlam.melox.ui.glass.MeloXGlassTextField
import com.lladlam.melox.ui.glass.MeloXSystemColors
import com.lladlam.melox.ui.legal.MeloXLegalLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 自建 melody 后端的登录页。
 *
 * 两条入口都留着：
 *   - **面板口令**：普通用户只需要输密码，`kind=api` 的令牌由 App 自己换、自己存；
 *   - **直接粘令牌**：已经在面板里发过 `api` 令牌（或不想再存口令）的人可以只贴令牌。
 *
 * 口令只发一次给 `/api/auth/login`，不落盘；落盘的是换回来的令牌，且进加密偏好文件。
 */
@Composable
fun LewisMelodyLoginScreen(onDismiss: () -> Unit, onLoggedIn: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val client = remember { LewisMelodyClient(context, MeloXHttpClient.shared) }
    val existing = remember { LewisMelodySessionStore.read(context) }
    var baseUrl by remember { mutableStateOf(existing.baseUrl) }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onDismiss)

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MeloXGlassButton(onClick = onDismiss, style = MeloXGlassButtonStyle.Plain) {
                Text("取消", color = MeloXSystemColors.Red)
            }
            Text("登录 Melody", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier)
        }

        Text(
            "用面板口令登录。App 会换一枚只属于本机的 API 令牌，口令本身不会保存。",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .62f),
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )

        MeloXGlassTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("服务器地址，例如 https://dash.example.com", maxLines = 1) },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )

        MeloXGlassTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("面板登录密码") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

        MeloXGlassButton(
            onClick = {
                if (busy) return@MeloXGlassButton
                busy = true
                error = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            client.login(password.trim(), baseUrl)
                        }
                    }.onSuccess {
                        busy = false
                        onLoggedIn()
                    }.onFailure { failure ->
                        busy = false
                        error = failure.message ?: "登录失败"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && baseUrl.isNotBlank() && password.isNotBlank(),
            style = MeloXGlassButtonStyle.BorderedProminent,
        ) { Text(if (busy) "正在登录…" else "登录") }

        Text(
            "已经有 API 令牌？直接粘贴即可，不需要密码。",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .62f),
            fontSize = 13.sp,
        )
        MeloXGlassTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("粘贴 API 令牌（可选）") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = VisualTransformation.None,
        )
        MeloXGlassButton(
            onClick = {
                if (busy) return@MeloXGlassButton
                busy = true
                error = null
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            LewisMelodySessionStore.updateBaseUrl(context, baseUrl)
                            LewisMelodySessionStore.save(
                                context,
                                existing.copy(baseUrl = baseUrl.trim().trimEnd('/'), token = token.trim()),
                            )
                            // 立刻验证这枚令牌：存下一个用不了的令牌，只会在搜索时变成一个莫名的失败。
                            client.refreshAccount() ?: error("令牌无效或服务器地址不可达")
                        }
                    }.onSuccess {
                        busy = false
                        onLoggedIn()
                    }.onFailure { failure ->
                        busy = false
                        LewisMelodySessionStore.clear(context)
                        error = failure.message ?: "令牌无效"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && baseUrl.isNotBlank() && token.isNotBlank(),
            style = MeloXGlassButtonStyle.Plain,
        ) { Text("用令牌登录") }

        Spacer(Modifier.weight(1f))
        MeloXLegalLinks()
    }
}
