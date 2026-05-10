# Anisubroid

Anisubroid 是一个 Android 应用，目前包含三个页面：

1. 字幕匹配
2. 种子下载
3. 单词摘记

右下角 `☰` 按钮可打开侧边栏进行页面切换。

## 字幕匹配（当前能力）

- 单条操作按钮：`匹配`、`偏移`、`播放`
- 偏移功能：支持输入毫秒偏移量（可为负数），直接修改对应字幕文件中的时间字段
- 支持字幕格式：
  - `srt`（`00:00:37,328 --> 00:00:38,872`）
  - `ass/ssa`（`Dialogue: 0,0:00:22.23,0:00:24.85,...`）
- 批量按钮为下拉菜单：
  - `批量匹配`
  - `批量偏移`（处理当前目录 `sub` 下全部 `srt/ass/ssa` 字幕）

## 构建与 ADB 联调（scripts）

### 1) 构建 Debug APK

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1
```

### 2) ADB 安装 + 启动 + 导出日志

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-adb-debug.ps1 -InstallApk:$true -LaunchApp:$true -CaptureLog
```

日志输出位置：

- `logs/adb-logcat-*.txt`
- `logs/adb-logcat-latest.txt`

## 其他脚本

- `scripts/release.ps1`：发布流程脚本
- `scripts/install-android-toolchain.ps1`：Android 工具链安装脚本
- `scripts/use-e-drive-android-env.ps1`：统一环境变量脚本

