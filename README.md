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
- 顶栏按钮提供配置入口：`Git配置`、`新建条目`、`图片质量`（均为弹窗交互）。
- 顶栏提供 `日志` 按钮，可查看每次 Git 交互明细（pull/fetch/commit/push/重试），并支持清空日志。
- 支持创建条目（设备 + 本地文件夹 B 绑定）。
- 条目列表会显示当前文件数量：本地绑定条目显示本地文件夹统计，远端条目显示仓库子目录统计。
- `Push`：先把文件夹 B 复制到仓库 A 对应子目录，再提交并推送。
- `Push` 复制时会对同名文件去重：目标已存在同名文件时跳过，不覆盖。
- `Push` 状态会显示复制文件数、压缩图片数、跳过重名文件数。
- 复制阶段会先做图片压缩并转为 `JPG`。
- 图片压缩参数可通过顶栏“图片质量”按钮配置：`缩放比 (1-100)%`、`JPG质量 (1-100)`。
- `Pull`：拉取远程仓库条目并刷新本地列表；`Pull` 按钮上方常驻显示当前 `HEAD` 的 commit id + message。
- 当其他设备已清空某个条目后，本设备 `Pull` 会自动识别并同步清空本地绑定文件夹与本地仓库缓存，文件数量会更新为 `0`。
- `清空`：清空条目内容并提交推送（操作前会二次确认）。
- 有本地绑定时：清空“仓库 A 子目录 + 本地绑定文件夹内容”（不删除目录本身）。
- 无本地绑定时：仅清空远端仓库子目录并推送。
- 清空操作会保留条目元数据文件 `entry.json`，不再删除元数据。
- 清空后会记录 `deleted-files.json`（删除标记）；后续 `Push` 会跳过这些已被清空过的同名文件（同相对路径）。
- `摘记`：打开“单词摘记”页面，并直接用该条目绑定文件夹加载图片。
- 按钮显示规则：
- `Push` 仅在“条目设备 = 当前设备”且存在本地绑定文件夹时显示。
- `清空` 与 `删除` 对所有条目都可见（仅在执行任务时禁用）。
- 条目 `删除` 按钮位于卡片右上角，样式为圆形 `×` 按钮。
- 条目操作按钮使用自适应换行布局，手机窄屏下可完整显示按钮。
- Android 10 兼容：远程同步使用对 Android 10 友好的 JGit 版本，并启用 core library desugaring，避免 `readNBytes` 相关崩溃。

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
