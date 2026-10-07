package com.mayegg.anisub

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
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
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.PushResult
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.random.Random

class VideoDownloadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: VideoDownloadViewModel = viewModel()
            val state by vm.uiState.collectAsStateWithLifecycle()
            val context = LocalContext.current
            VideoDownloadScreen(
                state = state,
                onBack = { finish() },
                onAddSubscription = vm::addSubscription,
                onRemoveSubscription = vm::removeSubscription,
                onOpenSubscription = vm::openSubscription,
                onBackToList = vm::backToList,
                onPullSubscriptionSync = vm::pullSubscriptionConfig,
                onPushSubscriptionSync = vm::pushSubscriptionConfig,
                onRefreshEntries = vm::refreshActiveSubscription,
                onDownloadTorrent = vm::downloadTorrent,
                onOpenTorrent = { path ->
                    val message = openTorrentFile(context, path)
                    if (message != null) vm.setMessage(message)
                },
                onOpenVideos = vm::openSubscriptionVideos,
                onRescanVideos = vm::rescanActiveVideos,
                onSaveVideoFolder = vm::saveVideoFolderInput,
                onVideoFolderPicked = vm::savePickedVideoFolder,
                onMatchVideo = vm::matchDownloadedVideo,
                onOffsetVideo = vm::offsetDownloadedVideo,
                onDeleteVideo = vm::deleteDownloadedVideo,
            )
        }
    }
}

@Composable
fun VideoDownloadPage() {
    val vm: VideoDownloadViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    VideoDownloadScreen(
        state = state,
        onBack = vm::backToList,
        onAddSubscription = vm::addSubscription,
        onRemoveSubscription = vm::removeSubscription,
        onOpenSubscription = vm::openSubscription,
        onBackToList = vm::backToList,
        onPullSubscriptionSync = vm::pullSubscriptionConfig,
        onPushSubscriptionSync = vm::pushSubscriptionConfig,
        onRefreshEntries = vm::refreshActiveSubscription,
        onDownloadTorrent = vm::downloadTorrent,
        onOpenTorrent = { path ->
            val message = openTorrentFile(context, path)
            if (message != null) vm.setMessage(message)
        },
        onOpenVideos = vm::openSubscriptionVideos,
        onRescanVideos = vm::rescanActiveVideos,
        onSaveVideoFolder = vm::saveVideoFolderInput,
        onVideoFolderPicked = vm::savePickedVideoFolder,
        onMatchVideo = vm::matchDownloadedVideo,
        onOffsetVideo = vm::offsetDownloadedVideo,
        onDeleteVideo = vm::deleteDownloadedVideo,
    )
}

@Composable
fun VideoDownloadEmbeddedPage() {
    val vm: VideoDownloadViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    VideoDownloadScreenEmbedded(
        state = state,
        onAddSubscription = vm::addSubscription,
        onRemoveSubscription = vm::removeSubscription,
        onOpenSubscription = vm::openSubscription,
        onBackToList = vm::backToList,
        onPullSubscriptionSync = vm::pullSubscriptionConfig,
        onPushSubscriptionSync = vm::pushSubscriptionConfig,
        onRefreshEntries = vm::refreshActiveSubscription,
        onDownloadTorrent = vm::downloadTorrent,
        onOpenTorrent = { path ->
            val message = openTorrentFile(context, path)
            if (message != null) vm.setMessage(message)
        },
        onOpenVideos = vm::openSubscriptionVideos,
        onRescanVideos = vm::rescanActiveVideos,
        onSaveVideoFolder = vm::saveVideoFolderInput,
        onVideoFolderPicked = vm::savePickedVideoFolder,
        onMatchVideo = vm::matchDownloadedVideo,
        onOffsetVideo = vm::offsetDownloadedVideo,
        onDeleteVideo = vm::deleteDownloadedVideo,
    )
}

data class VideoSubscriptionItem(
    val id: String,
    val label: String,
    val url: String,
    val folderName: String,
    val downloadedCount: Int = 0,
)

data class TorrentEntryItem(
    val id: String,
    val title: String,
    val sizeText: String,
    val uploadText: String,
    val downloadUrl: String,
    val localFilePath: String? = null,
    val downloading: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoDownloadScreenEmbedded(
    state: VideoDownloadUiState,
    onAddSubscription: (String) -> Unit,
    onRemoveSubscription: (String) -> Unit,
    onOpenSubscription: (String) -> Unit,
    onBackToList: () -> Unit,
    onPullSubscriptionSync: () -> Unit,
    onPushSubscriptionSync: () -> Unit,
    onRefreshEntries: () -> Unit,
    onDownloadTorrent: (String) -> Unit,
    onOpenTorrent: (String) -> Unit,
    onOpenVideos: (String) -> Unit,
    onRescanVideos: () -> Unit,
    onSaveVideoFolder: (String) -> SaveVideoFolderResult,
    onVideoFolderPicked: (Uri) -> Unit,
    onMatchVideo: (String) -> Unit,
    onOffsetVideo: (String, Long) -> Unit,
    onDeleteVideo: (String) -> Unit,
) {
    var addDialogVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val videoFolderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                persistTreeReadWritePermission(context, it)
                onVideoFolderPicked(it)
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.activeSubscriptionId == null) {
                            ""
                        } else if (state.videosViewActive) {
                            "${state.activeSubscriptionLabel} · 视频"
                        } else {
                            state.activeSubscriptionLabel
                        },
                    )
                },
                navigationIcon = {
                    if (state.activeSubscriptionId != null) {
                        TextButton(onClick = onBackToList) { Text("返回") }
                    }
                },
                actions = {
                    if (state.activeSubscriptionId == null) {
                        TextButton(onClick = onPullSubscriptionSync, enabled = !state.syncingConfig) { Text("Pull") }
                        TextButton(onClick = onPushSubscriptionSync, enabled = !state.syncingConfig) { Text("Push") }
                        TextButton(onClick = { addDialogVisible = true }, enabled = !state.syncingConfig) { Text("添加") }
                        TextButton(onClick = { settingsVisible = true }) { Text("设置") }
                    } else {
                        RefreshEntriesButton(
                            loading = if (state.videosViewActive) state.loadingVideos else state.loadingEntries,
                            failed = if (state.videosViewActive) state.videosLoadFailed else state.entriesRefreshFailed,
                            onRefresh = if (state.videosViewActive) onRescanVideos else onRefreshEntries,
                            confirmMessage =
                                if (state.videosViewActive) {
                                    "是否重新扫描本地视频目录？"
                                } else {
                                    "是否重新拉取最新的条目列表？"
                                },
                        )
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
            if (state.message.isNotBlank()) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.activeSubscriptionId == null) {
                SubscriptionList(
                    subscriptions = state.subscriptions,
                    onOpenSubscription = onOpenSubscription,
                    onOpenVideos = onOpenVideos,
                    onRemoveSubscription = onRemoveSubscription,
                )
            } else if (state.videosViewActive) {
                DownloadedVideoList(
                    videos = state.activeVideos,
                    controlsEnabled = !state.deletingVideo,
                    onMatchVideo = onMatchVideo,
                    onOffsetVideo = onOffsetVideo,
                    onDeleteVideo = onDeleteVideo,
                )
            } else {
                TorrentEntryList(
                    entries = state.activeEntries,
                    onDownloadTorrent = onDownloadTorrent,
                    onOpenTorrent = onOpenTorrent,
                )
            }
        }
    }

    if (addDialogVisible) {
        AddSubscriptionDialog(
            onDismiss = { addDialogVisible = false },
            onConfirm = { url ->
                onAddSubscription(url)
                addDialogVisible = false
            },
        )
    }

    if (settingsVisible) {
        VideoFolderSettingsDialog(
            currentLabel = state.videoFolderLabel,
            currentUri = state.videoFolderUri,
            onBrowse = { videoFolderLauncher.launch(null) },
            onSave = onSaveVideoFolder,
            onRequestGrant = { initial -> videoFolderLauncher.launch(initial) },
            onDismiss = { settingsVisible = false },
        )
    }
}

data class VideoDownloadUiState(
    val subscriptions: List<VideoSubscriptionItem> = emptyList(),
    val activeSubscriptionId: String? = null,
    val activeSubscriptionLabel: String = "",
    val activeEntries: List<TorrentEntryItem> = emptyList(),
    val loadingEntries: Boolean = false,
    val entriesRefreshFailed: Boolean = false,
    val videosViewActive: Boolean = false,
    val activeVideos: List<VideoItem> = emptyList(),
    val loadingVideos: Boolean = false,
    val videosLoadFailed: Boolean = false,
    val deletingVideo: Boolean = false,
    val videoFolderUri: String = "",
    val videoFolderLabel: String = "",
    val syncingConfig: Boolean = false,
    val message: String = "请先添加视频订阅链接。",
)

sealed interface SaveVideoFolderResult {
    data object Saved : SaveVideoFolderResult

    data object Cleared : SaveVideoFolderResult

    data class Invalid(
        val message: String,
    ) : SaveVideoFolderResult

    data class NeedGrant(
        val initialUri: Uri,
    ) : SaveVideoFolderResult
}

private data class PersistedSubscription(
    val id: String,
    val label: String,
    val url: String,
    val folderName: String,
)

private data class ParsedTorrentEntry(
    val id: String,
    val title: String,
    val sizeText: String,
    val uploadText: String,
    val downloadUrl: String,
)

private data class PersistedVideo(
    val id: String,
    val uri: String,
    val folderUri: String,
    val title: String,
    val episode: Int?,
    val subtitleStatus: String,
)

private data class SeedSyncConfig(
    val remoteUrl: String = "https://gitee.com/mayeggx/pic4nisub.git",
    val gitUsername: String = "",
    val gitToken: String = "",
    val commitUserName: String = "Anisubroid Remote Sync",
    val commitUserEmail: String = "anisubroid@local",
)

private data class SeedPullResult(
    val fileFound: Boolean,
    val syncedCount: Int,
)

class VideoDownloadViewModel(
    application: android.app.Application,
) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "VideoDownload"
        private const val PREF_NAME = "anisubroid_video_download"
        private const val KEY_SUBSCRIPTIONS = "subscriptions"
        private const val KEY_ENTRY_CACHE_PREFIX = "entry_cache_"
        private const val KEY_VIDEO_CACHE_PREFIX = "video_cache_"
        private const val KEY_VIDEO_FOLDER_URI = "video_folder_uri"
        private const val KEY_VIDEO_FOLDER_LABEL = "video_folder_label"
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        private const val VIDEO_SCAN_MAX_DEPTH = 2
        private const val ROW_SEPARATOR = "\n"
        private const val FIELD_SEPARATOR = "\t"
        private const val DOWNLOAD_ROOT = "video_subscriptions"
        private const val REMOTE_SYNC_PREF_NAME = "remote_sync_store"
        private const val REMOTE_SYNC_CONFIG_KEY = "config"
        private const val DEFAULT_REMOTE_URL = "https://gitee.com/mayeggx/pic4nisub.git"
        private const val DEFAULT_REMOTE_BRANCH = "main"
        private const val SEED_SYNC_REPO_SUBDIR = "remote-sync/repo-a"
        private const val SEED_SYNC_FILE_NAME = "seed-subscriptions.json"
        private val ID_PATTERN = Regex("""/(view|download)/(\d+)""")
        private val ROW_PATTERN = Regex("""(?is)<tr[^>]*>(.*?)</tr>""")
        private val TD_PATTERN = Regex("""(?is)<td[^>]*>(.*?)</td>""")
        private val VIEW_LINK_PATTERN = Regex("""(?is)<a[^>]*href\s*=\s*["']([^"']*/view/\d+[^"']*)["'][^>]*>(.*?)</a>""")
        private val DOWNLOAD_LINK_PATTERN = Regex("""(?is)<a[^>]*href\s*=\s*["']([^"']*/download/\d+[^"']*)["']""")
    }

    private val appContext = application.applicationContext
    private val prefs = application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val seedSyncRepoDir = File(appContext.filesDir, SEED_SYNC_REPO_SUBDIR)
    private val _uiState = MutableStateFlow(VideoDownloadUiState())
    val uiState: StateFlow<VideoDownloadUiState> = _uiState.asStateFlow()

    private val jimakuMatcher = JimakuSubtitleMatcher(application)
    private var subscriptions: List<PersistedSubscription> = emptyList()
    private var entryFetchGeneration = 0L
    private var videoScanGeneration = 0L

    init {
        subscriptions = readSubscriptions()
        _uiState.update {
            it.copy(
                subscriptions = subscriptions.map(::toUiSubscription),
                videoFolderUri = prefs.getString(KEY_VIDEO_FOLDER_URI, "").orEmpty(),
                videoFolderLabel = prefs.getString(KEY_VIDEO_FOLDER_LABEL, "").orEmpty(),
                message = if (subscriptions.isEmpty()) "请先添加视频订阅链接。" else "请选择一个订阅查看条目。",
            )
        }
    }

    fun setMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun pushSubscriptionConfig() {
        val snapshot = _uiState.value
        if (snapshot.syncingConfig) return
        _uiState.update { it.copy(syncingConfig = true, message = "正在 Push 订阅配置...") }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { pushSubscriptionConfigInternal() }
                .onSuccess { count ->
                    _uiState.update {
                        it.copy(
                            syncingConfig = false,
                            subscriptions = subscriptions.map(::toUiSubscription),
                            message = "Push 完成：已同步 $count 条订阅配置。",
                        )
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Push subscription config failed.", error)
                    _uiState.update {
                        it.copy(
                            syncingConfig = false,
                            message = "Push 失败：${error.message ?: "未知错误"}",
                        )
                    }
                }
        }
    }

    fun pullSubscriptionConfig() {
        val snapshot = _uiState.value
        if (snapshot.syncingConfig) return
        _uiState.update { it.copy(syncingConfig = true, message = "正在 Pull 订阅配置...") }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { pullSubscriptionConfigInternal() }
                .onSuccess { result ->
                    _uiState.update { state ->
                        state.copy(
                            syncingConfig = false,
                            subscriptions = subscriptions.map(::toUiSubscription),
                            activeSubscriptionId = null,
                            activeSubscriptionLabel = "",
                            activeEntries = emptyList(),
                            loadingEntries = false,
                            entriesRefreshFailed = false,
                            videosViewActive = false,
                            activeVideos = emptyList(),
                            loadingVideos = false,
                            videosLoadFailed = false,
                            deletingVideo = false,
                            message =
                                if (result.fileFound) {
                                    "Pull 完成：已同步 ${result.syncedCount} 条订阅配置。"
                                } else {
                                    "Pull 完成：远端仓库尚未创建订阅配置文件。"
                                },
                        )
                    }
                }.onFailure { error ->
                    Log.e(TAG, "Pull subscription config failed.", error)
                    _uiState.update {
                        it.copy(
                            syncingConfig = false,
                            message = "Pull 失败：${error.message ?: "未知错误"}",
                        )
                    }
                }
        }
    }

    private fun pushSubscriptionConfigInternal(): Int {
        val config = loadSeedSyncConfig()
        val git = openOrCreateSeedSyncRepository(config)
        try {
            safePullSeedSync(git, config)
            val uniqueUrls = subscriptions.map { it.url }.distinct()
            val syncFile = File(seedSyncRepoDir, SEED_SYNC_FILE_NAME)
            syncFile.writeText(buildSeedSyncPayload(uniqueUrls), Charsets.UTF_8)
            commitAndPushIfNeeded(
                git = git,
                config = config,
                message = "seed-sync: update subscriptions",
                paths = listOf(SEED_SYNC_FILE_NAME),
            )
            return uniqueUrls.size
        } finally {
            git.close()
        }
    }

    private fun pullSubscriptionConfigInternal(): SeedPullResult {
        val config = loadSeedSyncConfig()
        val git = openOrCreateSeedSyncRepository(config)
        try {
            safePullSeedSync(git, config)
            val syncFile = File(seedSyncRepoDir, SEED_SYNC_FILE_NAME)
            if (!syncFile.exists()) {
                return SeedPullResult(
                    fileFound = false,
                    syncedCount = subscriptions.size,
                )
            }

            val urls = parseSeedSyncPayload(syncFile.readText(Charsets.UTF_8))
            subscriptions = mergeSubscriptionsByUrls(urls)
            persistSubscriptions()
            return SeedPullResult(
                fileFound = true,
                syncedCount = subscriptions.size,
            )
        } finally {
            git.close()
        }
    }

    fun addSubscription(rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isBlank()) {
            setMessage("订阅链接不能为空。")
            return
        }
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
            setMessage("订阅链接格式无效。")
            return
        }
        val normalized = uri.toString()
        if (subscriptions.any { it.url == normalized }) {
            setMessage("该订阅已存在。")
            return
        }

        val label = buildSubscriptionLabel(uri)
        val id = "${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"
        val folderName = buildFolderName(label, id)
        val next = PersistedSubscription(id = id, label = label, url = normalized, folderName = folderName)
        subscriptions = listOf(next) + subscriptions
        persistSubscriptions()
        _uiState.update {
            it.copy(
                subscriptions = subscriptions.map(::toUiSubscription),
                message = "已添加订阅：$label",
            )
        }
    }

    fun removeSubscription(id: String) {
        val removed = subscriptions.firstOrNull { it.id == id } ?: return
        subscriptions = subscriptions.filterNot { it.id == id }
        persistSubscriptions()
        prefs.edit().remove(entryCacheKey(id)).remove(videoCacheKey(id)).apply()
        val current = _uiState.value
        val shouldExitDetail = current.activeSubscriptionId == id
        _uiState.update {
            it.copy(
                subscriptions = subscriptions.map(::toUiSubscription),
                activeSubscriptionId = if (shouldExitDetail) null else it.activeSubscriptionId,
                activeSubscriptionLabel = if (shouldExitDetail) "" else it.activeSubscriptionLabel,
                activeEntries = if (shouldExitDetail) emptyList() else it.activeEntries,
                videosViewActive = if (shouldExitDetail) false else it.videosViewActive,
                activeVideos = if (shouldExitDetail) emptyList() else it.activeVideos,
                loadingVideos = if (shouldExitDetail) false else it.loadingVideos,
                videosLoadFailed = if (shouldExitDetail) false else it.videosLoadFailed,
                deletingVideo = if (shouldExitDetail) false else it.deletingVideo,
                loadingEntries = false,
                entriesRefreshFailed = false,
                message = "已删除订阅：${removed.label}",
            )
        }
    }

    fun openSubscription(id: String) {
        val target = subscriptions.firstOrNull { it.id == id } ?: return
        val generation = ++entryFetchGeneration
        _uiState.update {
            it.copy(
                activeSubscriptionId = target.id,
                activeSubscriptionLabel = target.label,
                loadingEntries = true,
                entriesRefreshFailed = false,
                activeEntries = emptyList(),
                videosViewActive = false,
                activeVideos = emptyList(),
                loadingVideos = false,
                videosLoadFailed = false,
                deletingVideo = false,
                message = "",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            fun updateEntries(transform: VideoDownloadUiState.() -> VideoDownloadUiState) {
                _uiState.update {
                    if (it.activeSubscriptionId == target.id && generation == entryFetchGeneration) it.transform() else it
                }
            }
            val cachedEntries = readCachedEntries(target.id)
            if (cachedEntries.isNotEmpty()) {
                updateEntries {
                    copy(activeEntries = mapToUiEntries(target, cachedEntries))
                }
            }
            val result = runCatching { fetchEntriesFromSubscription(target.url) }
            val mappedSubscriptions = subscriptions.map(::toUiSubscription)
            result.fold(
                onSuccess = { entries ->
                    if (entries.isEmpty() && cachedEntries.isNotEmpty()) {
                        updateEntries {
                            copy(
                                loadingEntries = false,
                                subscriptions = mappedSubscriptions,
                            )
                        }
                        return@fold
                    }
                    if (entries != cachedEntries) {
                        persistCachedEntries(target.id, entries)
                    }
                    updateEntries {
                        copy(
                            loadingEntries = false,
                            activeEntries = mapToUiEntries(target, entries),
                            subscriptions = mappedSubscriptions,
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Open subscription failed: ${target.url}", error)
                    updateEntries {
                        copy(
                            loadingEntries = false,
                            entriesRefreshFailed = true,
                            activeEntries = if (cachedEntries.isEmpty()) emptyList() else activeEntries,
                        )
                    }
                },
            )
        }
    }

    fun refreshActiveSubscription() {
        val activeId = _uiState.value.activeSubscriptionId ?: return
        openSubscription(activeId)
    }

    fun savePickedVideoFolder(uri: Uri) {
        val label =
            runCatching { DocumentFile.fromTreeUri(appContext, uri)?.name }.getOrNull()
                ?: uri.lastPathSegment
                ?: uri.toString()
        prefs
            .edit()
            .putString(KEY_VIDEO_FOLDER_URI, uri.toString())
            .putString(KEY_VIDEO_FOLDER_LABEL, label)
            .apply()
        _uiState.update {
            it.copy(
                videoFolderUri = uri.toString(),
                videoFolderLabel = label,
                message = "已关联视频文件夹：$label",
            )
        }
    }

    fun saveVideoFolderInput(rawInput: String): SaveVideoFolderResult {
        val input = rawInput.trim()
        if (input.isBlank()) {
            prefs.edit().remove(KEY_VIDEO_FOLDER_URI).remove(KEY_VIDEO_FOLDER_LABEL).apply()
            _uiState.update {
                it.copy(
                    videoFolderUri = "",
                    videoFolderLabel = "",
                    message = "已清除视频文件夹关联。",
                )
            }
            return SaveVideoFolderResult.Cleared
        }

        val treeUri =
            if (input.startsWith("content://")) {
                Uri.parse(input)
            } else {
                val docId =
                    pathToTreeDocId(input)
                        ?: return SaveVideoFolderResult.Invalid("无法识别该路径，请使用「浏览」选择文件夹。")
                DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)
            }
        val docId =
            runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return SaveVideoFolderResult.Invalid("文件夹 URI 格式无效。")
        if (!hasTreePermissionFor(docId)) {
            val initialUri =
                runCatching { DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId) }
                    .getOrDefault(treeUri)
            return SaveVideoFolderResult.NeedGrant(initialUri)
        }

        savePickedVideoFolder(treeUri)
        return SaveVideoFolderResult.Saved
    }

    fun openSubscriptionVideos(id: String) {
        val target = subscriptions.firstOrNull { it.id == id } ?: return
        val folderRaw = prefs.getString(KEY_VIDEO_FOLDER_URI, null)
        if (folderRaw.isNullOrBlank()) {
            _uiState.update { it.copy(message = "请先在右上角「设置」中关联本地视频文件夹。") }
            return
        }
        val folderUri = Uri.parse(folderRaw)
        val generation = ++videoScanGeneration
        _uiState.update {
            it.copy(
                activeSubscriptionId = target.id,
                activeSubscriptionLabel = target.label,
                videosViewActive = true,
                activeVideos = emptyList(),
                loadingVideos = true,
                videosLoadFailed = false,
                activeEntries = emptyList(),
                loadingEntries = false,
                entriesRefreshFailed = false,
                deletingVideo = false,
                message = "",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            fun updateVideos(transform: VideoDownloadUiState.() -> VideoDownloadUiState) {
                _uiState.update {
                    if (it.activeSubscriptionId == target.id && generation == videoScanGeneration) it.transform() else it
                }
            }

            val cachedVideos = readCachedVideos(target.id)
            if (cachedVideos.isNotEmpty()) {
                updateVideos { copy(activeVideos = cachedVideos.map(::toUiVideo)) }
            }

            runCatching {
                if (SubtitleNameHeuristics.normalize(target.label).isBlank()) {
                    error("订阅名称为空，无法匹配视频。")
                }
                val root =
                    DocumentFile.fromTreeUri(appContext, folderUri)
                        ?: error("无法访问已关联的视频文件夹，请在「设置」中重新关联。")
                val scanned = collectVideoFilesUnder(root)
                val subtitleBases = collectExistingSubtitleBaseNames(root)
                scanned
                    .filter { videoNameMatchesQuery(it.file.name.orEmpty(), target.label) }
                    .sortedBy { it.file.name?.lowercase(Locale.ROOT).orEmpty() }
                    .map { item ->
                        val title = item.file.name ?: item.file.uri.lastPathSegment.orEmpty()
                        PersistedVideo(
                            id = item.file.uri.toString(),
                            uri = item.file.uri.toString(),
                            folderUri = item.parent.uri.toString(),
                            title = title,
                            episode = SubtitleNameHeuristics.extractEpisode(title),
                            subtitleStatus =
                                if (subtitleBases.contains(stripExtension(title).lowercase(Locale.ROOT))) {
                                    "已存在对应字幕"
                                } else {
                                    "未匹配"
                                },
                        )
                    }
            }.fold(
                onSuccess = { videos ->
                    if (videos != cachedVideos) {
                        persistCachedVideos(target.id, videos)
                    }
                    updateVideos {
                        copy(
                            loadingVideos = false,
                            activeVideos = videos.map(::toUiVideo),
                            message =
                                if (videos.isEmpty()) {
                                    "该目录下没有与订阅名称「${target.label}」匹配的视频。"
                                } else {
                                    "匹配到 ${videos.size} 个视频。"
                                },
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Scan downloaded videos failed.", error)
                    updateVideos {
                        copy(
                            loadingVideos = false,
                            videosLoadFailed = true,
                            message =
                                if (activeVideos.isEmpty()) {
                                    "视频加载失败：${error.message ?: "未知错误"}"
                                } else {
                                    message
                                },
                        )
                    }
                },
            )
        }
    }

    fun rescanActiveVideos() {
        val activeId = _uiState.value.activeSubscriptionId ?: return
        openSubscriptionVideos(activeId)
    }

    fun matchDownloadedVideo(videoId: String) {
        if (_uiState.value.deletingVideo) return
        val target = _uiState.value.activeVideos.firstOrNull { it.id == videoId } ?: return
        _uiState.update { state ->
            state.copy(
                activeVideos =
                    state.activeVideos.map { item ->
                        if (item.id == videoId) {
                            item.copy(subtitleStatus = "正在匹配并下载字幕...", matching = true)
                        } else {
                            item
                        }
                    },
                message = "正在匹配字幕：${target.title}",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val output =
                runCatching { jimakuMatcher.matchAndDownload(target) }
                    .fold(
                        onSuccess = { "匹配成功：${it.savedFileName}" },
                        onFailure = { error ->
                            Log.e(TAG, "Subtitle match failed for: ${target.title}", error)
                            "匹配失败：${error.message ?: "未知错误"}"
                        },
                    )
            _uiState.update { state ->
                state.copy(
                    activeVideos =
                        state.activeVideos.map { item ->
                            if (item.id == videoId) item.copy(subtitleStatus = output, matching = false) else item
                        },
                    message = output,
                )
            }
        }
    }

    fun offsetDownloadedVideo(
        videoId: String,
        offsetMillis: Long,
    ) {
        if (_uiState.value.deletingVideo) return
        val target = _uiState.value.activeVideos.firstOrNull { it.id == videoId } ?: return
        _uiState.update { state ->
            state.copy(
                activeVideos =
                    state.activeVideos.map { item ->
                        if (item.id == videoId) item.copy(subtitleStatus = "正在偏移字幕...", matching = true) else item
                    },
                message = "正在偏移字幕：${target.title}",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val output =
                runCatching {
                    val subtitleFile =
                        findMatchingSubtitleFile(appContext, target.folderUri, target.title)
                            ?: error("未找到对应字幕。")
                    val changedCount = applyOffsetToSubtitleFile(appContext, subtitleFile, offsetMillis)
                    "偏移成功：${subtitleFile.name ?: "未知字幕"}（$changedCount 处时间，${formatOffsetLabel(offsetMillis)}）"
                }.getOrElse { error -> "偏移失败：${error.message ?: "未知错误"}" }

            _uiState.update { state ->
                state.copy(
                    activeVideos =
                        state.activeVideos.map { item ->
                            if (item.id == videoId) item.copy(subtitleStatus = output, matching = false) else item
                        },
                    message = output,
                )
            }
        }
    }

    fun deleteDownloadedVideo(videoId: String) {
        val snapshot = _uiState.value
        if (snapshot.deletingVideo) return
        if (snapshot.activeVideos.any { it.matching }) {
            _uiState.update { it.copy(message = "请等待当前匹配或偏移完成后再删除。") }
            return
        }
        val target = snapshot.activeVideos.firstOrNull { it.id == videoId } ?: return
        _uiState.update {
            it.copy(
                deletingVideo = true,
                message = "正在硬删除：${target.title}",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { deleteVideoAndRelatedFiles(appContext, target) }
                .onSuccess { result ->
                    _uiState.value.activeSubscriptionId?.let { subscriptionId ->
                        persistCachedVideos(
                            subscriptionId,
                            readCachedVideos(subscriptionId).filterNot { it.id == videoId },
                        )
                    }
                    _uiState.update { state ->
                        state.copy(
                            deletingVideo = false,
                            activeVideos = state.activeVideos.filterNot { it.id == videoId },
                            message = buildDeletedVideoMessage(target, result),
                        )
                    }
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(
                            deletingVideo = false,
                            message = "删除失败：${error.message ?: "未知错误"}",
                        )
                    }
                }
        }
    }

    private fun hasTreePermissionFor(docId: String): Boolean =
        appContext.contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission &&
                runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }
                    .map { granted -> docId == granted || docId.startsWith("$granted/") }
                    .getOrDefault(false)
        }

    private data class ScannedDownloadedVideo(
        val file: DocumentFile,
        val parent: DocumentFile,
    )

    private fun collectVideoFilesUnder(root: DocumentFile): List<ScannedDownloadedVideo> {
        val results = mutableListOf<ScannedDownloadedVideo>()

        fun visit(
            dir: DocumentFile,
            depth: Int,
        ) {
            dir.listFiles().forEach { child ->
                when {
                    child.isFile && isVideoFile(child) -> results += ScannedDownloadedVideo(file = child, parent = dir)
                    child.isDirectory && depth < VIDEO_SCAN_MAX_DEPTH && !child.name.equals("sub", ignoreCase = true) ->
                        visit(child, depth + 1)
                }
            }
        }

        visit(root, 0)
        return results
    }

    private fun buildDeletedVideoMessage(
        video: VideoItem,
        result: DeleteVideoResult,
    ): String {
        val suffix =
            when {
                result.deletedSubtitleCount > 0 && result.failedSubtitleNames.isEmpty() ->
                    "，并删除 ${result.deletedSubtitleCount} 个同名字幕。"
                result.deletedSubtitleCount > 0 ->
                    "，已删除 ${result.deletedSubtitleCount} 个同名字幕，另有 ${result.failedSubtitleNames.size} 个同名字幕删除失败。"
                result.failedSubtitleNames.isNotEmpty() ->
                    "，但有 ${result.failedSubtitleNames.size} 个同名字幕删除失败。"
                else -> "。"
            }
        return "已硬删除：${video.title}$suffix"
    }

    fun backToList() {
        _uiState.update {
            it.copy(
                activeSubscriptionId = null,
                activeSubscriptionLabel = "",
                activeEntries = emptyList(),
                loadingEntries = false,
                entriesRefreshFailed = false,
                videosViewActive = false,
                activeVideos = emptyList(),
                loadingVideos = false,
                videosLoadFailed = false,
                deletingVideo = false,
                message = "请选择一个订阅查看条目。",
                subscriptions = subscriptions.map(::toUiSubscription),
            )
        }
    }

    fun downloadTorrent(entryId: String) {
        val state = _uiState.value
        val subscriptionId = state.activeSubscriptionId ?: return
        val subscription = subscriptions.firstOrNull { it.id == subscriptionId } ?: return
        val entry = state.activeEntries.firstOrNull { it.id == entryId } ?: return
        if (entry.downloading) return

        _uiState.update {
            it.copy(
                activeEntries = it.activeEntries.map { item -> if (item.id == entryId) item.copy(downloading = true) else item },
                message = "正在下载种子：${entry.title}",
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val output = runCatching {
                val folder = ensureSubscriptionFolder(subscription)
                val bytes = downloadBytes(entry.downloadUrl)
                val file = File(folder, torrentFileName(entry))
                file.writeBytes(bytes)
                file.absolutePath
            }
            output.fold(
                onSuccess = { path ->
                    _uiState.update {
                        it.copy(
                            activeEntries =
                                it.activeEntries.map { item ->
                                    if (item.id == entryId) {
                                        item.copy(localFilePath = path, downloading = false)
                                    } else {
                                        item
                                    }
                                },
                            subscriptions = subscriptions.map(::toUiSubscription),
                            message = "下载完成：${File(path).name}",
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Download torrent failed: ${entry.downloadUrl}", error)
                    _uiState.update {
                        it.copy(
                            activeEntries =
                                it.activeEntries.map { item ->
                                    if (item.id == entryId) item.copy(downloading = false) else item
                                },
                            message = "下载失败：${error.message ?: "未知错误"}",
                        )
                    }
                },
            )
        }
    }

    private fun mapToUiEntries(
        subscription: PersistedSubscription,
        entries: List<ParsedTorrentEntry>,
    ): List<TorrentEntryItem> {
        val folder = ensureSubscriptionFolder(subscription)
        return entries.map { entry ->
            val localFile = File(folder, torrentFileName(entry))
            TorrentEntryItem(
                id = entry.id,
                title = entry.title,
                sizeText = entry.sizeText,
                uploadText = entry.uploadText,
                downloadUrl = entry.downloadUrl,
                localFilePath = localFile.takeIf { it.exists() }?.absolutePath,
            )
        }
    }

    private fun toUiSubscription(item: PersistedSubscription): VideoSubscriptionItem {
        val folder = ensureSubscriptionFolder(item)
        val count =
            folder.listFiles()
                ?.count { it.isFile && it.name.lowercase(Locale.ROOT).endsWith(".torrent") }
                ?: 0
        return VideoSubscriptionItem(
            id = item.id,
            label = item.label,
            url = item.url,
            folderName = item.folderName,
            downloadedCount = count,
        )
    }

    private fun persistSubscriptions() {
        val payload =
            subscriptions.joinToString(ROW_SEPARATOR) { item ->
                listOf(item.id, item.label, item.url, item.folderName)
                    .joinToString(FIELD_SEPARATOR) { Uri.encode(it) }
            }
        prefs.edit().putString(KEY_SUBSCRIPTIONS, payload).apply()
    }

    private fun readSubscriptions(): List<PersistedSubscription> {
        val raw = prefs.getString(KEY_SUBSCRIPTIONS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(ROW_SEPARATOR)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { row ->
                val parts = row.split(FIELD_SEPARATOR, limit = 4)
                if (parts.size != 4) return@mapNotNull null
                PersistedSubscription(
                    id = Uri.decode(parts[0]),
                    label = Uri.decode(parts[1]),
                    url = Uri.decode(parts[2]),
                    folderName = Uri.decode(parts[3]),
                )
            }
            .toList()
    }

    private fun entryCacheKey(subscriptionId: String): String = "$KEY_ENTRY_CACHE_PREFIX$subscriptionId"

    private fun readCachedEntries(subscriptionId: String): List<ParsedTorrentEntry> {
        val raw = prefs.getString(entryCacheKey(subscriptionId), "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(ROW_SEPARATOR)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { row ->
                val parts = row.split(FIELD_SEPARATOR, limit = 5)
                if (parts.size != 5) return@mapNotNull null
                ParsedTorrentEntry(
                    id = Uri.decode(parts[0]),
                    title = Uri.decode(parts[1]),
                    sizeText = Uri.decode(parts[2]),
                    uploadText = Uri.decode(parts[3]),
                    downloadUrl = Uri.decode(parts[4]),
                )
            }
            .toList()
    }

    private fun persistCachedEntries(
        subscriptionId: String,
        entries: List<ParsedTorrentEntry>,
    ) {
        val payload =
            entries.joinToString(ROW_SEPARATOR) { entry ->
                listOf(entry.id, entry.title, entry.sizeText, entry.uploadText, entry.downloadUrl)
                    .joinToString(FIELD_SEPARATOR) { Uri.encode(it) }
            }
        prefs.edit().putString(entryCacheKey(subscriptionId), payload).apply()
    }

    private fun videoCacheKey(subscriptionId: String): String = "$KEY_VIDEO_CACHE_PREFIX$subscriptionId"

    private fun readCachedVideos(subscriptionId: String): List<PersistedVideo> {
        val raw = prefs.getString(videoCacheKey(subscriptionId), "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(ROW_SEPARATOR)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { row ->
                val parts = row.split(FIELD_SEPARATOR, limit = 6)
                if (parts.size != 6) return@mapNotNull null
                PersistedVideo(
                    id = Uri.decode(parts[0]),
                    uri = Uri.decode(parts[1]),
                    folderUri = Uri.decode(parts[2]),
                    title = Uri.decode(parts[3]),
                    episode = Uri.decode(parts[4]).toIntOrNull(),
                    subtitleStatus = Uri.decode(parts[5]),
                )
            }
            .toList()
    }

    private fun persistCachedVideos(
        subscriptionId: String,
        videos: List<PersistedVideo>,
    ) {
        if (videos.isEmpty()) {
            prefs.edit().remove(videoCacheKey(subscriptionId)).apply()
            return
        }
        val payload =
            videos.joinToString(ROW_SEPARATOR) { video ->
                listOf(
                    video.id,
                    video.uri,
                    video.folderUri,
                    video.title,
                    video.episode?.toString().orEmpty(),
                    video.subtitleStatus,
                ).joinToString(FIELD_SEPARATOR) { Uri.encode(it) }
            }
        prefs.edit().putString(videoCacheKey(subscriptionId), payload).apply()
    }

    private fun toUiVideo(item: PersistedVideo): VideoItem =
        VideoItem(
            id = item.id,
            uri = Uri.parse(item.uri),
            folderUri = Uri.parse(item.folderUri),
            title = item.title,
            episode = item.episode,
            subtitleStatus = item.subtitleStatus,
        )

    private fun fetchEntriesFromSubscription(url: String): List<ParsedTorrentEntry> {
        val html = downloadText(url)
        return ROW_PATTERN.findAll(html)
            .mapNotNull { rowMatch ->
                val rowHtml = rowMatch.groupValues[1]
                val downloadMatch = DOWNLOAD_LINK_PATTERN.find(rowHtml) ?: return@mapNotNull null
                val viewMatches = VIEW_LINK_PATTERN.findAll(rowHtml).toList()
                val viewMatch = viewMatches.maxByOrNull { it.groupValues[2].length } ?: return@mapNotNull null
                val viewHref = viewMatch.groupValues[1]
                val downloadHref = downloadMatch.groupValues[1]
                val id = extractTorrentId(viewHref) ?: extractTorrentId(downloadHref) ?: return@mapNotNull null
                val columns = TD_PATTERN.findAll(rowHtml).map { textFromHtml(it.groupValues[1]) }.toList()
                ParsedTorrentEntry(
                    id = id,
                    title = textFromHtml(viewMatch.groupValues[2]),
                    sizeText = columns.getOrNull(3).orEmpty(),
                    uploadText = columns.getOrNull(4).orEmpty(),
                    downloadUrl = resolveUrl(url, downloadHref),
                )
            }
            .distinctBy { it.id }
            .toList()
    }

    private fun extractTorrentId(link: String): String? = ID_PATTERN.find(link)?.groupValues?.getOrNull(2)

    private fun ensureSubscriptionFolder(item: PersistedSubscription): File {
        val baseDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir
        val root = File(baseDir, DOWNLOAD_ROOT)
        if (!root.exists()) root.mkdirs()
        val folder = File(root, item.folderName)
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    private fun torrentFileName(entry: ParsedTorrentEntry): String = "${entry.id}.torrent"

    private fun torrentFileName(entry: TorrentEntryItem): String = "${entry.id}.torrent"

    private fun downloadBytes(url: String): ByteArray {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.connect()
        return try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.connect()
        return try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveUrl(baseUrl: String, maybeRelative: String): String =
        runCatching { URL(URL(baseUrl), maybeRelative).toString() }.getOrElse { maybeRelative }

    private fun textFromHtml(fragment: String): String =
        HtmlCompat.fromHtml(fragment, HtmlCompat.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace('\u00a0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun mergeSubscriptionsByUrls(rawUrls: List<String>): List<PersistedSubscription> {
        val existingByUrl = subscriptions.associateBy { it.url }
        val merged = mutableListOf<PersistedSubscription>()
        rawUrls.forEach { rawUrl ->
            val normalized = normalizeSubscriptionUrl(rawUrl) ?: return@forEach
            val existing = existingByUrl[normalized]
            if (existing != null) {
                merged += existing
            } else {
                val uri = Uri.parse(normalized)
                val label = buildSubscriptionLabel(uri)
                val id = generateSubscriptionId()
                merged += PersistedSubscription(
                    id = id,
                    label = label,
                    url = normalized,
                    folderName = buildFolderName(label, id),
                )
            }
        }
        return merged.distinctBy { it.url }
    }

    private fun generateSubscriptionId(): String = "${System.currentTimeMillis()}_${Random.nextInt(1000, 9999)}"

    private fun normalizeSubscriptionUrl(rawUrl: String): String? {
        val url = rawUrl.trim()
        if (url.isBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) return null
        return uri.toString()
    }

    private fun buildSeedSyncPayload(urls: List<String>): String {
        val entries = JSONArray()
        urls.distinct().forEach { entries.put(JSONObject().put("url", it)) }
        return JSONObject().put("entries", entries).toString(2)
    }

    private fun parseSeedSyncPayload(payload: String): List<String> {
        val trimmed = payload.trim()
        if (trimmed.isBlank()) return emptyList()
        return runCatching {
            when {
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    when {
                        obj.has("entries") -> parseSeedSyncUrlArray(obj.optJSONArray("entries"))
                        obj.has("urls") -> parseSeedSyncUrlArray(obj.optJSONArray("urls"))
                        else -> emptyList()
                    }
                }

                trimmed.startsWith("[") -> parseSeedSyncUrlArray(JSONArray(trimmed))
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
            .mapNotNull(::normalizeSubscriptionUrl)
            .distinct()
    }

    private fun parseSeedSyncUrlArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (index in 0 until arr.length()) {
                when (val item = arr.opt(index)) {
                    is String -> add(item)
                    is JSONObject -> add(item.optString("url", ""))
                }
            }
        }
    }

    private fun loadSeedSyncConfig(): SeedSyncConfig {
        val remotePrefs = appContext.getSharedPreferences(REMOTE_SYNC_PREF_NAME, Context.MODE_PRIVATE)
        val raw = remotePrefs.getString(REMOTE_SYNC_CONFIG_KEY, null).orEmpty()
        if (raw.isBlank()) return SeedSyncConfig()
        return runCatching {
            val json = JSONObject(raw)
            SeedSyncConfig(
                remoteUrl = json.optString("remoteUrl", DEFAULT_REMOTE_URL).ifBlank { DEFAULT_REMOTE_URL },
                gitUsername = json.optString("gitUsername", ""),
                gitToken = json.optString("gitToken", ""),
                commitUserName = json.optString("commitUserName", "Anisubroid Remote Sync"),
                commitUserEmail = json.optString("commitUserEmail", "anisubroid@local"),
            )
        }.getOrDefault(SeedSyncConfig())
    }

    private fun openOrCreateSeedSyncRepository(config: SeedSyncConfig): Git {
        seedSyncRepoDir.mkdirs()
        val dotGit = File(seedSyncRepoDir, ".git")
        if (dotGit.exists()) {
            val git = Git.open(seedSyncRepoDir)
            configureSeedSyncRepository(git, config)
            return git
        }

        if (!seedSyncRepoDir.listFiles().isNullOrEmpty()) {
            seedSyncRepoDir.deleteRecursively()
            seedSyncRepoDir.mkdirs()
        }

        return runCatching {
            val clone = Git.cloneRepository().setURI(config.remoteUrl).setDirectory(seedSyncRepoDir)
            credentials(config)?.let { clone.setCredentialsProvider(it) }
            val git = clone.call()
            configureSeedSyncRepository(git, config)
            git
        }.getOrElse { error ->
            throw IllegalStateException("无法克隆远端仓库：${error.message ?: "未知错误"}", error)
        }
    }

    private fun configureSeedSyncRepository(
        git: Git,
        config: SeedSyncConfig,
    ) {
        val repoConfig = git.repository.config
        repoConfig.setString("remote", "origin", "url", config.remoteUrl)
        repoConfig.setStringList("remote", "origin", "fetch", listOf("+refs/heads/*:refs/remotes/origin/*"))
        repoConfig.setString("branch", DEFAULT_REMOTE_BRANCH, "remote", "origin")
        repoConfig.setString("branch", DEFAULT_REMOTE_BRANCH, "merge", "refs/heads/$DEFAULT_REMOTE_BRANCH")
        repoConfig.setString("user", null, "name", config.commitUserName.ifBlank { "Anisubroid Remote Sync" })
        repoConfig.setString("user", null, "email", config.commitUserEmail.ifBlank { "anisubroid@local" })
        repoConfig.save()
    }

    private fun safePullSeedSync(
        git: Git,
        config: SeedSyncConfig,
    ) {
        val provider = credentials(config)
        val pullCommand =
            git.pull()
                .setRemote("origin")
                .setRemoteBranchName(DEFAULT_REMOTE_BRANCH)
                .setRebase(true)
        provider?.let { pullCommand.setCredentialsProvider(it) }
        val pullResult =
            runCatching { pullCommand.call() }
                .getOrElse { error ->
                    val message = error.message.orEmpty()
                    if (!message.contains("unrelated", ignoreCase = true)) {
                        throw error
                    }
                    val fetchCommand =
                        git.fetch()
                            .setRemote("origin")
                            .setRefSpecs(RefSpec("+refs/heads/$DEFAULT_REMOTE_BRANCH:refs/remotes/origin/$DEFAULT_REMOTE_BRANCH"))
                    provider?.let { fetchCommand.setCredentialsProvider(it) }
                    fetchCommand.call()
                    val remoteMainRef = git.repository.findRef("refs/remotes/origin/$DEFAULT_REMOTE_BRANCH") ?: return
                    git.reset()
                        .setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD)
                        .setRef(remoteMainRef.name)
                        .call()
                    return
                }
        if (pullResult.isSuccessful) return

        val fetchCommand = git.fetch().setRemote("origin")
        provider?.let { fetchCommand.setCredentialsProvider(it) }
        fetchCommand.call()
        val remoteMainRef = git.repository.findRef("refs/remotes/origin/$DEFAULT_REMOTE_BRANCH")
        if (remoteMainRef == null) return
        throw IllegalStateException("Git pull 失败，请检查远端分支状态后重试。")
    }

    private fun commitAndPushIfNeeded(
        git: Git,
        config: SeedSyncConfig,
        message: String,
        paths: List<String>,
    ) {
        paths.forEach { path ->
            git.add().addFilepattern(path).call()
            git.add().setUpdate(true).addFilepattern(path).call()
        }
        if (git.status().call().hasUncommittedChanges()) {
            git.commit().setMessage(message).call()
        }
        pushOrThrow(git, config)
    }

    private fun pushOrThrow(
        git: Git,
        config: SeedSyncConfig,
    ) {
        val provider = credentials(config)
        val push =
            git.push()
                .setRemote("origin")
                .setRefSpecs(RefSpec("refs/heads/$DEFAULT_REMOTE_BRANCH:refs/heads/$DEFAULT_REMOTE_BRANCH"))
        provider?.let { push.setCredentialsProvider(it) }
        ensurePushSucceeded(push.call())
    }

    private fun ensurePushSucceeded(results: Iterable<PushResult>) {
        results.forEach { result ->
            result.remoteUpdates.forEach { update ->
                if (
                    update.status != RemoteRefUpdate.Status.OK &&
                    update.status != RemoteRefUpdate.Status.UP_TO_DATE
                ) {
                    throw IllegalStateException("Git push 失败：${update.status}")
                }
            }
        }
    }

    private fun credentials(config: SeedSyncConfig): UsernamePasswordCredentialsProvider? {
        if (config.gitUsername.isBlank() && config.gitToken.isBlank()) return null
        return UsernamePasswordCredentialsProvider(
            config.gitUsername.ifBlank { "oauth2" },
            config.gitToken,
        )
    }

    private fun buildSubscriptionLabel(uri: Uri): String {
        val query = uri.getQueryParameter("q").orEmpty().replace('+', ' ').trim()
        if (query.isNotBlank()) return query
        val last = uri.lastPathSegment.orEmpty().trim()
        if (last.isNotBlank()) return last
        return uri.host ?: "订阅"
    }

    private fun buildFolderName(label: String, id: String): String {
        val safe =
            label.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifBlank { "subscription" }
                .take(24)
        return "${safe}_$id"
    }
}

internal fun pathToTreeDocId(path: String): String? {
    val normalized = path.trim().trimEnd('/')
    if (normalized.isBlank()) return null
    val primaryRoots = listOf("/storage/emulated/0", "/sdcard", "/mnt/sdcard", "/storage/self/primary")
    for (root in primaryRoots) {
        if (normalized == root) return "primary:"
        if (normalized.startsWith("$root/")) return "primary:" + normalized.removePrefix("$root/")
    }
    val external = Regex("""^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})(?:/(.*))?$""").find(normalized)
    if (external != null) {
        val volume = external.groupValues[1]
        val relative = external.groupValues[2]
        return if (relative.isBlank()) "$volume:" else "$volume:$relative"
    }
    return null
}

internal fun treeDocIdToDisplayPath(docId: String): String {
    val separator = docId.indexOf(':')
    if (separator < 0) return docId
    val volume = docId.substring(0, separator)
    val relative = docId.substring(separator + 1)
    val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
    return if (relative.isBlank()) root else "$root/$relative"
}

internal fun videoFolderInputDisplay(storedUri: String): String {
    if (storedUri.isBlank()) return ""
    val uri = runCatching { Uri.parse(storedUri) }.getOrNull() ?: return storedUri
    val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return storedUri
    return treeDocIdToDisplayPath(docId)
}

internal fun videoNameMatchesQuery(
    videoName: String,
    query: String,
): Boolean {
    val name = SubtitleNameHeuristics.normalize(stripExtension(videoName))
    val tokens = SubtitleNameHeuristics.normalize(query).split(' ').filter { it.isNotBlank() }
    if (name.isBlank() || tokens.isEmpty()) return false
    return tokens.all { token -> name.contains(token) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoDownloadScreen(
    state: VideoDownloadUiState,
    onBack: () -> Unit,
    onAddSubscription: (String) -> Unit,
    onRemoveSubscription: (String) -> Unit,
    onOpenSubscription: (String) -> Unit,
    onBackToList: () -> Unit,
    onPullSubscriptionSync: () -> Unit,
    onPushSubscriptionSync: () -> Unit,
    onRefreshEntries: () -> Unit,
    onDownloadTorrent: (String) -> Unit,
    onOpenTorrent: (String) -> Unit,
    onOpenVideos: (String) -> Unit,
    onRescanVideos: () -> Unit,
    onSaveVideoFolder: (String) -> SaveVideoFolderResult,
    onVideoFolderPicked: (Uri) -> Unit,
    onMatchVideo: (String) -> Unit,
    onOffsetVideo: (String, Long) -> Unit,
    onDeleteVideo: (String) -> Unit,
) {
    var addDialogVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val videoFolderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                persistTreeReadWritePermission(context, it)
                onVideoFolderPicked(it)
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.activeSubscriptionId == null) {
                            "视频下载"
                        } else if (state.videosViewActive) {
                            "${state.activeSubscriptionLabel} · 视频"
                        } else {
                            state.activeSubscriptionLabel
                        },
                    )
                },
                navigationIcon = {
                    TextButton(onClick = {
                        if (state.activeSubscriptionId == null) onBack() else onBackToList()
                    }) {
                        Text(if (state.activeSubscriptionId == null) "关闭" else "返回")
                    }
                },
                actions = {
                    if (state.activeSubscriptionId == null) {
                        TextButton(onClick = onPullSubscriptionSync, enabled = !state.syncingConfig) { Text("Pull") }
                        TextButton(onClick = onPushSubscriptionSync, enabled = !state.syncingConfig) { Text("Push") }
                        TextButton(onClick = { addDialogVisible = true }, enabled = !state.syncingConfig) { Text("添加") }
                        TextButton(onClick = { settingsVisible = true }) { Text("设置") }
                    } else {
                        RefreshEntriesButton(
                            loading = if (state.videosViewActive) state.loadingVideos else state.loadingEntries,
                            failed = if (state.videosViewActive) state.videosLoadFailed else state.entriesRefreshFailed,
                            onRefresh = if (state.videosViewActive) onRescanVideos else onRefreshEntries,
                            confirmMessage =
                                if (state.videosViewActive) {
                                    "是否重新扫描本地视频目录？"
                                } else {
                                    "是否重新拉取最新的条目列表？"
                                },
                        )
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
            if (state.message.isNotBlank()) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.activeSubscriptionId == null) {
                SubscriptionList(
                    subscriptions = state.subscriptions,
                    onOpenSubscription = onOpenSubscription,
                    onOpenVideos = onOpenVideos,
                    onRemoveSubscription = onRemoveSubscription,
                )
            } else if (state.videosViewActive) {
                DownloadedVideoList(
                    videos = state.activeVideos,
                    controlsEnabled = !state.deletingVideo,
                    onMatchVideo = onMatchVideo,
                    onOffsetVideo = onOffsetVideo,
                    onDeleteVideo = onDeleteVideo,
                )
            } else {
                TorrentEntryList(
                    entries = state.activeEntries,
                    onDownloadTorrent = onDownloadTorrent,
                    onOpenTorrent = onOpenTorrent,
                )
            }
        }
    }

    if (addDialogVisible) {
        AddSubscriptionDialog(
            onDismiss = { addDialogVisible = false },
            onConfirm = { url ->
                onAddSubscription(url)
                addDialogVisible = false
            },
        )
    }

    if (settingsVisible) {
        VideoFolderSettingsDialog(
            currentLabel = state.videoFolderLabel,
            currentUri = state.videoFolderUri,
            onBrowse = { videoFolderLauncher.launch(null) },
            onSave = onSaveVideoFolder,
            onRequestGrant = { initial -> videoFolderLauncher.launch(initial) },
            onDismiss = { settingsVisible = false },
        )
    }
}

@Composable
private fun SubscriptionList(
    subscriptions: List<VideoSubscriptionItem>,
    onOpenSubscription: (String) -> Unit,
    onOpenVideos: (String) -> Unit,
    onRemoveSubscription: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<VideoSubscriptionItem?>(null) }

    if (subscriptions.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("暂无订阅。")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(subscriptions, key = { it.id }) { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = item.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = if (item.downloadedCount > 0) "本地状态：已下载 ${item.downloadedCount} 个种子" else "本地状态：未下载",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.downloadedCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalIconButton(
                            onClick = { pendingDelete = item },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Text("×")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onOpenSubscription(item.id) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("种子")
                        }
                        Button(
                            onClick = { onOpenVideos(item.id) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("视频")
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("确认删除") },
            text = { Text("确定删除订阅“${target.label}”吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveSubscription(target.id)
                        pendingDelete = null
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun RefreshEntriesButton(
    loading: Boolean,
    failed: Boolean,
    onRefresh: () -> Unit,
    confirmMessage: String,
) {
    var confirmVisible by remember { mutableStateOf(false) }
    val label =
        when {
            loading -> "刷新中..."
            failed -> "刷新失败"
            else -> "刷新"
        }
    TextButton(
        onClick = {
            if (loading || failed) {
                confirmVisible = true
            } else {
                onRefresh()
            }
        },
    ) {
        Text(label)
    }
    if (confirmVisible) {
        AlertDialog(
            onDismissRequest = { confirmVisible = false },
            title = { Text("刷新") },
            text = { Text(confirmMessage) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmVisible = false
                        onRefresh()
                    },
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmVisible = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun TorrentEntryList(
    entries: List<TorrentEntryItem>,
    onDownloadTorrent: (String) -> Unit,
    onOpenTorrent: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(entries, key = { it.id }) { item ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta =
                        listOfNotNull(
                            item.sizeText.takeIf { it.isNotBlank() }?.let { "大小: $it" },
                            item.uploadText.takeIf { it.isNotBlank() }?.let { "时间: $it" },
                        ).joinToString(" | ")
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = if (item.localFilePath == null) "本地：未下载" else "本地：已下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.localFilePath == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onDownloadTorrent(item.id) },
                            enabled = !item.downloading,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (item.downloading) "下载中..." else "下载种子")
                        }
                        Button(
                            onClick = { item.localFilePath?.let(onOpenTorrent) },
                            enabled = item.localFilePath != null && !item.downloading,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("打开种子")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadedVideoList(
    videos: List<VideoItem>,
    controlsEnabled: Boolean,
    onMatchVideo: (String) -> Unit,
    onOffsetVideo: (String, Long) -> Unit,
    onDeleteVideo: (String) -> Unit,
) {
    var offsetTarget by remember { mutableStateOf<VideoItem?>(null) }
    var deleteTarget by remember { mutableStateOf<VideoItem?>(null) }
    val context = LocalContext.current

    if (videos.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("没有匹配到视频。")
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(videos, key = { it.id }) { item ->
                VideoRow(
                    item = item,
                    onMatchSubtitle = { onMatchVideo(item.id) },
                    onOffsetSubtitle = { offsetTarget = item },
                    onPlayVideo = { playVideoWithMpv(context, item.uri, item.folderUri, item.title) },
                    onDeleteVideo = { deleteTarget = item },
                    controlsEnabled = controlsEnabled,
                )
            }
        }
    }

    offsetTarget?.let { target ->
        SubtitleOffsetDialog(
            title = "字幕偏移",
            description = "输入偏移量（毫秒，可为负数）后，将修改该条目对应字幕的全部时间。",
            onDismiss = { offsetTarget = null },
            onConfirm = { offset ->
                onOffsetVideo(target.id, offset)
                offsetTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        DeleteVideoConfirmDialog(
            target = target,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                onDeleteVideo(target.id)
                deleteTarget = null
            },
        )
    }
}

@Composable
private fun VideoFolderSettingsDialog(
    currentLabel: String,
    currentUri: String,
    onBrowse: () -> Unit,
    onSave: (String) -> SaveVideoFolderResult,
    onRequestGrant: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember(currentUri) { mutableStateOf(videoFolderInputDisplay(currentUri)) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("种子下载设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "关联本地视频文件夹后，点击订阅的「视频」可查看与条目同名的已下载视频。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (currentLabel.isNotBlank()) {
                    Text(
                        text = "当前关联：$currentLabel",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        errorText = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("文件夹路径") },
                    placeholder = { Text("/storage/emulated/0/Download 或文件夹 URI") },
                    maxLines = 3,
                )
                TextButton(onClick = onBrowse) {
                    Text("浏览选择文件夹")
                }
                errorText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (val result = onSave(input)) {
                        SaveVideoFolderResult.Saved,
                        SaveVideoFolderResult.Cleared,
                        -> onDismiss()
                        is SaveVideoFolderResult.Invalid -> errorText = result.message
                        is SaveVideoFolderResult.NeedGrant -> {
                            onRequestGrant(result.initialUri)
                            onDismiss()
                        }
                    }
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加视频订阅") },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("订阅链接") },
                placeholder = { Text("https://nyaa.si/?f=0&c=0_0&q=...") },
                maxLines = 4,
            )
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = { onConfirm(input) }) { Text("添加") }
            }
        },
    )
}

private fun openTorrentFile(
    context: Context,
    filePath: String,
): String? {
    val file = File(filePath)
    if (!file.exists()) return "打开失败：文件不存在。"

    val uri =
        runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }.getOrElse { error ->
            return "打开失败：${error.message ?: "无法生成文件 URI"}"
        }

    val primaryIntent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/x-bittorrent")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    return runCatching {
        context.startActivity(primaryIntent)
        null
    }.recoverCatching { error ->
        if (error !is ActivityNotFoundException) throw error
        val fallback =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(fallback)
        null
    }.getOrElse { error ->
        "打开失败：${error.message ?: "没有可用应用"}"
    }
}
