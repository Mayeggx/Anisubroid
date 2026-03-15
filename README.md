# Anisubroid

Anisubroid 是一个 Android 应用，用于扫描本地视频并自动从 `jimaku.cc` 匹配和下载字幕。

## 字幕匹配基本逻辑

1. 解析本地视频文件名
- 提取集数（支持 `S02E07`、`Episode 07`、`- 07`、`第07话` 等格式）。
- 尝试提取季信息（如 `S2`、`Season 2`、`2nd Season`）。
- 清洗标题噪声（发布组、编码信息、分辨率等）得到基础剧名。

2. 在 Jimaku 匹配剧集条目（entry）
- 读取 `https://jimaku.cc/` 首页条目列表（`/entry/{id}`）。
- 对每个 entry 标题进行打分：
  - token overlap（词项重合度）
  - 包含关系加分
  - 季信息加分
- 选取得分最高且超过阈值的 entry。

3. 在 entry 内匹配具体字幕文件
- 打开 `https://jimaku.cc/entry/{id}`，解析下载文件列表。
- 以“集数一致”为硬条件筛选候选文件。
- 再按文件类型和标题相关度排序（默认偏好 `srt`、`ass`）。

4. 下载并保存
- 使用 SAF（`DocumentFile`）将字幕保存到视频同目录。
- 若同名文件存在，当前策略为覆盖。

## 当前状态

- 已接入真实匹配下载流程（非占位逻辑）。
- 已支持实机 ADB 安装与验证。
- 已修复一次正则兼容导致的匹配崩溃问题（`PatternSyntaxException`）。

## 已知限制

- 仍为启发式匹配，极端命名场景可能误匹配。
- 暂未提供候选手动确认与偏好配置 UI。
- 暂未补充自动化测试。

## 运行与调试

- 构建 Debug：
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1
```

- 安装并启动：
```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-adb-debug.ps1 -InstallApk -LaunchApp
```
