# Anisubroid

Anisubroid 是一个 Android 应用，当前通过统一侧栏管理多页面功能。

## 页面结构

1. 字幕匹配（主页面）
2. 种子下载
3. 单词摘记

页面入口为右下角悬浮圆形按钮（“页”）。点击后呼出右侧栏进行页面切换。

## 最近 UI 调整

- 主界面入口按钮改为右下角悬浮圆形按钮。
- 右侧栏新增“关于”入口，集中展示应用信息。
- 关于弹窗新增更新地址：  
  `https://github.com/Mayeggx/Anisubroid/releases`
- 种子下载页面（侧栏内嵌版本）去掉了：
  - “关闭”按钮
  - “视频下载”标题
- 单词摘记页面移除了“更多 -> 关于”入口，关于信息统一放到全局右侧栏。

## 单词摘记说明

- 单词摘记复用了 `Pstankiroid` 逻辑并接入本应用页面。
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
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-adb-debug.ps1 -InstallApk 1 -LaunchApp 1 -CaptureLog
```

日志会输出到：

- `logs/adb-logcat-*.txt`
- `logs/adb-logcat-latest.txt`

## 注意事项

- 若手机端弹出安装确认，请在设备上手动允许；否则可能出现 `INSTALL_FAILED_ABORTED`。
- 若日志出现 `Failed to find provider info for com.ichi2.anki.flashcards`，表示设备侧 AnkiDroid 未安装或 Provider/权限不可用。
