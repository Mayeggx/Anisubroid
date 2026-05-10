# Anisubroid

Anisubroid 是一个 Android 应用，当前包含三个可切换页面：

1. 字幕匹配（主页面）
2. 种子下载
3. 单词摘记

页面切换方式：点击主界面右上角“页面”按钮，呼出右侧栏后进行切换。

## 页面说明

### 1) 字幕匹配
- 扫描本地视频文件夹
- 匹配并下载字幕（支持来源切换与匹配模式切换）
- 支持日志查看、文件夹管理、候选字幕确认

### 2) 种子下载
- 原先通过顶部“种子”按钮进入
- 现已改为通过右侧栏进入（顶部“种子”按钮已移除）

### 3) 单词摘记
- 复用了 `Pstankiroid` 的完整核心逻辑（先原样迁移，再接入页面）
- 代码位置：`app/src/main/java/com/mayegg/anisub/wordnote`
- 资源位置：`app/src/main/assets/prompts`
- 依赖 AnkiDroid Provider：`com.ichi2.anki.flashcards`

## 运行与调试（scripts）

### 构建 Debug APK
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1
```

### ADB 安装、启动、导出日志
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-adb-debug.ps1 -InstallApk 1 -LaunchApp 1 -CaptureLog
```

说明：
- 日志默认导出到 `logs/adb-logcat-*.txt`，并同步为 `logs/adb-logcat-latest.txt`。
- 若手机端弹出安装确认，请在设备上手动允许，否则会出现 `INSTALL_FAILED_ABORTED`。

## 备注

- 单词摘记页面需要设备已安装 AnkiDroid，并授予数据库读写权限：
  `com.ichi2.anki.permission.READ_WRITE_DATABASE`
- 若日志出现 `Failed to find provider info for com.ichi2.anki.flashcards`，说明 AnkiDroid Provider 当前不可用。
