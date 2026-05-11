package com.mayegg.anisub

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
) {
    var addDialogVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.activeSubscriptionId == null) "" else state.activeSubscriptionLabel) },
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
                    } else {
                        TextButton(onClick = onRefreshEntries) { Text("刷新") }
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

            if (state.activeSubscriptionId == null) {
                SubscriptionList(
                    subscriptions = state.subscriptions,
                    onOpenSubscription = onOpenSubscription,
                    onRemoveSubscription = onRemoveSubscription,
                )
            } else {
                if (state.loadingEntries) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
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
}

data class VideoDownloadUiState(
    val subscriptions: List<VideoSubscriptionItem> = emptyList(),
    val activeSubscriptionId: String? = null,
    val activeSubscriptionLabel: String = "",
    val activeEntries: List<TorrentEntryItem> = emptyList(),
    val loadingEntries: Boolean = false,
    val syncingConfig: Boolean = false,
    val message: String = "请先添加视频订阅链接。",
)

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

    private var subscriptions: List<PersistedSubscription> = emptyList()

    init {
        subscriptions = readSubscriptions()
        _uiState.update {
            it.copy(
                subscriptions = subscriptions.map(::toUiSubscription),
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
        val current = _uiState.value
        val shouldExitDetail = current.activeSubscriptionId == id
        _uiState.update {
            it.copy(
                subscriptions = subscriptions.map(::toUiSubscription),
                activeSubscriptionId = if (shouldExitDetail) null else it.activeSubscriptionId,
                activeSubscriptionLabel = if (shouldExitDetail) "" else it.activeSubscriptionLabel,
                activeEntries = if (shouldExitDetail) emptyList() else it.activeEntries,
                loadingEntries = false,
                message = "已删除订阅：${removed.label}",
            )
        }
    }

    fun openSubscription(id: String) {
        val target = subscriptions.firstOrNull { it.id == id } ?: return
        _uiState.update {
            it.copy(
                activeSubscriptionId = target.id,
                activeSubscriptionLabel = target.label,
                loadingEntries = true,
                activeEntries = emptyList(),
                message = "正在解析订阅：${target.label}",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { fetchEntriesFromSubscription(target.url) }
            result.fold(
                onSuccess = { entries ->
                    val folder = ensureSubscriptionFolder(target)
                    val mapped =
                        entries.map { entry ->
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
                    _uiState.update {
                        it.copy(
                            loadingEntries = false,
                            activeEntries = mapped,
                            message = if (mapped.isEmpty()) "未解析到条目。" else "已解析到 ${mapped.size} 个条目。",
                            subscriptions = subscriptions.map(::toUiSubscription),
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Open subscription failed: ${target.url}", error)
                    _uiState.update {
                        it.copy(
                            loadingEntries = false,
                            activeEntries = emptyList(),
                            message = "解析失败：${error.message ?: "未知错误"}",
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

    fun backToList() {
        _uiState.update {
            it.copy(
                activeSubscriptionId = null,
                activeSubscriptionLabel = "",
                activeEntries = emptyList(),
                loadingEntries = false,
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
) {
    var addDialogVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.activeSubscriptionId == null) "视频下载" else state.activeSubscriptionLabel) },
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
                    } else {
                        TextButton(onClick = onRefreshEntries) { Text("刷新") }
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

            if (state.activeSubscriptionId == null) {
                SubscriptionList(
                    subscriptions = state.subscriptions,
                    onOpenSubscription = onOpenSubscription,
                    onRemoveSubscription = onRemoveSubscription,
                )
            } else {
                if (state.loadingEntries) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
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
}

@Composable
private fun SubscriptionList(
    subscriptions: List<VideoSubscriptionItem>,
    onOpenSubscription: (String) -> Unit,
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onOpenSubscription(item.id) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("查看条目")
                        }
                        TextButton(
                            onClick = { pendingDelete = item },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("删除")
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
