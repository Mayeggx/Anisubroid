package com.mayegg.anisub

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.withContext
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
                onMatchSubtitle = vm::matchSubtitle,
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
    val videos: List<VideoItem> = emptyList(),
    val message: String = "请选择视频文件夹。",
)

class MainViewModel(
    application: android.app.Application,
) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "AnisubroidMain"
    }
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val matcher = JimakuSubtitleMatcher(application)

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
                    .map { item ->
                        VideoItem(
                            id = item.file.uri.toString(),
                            uri = item.file.uri,
                            folderUri = item.parent.uri,
                            title = item.file.name ?: item.file.uri.lastPathSegment.orEmpty(),
                        )
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
                message = "正在从 Jimaku 匹配：${target.title}",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val output =
                runCatching { matcher.matchAndDownload(target) }
                    .fold(
                        onSuccess = { result ->
                            "匹配成功：${result.savedFileName}"
                        },
                        onFailure = { error ->
                            Log.e(TAG, "Subtitle match failed for: ${target.title}", error)
                            "匹配失败：${error.message ?: "未知错误"}"
                        },
                    )

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

    private fun resolveFolderLabel(activity: ComponentActivity, uri: Uri): String =
        DocumentFile.fromTreeUri(activity, uri)?.name ?: uri.lastPathSegment ?: uri.toString()

    private fun collectVideoFiles(activity: ComponentActivity, treeUri: Uri): List<ScannedVideo> {
        val root = DocumentFile.fromTreeUri(activity, treeUri) ?: return emptyList()
        return collectRecursively(root)
    }

    private fun collectRecursively(node: DocumentFile): List<ScannedVideo> =
        node.listFiles().flatMap { file ->
            when {
                file.isDirectory -> collectRecursively(file)
                file.isFile && isVideo(file) -> listOf(ScannedVideo(file = file, parent = node))
                else -> emptyList()
            }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScreen(
    state: MainUiState,
    onPickFolder: () -> Unit,
    onMatchSubtitle: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Anisubroid", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = state.folderLabel,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onPickFolder) {
                        Text("打开文件夹")
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
                    VideoRow(item = item, onMatchSubtitle = { onMatchSubtitle(item.id) })
                }
            }
        }
    }
}

@Composable
private fun VideoRow(
    item: VideoItem,
    onMatchSubtitle: () -> Unit,
) {
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(initialValue = null, key1 = item.id) {
        value = loadVideoThumbnail(context as ComponentActivity, item.uri)
    }

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
                ThumbnailView(bitmap = thumbnail)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = "字幕状态: ${item.subtitleStatus}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Button(onClick = onMatchSubtitle, enabled = !item.matching, modifier = Modifier.fillMaxWidth()) {
                Text(if (item.matching) "匹配中..." else "匹配并下载字幕")
            }
        }
    }
}

@Composable
private fun ThumbnailView(bitmap: ImageBitmap?) {
    Surface(
        modifier = Modifier.size(120.dp, 68.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        if (bitmap == null) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0x33000000)),
                contentAlignment = Alignment.Center,
            ) {
                Text("无预览", style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

private suspend fun loadVideoThumbnail(activity: ComponentActivity, uri: Uri): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            activity.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
            } ?: return@runCatching null

            val frame = retriever.frameAtTime ?: retriever.getFrameAtTime(0)
            retriever.release()
            frame?.toScaledBitmap()?.asImageBitmap()
        }.getOrNull()
    }

private fun Bitmap.toScaledBitmap(): Bitmap {
    val targetHeight = 200
    val ratio = width.toFloat() / height.toFloat()
    val targetWidth = (targetHeight * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}
