# AnhePlayer

<img src="./assets/outertune.webp" height="88" alt="AnhePlayer 应用图标">

AnhePlayer 是一个 Android 本地音乐播放器和在线音乐播放器二次开发项目。项目保留原有 Material 3 界面、播放队列和 Media3 播放内核，在线搜索与播放链路接入酷我搜索和洛雪自定义音源。

## 系统要求

- Android 7.0（API 24）及以上
- 正式包分为两份，按手机架构安装，没有同时包含两种架构的 universal 包：
  - `arm64-v8a`：64 位手机
  - `armeabi-v7a`：32 位手机

## 界面预览

<p align="center">
  <img src="./assets/微信图片_20260810082213_315_45.jpg" width="30%" alt="歌曲与媒体库">
  <img src="./assets/微信图片_20260810082214_316_45.jpg" width="30%" alt="搜索与音源切换">
  <img src="https://img.meituan.net/content/b7b82e114a8b0c4da6b939030a9f3933729054.jpg" width="30%" alt="播放器">
</p>

<p align="center">歌曲与媒体库　·　搜索与音源切换　·　播放器</p>

## 当前功能

- 本地音乐播放
  - MP3、FLAC、OGG 等常见格式
  - 本地文件夹、媒体库、播放历史
  - 优先读取音频文件内嵌封面；最近播放与歌曲列表共享该回退逻辑
  - 优先读取音频标签中的内嵌歌词，不命中时才按设置使用同目录歌词或在线歌词源
  - 多播放队列、队列持久化和恢复
- 洛雪在线音乐
  - 支持在酷狗、网易云、QQ 音乐、酷我之间切换搜索歌曲、歌手、专辑和时长
  - 先显示文本结果，再异步加载搜索响应自带的封面 URL
  - 仅当前播放歌曲在封面缺失时按需补充封面
  - 使用洛雪 JS 音源解析实际播放地址
  - 在线播放和下载统一通过洛雪音源解析
  - 音源脚本需由用户自行导入，不再内置默认音源
  - 支持从本地 `.js` 文件或在线链接导入洛雪音源
  - 支持 `128k`、`320k`、`flac`、`flac24bit` 音质选择
  - 自动修正酷我封面 CDN 地址，兼容 Android HTTPS 网络环境
- 播放体验
  - Material 3 界面和深色模式
  - 后台播放和媒体通知
  - 歌词显示、LRC/TTML 等歌词格式
  - 音频均衡、速度和音调调整
  - Android Auto 支持
- 在线电台
  - 基于 Radio Browser 发现全球电台，默认收听中国地区与中文广播
  - 极简 RoamDial 风格交互：呼吸状态灯、复古水平调频标尺、随波漫游（Roam）
  - 码率、国家/地区、广播语言过滤全部抽离至「设置 → 电台设置」，保持主收听界面清爽纯粹
  - 独立流媒体播放引擎，支持后台播放、音频焦点和电台收藏

## 导入洛雪音源

首次使用在线搜索前，需要先导入一个洛雪 JS 音源脚本：

1. 打开应用搜索页。
2. 进入 **设置 → 洛雪音源**。
3. 选择一种导入方式：
   - **导入本地音源脚本**：选择一个 `.js` 音源文件。
   - **从在线链接导入**：粘贴指向 `.js` 音源脚本的 `http/https` 链接并确认。
4. 在同一页面分别选择播放音质与下载音质：低品质（128k）、标准（320k）、高品质（FLAC）或 Hi-Res（FLAC 24bit）。

音源脚本只保存在本机应用数据中；未导入音源时，在线播放会提示前往设置导入。

## 搜索与播放

1. 在搜索页输入歌曲名称，例如 `一路生花`。
2. 在线结果默认来自酷我；搜索结果页可直接切换酷狗、网易云、QQ 音乐或酷我。
3. 搜索阶段立即显示文字结果；可见歌曲的封面由 Coil 异步加载，不等待逐首封面请求。
4. 点击歌曲后，应用将歌曲元数据转换为统一的 `MediaMetadata`，仅在当前歌曲封面缺失时补充封面。
5. 播放服务根据歌曲的 `source` 信息调用洛雪 JS 音源解析播放地址；下载任务复用同一解析链路。

在线源参数会随歌曲保存到本地数据库，队列重启恢复后仍然可以使用洛雪解析链路。

在 **设置 → 存储** 选择下载文件夹后，下载任务会直接将歌曲写入该目录；128k/320k 文件保存为 `.mp3`，FLAC/Hi-Res 文件保存为 `.flac`。下载音质独立于播放音质，不再使用或迁移应用内部的 Media3 下载缓存。

## 架构说明

```text
搜索页
  -> KuwoSearchClient
  -> MediaMetadata(source/sourceId/sourceData)
  -> MusicService / DownloadUtil
  -> LxSourceRuntime (QuickJS)
  -> 洛雪 JS 音源
  -> Media3 播放器
```

- `source/lx/LxSearchClient.kt`：酷狗、网易云、QQ 音乐、酷我的搜索与封面元数据适配。
- `source/lx/LxSourceRuntime.kt`：QuickJS 运行时、洛雪 API 和 HTTP Bridge。
- `source/lx/LxSourceInstaller.kt`：本地/在线音源脚本导入与链接校验。
- `app/src/main/assets/script/user-api-preload.js`：洛雪移动版 API 兼容层。
- `MediaMetadata.sourceData`：保存音源所需的歌曲参数，不让平台字段渗透到 UI 和队列代码。

## 构建

### 环境

- Windows 11 或 Linux
- JDK 21
- Android SDK 36
- Android NDK/CMake（本地 TagLib 模块需要）

### PowerShell

```powershell
$env:JAVA_HOME = "PATH_TO_JDK_21"
$env:ANDROID_SDK_ROOT = "PATH_TO_ANDROID_SDK"
./gradlew.bat :app:assembleCoreDebug --no-configuration-cache
```

生成的 APK 位于：

```text
app/build/outputs/apk/core/debug/
```

Debug 包名为 `com.dd3boh.outertune.debug`，应用名称为 **AnhePlayer**，可与其他安装包并存。

## 项目状态

当前版本只保留本地音乐与洛雪在线音源两类功能。YouTube/InnerTube 登录、浏览、推荐、同步、队列、播放解析及构建模块均已移除；在线搜索、播放和下载不再依赖原平台接口。

## 目录结构

```text
app/src/main/java/com/dd3boh/outertune/
  source/lx/          洛雪搜索与脚本运行时
  playback/           播放服务、队列和 Media3 接入
  ui/screens/         Compose 页面
  db/                 Room 数据库和队列持久化
app/src/main/assets/script/
  user-api-preload.js
media/                本地 Media3 定制模块
```

## 许可证与致谢

本项目沿用原 OuterTune/InnerTune 代码的 GPL-3.0 许可证。具体版权和第三方依赖请查看应用内开源许可页面及各模块源码头部声明。

- [OuterTune](https://github.com/OuterTune/OuterTune)
- [InnerTune](https://github.com/z-huang/InnerTune)
- [洛雪音乐 API 规范](https://github.com/lyswhut/lx-music-desktop)
