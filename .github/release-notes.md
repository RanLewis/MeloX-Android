# MeloX + Melody 原生音源

在 MeloX 0.7.4 基础上加入 **Melody** 作为内置音源（`MusicSource.Lewis`），走的是原生 provider，不是 `plugin:` 沙箱通道。

## 这个包里有什么

- **搜索**：歌曲 / 歌手 / 专辑 / 歌单四类
- **播放**：音质档位映射到后端 standard / exhigh / lossless / hires / sky / jymaster，渐进式加载
- **歌词**：优先 `yrc` 逐字时间轴，缺则退回 `lrc`，同时合并 `tlyric`（中文翻译）与 `romalrc`（罗马音）
- **账号**：面板口令换 `api` 令牌（`/api/auth/login`），或直接粘贴令牌；令牌存进 EncryptedSharedPreferences，失效自动清除
- **收藏**：红心同步，统一收藏页可读
- **音乐库**：我的歌单（含封面与曲目数）、歌单详情、歌单加/删歌
- **首页**：每日推荐 = 我的歌单；为你推荐 = 最近喜欢的歌曲
- **专辑 / 歌手**：详情页与作品列表

## 安装

1. 下载对应架构的 APK：绝大多数手机用 `arm64-v8a`，老 32 位设备用 `armeabi-v7a`。
2. **如果装过官方 MeloX，先卸载**。这个包沿用官方包名 `com.lladlam.melox.android`，但用的是本仓库的自签名证书，签名不同无法覆盖安装（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。卸载会清掉官方版数据。
3. 安装后进入 设置 → 音源服务 → Melody → 登录。

服务器地址已编进包内（`https://dash.ranlewis.us.ci`），一般只需输入面板口令。

## 已知边界

- 发现页不提供数据：后端没有推荐/榜单/播客接口。
- 首页的「为你推荐」取的是**最近红心**，后端没有播放次数或热度字段，做不到按热度排序。
- 歌单详情一次全量返回，超大歌单首屏会慢一拍。

## 签名

本包由仓库内 `android/signing/melody-release.p12` 签名，证书 `CN=RanLewis Melody`。与官方 MeloX 的证书无关，两者不能互相升级。
