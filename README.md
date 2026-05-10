# Anisubroid

Anisubroid 是一个 Android 应用，目前通过统一侧栏组织多页面能力。

## 页面结构

1. 字幕匹配（主页面）
2. 种子下载
3. 单词摘记

入口为右下角悬浮按钮（`☰`），点击后打开右侧栏进行页面切换。

## 字幕匹配（近期变更）

- 顶栏新增 `批量` 按钮。
- 批量模式行为：
  - 自动切换到 `自动` 匹配模式；
  - 只处理“尚无对应字幕”的视频；
  - 对已有字幕条目自动跳过；
  - 逐条写入批量日志并更新状态。
- 批量启动前的“目标扫描”在后台线程执行，避免主线程阻塞导致 ANR。

## 单词摘记说明

- 单词摘记复用了 `Pstankiroid` 的逻辑并接入本应用页面。
- 代码目录：`app/src/main/java/com/mayegg/anisub/wordnote`
- Prompt 资源：`app/src/main/assets/prompts`
- 依赖 AnkiDroid Provider：`com.ichi2.anki.flashcards`

## 构建与调试（scripts）

### 1) 构建 Debug APK

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1
```

### 2) ADB 安装 + 启动 + 导出日志

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-adb-debug.ps1 -InstallApk $true -LaunchApp $true -CaptureLog
```

日志输出到：

- `logs/adb-logcat-*.txt`
- `logs/adb-logcat-latest.txt`

## 注意事项

- 如果手机端弹出安装确认，请在设备上手动允许，否则可能出现 `INSTALL_FAILED_ABORTED`。
- 若日志出现 `Failed to find provider info for com.ichi2.anki.flashcards`，表示设备侧 AnkiDroid 未安装或 Provider/权限不可用。
- 更新地址：`https://github.com/Mayeggx/Anisubroid/releases`
