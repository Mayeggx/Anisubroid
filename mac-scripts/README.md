# macOS Android 构建

此目录提供 Anisubroid 在 macOS 下的 Android 环境初始化、Debug 构建和单元测试脚本。它与 `scripts/` 下绑定 Windows PowerShell 和 `E:\` 路径的脚本相互独立。

## 项目要求

- macOS
- JDK 17
- Android SDK Platform 35（项目 `compileSdk = 35`）
- Android Build Tools 36.0.0（AGP 9.0.0 在首次构建时实际要求）
- Android Platform Tools（`adb`）
- 网络可访问 Gradle、Google Maven 与 Maven Central

默认 SDK 路径：`$HOME/Library/Android/sdk`。如你的 SDK 位于其他目录，在运行前设置：

```sh
export ANDROID_SDK_ROOT="/你的/Android/sdk"
```

## 1. 初始化环境

在项目根目录运行：

```sh
./mac-scripts/init-android-env.sh --install-jdk --accept-licenses
```

该命令会：

1. 在找不到 JDK 17 时，通过 Homebrew 安装 Eclipse Temurin 17；
2. 下载 Android command-line tools；
3. 安装 `platform-tools`、`platforms;android-35` 与 `build-tools;36.0.0`；
4. 创建或更新被 Git 忽略的 `local.properties`，让 Gradle 使用本机 Android SDK。

`--install-jdk` 会调用 macOS Installer 并要求输入管理员密码，因此必须在可交互的本机终端运行；后台或非交互会话会在该步骤失败。若 JDK 已安装，直接使用不带该参数的命令。

若 JDK 17 已安装，可省略 `--install-jdk`：

```sh
./mac-scripts/init-android-env.sh --accept-licenses
```

如果 Android Studio 已经安装了 SDK，脚本会复用 SDK，不重复下载 command-line tools。

### 检查环境

初始化后可运行只读检查；`--strict` 会在任何必需组件、代理或 JDK 缺失时返回非零退出码：

```sh
./mac-scripts/check-android-env.sh --strict
```

## 2. 构建 Debug APK

```sh
./mac-scripts/build-debug.sh
```

构建产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 持续自动重试构建

网络抖动或依赖下载等临时故障可使用以下脚本自动重试，直到 Debug APK 构建成功；需要停止时按 `Ctrl-C`：

```sh
./mac-scripts/build-debug-until-success.sh
```

默认失败后等待 15 秒重试。每次尝试的完整输出会保存到 `build/reports/macos-build-retries/`，便于定位持续失败的根因。可通过环境变量调整间隔或日志目录：

```sh
RETRY_DELAY_SECONDS=30 ./mac-scripts/build-debug-until-success.sh
BUILD_RETRY_LOG_DIR="/tmp/anisubroid-build-logs" ./mac-scripts/build-debug-until-success.sh
```

代码编译错误、缺少 JDK 或 SDK 等确定性问题也会持续失败；此时应先修复根因，再继续重试。

## 3. Git 发布 Debug APK

`release-git.sh` 对应 Windows 的 `scripts/release.ps1`，可更新 Android 版本、构建并归档 APK、创建 Git commit 与 annotated tag，最后推送分支和 tag。正式发布前先执行 dry-run，它不会修改文件、Git 状态或远端：

```sh
./mac-scripts/release-git.sh --version-name "1.1.1" --dry-run
```

确认输出无误后执行正式 Git 发布：

```sh
./mac-scripts/release-git.sh --version-name "1.1.1"
```

默认行为：

1. 若存在已跟踪的本地改动，临时 stash，流程结束后恢复；未跟踪文件不会被 stash；
2. 递增 `app/build.gradle.kts` 中的 `versionCode`，更新 `versionName`；
3. 执行 `assembleDebug`，归档到 `release/Anisubroid-v<version>.apk`；
4. 提交 `Release v<version>`，创建同名 annotated tag，并推送 `main` 分支和 tag 到 `origin`。

常用选项：

```sh
# 构建失败时持续重试；仅适用于默认 assembleDebug 任务
./mac-scripts/release-git.sh --version-name "1.1.1" --retry-build

# 仅在本地创建 commit 和 tag，不推送
./mac-scripts/release-git.sh --version-name "1.1.1" --skip-push

# 复用已存在 APK，不重新构建
./mac-scripts/release-git.sh --version-name "1.1.1" --skip-build
```

如果 tag 已存在，脚本会进入重新发布模式：不再修改版本号、创建 commit 或重复打 tag，但会重新构建并归档 APK。为避免 tag 与源码版本不一致，`build.gradle.kts` 的版本必须与请求的版本完全相同。

### 可选：GitHub Release

默认只发布 Git commit/tag，不创建 GitHub Release。需要同步上传 APK 时，先完成 `gh auth login`，然后显式传入：

```sh
./mac-scripts/release-git.sh \
  --version-name "1.1.1" \
  --create-github-release \
  --repo "Mayeggx/Anisubroid"
```

若同 tag 的 GitHub Release 已存在，脚本使用 `gh release upload --clobber` 替换同名 APK；否则新建 Release。该选项会执行公网发布操作。

## 4. 运行 Debug 单元测试

```sh
./mac-scripts/test-debug.sh
```

## 5. 安装到真机或模拟器

```sh
export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.mayegg.anisub/.MainActivity
```

## 代理说明

项目根目录的 `gradle.properties` 已固定 Gradle 代理为 `127.0.0.1:7897`。本机该端口可用时，脚本会自动将 `HTTP_PROXY` 和 `HTTPS_PROXY` 设置为同一地址，以供 SDK 下载使用。

- 未使用本地代理且需要正常下载依赖：请将 `gradle.properties` 中的四项 `systemProp.*proxy*` 配置改为实际代理，或移除它们。
- 想强制给 SDK 下载使用本地代理：`USE_LOCAL_PROXY=1 ./mac-scripts/init-android-env.sh --accept-licenses`
- 想禁止脚本设置环境代理：`USE_LOCAL_PROXY=0 ./mac-scripts/init-android-env.sh --accept-licenses`

`USE_LOCAL_PROXY=0` 不会覆盖 Gradle 文件中已写死的代理配置。

## 可选配置

若 Google 更新 command-line tools 下载版本，可在运行时提供替代地址：

```sh
ANDROID_CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-mac-<version>_latest.zip" \
  ./mac-scripts/init-android-env.sh --accept-licenses
```

## 不做的事情

- 不修改应用源码和签名配置；
- 不执行发布、Git 提交、打 tag 或上传 Release；
- `build-debug.sh` 仅生成未签名的 Debug APK，不代表可发布的正式包。
