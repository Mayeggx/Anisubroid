package com.mayegg.anisub

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private enum class FolderPickMode {
        OpenAndLoad,
        AddOnly,
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val vm: MainViewModel = viewModel()
            val state by vm.uiState.collectAsStateWithLifecycle()
            var folderPickMode by remember { mutableStateOf(FolderPickMode.OpenAndLoad) }

            val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                uri?.let {
                    persistReadWritePermission(it)
                    when (folderPickMode) {
                        FolderPickMode.OpenAndLoad -> vm.loadFolder(this, it)
                        FolderPickMode.AddOnly -> vm.addFolder(this, it)
                    }
                }
            }

            LaunchedEffect(Unit) {
                vm.restoreLastOpenedFolder(this@MainActivity)
            }

            AppScreen(
                state = state,
                onPickFolder = {
                    folderPickMode = FolderPickMode.OpenAndLoad
                    folderLauncher.launch(null)
                },
                onAddFolder = {
                    folderPickMode = FolderPickMode.AddOnly
                    folderLauncher.launch(null)
                },
                onSelectSavedFolder = { vm.loadFolder(this, it) },
                onRemoveSavedFolder = vm::removeFolder,
                onSelectSubtitleSource = vm::setSubtitleSource,
                onSelectMatchMode = vm::setMatchMode,
                onMatchSubtitle = vm::matchSubtitle,
                onConfirmCandidate = vm::confirmCandidate,
                onDismissCandidates = vm::dismissCandidates,
            )
        }
    }

    private fun persistReadWritePermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
        }
    }
}

data class VideoItem(
    val id: String,
    val uri: Uri,
    val folderUri: Uri,
    val title: String,
    val subtitleStatus: String = "未匹配",
    val matching: Boolean = false,
)

data class MainUiState(
    val loading: Boolean = false,
    val batchRunning: Boolean = false,
    val batchViewerVisible: Boolean = false,
    val batchTotal: Int = 0,
    val batchDone: Int = 0,
    val batchLogs: List<String> = emptyList(),
    val folderLabel: String = "未选择文件夹",
    val folderHistory: List<SavedFolder> = emptyList(),
    val subtitleSource: SubtitleSource = SubtitleSource.Jimaku,
    val matchMode: MatchMode = MatchMode.Candidate,
    val videos: List<VideoItem> = emptyList(),
    val logs: List<MatchLogItem> = emptyList(),
    val pendingCandidates: List<SubtitleCandidate> = emptyList(),
    val pendingVideoId: String? = null,
    val message: String = "请选择视频文件夹。",
)

data class SavedFolder(
    val uri: Uri,
    val label: String,
)

data class MatchLogItem(
    val timestamp: String,
    val source: SubtitleSource,
    val seriesTitle: String,
    val episode: Int?,
    val originalFileName: String,
    val renamedFileName: String,
)

enum class SubtitleSource(
    val label: String,
) {
    Jimaku("Jimaku"),
    Edatribe("EdaTribe"),
}

enum class MatchMode(
    val label: String,
) {
    Auto("自动"),
    Candidate("候选"),
}

class MainViewModel(
    application: android.app.Application,
) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "AnisubroidMain"
        private const val TAG_BATCH = "AnisubroidBatch"
        private const val PREF_NAME = "anisubroid_prefs"
        private const val KEY_FOLDER_LIST = "folder_list"
        private const val KEY_LAST_FOLDER = "last_folder"
        private const val LIST_SEPARATOR = "\n"
    }
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val jimakuMatcher = JimakuSubtitleMatcher(application)
    private val edatribeMatcher = EdatribeSubtitleMatcher(application)
    private val prefs = application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private var batchJob: Job? = null

    init {
        _uiState.update { it.copy(folderHistory = readFolderHistory()) }
    }

    fun setSubtitleSource(source: SubtitleSource) {
        _uiState.update {
            it.copy(
                subtitleSource = source,
                message = "当前字幕来源：${source.label}",
            )
        }
    }

    fun setMatchMode(mode: MatchMode) {
        _uiState.update {
            it.copy(
                matchMode = mode,
                message = "当前匹配模式：${mode.label}",
            )
        }
    }

    fun loadFolder(context: Context, treeUri: Uri) {
        addFolderInternal(context, treeUri)
        _uiState.update {
            it.copy(
                loading = true,
                folderLabel = resolveFolderLabel(context, treeUri),
                message = "正在扫描视频...",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val videoFiles = collectVideoFiles(context, treeUri)
            val videos =
                videoFiles
                    .sortedBy { it.file.name.orEmpty().lowercase(Locale.ROOT) }
                    .let { scanned ->
                        val subtitleBases = collectExistingSubtitleBaseNames(scanned.firstOrNull()?.parent)
                        scanned.map { item ->
                            val title = item.file.name ?: item.file.uri.lastPathSegment.orEmpty()
                            val subtitleStatus =
                                if (subtitleBases.contains(baseName(title).lowercase(Locale.ROOT))) {
                                    "已存在对应字幕"
                                } else {
                                    "未匹配"
                                }
                            VideoItem(
                                id = item.file.uri.toString(),
                                uri = item.file.uri,
                                folderUri = item.parent.uri,
                                title = title,
                                subtitleStatus = subtitleStatus,
                            )
                        }
                    }

            _uiState.update {
                it.copy(
                    loading = false,
                    videos = videos,
                    message = if (videos.isEmpty()) "该文件夹内未找到视频文件。" else "找到 ${videos.size} 个视频文件。",
                )
            }
        }
    }

    fun restoreLastOpenedFolder(context: Context) {
        val raw = prefs.getString(KEY_LAST_FOLDER, null).orEmpty()
        if (raw.isBlank()) return
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        loadFolder(context, uri)
    }

    fun addFolder(context: Context, treeUri: Uri) {
        addFolderInternal(context, treeUri)
        _uiState.update {
            it.copy(message = "已添加文件夹：${resolveFolderLabel(context, treeUri)}")
        }
    }

    fun removeFolder(folderUri: Uri) {
        val updated = readFolderHistory().filterNot { it.uri.toString() == folderUri.toString() }
        saveFolderHistory(updated)
        val current = _uiState.value
        val shouldResetCurrent = current.videos.isNotEmpty() && current.videos.first().folderUri.toString() == folderUri.toString()
        _uiState.update {
            it.copy(
                folderHistory = updated,
                folderLabel = if (shouldResetCurrent) "未选择文件夹" else it.folderLabel,
                videos = if (shouldResetCurrent) emptyList() else it.videos,
                message = if (shouldResetCurrent) "已删除当前文件夹，请重新选择。" else "已删除文件夹。",
            )
        }
        if (prefs.getString(KEY_LAST_FOLDER, null) == folderUri.toString()) {
            prefs.edit().remove(KEY_LAST_FOLDER).apply()
        }
    }

    fun matchSubtitle(videoId: String) {
        if (_uiState.value.batchRunning) return
        val target = _uiState.value.videos.firstOrNull { it.id == videoId } ?: return
        val stateSnapshot = _uiState.value
        val source = stateSnapshot.subtitleSource
        val mode = stateSnapshot.matchMode
        _uiState.update { state ->
            state.copy(
                videos =
                    state.videos.map { item ->
                        if (item.id == videoId) {
                            item.copy(subtitleStatus = "正在匹配并下载字幕...", matching = true)
                        } else {
                            item
                        }
                    },
                message = "正在从 ${source.label} (${mode.label}) 匹配：${target.title}",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val output = runMatchByCurrentMode(videoId = videoId, source = source, mode = mode)

            _uiState.update { state ->
                state.copy(
                    videos =
                        state.videos.map { item ->
                            if (item.id == videoId) item.copy(subtitleStatus = output, matching = false) else item
                        },
                    message = output,
                )
            }
        }
    }

    fun batchMatchAuto(context: Context) {
        val snapshot = _uiState.value
        if (snapshot.batchRunning) return
        if (snapshot.loading) return
        val targets = snapshot.videos.filter { !hasExistingSubtitle(context, it.folderUri, it.title) }
        if (targets.isEmpty()) {
            _uiState.update { it.copy(message = "无需批量处理：当前视频都已有字幕。") }
            Log.i(TAG_BATCH, "[BATCH] skip: no videos without subtitle")
            return
        }

        _uiState.update {
            it.copy(
                batchRunning = true,
                batchViewerVisible = true,
                matchMode = MatchMode.Auto,
                batchTotal = targets.size,
                batchDone = 0,
                batchLogs = listOf("批量开始：共 ${targets.size} 个待处理视频，来源=${snapshot.subtitleSource.label}"),
                message = "批量自动匹配开始，共 ${targets.size} 个待处理视频。已切换到自动模式。",
            )
        }
        Log.i(TAG_BATCH, "[BATCH] start count=${targets.size}, source=${snapshot.subtitleSource.label}")

        batchJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val source = _uiState.value.subtitleSource
                var success = 0
                var failed = 0
                var skipped = 0
                targets.forEachIndexed { index, video ->
                    val seq = index + 1
                    if (hasExistingSubtitle(context, video.folderUri, video.title)) {
                        skipped += 1
                        Log.i(TAG_BATCH, "[BATCH] [$seq/${targets.size}] skip(existing): ${video.title}")
                        appendBatchLog("[$seq/${targets.size}] 跳过（已存在字幕）：${video.title}")
                        _uiState.update { state ->
                            state.copy(
                                videos = state.videos.map { item ->
                                    if (item.id == video.id) item.copy(subtitleStatus = "已存在对应字幕", matching = false) else item
                                },
                                message = "批量跳过（已存在字幕）：${video.title}",
                            )
                        }
                    } else {
                        Log.i(TAG_BATCH, "[BATCH] [$seq/${targets.size}] matching(auto): ${video.title}")
                        appendBatchLog("[$seq/${targets.size}] 开始：${video.title}")
                        _uiState.update { state ->
                            state.copy(
                                videos = state.videos.map { item ->
                                    if (item.id == video.id) item.copy(subtitleStatus = "批量匹配中...", matching = true) else item
                                },
                                message = "批量处理中（$seq/${targets.size}）：${video.title}",
                            )
                        }
                        val output = runMatchByCurrentMode(videoId = video.id, source = source, mode = MatchMode.Auto)
                        if (output.startsWith("匹配成功：")) {
                            success += 1
                            appendBatchLog("[$seq/${targets.size}] 成功：${video.title}")
                        } else {
                            failed += 1
                            appendBatchLog("[$seq/${targets.size}] 失败：${video.title} -> $output")
                        }
                    }
                    _uiState.update { it.copy(batchDone = seq) }
                    if (seq < targets.size) {
                        delay(1000)
                    }
                }

                val summary = "批量完成：成功 $success，失败 $failed，跳过 $skipped。"
                Log.i(TAG_BATCH, "[BATCH] done success=$success failed=$failed skipped=$skipped")
                appendBatchLog(summary)
                _uiState.update { it.copy(batchRunning = false, message = summary) }
            }.onFailure { error ->
                Log.e(TAG_BATCH, "[BATCH] unexpected crash", error)
                appendBatchLog("批量异常终止：${error.message ?: "未知错误"}")
                _uiState.update {
                    it.copy(
                        batchRunning = false,
                        message = "批量异常终止：${error.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    private suspend fun runMatchByCurrentMode(
        videoId: String,
        source: SubtitleSource,
        mode: MatchMode,
    ): String {
        val target = _uiState.value.videos.firstOrNull { it.id == videoId } ?: return "匹配失败：目标视频不存在。"
        val matcher: SubtitleMatcher =
            when (source) {
                SubtitleSource.Jimaku -> jimakuMatcher
                SubtitleSource.Edatribe -> edatribeMatcher
            }
        return runCatching {
            if (mode == MatchMode.Auto) {
                val result = matcher.matchAndDownload(target)
                addLog(source = source, result = result)
                "匹配成功：${result.savedFileName}"
            } else {
                val candidates = matcher.findCandidates(target).take(8)
                if (candidates.isEmpty()) {
                    "匹配失败：未找到候选字幕。"
                } else if (candidates.size == 1) {
                    val result = matcher.downloadCandidate(target, candidates.first())
                    addLog(source = source, result = result)
                    "匹配成功：${result.savedFileName}"
                } else {
                    _uiState.update { state ->
                        state.copy(
                            pendingCandidates = candidates,
                            pendingVideoId = videoId,
                            message = "找到 ${candidates.size} 个候选字幕，请确认。",
                        )
                    }
                    "找到 ${candidates.size} 个候选字幕，请确认。"
                }
            }
        }.getOrElse { error ->
            Log.e(TAG, "Subtitle match failed for: ${target.title}", error)
            "匹配失败：${error.message ?: "未知错误"}"
        }
    }

    fun stopBatch() {
        if (!_uiState.value.batchRunning) {
            _uiState.update { it.copy(batchViewerVisible = false) }
            return
        }
        batchJob?.cancel()
        batchJob = null
        appendBatchLog("批量已手动停止。")
        _uiState.update {
            it.copy(
                batchRunning = false,
                batchViewerVisible = false,
                message = "批量已停止。",
            )
        }
        Log.i(TAG_BATCH, "[BATCH] cancelled by user")
    }

    fun confirmCandidate(index: Int) {
        val state = _uiState.value
        val videoId = state.pendingVideoId ?: return
        val video = state.videos.firstOrNull { it.id == videoId } ?: return
        val candidate = state.pendingCandidates.getOrNull(index) ?: return
        val source = state.subtitleSource

        _uiState.update {
            it.copy(
                pendingCandidates = emptyList(),
                pendingVideoId = null,
                videos =
                    it.videos.map { item ->
                        if (item.id == videoId) item.copy(subtitleStatus = "正在下载候选字幕...", matching = true) else item
                    },
                message = "正在下载已选候选字幕：${candidate.originalSubtitleName}",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val matcher: SubtitleMatcher =
                when (source) {
                    SubtitleSource.Jimaku -> jimakuMatcher
                    SubtitleSource.Edatribe -> edatribeMatcher
                }
            val output =
                runCatching { matcher.downloadCandidate(video, candidate) }
                    .fold(
                        onSuccess = { result ->
                            addLog(source = source, result = result)
                            "匹配成功：${result.savedFileName}"
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Candidate download failed for: ${video.title}", error)
                            "匹配失败：${error.message ?: "未知错误"}"
                        },
                    )

            _uiState.update {
                it.copy(
                    videos =
                        it.videos.map { item ->
                            if (item.id == videoId) item.copy(subtitleStatus = output, matching = false) else item
                        },
                    message = output,
                )
            }
        }
    }

    fun dismissCandidates() {
        _uiState.update {
            it.copy(
                pendingCandidates = emptyList(),
                pendingVideoId = null,
                message = "已取消候选选择。",
            )
        }
    }

    private fun addLog(
        source: SubtitleSource,
        result: SubtitleDownloadResult,
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val renamed = result.savedFileName.substringAfterLast('/')
        val log =
            MatchLogItem(
                timestamp = timestamp,
                source = source,
                seriesTitle = result.seriesTitle,
                episode = result.episode,
                originalFileName = result.originalSubtitleName,
                renamedFileName = renamed,
            )
        _uiState.update { it.copy(logs = listOf(log) + it.logs) }
    }

    private fun addFolderInternal(context: Context, treeUri: Uri) {
        val label = resolveFolderLabel(context, treeUri)
        val old = readFolderHistory().filterNot { it.uri.toString() == treeUri.toString() }
        val updated = listOf(SavedFolder(treeUri, label)) + old
        saveFolderHistory(updated)
        prefs.edit().putString(KEY_LAST_FOLDER, treeUri.toString()).apply()
        _uiState.update { it.copy(folderHistory = updated) }
    }

    private fun resolveFolderLabel(context: Context, uri: Uri): String =
        DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: uri.toString()

    private fun collectVideoFiles(context: Context, treeUri: Uri): List<ScannedVideo> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        return root.listFiles()
            .filter { it.isFile && isVideo(it) }
            .map { ScannedVideo(file = it, parent = root) }
    }

    private fun readFolderHistory(): List<SavedFolder> {
        val rows = prefs.getString(KEY_FOLDER_LIST, "").orEmpty()
            .split(LIST_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return rows.mapNotNull { row ->
            val parts = row.split("|", limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val uri = runCatching { Uri.parse(parts[0]) }.getOrNull() ?: return@mapNotNull null
            SavedFolder(uri = uri, label = parts[1])
        }
    }

    private fun saveFolderHistory(items: List<SavedFolder>) {
        val payload = items.joinToString(LIST_SEPARATOR) { "${it.uri}|${it.label}" }
        prefs.edit().putString(KEY_FOLDER_LIST, payload).apply()
    }

    private data class ScannedVideo(
        val file: DocumentFile,
        val parent: DocumentFile,
    )

    private fun isVideo(file: DocumentFile): Boolean {
        if (file.type?.startsWith("video/") == true) return true
        val lower = file.name?.lowercase(Locale.ROOT) ?: return false
        return lower.endsWith(".mp4") ||
            lower.endsWith(".mkv") ||
            lower.endsWith(".avi") ||
            lower.endsWith(".mov") ||
            lower.endsWith(".wmv") ||
            lower.endsWith(".flv") ||
            lower.endsWith(".webm") ||
            lower.endsWith(".m4v")
    }

    private fun collectExistingSubtitleBaseNames(parent: DocumentFile?): Set<String> {
        if (parent == null || !parent.isDirectory) return emptySet()
        val subDir = parent.findFile("sub") ?: return emptySet()
        if (!subDir.isDirectory) return emptySet()
        return subDir.listFiles()
            .asSequence()
            .filter { it.isFile }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                if (!isSubtitleFileName(name)) return@mapNotNull null
                baseName(name).lowercase(Locale.ROOT)
            }
            .toSet()
    }

    private fun isSubtitleFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".srt") ||
            lower.endsWith(".ass") ||
            lower.endsWith(".ssa") ||
            lower.endsWith(".vtt")
    }

    private fun hasExistingSubtitle(
        context: Context,
        folderUri: Uri,
        videoTitle: String,
    ): Boolean = runCatching { findMatchingSubtitleUri(context, folderUri, videoTitle) != null }.getOrDefault(false)

    private fun appendBatchLog(line: String) {
        _uiState.update {
            val next = (it.batchLogs + line).takeLast(200)
            it.copy(batchLogs = next)
        }
    }

    private fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(
    state: MainUiState,
    onPickFolder: () -> Unit,
    onAddFolder: () -> Unit,
    onSelectSavedFolder: (Uri) -> Unit,
    onRemoveSavedFolder: (Uri) -> Unit,
    onSelectSubtitleSource: (SubtitleSource) -> Unit,
    onSelectMatchMode: (MatchMode) -> Unit,
    onMatchSubtitle: (String) -> Unit,
    onConfirmCandidate: (Int) -> Unit,
    onDismissCandidates: () -> Unit,
) {
    var logVisible by remember { mutableStateOf(false) }
    var folderEditVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.folderLabel,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    TextButton(onClick = { logVisible = true }) {
                        Text("日志")
                    }
                    MatchModeDropdown(
                        selected = state.matchMode,
                        onSelect = onSelectMatchMode,
                    )
                    SubtitleSourceDropdown(
                        selected = state.subtitleSource,
                        onSelect = onSelectSubtitleSource,
                    )
                    FolderDropdown(
                        folders = state.folderHistory,
                        onSelectSavedFolder = onSelectSavedFolder,
                        onEditFolders = { folderEditVisible = true },
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.loading) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.videos, key = { it.id }) { item ->
                    VideoRow(
                        item = item,
                        onMatchSubtitle = { onMatchSubtitle(item.id) },
                        onPlayVideo = { playVideoWithMpv(context, item.uri, item.folderUri, item.title) },
                    )
                }
            }
        }
    }

    if (logVisible) {
        LogDialog(
            logs = state.logs,
            onDismiss = { logVisible = false },
        )
    }

    if (folderEditVisible) {
        FolderEditDialog(
            folders = state.folderHistory,
            onDismiss = { folderEditVisible = false },
            onAddFolder = onAddFolder,
            onRemoveFolder = onRemoveSavedFolder,
        )
    }

    if (state.pendingCandidates.isNotEmpty()) {
        CandidateDialog(
            candidates = state.pendingCandidates,
            onDismiss = onDismissCandidates,
            onSelect = onConfirmCandidate,
        )
    }

}

@Composable
private fun FolderDropdown(
    folders: List<SavedFolder>,
    onSelectSavedFolder: (Uri) -> Unit,
    onEditFolders: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("文件夹")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            folders.forEach { folder ->
                DropdownMenuItem(
                    text = { Text(folder.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onSelectSavedFolder(folder.uri)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("编辑") },
                onClick = {
                    expanded = false
                    onEditFolders()
                },
            )
        }
    }
}

@Composable
private fun SubtitleSourceDropdown(
    selected: SubtitleSource,
    onSelect: (SubtitleSource) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("来源: ${selected.label}")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SubtitleSource.entries.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.label) },
                    onClick = {
                        expanded = false
                        onSelect(source)
                    },
                )
            }
        }
    }
}

@Composable
private fun FolderEditDialog(
    folders: List<SavedFolder>,
    onDismiss: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (Uri) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑文件夹") },
        text = {
            if (folders.isEmpty()) {
                Text("暂无已添加文件夹。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(folders, key = { it.uri.toString() }) { folder ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = folder.label,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { onRemoveFolder(folder.uri) }) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAddFolder) {
                    Text("添加")
                }
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        },
    )
}

@Composable
private fun MatchModeDropdown(
    selected: MatchMode,
    onSelect: (MatchMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("模式: ${selected.label}")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MatchMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun LogDialog(
    logs: List<MatchLogItem>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("匹配日志") },
        text = {
            if (logs.isEmpty()) {
                Text("暂无日志。")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(logs, key = { "${it.timestamp}-${it.renamedFileName}" }) { item ->
                        Text(
                            text = "[${item.timestamp}] ${item.source.label} | ${item.seriesTitle} | ${episodeLabel(item.episode)} | 原文件: ${item.originalFileName} | 改名: ${item.renamedFileName}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        },
    )
}

@Composable
private fun CandidateDialog(
    candidates: List<SubtitleCandidate>,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("候选字幕确认") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(candidates.size, key = { it }) { index ->
                    val item = candidates[index]
                    TextButton(onClick = { onSelect(index) }) {
                        Text(
                            text = "${index + 1}. ${item.originalSubtitleName} (${item.seriesTitle} ${episodeLabel(item.episode)})",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun VideoRow(
    item: VideoItem,
    onMatchSubtitle: () -> Unit,
    onPlayVideo: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = "字幕状态: ${item.subtitleStatus}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onMatchSubtitle,
                    enabled = !item.matching,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (item.matching) "匹配中..." else "匹配并下载字幕")
                }
                Button(
                    onClick = onPlayVideo,
                    enabled = !item.matching,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("播放")
                }
            }
        }
    }
}

private fun playVideoWithMpv(
    context: android.content.Context,
    uri: Uri,
    folderUri: Uri,
    videoTitle: String,
): Boolean {
    val subtitleUri = findMatchingSubtitleUri(context, folderUri, videoTitle)
    val subtitleUris = subtitleUri?.let { arrayOf(it) }
    val openIntent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("is.xyz.mpv")
            if (subtitleUris != null) {
                putExtra("subs", subtitleUris)
                putExtra("subs.enable", subtitleUris)
            }
            clipData =
                buildClipData(
                    context = context,
                    videoUri = uri,
                    subtitleUri = subtitleUri,
                )
        }
    grantReadPermissionForMpv(context, uri, subtitleUri)
    return runCatching {
        context.startActivity(openIntent)
        true
    }.getOrElse {
        if (it !is ActivityNotFoundException) return@getOrElse false
        runCatching {
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(fallback)
            true
        }.getOrDefault(false)
    }
}

private fun findMatchingSubtitleUri(
    context: android.content.Context,
    folderUri: Uri,
    videoTitle: String,
): Uri? {
    val folder = DocumentFile.fromTreeUri(context, folderUri) ?: return null
    val subDir = folder.findFile("sub")?.takeIf { it.isDirectory } ?: return null
    val targetBase = stripExtension(videoTitle).lowercase(Locale.ROOT)
    val candidates =
        subDir
            .listFiles()
            .asSequence()
            .filter { it.isFile }
            .mapNotNull { file ->
                val name = file.name ?: return@mapNotNull null
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (!isSubtitleExtension(ext)) return@mapNotNull null
                if (stripExtension(name).lowercase(Locale.ROOT) != targetBase) return@mapNotNull null
                subtitlePriority(ext) to file.uri
            }
            .sortedBy { it.first }
            .toList()
    return candidates.firstOrNull()?.second
}

private fun buildClipData(
    context: android.content.Context,
    videoUri: Uri,
    subtitleUri: Uri?,
): ClipData {
    return ClipData.newUri(context.contentResolver, "video", videoUri).apply {
        if (subtitleUri != null) {
            addItem(ClipData.Item(subtitleUri))
        }
    }
}

private fun grantReadPermissionForMpv(
    context: android.content.Context,
    videoUri: Uri,
    subtitleUri: Uri?,
) {
    runCatching {
        context.grantUriPermission(
            "is.xyz.mpv",
            videoUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        subtitleUri?.let {
            context.grantUriPermission(
                "is.xyz.mpv",
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }
}

private fun isSubtitleExtension(ext: String): Boolean =
    ext == "srt" || ext == "ass" || ext == "ssa" || ext == "vtt"

private fun subtitlePriority(ext: String): Int =
    when (ext) {
        "srt" -> 0
        "ass" -> 1
        "ssa" -> 2
        "vtt" -> 3
        else -> Int.MAX_VALUE
    }

private fun stripExtension(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) name else name.substring(0, dot)
}

private fun episodeLabel(episode: Int?): String = if (episode == null) "电影" else "第${episode}集"


