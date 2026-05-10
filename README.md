# Anisubroid

Anisubroid 是一个 Android 应用，当前包含四个页面：

1. 字幕匹配
2. 种子下载
3. 单词摘记
4. 远程同步

右下角 `☰` 按钮可打开侧边栏切换页面。

## 字幕匹配（当前能力）

- 单条操作按钮：`匹配`、`偏移`、`播放`
- 偏移功能：支持输入毫秒偏移量（可为负数），直接修改字幕时间字段
- 支持字幕格式：
  - `srt`（`00:00:37,328 --> 00:00:38,872`）
  - `ass/ssa`（`Dialogue: 0,0:00:22.23,0:00:24.85,...`）
- 批量按钮为下拉菜单：
  - `批量匹配`
  - `批量偏移`（处理当前目录 `sub` 下全部 `srt/ass/ssa` 字幕）

## 远程同步（Git）

- 在应用内新增“远程同步”页面。
- 使用本地仓库 A（应用私有目录）绑定远程 Git 仓库。
- 支持创建条目（设备 + 本地文件夹 B 绑定）。
- `Push`：将文件夹 B 内容复制到仓库 A 的条目子目录后提交并推送。
- `Pull`：拉取远程仓库条目并刷新本地列表。
- `删除`：删除条目目录并提交推送。

## Scripts（构建 / 测试 / ADB）

### 1) 构建 Debug APK

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1
```

### 2) 运行 Debug 单元测试

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\test-debug.ps1
```

### 3) ADB 安装 + 启动 + 导出日志

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-adb-debug.ps1 -InstallApk $true -LaunchApp $true -CaptureLog
```

日志输出位置：

- `logs/adb-logcat-*.txt`
- `logs/adb-logcat-latest.txt`

## 其他脚本

- `scripts/release.ps1`：发布流程脚本
- `scripts/install-android-toolchain.ps1`：Android 工具链安装脚本
- `scripts/use-e-drive-android-env.ps1`：统一环境变量脚本
