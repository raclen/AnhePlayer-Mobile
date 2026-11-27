# AnhePlayer

<img src="./assets/outertune.webp" height="88" alt="AnhePlayer 应用图标">

AnhePlayer 是基于 OuterTune/InnerTune 二次开发的 Android 音乐播放器。项目保留 Material 3 界面、播放队列和 Media3 播放内核，提供本地媒体库、多平台在线搜索、洛雪自定义音源、账号专区与在线电台。

## 下载与系统要求

当前代码版本：**v2.0.44**（versionCode **244**）。版本变更见 [CHANGELOG.md](./CHANGELOG.md)。

- [下载最新正式版](https://github.com/raclen/AnhePlayer-Mobile/releases/latest)
- [全部版本](https://github.com/raclen/AnhePlayer-Mobile/releases)
- [GitHub Actions](https://github.com/raclen/AnhePlayer-Mobile/actions)

支持 **Android 7.0（API 24）及以上**。正式发布仅提供两份 core APK，请按设备架构安装：

| 架构 | 适用设备 |
| --- | --- |
| `arm64-v8a` | 64 位 ARM 设备 |
| `armeabi-v7a` | 32 位 ARM 设备 |

不提供 universal、x86 或 full 正式包。新版本的 APK 会在发布工作流构建完成后上传到 Release 页面。

## 界面预览

<p align="center">
  <img src="./assets/微信图片_20260810082213_315_45.jpg" width="30%" alt="歌曲与媒体库">
  <img src="./assets/微信图片_20260810082214_316_45.jpg" width="30%" alt="搜索与音源切换">
  <img src="./assets/微信图片_20260810082215_317_45.jpg" width="30%" alt="播放器">
</p>

<p align="center">歌曲与媒体库　·　搜索与音源切换　·　播放器</p>

截图来自旧版本，仅供界面风格参考；当前功能与交互以实际安装版本为准。

## 当前功能

- 本地音乐播放
  - MP3、FLAC、OGG 等常见格式
  - 本地文件夹、媒体库、播放历史
  - 优先读取音频文件内嵌封面；最近播放与歌曲列表共享该回退逻辑
  - 优先读取音频标签中的内嵌歌词，不命中时才按设置使用同目录歌词或在线歌词源
  - 多播放队列、队列持久化和恢复
- 洛雪在线音乐与统一搜索
  - 支持酷狗、网易云、QQ 音乐、酷我、汽水五个平台，切换后查询当前平台，并非同时聚合搜索
  - 同时匹配本地歌名、歌手与专辑，本地结果优先显示且不再截断为 15 首，切换在线平台不影响本地结果
  - 支持歌曲／歌单结果切换；酷狗、网易云、QQ 音乐与酷我支持歌曲和歌单分页，汽水仅支持首屏歌曲搜索
  - 在线歌单支持打开详情、继续加载与播放已加载歌曲；本地歌单搜索包含空歌单和仅含在线歌曲的已保存歌单
  - 先显示文字结果，再异步加载搜索响应自带的封面；仅当前播放歌曲在封面缺失时按需补充
  - 搜索元数据不依赖用户音源脚本；播放与下载使用自行导入的洛雪 JS 音源解析地址
  - 支持本地 `.js` 文件或在线链接导入，不内置默认音源
  - 支持 `128k`、`320k`、`flac`、`flac24bit` 音质设置；播放按歌曲支持音质降级，下载音质独立设置
- 酷狗概念版专区
  - 在 **设置 → 酷狗账号** 通过扫码或短信验证码登录
  - 使用登录账号直连取流，与洛雪酷狗来源隔离，不依赖洛雪脚本，也不降级给洛雪解析
  - 仅支持在线播放，不下载、不导出、不使用永久磁盘播放缓存；可播放范围取决于账号与歌曲权益
- 网易云推荐专区
  - 从抽屉 **在线 → 网易云推荐** 进入，支持扫码、内嵌网页登录和手动 Cookie 登录
  - 提供私人雷达、每日推荐、私人漫游/FM；账号资料与登录状态保存在本机
  - 推荐歌曲进入应用播放队列后仍通过洛雪网易云来源解析，需导入可用音源；登录不会直接解锁会员音频
  - 推荐内容取决于平台返回结果，私人雷达可能使用其他推荐内容兜底
- 在线歌单导入
  - 支持网易云、QQ 音乐、酷狗、酷我，不包含汽水歌单
  - 支持歌单链接、分享文本、短链和手动选择平台后的歌单 ID
  - 解析后预览曲目，可新建本地歌单或加入已有可编辑歌单，不是云端双向同步
- 播放体验
  - Material 3 界面、系统字体和浅色／深色主题
  - 后台播放和媒体通知
  - 歌词显示、LRC/TTML 等歌词格式
  - 音频均衡、速度和音调调整
  - Android Auto 支持
  - 紧凑播放失败弹框：简短提示、音源帮助链接，以及「今日关闭」「知道了」两个按钮
  - 「今日关闭」仅关闭当天的播放失败弹框，重启后当天仍有效，次日自动恢复；不改变重试或自动跳过行为
- 在线电台
  - 基于 Radio Browser 发现电台，默认中国地区与中文广播
  - 电台名称联网搜索，每批 80 个并支持「加载更多」，不再只搜索当前列表
  - 顶部星星打开收藏，列表支持「全部／收藏」切换；可点击电台右侧星星直接收藏或取消
  - 收藏不受地区、语言、码率及首批数量限制，保存电台信息缓存并兼容旧版收藏
  - 支持上一台／下一台与后台收听；从收藏中选台后沿收藏队列切换
  - 在 **设置 → 电台设置** 调整普通电台搜索的码率、国家/地区和广播语言过滤
- 应用内更新
  - 从 **关于** 页检查本仓库正式 Release；自动检查限频，同版本自动提醒不重复弹出
  - 按设备架构下载对应 APK，使用系统 DownloadManager 执行后台下载
  - 安装前校验包名、版本、签名兼容性及可用的 SHA-256，随后交给系统安装器；不进行静默安装

## 导入洛雪音源

搜索可以先使用；通过洛雪来源播放或下载歌曲前，需要导入可用的 JS 音源脚本。网易云推荐歌曲也使用该解析路径，酷狗概念版账号直连播放不需要洛雪脚本。

1. 进入 **设置 → 洛雪音源**。
2. 选择 **导入本地音源脚本**，打开 `.js` 文件；或选择 **从在线链接导入**，填写指向 `.js` 文件的 HTTP(S) 链接。
3. 在同一页面分别选择播放音质和下载音质：128k、320k、FLAC 或 Hi-Res（FLAC 24bit）。

音源脚本只保存在本机应用数据中，大小上限为 2 MiB；应用不内置默认音源。实际支持的平台与音质取决于导入脚本和歌曲，可在 [音源帮助网站](https://anhe-player.loc.cc/sources/) 查找相关说明。

## 搜索、播放与下载

1. 点击搜索后输入框自动获得焦点，可输入歌名、歌手或专辑。搜索页支持在线／本地模式，并记忆模式选择。
2. 在线结果页默认选择酷我，可切换酷狗、网易云、QQ 音乐、酷我或汽水；本地匹配结果始终排在在线结果之前。
3. 使用「歌曲／歌单」切换结果类型；酷狗、网易云、QQ 音乐和酷我每页最多 15 条，可点击「加载更多」，不再只展示第一屏。
4. 点击歌曲后加入播放队列，或打开歌单详情播放已加载歌曲；洛雪来源调用用户脚本解析地址，酷狗概念版使用登录账号独立取流。

歌曲的来源参数会随元数据保存到本地数据库，供队列恢复后继续选择正确的播放路径；恢复队列不等于缓存了在线音频。

在 **设置 → 存储** 选择下载文件夹后，可下载的洛雪歌曲会按独立下载音质保存到该目录：128k/320k 为 `.mp3`，FLAC/Hi-Res 为 `.flac`。酷狗概念版歌曲不支持下载或导出。

播放失败时会显示简洁弹框：**知道了** 只关闭当前提示，**今日关闭** 关闭当天同类弹框，次日恢复；音源问题可点击 **音源帮助** 查看说明。

## 导入在线歌单

从歌单列表菜单，或可编辑歌单的菜单／详情页进入导入入口：

1. 粘贴网易云、QQ 音乐、酷狗或酷我的歌单链接、分享文本或短链。
2. 使用纯歌单 ID 时，先手动选择对应平台。
3. 解析后预览歌曲，选择新建本地歌单或加入已有可编辑歌单。

导入保存的是歌单与歌曲信息，不会直接下载音频，也不会同步回平台账号；实际播放仍需对应的可用来源。

## 架构说明

UI 与播放队列统一使用 `MediaMetadata`。平台信息放入 `source`、`sourceId` 和 `sourceData`，随歌曲持久化，由统一来源边界决定解析与下载能力。

- [LxSearchClient.kt](app/src/main/java/com/dd3boh/outertune/source/lx/LxSearchClient.kt)：五个平台的搜索元数据适配。
- [SourceResolver.kt](app/src/main/java/com/dd3boh/outertune/source/SourceResolver.kt)：按来源选择洛雪或酷狗概念版的播放、歌词解析。
- [SourceCapabilities.kt](app/src/main/java/com/dd3boh/outertune/source/SourceCapabilities.kt)：统一判断来源能力，隔离概念版的下载限制。
- [LxSourceRuntime.kt](app/src/main/java/com/dd3boh/outertune/source/lx/LxSourceRuntime.kt)：QuickJS 运行时、洛雪 API 和 HTTP Bridge。
- [LxSourceInstaller.kt](app/src/main/java/com/dd3boh/outertune/source/lx/LxSourceInstaller.kt)：本地／在线音源脚本导入与链接校验。
- [KugouConcept.kt](app/src/main/java/com/dd3boh/outertune/source/kugou/KugouConcept.kt)：酷狗概念版账号与独立取流。
- [NeteaseApi.kt](app/src/main/java/com/dd3boh/outertune/source/netease/NeteaseApi.kt)：网易云登录、账号资料与推荐内容。
- [PlaylistImporter.kt](app/src/main/java/com/dd3boh/outertune/source/playlist/PlaylistImporter.kt)：在线歌单识别、解析与元数据导入。
- [user-api-preload.js](app/src/main/assets/script/user-api-preload.js)：洛雪移动版 API 兼容桥接，不是默认音源脚本。
- [updates](app/src/main/java/com/dd3boh/outertune/updates/)：正式版本检查、APK 下载与安装前校验。

## 构建

### 环境

- Windows 11（Git Bash / PowerShell）或 Linux
- JDK **21**
- Android SDK Platform **36**、Build Tools **36.0.0**
- Android NDK **29.0.13113456（r29-beta1）**、CMake **3.31.6**：完整 APK 构建所需的固定原生工具链

SDK 组件可通过 Android Studio 的 SDK Manager 或 `sdkmanager` 安装；安装固定 beta NDK 时需启用 beta 渠道。不要随意替换模块声明的工具链版本。

### 本地命令（Git Bash / Linux）

先将以下路径替换为本机的 JDK 与 Android SDK 安装位置：

```bash
export JAVA_HOME="PATH_TO_JDK_21"
```

```bash
export ANDROID_HOME="PATH_TO_ANDROID_SDK"
```

```bash
export ANDROID_SDK_ROOT="$ANDROID_HOME"
```

仅校验 core Kotlin 编译与非 UI 单测，不生成 APK：

```bash
./gradlew :app:compileCoreDebugKotlin :app:testCoreDebugUnitTest --no-daemon "-Dkotlin.incremental=false"
```

构建 core Debug 安装包：

```bash
./gradlew :app:assembleCoreDebug --no-daemon "-Dkotlin.incremental=false"
```

构建结束后停止 Gradle 守护进程：

```bash
./gradlew --stop
```

PowerShell 下设置同名环境变量，并使用 `./gradlew.bat` 执行对应任务。生成的 Debug APK 位于 `app/build/outputs/apk/core/debug/`，包名为 `com.dd3boh.outertune.debug`，可与正式包并存。

### 正式包与 CI

- 正式包使用 `:app:assembleCoreRelease`，输出目录为 `app/build/outputs/apk/core/release/`。
- 本地构建正式包必须提供有效的 `keystore.properties` 与对应签名文件；缺少正式签名配置时构建会失败。升级包必须沿用既有签名，不要提交私钥或密码。
- `main` 的自动 push 只执行 core Kotlin 编译与非 UI 单测，不生成 Debug APK。
- PR、canary 与手动触发保留 Debug 构建流程。
- 推送 `v*` tag 会触发正式构建，并根据 [CHANGELOG.md](./CHANGELOG.md) 的对应版本段落发布 GitHub Release。

## 项目状态

YouTube/InnerTube 登录、浏览、推荐、同步、队列、播放解析及构建模块均已移除，构建不再包含 `:innertube`。新增在线能力通过统一来源边界接入，不将平台专属类型传播到 UI 和播放队列。

在线搜索结果不代表歌曲一定可播放；实际播放仍取决于用户音源、网络、平台版权及账号权益。开发与发布约束见 [PROJECT_RULES.md](./PROJECT_RULES.md)。

## 目录结构

```text
app/src/main/java/com/dd3boh/outertune/
  source/             统一来源解析与能力边界
    lx/               多平台搜索与洛雪脚本运行时
    kugou/            酷狗概念版账号与直连播放
    netease/          网易云账号与推荐接口
    playlist/         在线歌单导入
  playback/           播放服务、队列和 Media3 接入
  radio/              在线电台与独立播放管理
  updates/            正式版本检查、下载与安装校验
  ui/screens/         Compose 页面
  db/                 Room 数据库和队列持久化
app/src/main/assets/script/
  user-api-preload.js  洛雪 API 兼容桥接
media/                本地 Media3 定制模块
```

## 许可证与致谢

本项目沿用原 OuterTune/InnerTune 代码的 GPL-3.0 许可证。具体版权和第三方依赖请查看应用内开源许可页面及各模块源码头部声明。

- [OuterTune](https://github.com/OuterTune/OuterTune)
- [InnerTune](https://github.com/z-huang/InnerTune)
- [洛雪音乐 API 规范](https://github.com/lyswhut/lx-music-desktop)
