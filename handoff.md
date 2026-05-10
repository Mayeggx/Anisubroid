# Anisubroid Handoff（精简版）

## 当前状态
- 分支：`main`
- 页面结构：
  1. 字幕匹配
  2. 种子下载
  3. 单词摘记

## 最近完成
- 右下角悬浮圆形按钮（“页”）呼出右侧栏。
- 右侧栏支持页面切换，并新增全局“关于”。
- 全局关于新增更新地址：`https://github.com/Mayeggx/Anisubroid/releases`
- 种子下载（侧栏内嵌版）移除“关闭”按钮和“视频下载”标题。
- 单词摘记移除“更多 -> 关于”，统一使用全局关于。

## 关键目录
- `app/src/main/java/com/mayegg/anisub/MainActivity.kt`
- `app/src/main/java/com/mayegg/anisub/VideoDownloadActivity.kt`
- `app/src/main/java/com/mayegg/anisub/wordnote/`

## 构建与测试
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-adb-debug.ps1 -InstallApk 1 -LaunchApp 1 -CaptureLog
```

## 已知事项
- 单词摘记依赖 AnkiDroid Provider。
- 若日志出现 `Failed to find provider info for com.ichi2.anki.flashcards`，通常是设备未安装或未授权 AnkiDroid。
