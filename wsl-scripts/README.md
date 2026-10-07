# WSL Android 构建

此目录提供 Anisubroid 在 WSL（Ubuntu）下的 Android 环境初始化、Debug 构建、单元测试与真机调试脚本。它与 `scripts/`（Windows PowerShell + `E:\` 路径）和 `mac-scripts/`（macOS）相互独立，专用于在 WSL 内直接构建同一个 `/mnt/e/Mega/Anisubroid` 工作区。

**核心思路是尽量复用宿主机的现有环境**，实测初始化只需下载约 250MB（JDK ~190MB + build-tools ~61MB + platform-tools ~9MB），其余全部共享：

| 组件 | 复用方式 |
|---|---|
| JDK 17 | 不能复用（Windows `java.exe` 无法给 Linux Gradle 用），`--install-jdk` 走 apt 装 OpenJDK |
| `platforms/android-35` | 纯数据文件，直接共享 `/mnt/e/Android/Sdk` |
| `licenses/` | 许可哈希与平台无关，直接共享 |
| `build-tools/36.0.0` | AGP 校验必须有 **Linux 二进制**；脚本从官方 manifest 解析 URL，把 Linux 版二进制**合并进** Windows 的 `build-tools/36.0.0/`（与 `.exe` 共存，互不影响） |
| `platform-tools` | 同上合并出 Linux `adb`；USB 调试也能直接调 `adb.exe` |
| Gradle 依赖缓存 | **一次性快照拷贝** `E:\Gradle\user-home/caches/modules-2` → `~/.gradle`（不能直接共享：kotlin-dsl 等生成缓存按绝对路径哈希，跨系统会损坏；快照拷贝无锁竞争问题） |
| Gradle wrapper dist | 同上随缓存一起拷入 |

## 项目要求

- WSL2（已在 Ubuntu-24.04 验证，root 或 sudo 可用）
- 宿主机已有 Windows 版 Android SDK（默认读 `/mnt/e/Android/Sdk`，可用 `WINDOWS_SDK_ROOT` 覆盖）
- JDK 17（没有则由脚本安装）
- 网络可访问 Gradle、Google Maven 与 Maven Central

## 1. 初始化环境

在项目根目录运行：

```sh
./wsl-scripts/init-android-env.sh --install-jdk --accept-licenses
```

共享 SDK 模式下该命令会：

1. 找不到 JDK 17 时，通过 apt 安装 `openjdk-17-jdk-headless`（root 直接执行，普通用户经 `sudo`）；
2. 自动补齐缺失的 `curl`/`unzip`/`netcat`；
3. 把 Linux 版 `build-tools`、`platform-tools`（以及缺失的 `platforms`）合并进共享 SDK；
4. 把 Windows 的 Gradle 依赖缓存快照拷入 `~/.gradle`（已拷过则跳过）；
5. 创建或更新被 Git 忽略的 `local.properties`，把 `sdk.dir` 指向共享 SDK。

若 JDK 17 已安装，可省略 `--install-jdk`。

**独立 SDK 模式**：不想让 WSL 往宿主 SDK 里写东西时，指定 WSL 本地路径即可走完整 cmdline-tools 安装流程（约 340MB）：

```sh
ANDROID_SDK_ROOT=$HOME/Android/Sdk ./wsl-scripts/init-android-env.sh --install-jdk --accept-licenses
```

### 检查环境

初始化后可运行只读检查；`--strict` 会在任何必需组件缺失时返回非零退出码：

```sh
./wsl-scripts/check-android-env.sh --strict
```

## 2. 构建 Debug APK

```sh
./wsl-scripts/build-debug.sh
```

构建产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Debug 签名来源（覆盖安装的前提）

Debug APK 的签名 keystore 按以下优先级选取：

1. 项目内 `.local-signing/debug.keystore`——`scripts/`、`mac-scripts/`、`wsl-scripts/` 三套脚本检测到它都会自动传入 `-Panisubroid.debug.keystore`，**推荐**；
2. 否则用系统默认 debug keystore：WSL 为 `~/.android/debug.keystore`，Windows 为 `ANDROID_USER_HOME` 下的 `debug.keystore`（本机 `scripts/use-e-drive-android-env.ps1` 把它设为 `E:\Android\UserHome`，**不是** `C:\Users\<你>\.android`）。

这意味着不同设备使用各自的默认 keystore 时，构建出的包签名不同，互相覆盖安装会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。因此本仓库**直接提交了**统一的 keystore：文件位于**仓库根目录下以点开头的隐藏目录** `.local-signing/` 中，完整相对路径为 `.local-signing/debug.keystore`（本机即 `E:\Mega\Anisubroid\.local-signing\debug.keystore`，Windows 资源管理器需显示隐藏文件才可见），各设备克隆即用（debug 证书不含真实身份；公开后他人也能签出同签名的包，仅用于本项目内部开发，风险自担）。

`.gitignore` 中 `*.keystore`、`.local-signing/` 规则仍然生效，仅用于防止其他密钥文件被误提交，不影响本文件。如要更换 keystore，覆盖该文件后重新构建即可，无需卸载手机上的旧包，应用数据保留。

### 持续自动重试构建

网络抖动或依赖下载等临时故障可使用以下脚本自动重试，直到 Debug APK 构建成功；需要停止时按 `Ctrl-C`：

```sh
./wsl-scripts/build-debug-until-success.sh
```

默认失败后等待 15 秒重试。每次尝试的完整输出保存到 `build/reports/wsl-build-retries/`。可通过环境变量调整：

```sh
RETRY_DELAY_SECONDS=30 ./wsl-scripts/build-debug-until-success.sh
BUILD_RETRY_LOG_DIR="/tmp/anisubroid-build-logs" ./wsl-scripts/build-debug-until-success.sh
```

## 3. Git 发布 Debug APK

`release-git.sh` 对应 Windows 的 `scripts/release.ps1` 和 macOS 的 `release-git.sh`。正式发布前先执行 dry-run，它不会修改文件、Git 状态或远端：

```sh
./wsl-scripts/release-git.sh --version-name "1.1.1" --dry-run
```

确认输出无误后执行正式 Git 发布：

```sh
./wsl-scripts/release-git.sh --version-name "1.1.1"
```

行为与 macOS 版本一致：自动 stash 未提交改动 → 递增 `versionCode`、更新 `versionName` → 构建并归档到 `release/` → 提交 `Release v<version>` 与 annotated tag → 推送分支和 tag → 恢复 stash。

常用选项：`--retry-build`、`--skip-push`、`--skip-build`、`--create-github-release`（需要 WSL 内已 `gh auth login`）。

### WSL 内的 Git 身份与凭据

WSL 的 Git 配置与 Windows 相互独立。首次发布前需要在 WSL 里配置：

```sh
git config --global user.name "你的名字"
git config --global user.email "你的邮箱"
# 复用 Windows 的 Git Credential Manager 完成 HTTPS 推送认证:
git config --global credential.helper "/mnt/c/Program Files/Git/mingw64/bin/git-credential-manager.exe"
```

## 4. 运行 Debug 单元测试

```sh
./wsl-scripts/test-debug.sh
```

## 5. 安装到真机（adb）

```sh
./wsl-scripts/start-adb-debug.sh
```

对应 Windows 的 `scripts/start-adb-debug.ps1`：重启 adb server、列出设备、安装 `app-debug.apk`、启动应用并导出一份 logcat 快照到 `logs/`。可选 `--skip-install` / `--skip-launch` / `--skip-log`。

设备发现逻辑：

1. 优先使用 `ADB_BIN` 环境变量指定的 adb；
2. 然后尝试 Linux adb（`$ANDROID_SDK_ROOT/platform-tools/adb`——共享模式下它就是合并进 Windows SDK 的 Linux 二进制）——USB 直连的设备在 WSL2 下需要先通过 `usbipd` 附加才能看到；
3. 若 Linux adb 找不到设备，回退到 Windows 的 `adb.exe`（默认 `/mnt/e/Android/Sdk/platform-tools/adb.exe`，可用 `WINDOWS_PLATFORM_TOOLS` 覆盖），经 WSL interop 调用宿主机的 adb server——手机插在 Windows 上时这条路最省事。

两种 adb 都没有设备时，脚本会提示连接手机或配置 usbipd。

### 首次连接：设备显示 unauthorized

`adb devices` 里设备状态为 `unauthorized` 表示手机还没授权这台电脑的 RSA 指纹：

1. 解锁手机，屏幕会弹出「是否允许 USB 调试？」→ 勾选「一律允许使用这台计算机进行调试」→ 点允许；
2. 没有弹窗时：拔插一次 USB 线，或在开发者选项中关闭再开启「USB 调试」、点「撤销 USB 调试授权」后重连；
3. Linux adb 与 Windows `adb.exe` 各自使用不同的 `adbkey`（`~/.android/adbkey` vs `ANDROID_USER_HOME/adbkey`），第一次分别用两个 adb 时手机需要各授权一次。

### 安装报 INSTALL_FAILED_UPDATE_INCOMPATIBLE

说明手机上的旧包与新包签名不同——典型场景是旧包装自 Windows 脚本（`E:\Android\UserHome\debug.keystore`），新包是 WSL 默认 `~/.android/debug.keystore`。按第 2 节「Debug 签名来源」导入 `.local-signing/debug.keystore` 统一签名后重新构建即可覆盖安装、保留数据；否则只能 `adb uninstall` 后重装（应用数据丢失）。

不确定手机上旧包的签名时，可以拉取下来对比（注意剥掉 `pm path` 输出的 `package:` 前缀）：

```sh
/mnt/e/Android/Sdk/platform-tools/adb.exe shell pm path com.mayegg.anisub
# 输出形如 package:/data/app/~~xxx==/com.mayegg.anisub-yyy==/base.apk
/mnt/e/Android/Sdk/platform-tools/adb.exe pull /data/app/~~xxx==/com.mayegg.anisub-yyy==/base.apk "E:\\Temp\\installed.apk"
$ANDROID_SDK_ROOT/build-tools/36.0.0/apksigner verify --print-certs /mnt/e/Temp/installed.apk
```

注意 `adb.exe` 是 Windows 程序，`pull` 的目标路径要写 Windows 路径；脚本内已用 `wslpath` 自动处理。

## 代理说明（重要）

项目根目录 `gradle.properties` 固定了 `systemProp.*.proxyHost=127.0.0.1` + 端口 `7897`。在 WSL2 NAT 网络模式下，`127.0.0.1` 指向 WSL 虚拟机自身，无法到达 Windows 上的代理。

脚本每次运行都会自动处理：

- 先探测 `127.0.0.1:7897`（对应 mirrored 网络模式）；不通再探测宿主机网关 `7897`（需要代理软件开启"允许局域网连接"）；
- 两者都不可用时判定为直连网络；
- 然后把结果写入 `$GRADLE_USER_HOME/init.d/anisubroid-wsl-proxy.gradle` 这个 Gradle init script（限定只在 Linux + 本项目目录生效）：找到可用代理则把系统属性重定向过去，否则清除代理属性让 Gradle 直连。这样无需改动已提交的 `gradle.properties`。
- 若之后代理变为可达（例如开启 mirrored 模式），脚本会自动删除该覆盖文件。

环境变量 `HTTP_PROXY`/`HTTPS_PROXY` 也会按探测结果设置；想禁止设置环境代理：`USE_LOCAL_PROXY=0`；想强制要求代理：`USE_LOCAL_PROXY=1`。

## `local.properties` 的双系统说明

`sdk.dir` 只能保存一个操作系统的路径。本目录脚本每次构建都会把它重写为 WSL 看到的 SDK 路径（`/mnt/e/Android/Sdk` 或自定义）；Windows 侧脚本（`build-debug.ps1`/`test-debug.ps1`）同样每次重写为 `E:\Android\Sdk`。因此两边轮换构建都能自愈，只有"两边同时构建"会互相覆盖。

## 性能提示

在 `/mnt/e`（drvfs）上构建比 WSL 原生文件系统慢，因为 Gradle 要大量读写小文件。能接受的话直接构建即可；追求速度可把仓库克隆到 `~/`（ext4）再跑同样的脚本。

## 可选配置

```sh
# 使用独立 SDK / Gradle home(见上文独立 SDK 模式)
ANDROID_SDK_ROOT=$HOME/Android/Sdk GRADLE_USER_HOME=$HOME/.gradle ./wsl-scripts/init-android-env.sh --accept-licenses

# 强制完全共享 Windows 的 Gradle home(不推荐:kotlin-dsl 生成缓存按路径哈希,跨系统会损坏;
# 此处仅在你明确知道后果时使用)
GRADLE_USER_HOME=/mnt/e/Gradle/user-home ./wsl-scripts/build-debug.sh

# Google 更新 command-line tools 下载版本时(仅独立 SDK 模式需要):
ANDROID_CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-<version>_latest.zip" \
  ANDROID_SDK_ROOT=$HOME/Android/Sdk ./wsl-scripts/init-android-env.sh --accept-licenses
```

## 不做的事情

- 不修改应用源码和签名配置；
- 不覆盖 Windows SDK 的任何现有文件，只向其中**追加** Linux 二进制；
- `build-debug.sh` 生成的是 Debug 签名的 APK（签名来源见上文），不代表使用正式签名发布的包；
- `release-git.sh` 只在显式传入 `--create-github-release` 时才执行公网发布操作。
