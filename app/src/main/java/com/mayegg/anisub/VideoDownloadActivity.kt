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

data class VideoDownloadUiState(
    val subscriptions: List<VideoSubscriptionItem> = emptyList(),
    val activeSubscriptionId: String? = null,
    val activeSubscriptionLabel: String = "",
    val activeEntries: List<TorrentEntryItem> = emptyList(),
    val loadingEntries: Boolean = false,
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
        private val ID_PATTERN = Regex("""/(view|download)/(\d+)""")
        private val ROW_PATTERN = Regex("""(?is)<tr[^>]*>(.*?)</tr>""")
        private val TD_PATTERN = Regex("""(?is)<td[^>]*>(.*?)</td>""")
        private val VIEW_LINK_PATTERN = Regex("""(?is)<a[^>]*href\s*=\s*["']([^"']*/view/\d+[^"']*)["'][^>]*>(.*?)</a>""")
        private val DOWNLOAD_LINK_PATTERN = Regex("""(?is)<a[^>]*href\s*=\s*["']([^"']*/download/\d+[^"']*)["']""")
    }

    private val appContext = application.applicationContext
    private val prefs = application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
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
                        TextButton(onClick = { addDialogVisible = true }) { Text("添加") }
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
                            onClick = { onRemoveSubscription(item.id) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("删除")
                        }
                    }
                }
            }
        }
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
