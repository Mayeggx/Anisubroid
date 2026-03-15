package com.mayegg.anisub

import android.content.Intent
import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val vm: MainViewModel = viewModel()
            val state by vm.uiState.collectAsStateWithLifecycle()

            val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                uri?.let {
                    persistReadWritePermission(it)
                    vm.loadFolder(this, it)
                }
            }

            AppScreen(
                state = state,
                onPickFolder = { folderLauncher.launch(null) },
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
    val folderLabel: String = "未选择文件夹",
    val subtitleSource: SubtitleSource = SubtitleSource.Jimaku,
    val matchMode: MatchMode = MatchMode.Auto,
    val videos: List<VideoItem> = emptyList(),
    val logs: List<MatchLogItem> = emptyList(),
    val pendingCandidates: List<SubtitleCandidate> = emptyList(),
    val pendingVideoId: String? = null,
    val message: String = "请选择视频文件夹。",
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
    }
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val jimakuMatcher = JimakuSubtitleMatcher(application)
    private val edatribeMatcher = EdatribeSubtitleMatcher(application)

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

    fun loadFolder(activity: ComponentActivity, treeUri: Uri) {
        _uiState.update {
            it.copy(
                loading = true,
                folderLabel = resolveFolderLabel(activity, treeUri),
                message = "正在扫描视频...",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val videoFiles = collectVideoFiles(activity, treeUri)
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

    fun matchSubtitle(videoId: String) {
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
            val matcher: SubtitleMatcher =
                when (source) {
                    SubtitleSource.Jimaku -> jimakuMatcher
                    SubtitleSource.Edatribe -> edatribeMatcher
                }
            val output = runCatching {
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

    private fun resolveFolderLabel(activity: ComponentActivity, uri: Uri): String =
        DocumentFile.fromTreeUri(activity, uri)?.name ?: uri.lastPathSegment ?: uri.toString()

    private fun collectVideoFiles(activity: ComponentActivity, treeUri: Uri): List<ScannedVideo> {
        val root = DocumentFile.fromTreeUri(activity, treeUri) ?: return emptyList()
        return root.listFiles()
            .filter { it.isFile && isVideo(it) }
            .map { ScannedVideo(file = it, parent = root) }
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
    onSelectSubtitleSource: (SubtitleSource) -> Unit,
    onSelectMatchMode: (MatchMode) -> Unit,
    onMatchSubtitle: (String) -> Unit,
    onConfirmCandidate: (Int) -> Unit,
    onDismissCandidates: () -> Unit,
) {
    var logVisible by remember { mutableStateOf(false) }
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
                    TextButton(onClick = onPickFolder) {
                        Text("打开")
                    }
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
                        onPlayVideo = { playVideoWithMpv(context, item.uri) },
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

    if (state.pendingCandidates.isNotEmpty()) {
        CandidateDialog(
            candidates = state.pendingCandidates,
            onDismiss = onDismissCandidates,
            onSelect = onConfirmCandidate,
        )
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

private fun playVideoWithMpv(context: android.content.Context, uri: Uri): Boolean {
    val openIntent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("is.xyz.mpv")
        }
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

private fun episodeLabel(episode: Int?): String = if (episode == null) "电影" else "第${episode}集"
