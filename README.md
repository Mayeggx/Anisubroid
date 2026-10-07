# Anisubroid

## 1. 项目介绍和基本结构

Anisubroid 是一个 Kotlin + Jetpack Compose 编写的单模块 Android 应用，用于处理动画视频相关的字幕、种子订阅、图片制卡和 Git 文件同步。

### 1.1 功能

| 页面 | 能力 |
|---|---|
| 字幕匹配 | 选择视频目录，使用 Jimaku 或 EdaTribe 搜索、确认并下载字幕；支持单条/批量匹配、`srt`/`ass`/`ssa` 时间偏移、播放，以及按集数批量硬删除视频与同名字幕。 |
| 种子下载 | 管理视频订阅 URL，下载并打开 `.torrent`；通过远端仓库根目录的 `seed-subscriptions.json` Push / Pull 订阅配置。 |
| 单词摘记 | 从图片目录批量生成英/日文卡片内容，并写入 AnkiDroid 的牌组与笔记。 |
| 远程同步 | 将“设备 + 本地目录”条目同步到 Git 仓库，支持 Pull、Push、清空、删除、图片转 JPG 与压缩、操作日志。 |

### 1.2 技术与版本

- Kotlin、Jetpack Compose、Material 3、AndroidX、Kotlin Coroutines、JGit
- Android Gradle Plugin 9.0.0、Kotlin Compose Plugin 2.2.21、Gradle 9.1.0
- JDK 17
- `compileSdk = 35`、`targetSdk = 34`、`minSdk = 26`
- 当前应用版本：`versionName = "1.1.0"`、`versionCode = 13`

### 1.3 目录

```text
Anisubroid/
├── app/
│   ├── build.gradle.kts                 # Android 应用配置、SDK 范围、版本与依赖
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/prompts/          # 英/日文制卡 Prompt
│       │   └── java/com/mayegg/anisub/
│       │       ├── MainActivity.kt      # 字幕匹配与页面导航入口
│       │       ├── VideoDownloadActivity.kt
│       │       ├── wordnote/            # 单词摘记与 AnkiDroid 集成
│       │       └── remotesync/          # Git 远程同步
│       └── test/                        # 单元测试
├── scripts/                             # Windows PowerShell / Batch 构建与发布脚本
├── mac-scripts/                         # macOS Shell 构建与 Git 发布脚本
├── wsl-scripts/                         # WSL Shell 构建、调试与 Git 发布脚本(复用宿主环境)
├── artwork/                             # 图标预览素材
├── build.gradle.kts                     # 根插件版本
├── settings.gradle.kts                  # 单模块 :app 配置
├── gradlew / gradlew.bat                # Gradle Wrapper
└── gradle.properties                    # Gradle JVM 与代理配置
```

## 2. 构建方法

### 2.1 macOS 构建

macOS 脚本位于 `mac-scripts/`。首次初始化需要 JDK 17、Android SDK Platform 35、Build Tools 36.0.0 与 Platform Tools。

#### 初始化环境

在项目根目录执行：

```sh
./mac-scripts/init-android-env.sh --install-jdk --accept-licenses
```

`--install-jdk` 会通过 Homebrew 安装 Temurin 17，并要求在可交互终端输入 macOS 管理员密码。JDK 已安装时使用：

```sh
./mac-scripts/init-android-env.sh --accept-licenses
```

默认 Android SDK 路径为 `$HOME/Library/Android/sdk`；使用自定义目录时先设置：

```sh
export ANDROID_SDK_ROOT="/path/to/Android/sdk"
```

初始化完成后检查环境：

```sh
./mac-scripts/check-android-env.sh --strict
```

#### 构建和测试

```sh
# 构建 Debug APK
./mac-scripts/build-debug.sh

# 构建失败时持续自动重试，日志输出至 build/reports/macos-build-retries/
./mac-scripts/build-debug-until-success.sh

# 运行 Debug 单元测试
./mac-scripts/test-debug.sh
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

#### 安装到设备

```sh
export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.mayegg.anisub/.MainActivity
```

#### Git 发布

`mac-scripts/release-git.sh` 用于更新版本、构建与归档 APK、创建 Git commit / annotated tag 并推送。正式发布前先预演：

```sh
./mac-scripts/release-git.sh --version-name "1.1.1" --dry-run
```

确认后发布：

```sh
./mac-scripts/release-git.sh --version-name "1.1.1"
```

脚本默认发布到 `main`，会临时 stash 已跟踪本地修改并在结束后恢复。使用 `--skip-push` 仅创建本地 commit/tag；使用 `--create-github-release` 可在已完成 `gh auth login` 后创建或更新 GitHub Release 并上传 APK。

### 2.2 Windows 构建

Windows 脚本位于 `scripts/`，当前默认使用以下固定环境：

```text
JDK 17:       E:\Development\jdk17
Android SDK:  E:\Android\Sdk
Gradle Cache: E:\Gradle\user-home
代理:         http://127.0.0.1:7897
```

#### 准备环境

`use-e-drive-android-env.ps1` 会加载上述环境变量。项目要求 `compileSdk = 35`，因此 SDK 必须包含 `platforms;android-35`；旧的 `install-android-toolchain.ps1` 仅安装 `android-34`，首次使用时需要额外通过 `sdkmanager.bat` 安装 API 35：

```powershell
E:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat --sdk_root=E:\Android\Sdk `
  "platform-tools" "platforms;android-35" "build-tools;36.0.0"
```

#### 构建、测试与 ADB 调试

在项目根目录执行：

```powershell
# 构建 Debug APK
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1

# 运行 Debug 单元测试
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-debug.ps1

# 安装 APK、启动应用并导出 logcat
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-adb-debug.ps1 `
  -InstallApk $true -LaunchApp $true -CaptureLog
```

APK 输出：

```text
app\build\outputs\apk\debug\app-debug.apk
```

Windows 发布使用 `scripts/release.ps1`，支持版本更新、构建、归档 APK、Git commit/tag/push，以及可选 GitHub Release 上传。发布脚本会产生本地 Git 与远端发布副作用，执行前应确认版本号、分支与远端仓库。

### 2.3 WSL 构建（复用 Windows 环境）

WSL 脚本位于 `wsl-scripts/`，用于在 WSL2（已在 Ubuntu-24.04 验证）中直接构建本仓库。设计上尽量复用宿主环境，首次初始化仅需约 250MB 下载：

- 宿主机 SDK（`/mnt/e/Android/Sdk`）的 `platforms/`、`licenses/` 直接共享；Linux 版 `build-tools`、`platform-tools` 二进制由脚本合并进同一目录，与 Windows 的 `.exe` 共存
- Windows 的 Gradle 依赖缓存（`E:\Gradle\user-home/caches/modules-2`）一次性快照拷贝到 `~/.gradle`，不重新下载依赖
- 唯一必须安装的是 Linux JDK（apt 安装 `openjdk-17-jdk-headless`）

详细说明与共享原理见 `wsl-scripts/README.md`。

#### 初始化环境

在 WSL 中进入项目根目录（如 `/mnt/e/Mega/Anisubroid`）执行：

```sh
./wsl-scripts/init-android-env.sh --install-jdk --accept-licenses
```

JDK 已安装时可省略 `--install-jdk`。不想往宿主 SDK 写入文件时，可用 `ANDROID_SDK_ROOT=$HOME/Android/Sdk` 走独立 SDK 完整安装（约 340MB）。

初始化完成后检查环境：

```sh
./wsl-scripts/check-android-env.sh --strict
```

#### 构建、测试与 ADB 调试

```sh
# 构建 Debug APK
./wsl-scripts/build-debug.sh

# 构建失败时持续自动重试,日志输出至 build/reports/wsl-build-retries/
./wsl-scripts/build-debug-until-success.sh

# 运行 Debug 单元测试
./wsl-scripts/test-debug.sh

# 安装 APK、启动应用并导出 logcat(自动选择 Linux adb 或 Windows adb.exe)
./wsl-scripts/start-adb-debug.sh
```

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

USB 设备默认归 Windows 管理：Linux adb 找不到设备时脚本自动回退 `adb.exe` 经 interop 调用；想在 WSL 内直连 USB 需用 `usbipd` 附加设备。

#### Git 发布

`wsl-scripts/release-git.sh` 与 macOS 版本行为一致。先 dry-run 预演：

```sh
./wsl-scripts/release-git.sh --version-name "1.1.1" --dry-run
./wsl-scripts/release-git.sh --version-name "1.1.1"
```

WSL 内 Git 身份与凭据独立于 Windows，首次发布前需配置 `user.name`/`user.email`；HTTPS 推送可复用 Windows 的 Git Credential Manager：

```sh
git config --global credential.helper "/mnt/c/Program Files/Git/mingw64/bin/git-credential-manager.exe"
```

`local.properties` 的 `sdk.dir` 只能保存一个系统路径；WSL 与 Windows 脚本都会在构建时自动重写为本系统的路径，两边轮换构建可自愈。

### 构建代理说明

根目录 `gradle.properties` 固定设置了 Gradle HTTP/HTTPS 代理 `127.0.0.1:7897`。首次下载 Gradle、Android SDK 或 Maven 依赖时，需确保该代理可用；不使用该代理时，应修改或移除对应的 `systemProp.*proxy*` 配置。

在 WSL2 NAT 网络模式下 `127.0.0.1` 指向虚拟机自身，`wsl-scripts/` 会自动探测可用代理（127.0.0.1 → 宿主机网关 → 直连），并写入限域于 Linux + 本项目的 `$GRADLE_USER_HOME/init.d/anisubroid-wsl-proxy.gradle` 覆盖配置，无需改动已提交的 `gradle.properties`。

## 3. 迭代日志

### 2026-05-12

- 字幕匹配条目右上角新增圆形 `×` 删除按钮，并在删除前二次确认。
- 单条删除改为硬删除：删除视频文件，并尝试删除 `sub` 目录下同名字幕文件。
- `批量` 菜单新增批量删除：按字幕匹配规则识别集数，并硬删除集数小于等于输入阈值的条目。
- 新增批量删除集数阈值规则的单元测试。

### 2026-05-11

- 修复批量字幕匹配完成后条目仍显示“匹配中”的状态问题，批量结束时会回写每条视频最终状态。
- 种子下载页新增订阅 URL 的 Push / Pull；仓库根目录使用 `seed-subscriptions.json` 保存 URL，不包含设备信息、本地目录或下载状态，也不会在远程同步页展示。
- 批量匹配或批量偏移期间，顶部“批量中...”按钮可点击并确认中断任务。
- 删除种子订阅前新增二次确认。
