# Anisubroid Handoff

## 0. 本轮追加需求（2026-03-16）
- 需求：字幕下载到“当前视频目录/sub/”下，字幕名与视频同名；后缀允许 srt / ass（沿用实际下载字幕后缀）。
- 已完成：
  - 下载落盘路径改为 视频目录/sub/（自动创建 sub 目录）。
  - 下载文件名改为 视频文件名(去扩展名).字幕后缀，例如：
    - 视频：[shincaps] xxx.mkv
    - 字幕：sub/[shincaps] xxx.srt 或 sub/[shincaps] xxx.ass
  - 仍保留“同名覆盖”行为：若 sub/ 下同名字幕已存在，会先删除再写入。
- 本轮验证：
  - scripts/build-debug.ps1 编译通过。
  - scripts/start-adb-debug.ps1 已执行（启动了 adb daemon），但本次输出未包含设备安装/拉起成功明细。

## 1. 本次目标
- 按 `plan.md` 完成字幕匹配下载逻辑：`jimaku.cc` 检索 -> 进入 `entry` -> 按集数筛选 -> 下载到视频目录。

## 2. 本次已完成
- 新增 `JimakuSubtitleMatcher`：
  - 抓取 `https://jimaku.cc/` 首页并解析 `/entry/{id}` 列表。
  - 对视频名进行启发式解析（剧名、季数、集数）。
  - 使用 token overlap + 包含关系 + season bonus 进行 entry 模糊匹配。
  - 进入 `https://jimaku.cc/entry/{id}` 解析下载链接。
  - 基于“集数相同”筛选字幕文件，再按扩展名/标题相关度排序选最佳。
  - 下载文件并写入视频所在目录（SAF `DocumentFile`）。
- 更新 `MainActivity` / `MainViewModel`：
  - `OpenDocumentTree` 权限升级为读写持久权限。
  - `VideoItem` 增加 `folderUri` 与 `matching` 状态。
  - “匹配并下载字幕”按钮接入真实逻辑，支持匹配中状态与失败原因回显。
  - 扫描视频时记录父目录 URI，用于字幕落盘到同目录。
- 增加调试日志与问题修复：
  - 新增 `JimakuMatcher` / `SubtitleHeuristics` / `AnisubroidMain` 详细日志，打印匹配阶段、异常栈、正则标签与 pattern。
  - 定位并修复 `PatternSyntaxException`：`cleanBaseTitle:brace` 的正则由 `\{[^}]*}` 改为 `\{[^}]*\}`。
  - 修复后用户实机复测通过，字幕匹配成功。

## 3. 关键改动文件
- `app/src/main/java/com/mayegg/anisub/MainActivity.kt`
- `app/src/main/java/com/mayegg/anisub/JimakuSubtitleMatcher.kt`

## 4. 匹配策略说明
- 视频名常见处理：
  - 去除发布组标签、编码信息、分辨率等噪声词。
  - 识别 `SxxEyy`、`Episode xx`、`- 08`、`第08话` 等集数格式。
  - 识别 `S2` / `2nd Season` / `Season 2` 等季信息，生成多组查询词。
- 站点匹配流程：
  - 先匹配 entry（剧集层）。
  - 进入 entry 后以“集数匹配”为主，不强依赖具体文件命名。

## 5. 已验证
- 执行 `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-debug.ps1` 成功。
- 生成 APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 使用 `E:\Android\Sdk\platform-tools\adb.exe` 完成实机安装与启动：
  - `adb install -r ...` 返回 `Success`
  - `adb shell am start -n com.mayegg.anisub/.MainActivity` 已成功拉起
  - 设备在线：`SM-T830`
- 用户反馈：最新版本“匹配成功”。

## 6. 当前限制
- 目前使用启发式匹配，极端命名（如纯日文别名、剧场版/OVA混排）仍可能错配。
- 下载后采用同名覆盖策略（同目录同名文件会先删除再写入）。
- 未补充自动化测试（单元测试/仪器测试）。

## 7. 建议下一步
1. 增加“选择字幕偏好”选项（优先 `srt` / 仅文本字幕 / 避免压缩包）。
2. 增加“候选列表确认”模式（匹配结果给用户二次确认）。
3. 为文件名解析和评分器补充单元测试样例集。

