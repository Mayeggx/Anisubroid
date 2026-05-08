# Anisubroid Handoff

## 0. 本轮追加需求（2026-05-09，Jimaku badcase：方括号集数）
- 需求：
  - 修复 badcase：视频文件 `[ASW] Awajima Hyakkei - 02 [1080p HEVC][A19228D7].mkv` 无法匹配 `https://jimaku.cc/entry/11862` 下的 `[KitaujiSub] Awajima Hyakkei [02][WebRip][HEVC_AAC][JPN].ass`。
- 根因：
  - `SubtitleNameHeuristics.extractEpisode` 的通用集数正则不支持 `[02]` 这类“被 `[]` 包裹”的集数格式，导致进入正确条目后无法按集数筛选到候选字幕。
- 已完成：
  - 更新 `extractEpisode:generic` 正则，支持 `[]`/`()` 包裹的集数边界。
  - 同步更新 `cleanBaseTitle:genericNumber` 正则，避免同类格式在清洗时漏处理。
- 本轮验证：
  - `scripts/build-debug.ps1` 执行通过（exit code 0）。
  - `scripts/start-adb-debug.ps1` 执行完成；adb daemon 启动成功。
  - 设备安装阶段仍为 `INSTALL_FAILED_ABORTED: User rejected permissions`（设备侧授权问题，非代码错误）。

## 0. 本轮追加需求（2026-05-09，视频下载界面）
- 需求：
  - 新增“视频下载”界面。
  - 可编辑视频订阅列表（增加/删除），每个订阅对应一个链接（如 nyaa 搜索 URL）。
  - 点击订阅后进入二级界面，解析并展示该订阅对应的多个条目。
  - 每个条目提供“下载种子”与“打开种子”按钮。
  - 每个订阅维护独立文件夹保存种子，并在 UI 显示是否已下载到本地。
- 已完成：
  - 新增 `VideoDownloadActivity`（独立界面）并在主界面顶栏增加“视频下载”入口。
  - 新增订阅管理能力：
    - 支持添加/删除订阅。
    - 使用 `SharedPreferences` 持久化订阅列表。
  - 新增订阅解析能力：
    - 通过网络请求解析订阅 URL 页面中的条目列表（Nyaa 行级 HTML 解析）。
    - 展示条目标题、大小、时间等信息。
  - 新增种子下载/打开能力：
    - “下载种子”会将 `.torrent` 保存到默认目录下的订阅专属子目录。
    - “打开种子”通过 `FileProvider` 以文件方式打开已下载种子。
    - 本地存在状态会回显到订阅页与条目页（已下载/未下载）。
  - 新增系统配置：
    - `AndroidManifest.xml` 注册 `VideoDownloadActivity`。
    - `AndroidManifest.xml` 注册 `FileProvider`。
    - 新增 `res/xml/file_paths.xml`。
- 本轮验证：
  - `scripts/build-debug.ps1` 编译通过（exit code 0）。
  - `scripts/start-adb-debug.ps1` 已执行并正常启动 adb daemon（exit code 0）。
  - 本次 adb 输出未包含安装/拉起明细，需在连接设备后再次确认安装与界面交互。
- 收尾：
  - 已按需求更新 `handoff.md`，并将本轮“视频下载界面”相关代码推送到远端仓库 `origin/main`。

## 0. 本轮追加需求（2026-05-09，批量功能回退 + 构建与 adb 状态）
- 需求变化：
  - 原“批量添加”功能在实机上触发闪退，最终决定移除该按钮。
- 已完成：
  - 已从主界面移除“批量添加（自动）”按钮。
  - 已移除批量确认弹窗与批量进度窗口入口，避免再触发该路径。
  - 保留其余单个视频“匹配并下载字幕”流程不变。
- 问题排查记录：
  - 多次复现中未稳定捕获到 `com.mayegg.anisub` 的 `FATAL EXCEPTION` 栈；日志中大量异常来自其他应用进程。
  - 批量相关逻辑曾做过“自动模式 + 1s 间隔 + 可关闭停止”实现，但按需求已回退 UI 入口。
- 构建与调试现状：
  - 构建阶段曾因 `app/build/outputs/apk/debug/app-debug.apk` 被外部进程占用导致失败，已通过停止相关进程并删除被锁 APK 解除。
  - `assembleDebug` 已可再次成功执行。
  - adb 连接正常（设备可见）。
  - 安装阶段最近一次失败原因为手机端拒绝安装权限：`INSTALL_FAILED_ABORTED: User rejected permissions`（非代码问题）。

## 0. 本轮追加需求（2026-05-08，文件夹下拉 + 历史管理 + 上次目录恢复）
- 需求：
  - 顶栏“打开”按钮改为“文件夹”按钮。
  - 点击“文件夹”后显示下拉栏：历史添加的文件夹名称；点击名称可加载该文件夹。
  - 下拉栏底部增加“编辑”，进入文件夹编辑界面，可添加/删除文件夹。
  - 应用启动后默认加载上次关闭时加载的文件夹。
- 已完成：
  - 顶栏按钮已改为“文件夹”，并接入下拉菜单。
  - 历史文件夹持久化：
    - 使用 `SharedPreferences` 保存历史目录（URI + 展示名称）。
    - 打开目录时自动写入历史并置顶去重。
  - 新增“编辑文件夹”弹窗：
    - 支持查看历史列表。
    - 支持“添加”新目录。
    - 支持“删除”历史目录（若删除当前目录，会重置当前列表并提示重新选择）。
  - 启动恢复逻辑：
    - 启动时读取上次目录 URI，自动加载视频列表。
- 本轮验证：
  - `scripts/build-debug.ps1` 编译通过（exit code 0）。
  - `scripts/start-adb-debug.ps1` 执行通过并启动 adb daemon（exit code 0）。
  - 本次 adb 脚本输出未包含安装/拉起明细（仅看到 daemon 启动日志）。

## 0. 本轮追加需求（2026-03-16，播放按钮 + 状态初始化）
- 需求：
  - “打开文件夹”按钮更名为“打开”。
  - 在“匹配并下载字幕”旁新增“播放”按钮，用 mpv 打开当前视频。
  - 取消视频缩略图显示。
  - 打开文件夹初始化视频列表时，检查当前目录 sub/ 下是否已有同名字幕；若存在，状态显示“已存在对应字幕”。
- 已完成：
  - 顶栏按钮文案已从“打开文件夹”改为“打开”。
  - 每个视频条目新增“播放”按钮：
    - 优先使用 is.xyz.mpv 包名启动播放。
    - 若 mpv 不可用，自动回退到系统 ACTION_VIEW 播放。
  - 删除视频缩略图加载与渲染逻辑（移除 MediaMetadataRetriever 预览流程）。
  - 视频扫描后会读取当前目录 sub/，按同名基名匹配 srt/ass/ssa/vtt，命中则初始化状态为“已存在对应字幕”。
- 本轮验证：
  - scripts/build-debug.ps1 执行成功（exit code 0）。
  - adb install -r app/build/outputs/apk/debug/app-debug.apk 返回 Success。
  - adb shell am start -n com.mayegg.anisub/.MainActivity 已成功投递到前台实例。
  - adb shell pm list packages 确认 mpv 包名：is.xyz.mpv。
## 0. 本轮追加需求（2026-03-16，来源切换 + EdaTribe）
- 需求：
  - 新增 cc.edatribe.com 字幕 matcher（目录型站点，先匹配 TV series 目录，再按集数匹配字幕）。
  - 主界面右上角增加“字幕来源”下拉选择，可在不同来源间切换。
- 已完成：
  - 新增 EdatribeSubtitleMatcher：
    - 从 https://cc.edatribe.com/files/TV%20series/ 获取剧集目录 JSON。
    - 根据视频名启发式匹配最佳剧集目录。
    - 进入剧集目录后，按集数筛选字幕文件（支持 srt/ass/ssa/vtt 优先级）。
    - 下载落盘仍沿用 视频目录/sub/视频同名.字幕后缀 的策略。
  - 新增来源抽象：
    - 新增 SubtitleMatcher 接口。
    - JimakuSubtitleMatcher / EdatribeSubtitleMatcher 统一实现该接口。
  - 主界面改造：
    - 顶栏右上角新增来源下拉（Jimaku / EdaTribe）。
    - ViewModel 根据来源动态分发 matcher，并更新状态提示文案。
  - 复用解析能力：
    - 将 ParsedVideo 与 SubtitleNameHeuristics 调整为同包可复用。
- 本轮验证：
  - scripts/build-debug.ps1 编译通过。
  - scripts/start-adb-debug.ps1 执行通过（adb daemon 启动成功，应用 Activity 被带到前台）。
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


## 8. 2026-03-16：.srt.txt 修复 + 匹配日志
- 修复：为字幕创建文件时按后缀使用更准确的 MIME（application/x-subrip、text/x-ssa、text/vtt），避免高版本 Android 将 .srt 自动转成 .srt.txt。
- 新增：右上角“日志”入口，展示每次匹配记录。
- 日志字段：来源、匹配剧集名、集数、原文件名、改名后文件名、时间戳。
- 验证：scripts/build-debug.ps1 通过；scripts/start-adb-debug.ps1 已执行。
## 9. 2026-03-16：列表范围 + 候选确认模式 + 顶栏模式切换
- 打开文件夹后仅扫描当前目录下的视频文件，不再递归展示子目录视频。
- 新增匹配模式：自动 / 候选（顶栏按钮切换）。
- 候选模式行为：
  - 若匹配到多个候选字幕，弹出“候选字幕确认”对话框供用户二次选择。
  - 若仅一个候选，则直接下载。
- 顶栏调整：去掉左上角应用名，仅显示当前目录名称；保留来源选择和日志入口。

## 10. 2026-05-09：顶栏文案缩短 + 间距压缩
- 需求：
  - 顶栏占据位置偏大，要求五个按钮名改为：`种子 / 日志 / 模式 / 来源 / 文件`。
  - 进一步减少顶栏横向与纵向间距。
- 已完成：
  - 顶栏按钮文案已更新为：`种子 / 日志 / 模式 / 来源 / 文件`。
  - `AppTopBar` 容器内边距由 `12x8dp` 调整为 `8x4dp`，标题与按钮区间距由 `8dp` 调整为 `4dp`。
  - `FlowRow` 按钮间距由 `6x4dp` 调整为 `4x2dp`。
  - 各按钮 `TextButton` 统一为更紧凑的 `contentPadding(8x4dp)` 与 `minHeight 32dp`。
  - 移除顶栏底部 `HorizontalDivider`，进一步减少垂直占用。
- 本轮验证（串行）：
  - `scripts/build-debug.ps1` 编译通过（exit code 0）。
  - `scripts/start-adb-debug.ps1` 执行完成，adb daemon 启动成功（exit code 0）。
