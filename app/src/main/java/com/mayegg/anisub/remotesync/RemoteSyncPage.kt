package com.mayegg.anisub.remotesync

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.json.JSONArray
import org.json.JSONObject

private const val DEFAULT_REMOTE_URL = "https://gitee.com/mayeggx/pic4nisub.git"
private const val DEFAULT_BRANCH = "main"
private const val ENTRIES_DIR = "entries"
private const val ENTRY_META_FILE = "entry.json"
private const val DEFAULT_IMAGE_SCALE_PERCENT = 50
private const val DEFAULT_IMAGE_JPEG_QUALITY = 70

data class RemoteSyncConfig(
    val remoteUrl: String = DEFAULT_REMOTE_URL,
    val gitUsername: String = "",
    val gitToken: String = "",
    val commitUserName: String = "Anisubroid Remote Sync",
    val commitUserEmail: String = "anisubroid@local",
    val imageScalePercent: Int = DEFAULT_IMAGE_SCALE_PERCENT,
    val imageJpegQuality: Int = DEFAULT_IMAGE_JPEG_QUALITY,
)

data class EntryBinding(
    val id: String,
    val displayName: String,
    val deviceId: String,
    val deviceName: String,
    val folderUri: String,
    val folderLabel: String,
    val repoPath: String,
    val updatedAt: Long,
)

data class RepoEntryMeta(
    val id: String,
    val displayName: String,
    val deviceId: String,
    val deviceName: String,
    val repoPath: String,
    val updatedAt: Long,
)

data class SyncEntryUi(
    val id: String,
    val displayName: String,
    val deviceId: String,
    val deviceName: String,
    val repoPath: String,
    val updatedAt: Long,
    val folderUri: String?,
    val folderLabel: String?,
    val folderFileCount: Int? = null,
)

data class RemoteSyncUiState(
    val config: RemoteSyncConfig = RemoteSyncConfig(),
    val entries: List<SyncEntryUi> = emptyList(),
    val loading: Boolean = false,
    val statusMessage: String = "请先填写 Git 配置，然后创建条目并执行 Push。",
    val deviceId: String = "",
    val deviceName: String = "",
    val repoPath: String = "",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RemoteSyncPage(
    onOpenWordNoteForFolder: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel: RemoteSyncViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var scaleDraft by remember(state.config.imageScalePercent) { mutableStateOf(state.config.imageScalePercent.toString()) }
    var pendingName by rememberSaveable { mutableStateOf("") }
    var qualityDraft by remember(state.config.imageJpegQuality) { mutableStateOf(state.config.imageJpegQuality.toString()) }
    var showDeleteDialogFor by remember { mutableStateOf<SyncEntryUi?>(null) }
    var showClearDialogFor by remember { mutableStateOf<SyncEntryUi?>(null) }
    var showGitConfigDialog by remember { mutableStateOf(false) }
    var showCreateEntryDialog by remember { mutableStateOf(false) }
    var showImageQualityDialog by remember { mutableStateOf(false) }
    var configDraft by remember(state.config) { mutableStateOf(state.config) }

    val folderLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            persistTreePermission(context, uri)
            val label = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.toString()
            viewModel.createEntry(
                displayName = pendingName,
                folderUri = uri.toString(),
                folderLabel = label,
            )
            pendingName = ""
            showCreateEntryDialog = false
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("远程同步") },
                actions = {
                    TextButton(
                        onClick = {
                            scaleDraft = state.config.imageScalePercent.toString()
                            qualityDraft = state.config.imageJpegQuality.toString()
                            showImageQualityDialog = true
                        },
                        enabled = !state.loading,
                    ) { Text("图片质量") }
                    TextButton(
                        onClick = {
                            configDraft = state.config
                            showGitConfigDialog = true
                        },
                        enabled = !state.loading,
                    ) { Text("Git配置") }
                    TextButton(
                        onClick = { showCreateEntryDialog = true },
                        enabled = !state.loading,
                    ) { Text("新建条目") }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("仓库 A: ${state.repoPath}", style = MaterialTheme.typography.bodySmall)
                        Text("当前设备: ${state.deviceName} (${state.deviceId})", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "图片压缩: 缩放 ${state.config.imageScalePercent}% + JPEG 质量 ${state.config.imageJpegQuality}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = viewModel::pull, enabled = !state.loading) { Text("Pull") }
                            Button(onClick = viewModel::refreshEntries, enabled = !state.loading) { Text("刷新列表") }
                        }
                        if (state.loading) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(modifier = Modifier.padding(top = 2.dp))
                                Text("正在执行 Git 操作...")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    text = "状态: ${state.statusMessage}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            items(state.entries, key = { it.id }) { entry ->
                val canPush = shouldShowPushButton(entry, state.deviceId)
                val hasLocalFolder = !entry.folderUri.isNullOrBlank()
                Card {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(entry.displayName, style = MaterialTheme.typography.titleMedium)
                        Text("设备: ${entry.deviceName} (${entry.deviceId})", style = MaterialTheme.typography.bodySmall)
                        Text("仓库路径: ${entry.repoPath}", style = MaterialTheme.typography.bodySmall)
                        Text("本地绑定: ${entry.folderLabel ?: "无"}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "当前文件夹文件数量: ${entry.folderFileCount?.toString() ?: "-"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (canPush) {
                                Button(
                                    onClick = { viewModel.push(entry.id) },
                                    enabled = !state.loading,
                                ) { Text("Push") }
                            }
                            Button(
                                onClick = { showClearDialogFor = entry },
                                enabled = !state.loading,
                            ) { Text("清空") }
                            if (hasLocalFolder) {
                                Button(
                                    onClick = { onOpenWordNoteForFolder(entry.folderUri.orEmpty()) },
                                    enabled = !state.loading,
                                ) { Text("摘记") }
                            }
                            Button(
                                onClick = { showDeleteDialogFor = entry },
                                enabled = !state.loading,
                            ) { Text("删除") }
                        }
                    }
                }
            }
        }
    }

    showDeleteDialogFor?.let { entry ->
        AlertDialog(
            onDismissRequest = { showDeleteDialogFor = null },
            title = { Text("删除条目") },
            text = { Text("确认删除 `${entry.displayName}` 并 push 到远程仓库吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialogFor = null
                        viewModel.delete(entry.id)
                    },
                ) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialogFor = null }) { Text("取消") } },
        )
    }

    showClearDialogFor?.let { entry ->
        val clearTargetText =
            if (entry.folderUri.isNullOrBlank()) {
                "仓库子文件夹"
            } else {
                "仓库子文件夹和本地绑定文件夹内容"
            }
        AlertDialog(
            onDismissRequest = { showClearDialogFor = null },
            title = { Text("清空条目") },
            text = { Text("确认清空 `${entry.displayName}` 对应的$clearTargetText，并 push 到远程吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialogFor = null
                        viewModel.clear(entry.id)
                    },
                ) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { showClearDialogFor = null }) { Text("取消") } },
        )
    }

    if (showGitConfigDialog) {
        AlertDialog(
            onDismissRequest = { showGitConfigDialog = false },
            title = { Text("Git 配置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = configDraft.remoteUrl,
                        onValueChange = { configDraft = configDraft.copy(remoteUrl = it) },
                        label = { Text("远程仓库 URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = configDraft.gitUsername,
                        onValueChange = { configDraft = configDraft.copy(gitUsername = it) },
                        label = { Text("Git 用户名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = configDraft.gitToken,
                        onValueChange = { configDraft = configDraft.copy(gitToken = it) },
                        label = { Text("Git Token/PAT") },
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = configDraft.commitUserName,
                        onValueChange = { configDraft = configDraft.copy(commitUserName = it) },
                        label = { Text("提交作者名") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = configDraft.commitUserEmail,
                        onValueChange = { configDraft = configDraft.copy(commitUserEmail = it) },
                        label = { Text("提交作者邮箱") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showGitConfigDialog = false
                        viewModel.saveConfig(configDraft)
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showGitConfigDialog = false }) { Text("取消") }
            },
        )
    }

    if (showCreateEntryDialog) {
        AlertDialog(
            onDismissRequest = { showCreateEntryDialog = false },
            title = { Text("新建条目") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("绑定当前设备 + 文件夹 B")
                    OutlinedTextField(
                        value = pendingName,
                        onValueChange = { pendingName = it },
                        label = { Text("条目名称（可空）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { folderLauncher.launch(null) }) {
                    Text("选择文件夹B")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateEntryDialog = false }) { Text("取消") }
            },
        )
    }

    if (showImageQualityDialog) {
        AlertDialog(
            onDismissRequest = { showImageQualityDialog = false },
            title = { Text("图片质量") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Push 前会把图片缩放并转为 JPG。")
                    OutlinedTextField(
                        value = scaleDraft,
                        onValueChange = { scaleDraft = it.filter { ch -> ch.isDigit() }.take(3) },
                        label = { Text("缩放比 (1-100)%") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = qualityDraft,
                        onValueChange = { qualityDraft = it.filter { ch -> ch.isDigit() }.take(3) },
                        label = { Text("JPG 质量 (1-100)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val scale = scaleDraft.toIntOrNull()?.coerceIn(1, 100) ?: DEFAULT_IMAGE_SCALE_PERCENT
                        val quality = qualityDraft.toIntOrNull()?.coerceIn(1, 100) ?: DEFAULT_IMAGE_JPEG_QUALITY
                        viewModel.updateImageCompression(scalePercent = scale, jpegQuality = quality)
                        showImageQualityDialog = false
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showImageQualityDialog = false }) { Text("取消") }
            },
        )
    }
}

class RemoteSyncViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val store = RemoteSyncStore(appContext)
    private val gitService = RemoteSyncGitService(appContext)
    private val deviceInfo = resolveDeviceInfo(appContext)

    private val _uiState =
        MutableStateFlow(
            RemoteSyncUiState(
                config = store.loadConfig(),
                deviceId = deviceInfo.id,
                deviceName = deviceInfo.name,
                repoPath = gitService.repoDir.absolutePath,
            ),
        )
    val uiState: StateFlow<RemoteSyncUiState> = _uiState.asStateFlow()

    init {
        refreshEntries()
    }

    fun saveConfig(config: RemoteSyncConfig) {
        val normalized = normalizeConfig(config)
        store.saveConfig(normalized)
        _uiState.update {
            it.copy(
                config = normalized,
                statusMessage = "配置已保存。",
            )
        }
    }

    fun updateImageCompression(
        scalePercent: Int,
        jpegQuality: Int,
    ) {
        val current = store.loadConfig()
        saveConfig(
            current.copy(
                imageScalePercent = scalePercent.coerceIn(1, 100),
                imageJpegQuality = jpegQuality.coerceIn(1, 100),
            ),
        )
    }

    fun refreshEntries() {
        runTask {
            refreshEntryListInternal()
            "已刷新条目列表。"
        }
    }

    fun createEntry(
        displayName: String,
        folderUri: String,
        folderLabel: String,
    ) {
        val cleanName = displayName.trim().ifBlank { "entry-${System.currentTimeMillis()}" }
        val slug = sanitizePathSegment(cleanName)
        val id = "${deviceInfo.id}-$slug"
        val repoPath = "$ENTRIES_DIR/${sanitizePathSegment(deviceInfo.id)}/$slug"
        val binding =
            EntryBinding(
                id = id,
                displayName = cleanName,
                deviceId = deviceInfo.id,
                deviceName = deviceInfo.name,
                folderUri = folderUri,
                folderLabel = folderLabel,
                repoPath = repoPath,
                updatedAt = System.currentTimeMillis(),
            )
        val updated =
            store
                .loadBindings()
                .filterNot { it.id == id } + binding
        store.saveBindings(updated)
        _uiState.update { it.copy(statusMessage = "已创建条目：$cleanName") }
        refreshEntries()
    }

    fun pull() {
        runTask {
            val config = store.loadConfig()
            gitService.pull(config)
            refreshEntryListInternal()
            "Pull 完成。"
        }
    }

    fun push(entryId: String) {
        runTask {
            val config = store.loadConfig()
            val entry = requirePushableBinding(entryId)
            val stats = gitService.pushEntry(config = config, entry = entry)
            refreshEntryListInternal()
            "Push 完成：${entry.displayName}，复制 ${stats.copiedFiles} 个文件，压缩 ${stats.compressedImages} 张图片，跳过重名 ${stats.skippedFiles} 个文件。"
        }
    }

    fun clear(entryId: String) {
        runTask {
            val config = store.loadConfig()
            val localBindings = store.loadBindings()
            val knownEntries = mergeEntries(localBindings, gitService.scanRemoteEntries(), emptyMap())
            val entry = knownEntries.firstOrNull { it.id == entryId } ?: throw IllegalStateException("条目不存在。")
            val localBinding = localBindings.firstOrNull { it.id == entryId }
            val deletedFiles =
                localBinding
                    ?.folderUri
                    ?.takeIf { it.isNotBlank() }
                    ?.let { clearFolderTreeContents(appContext, it) }
            gitService.clearEntry(
                config = config,
                entry =
                    RepoEntryMeta(
                        id = entry.id,
                        displayName = entry.displayName,
                        deviceId = entry.deviceId,
                        deviceName = entry.deviceName,
                        repoPath = entry.repoPath,
                        updatedAt = entry.updatedAt,
                    ),
            )
            refreshEntryListInternal()
            val localMessage =
                if (deletedFiles == null) {
                    "无本地绑定，已仅清空远端。"
                } else {
                    "本地删除 $deletedFiles 个文件，远端已推送。"
                }
            "清空完成：${entry.displayName}，$localMessage"
        }
    }

    fun delete(entryId: String) {
        runTask {
            val config = store.loadConfig()
            val localBindings = store.loadBindings()
            val knownEntries = mergeEntries(localBindings, gitService.scanRemoteEntries(), emptyMap())
            val entry = knownEntries.firstOrNull { it.id == entryId } ?: throw IllegalStateException("条目不存在。")
            gitService.deleteEntry(config, entry.repoPath)
            store.saveBindings(localBindings.filterNot { it.id == entryId })
            refreshEntryListInternal()
            "删除完成：${entry.displayName}"
        }
    }

    private fun runTask(
        fallbackMessage: String? = null,
        task: suspend () -> String,
    ) {
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val message =
                runCatching { task() }
                    .getOrElse { "失败：${it.message ?: "未知错误"}" }
            _uiState.update {
                it.copy(
                    loading = false,
                    statusMessage = if (message.isBlank()) fallbackMessage ?: it.statusMessage else message,
                )
            }
        }
    }

    private fun refreshEntryListInternal() {
        val localBindings = store.loadBindings()
        val remoteEntries = runCatching { gitService.scanRemoteEntries() }.getOrDefault(emptyList())
        val folderFileCounts =
            localBindings.associate { binding ->
                binding.id to countFilesInFolderTree(appContext, binding.folderUri)
            }
        val merged = mergeEntries(localBindings, remoteEntries, folderFileCounts)
        _uiState.update { it.copy(entries = merged, config = store.loadConfig()) }
    }

    private fun requirePushableBinding(entryId: String): EntryBinding {
        val entry = store.loadBindings().firstOrNull { it.id == entryId }
            ?: throw IllegalStateException("找不到本地绑定条目。")
        if (entry.deviceId != deviceInfo.id || entry.folderUri.isBlank()) {
            throw IllegalStateException("该条目不支持当前设备执行 Push。")
        }
        return entry
    }

    private fun normalizeConfig(config: RemoteSyncConfig): RemoteSyncConfig =
        config.copy(
            imageScalePercent = config.imageScalePercent.coerceIn(1, 100),
            imageJpegQuality = config.imageJpegQuality.coerceIn(1, 100),
        )
}

private class RemoteSyncStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("remote_sync_store", Context.MODE_PRIVATE)

    fun loadConfig(): RemoteSyncConfig {
        val raw = prefs.getString("config", null).orEmpty()
        if (raw.isBlank()) return RemoteSyncConfig()
        return runCatching {
            val json = JSONObject(raw)
            RemoteSyncConfig(
                remoteUrl = json.optString("remoteUrl", DEFAULT_REMOTE_URL),
                gitUsername = json.optString("gitUsername", ""),
                gitToken = json.optString("gitToken", ""),
                commitUserName = json.optString("commitUserName", "Anisubroid Remote Sync"),
                commitUserEmail = json.optString("commitUserEmail", "anisubroid@local"),
                imageScalePercent = json.optInt("imageScalePercent", DEFAULT_IMAGE_SCALE_PERCENT),
                imageJpegQuality = json.optInt("imageJpegQuality", DEFAULT_IMAGE_JPEG_QUALITY),
            )
        }.getOrDefault(RemoteSyncConfig())
    }

    fun saveConfig(config: RemoteSyncConfig) {
        val json =
            JSONObject()
                .put("remoteUrl", config.remoteUrl)
                .put("gitUsername", config.gitUsername)
                .put("gitToken", config.gitToken)
                .put("commitUserName", config.commitUserName)
                .put("commitUserEmail", config.commitUserEmail)
                .put("imageScalePercent", config.imageScalePercent.coerceIn(1, 100))
                .put("imageJpegQuality", config.imageJpegQuality.coerceIn(1, 100))
        prefs.edit().putString("config", json.toString()).apply()
    }

    fun loadBindings(): List<EntryBinding> {
        val raw = prefs.getString("bindings", null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (index in 0 until arr.length()) {
                    val obj = arr.getJSONObject(index)
                    add(
                        EntryBinding(
                            id = obj.optString("id"),
                            displayName = obj.optString("displayName"),
                            deviceId = obj.optString("deviceId"),
                            deviceName = obj.optString("deviceName"),
                            folderUri = obj.optString("folderUri"),
                            folderLabel = obj.optString("folderLabel"),
                            repoPath = obj.optString("repoPath"),
                            updatedAt = obj.optLong("updatedAt", 0L),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveBindings(bindings: List<EntryBinding>) {
        val arr = JSONArray()
        bindings.forEach { binding ->
            arr.put(
                JSONObject()
                    .put("id", binding.id)
                    .put("displayName", binding.displayName)
                    .put("deviceId", binding.deviceId)
                    .put("deviceName", binding.deviceName)
                    .put("folderUri", binding.folderUri)
                    .put("folderLabel", binding.folderLabel)
                    .put("repoPath", binding.repoPath)
                    .put("updatedAt", binding.updatedAt),
            )
        }
        prefs.edit().putString("bindings", arr.toString()).apply()
    }
}

private class RemoteSyncGitService(
    private val context: Context,
) {
    val repoDir: File = File(context.filesDir, "remote-sync/repo-a")

    fun pull(config: RemoteSyncConfig) {
        val git = openOrCreateRepository(config)
        try {
            safePull(git, config)
        } finally {
            git.close()
        }
    }

    fun pushEntry(
        config: RemoteSyncConfig,
        entry: EntryBinding,
    ): CopyStats {
        val git = openOrCreateRepository(config)
        try {
            safePull(git, config)
            val targetDir = File(repoDir, entry.repoPath)
            val stats =
                copyFolderTree(
                    context = context,
                    resolver = context.contentResolver,
                    treeUri = Uri.parse(entry.folderUri),
                    destinationDir = targetDir,
                    setting =
                        ImageCompressionSetting(
                            scalePercent = config.imageScalePercent.coerceIn(1, 100),
                            jpegQuality = config.imageJpegQuality.coerceIn(1, 100),
                        ),
                )
            writeEntryMetadata(
                targetDir = targetDir,
                entry =
                    RepoEntryMeta(
                        id = entry.id,
                        displayName = entry.displayName,
                        deviceId = entry.deviceId,
                        deviceName = entry.deviceName,
                        repoPath = entry.repoPath,
                        updatedAt = System.currentTimeMillis(),
                    ),
            )
            commitAndPushIfNeeded(
                git = git,
                config = config,
                message = "sync: push ${entry.displayName} (${entry.deviceName})",
                paths = listOf(entry.repoPath),
            )
            return stats
        } finally {
            git.close()
        }
    }

    fun clearEntry(
        config: RemoteSyncConfig,
        entry: RepoEntryMeta,
    ) {
        val git = openOrCreateRepository(config)
        try {
            safePull(git, config)
            val targetDir = File(repoDir, entry.repoPath)
            clearDirectoryContents(targetDir)
            writeEntryMetadata(
                targetDir = targetDir,
                entry =
                    RepoEntryMeta(
                        id = entry.id,
                        displayName = entry.displayName,
                        deviceId = entry.deviceId,
                        deviceName = entry.deviceName,
                        repoPath = entry.repoPath,
                        updatedAt = System.currentTimeMillis(),
                    ),
            )
            commitAndPushIfNeeded(
                git = git,
                config = config,
                message = "sync: clear ${entry.displayName} (${entry.deviceName})",
                paths = listOf(entry.repoPath),
            )
        } finally {
            git.close()
        }
    }

    fun deleteEntry(
        config: RemoteSyncConfig,
        repoPath: String,
    ) {
        val git = openOrCreateRepository(config)
        try {
            safePull(git, config)
            val targetDir = File(repoDir, repoPath)
            if (targetDir.exists()) {
                targetDir.deleteRecursively()
            }
            commitAndPushIfNeeded(
                git = git,
                config = config,
                message = "sync: delete $repoPath",
                paths = listOf(repoPath),
            )
        } finally {
            git.close()
        }
    }

    fun scanRemoteEntries(): List<RepoEntryMeta> {
        val entriesRoot = File(repoDir, ENTRIES_DIR)
        if (!entriesRoot.exists()) return emptyList()
        return entriesRoot
            .walkTopDown()
            .filter { it.isFile && it.name == ENTRY_META_FILE }
            .mapNotNull { file ->
                runCatching {
                    val json = JSONObject(file.readText(Charsets.UTF_8))
                    RepoEntryMeta(
                        id = json.optString("id"),
                        displayName = json.optString("displayName"),
                        deviceId = json.optString("deviceId"),
                        deviceName = json.optString("deviceName"),
                        repoPath = json.optString("repoPath"),
                        updatedAt = json.optLong("updatedAt", file.lastModified()),
                    )
                }.getOrNull()
            }.toList()
    }

    private fun openOrCreateRepository(config: RemoteSyncConfig): Git {
        repoDir.mkdirs()
        val dotGit = File(repoDir, ".git")
        if (dotGit.exists()) {
            val git = Git.open(repoDir)
            configureRepository(git, config)
            return git
        }

        if (repoDir.listFiles().isNullOrEmpty()) {
            runCatching {
                val cloned =
                    Git.cloneRepository()
                        .setURI(config.remoteUrl)
                        .setDirectory(repoDir)
                        .setCredentialsProvider(credentials(config))
                        .call()
                configureRepository(cloned, config)
                return cloned
            }
        }

        val git =
            Git.init()
                .setDirectory(repoDir)
                .setInitialBranch(DEFAULT_BRANCH)
                .call()
        configureRepository(git, config)
        return git
    }

    private fun configureRepository(
        git: Git,
        config: RemoteSyncConfig,
    ) {
        val repoConfig = git.repository.config
        repoConfig.setString("remote", "origin", "url", config.remoteUrl)
        repoConfig.setString("branch", DEFAULT_BRANCH, "remote", "origin")
        repoConfig.setString("branch", DEFAULT_BRANCH, "merge", "refs/heads/$DEFAULT_BRANCH")
        repoConfig.setString("user", null, "name", config.commitUserName.ifBlank { "Anisubroid Remote Sync" })
        repoConfig.setString("user", null, "email", config.commitUserEmail.ifBlank { "anisubroid@local" })
        repoConfig.save()
    }

    private fun safePull(
        git: Git,
        config: RemoteSyncConfig,
    ) {
        runCatching {
            git.pull()
                .setRemote("origin")
                .setRemoteBranchName(DEFAULT_BRANCH)
                .setRebase(true)
                .setCredentialsProvider(credentials(config))
                .call()
        }
    }

    private fun commitAndPushIfNeeded(
        git: Git,
        config: RemoteSyncConfig,
        message: String,
        paths: List<String>,
    ) {
        paths.forEach { path ->
            git.add().addFilepattern(path).call()
            git.add().setUpdate(true).addFilepattern(path).call()
        }
        if (!git.status().call().hasUncommittedChanges()) return

        git.commit().setMessage(message).call()
        try {
            git.push()
                .setRemote("origin")
                .setCredentialsProvider(credentials(config))
                .setRefSpecs(RefSpec("refs/heads/$DEFAULT_BRANCH:refs/heads/$DEFAULT_BRANCH"))
                .call()
        } catch (_: TransportException) {
            safePull(git, config)
            git.push()
                .setRemote("origin")
                .setCredentialsProvider(credentials(config))
                .setRefSpecs(RefSpec("refs/heads/$DEFAULT_BRANCH:refs/heads/$DEFAULT_BRANCH"))
                .call()
        }
    }

    private fun writeEntryMetadata(
        targetDir: File,
        entry: RepoEntryMeta,
    ) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val metaFile = File(targetDir, ENTRY_META_FILE)
        val json =
            JSONObject()
                .put("id", entry.id)
                .put("displayName", entry.displayName)
                .put("deviceId", entry.deviceId)
                .put("deviceName", entry.deviceName)
                .put("repoPath", entry.repoPath)
                .put("updatedAt", entry.updatedAt)
        metaFile.writeText(json.toString(2), Charsets.UTF_8)
    }

    private fun clearDirectoryContents(directory: File) {
        if (!directory.exists()) {
            directory.mkdirs()
            return
        }
        directory.listFiles().orEmpty().forEach { child ->
            child.deleteRecursively()
        }
    }
}

private fun mergeEntries(
    localBindings: List<EntryBinding>,
    remoteEntries: List<RepoEntryMeta>,
    folderFileCounts: Map<String, Int?>,
): List<SyncEntryUi> {
    val localById = localBindings.associateBy { it.id }
    val merged = mutableListOf<SyncEntryUi>()
    val knownIds = mutableSetOf<String>()

    remoteEntries.forEach { remote ->
        knownIds += remote.id
        val local = localById[remote.id]
        merged +=
            SyncEntryUi(
                id = remote.id,
                displayName = remote.displayName,
                deviceId = remote.deviceId,
                deviceName = remote.deviceName,
                repoPath = remote.repoPath,
                updatedAt = max(remote.updatedAt, local?.updatedAt ?: 0L),
                folderUri = local?.folderUri,
                folderLabel = local?.folderLabel,
                folderFileCount = folderFileCounts[remote.id],
            )
    }

    localBindings
        .filterNot { it.id in knownIds }
        .forEach { local ->
            merged +=
                SyncEntryUi(
                    id = local.id,
                    displayName = local.displayName,
                    deviceId = local.deviceId,
                    deviceName = local.deviceName,
                    repoPath = local.repoPath,
                    updatedAt = local.updatedAt,
                    folderUri = local.folderUri,
                    folderLabel = local.folderLabel,
                    folderFileCount = folderFileCounts[local.id],
                )
        }

    return merged.sortedWith(compareByDescending<SyncEntryUi> { it.updatedAt }.thenBy { it.displayName.lowercase(Locale.ROOT) })
}

internal fun shouldShowPushButton(
    entry: SyncEntryUi,
    currentDeviceId: String,
): Boolean = entry.deviceId == currentDeviceId && !entry.folderUri.isNullOrBlank()

private data class DeviceInfo(
    val id: String,
    val name: String,
)

private data class ImageCompressionSetting(
    val scalePercent: Int,
    val jpegQuality: Int,
)

private data class CopyStats(
    val copiedFiles: Int = 0,
    val compressedImages: Int = 0,
    val skippedFiles: Int = 0,
) {
    operator fun plus(other: CopyStats): CopyStats =
        CopyStats(
            copiedFiles = copiedFiles + other.copiedFiles,
            compressedImages = compressedImages + other.compressedImages,
            skippedFiles = skippedFiles + other.skippedFiles,
        )
}

private fun resolveDeviceInfo(context: Context): DeviceInfo {
    val rawId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    val fallback = "${Build.MANUFACTURER}-${Build.MODEL}-${Build.VERSION.SDK_INT}"
    val id = sanitizePathSegment(if (rawId.isBlank()) fallback else rawId.lowercase(Locale.ROOT))
    val name =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")
            .ifBlank { "Android Device" }
    return DeviceInfo(id = id, name = name)
}

internal fun sanitizePathSegment(raw: String): String {
    val cleaned =
        raw
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    return cleaned.ifBlank { "entry" }
}

private fun persistTreePermission(
    context: Context,
    uri: Uri,
) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    try {
        context.contentResolver.takePersistableUriPermission(uri, flags)
    } catch (_: SecurityException) {
    }
}

private fun countFilesInFolderTree(
    context: Context,
    folderUri: String,
): Int? {
    val treeUri = runCatching { Uri.parse(folderUri) }.getOrNull() ?: return null
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
    if (!root.exists() || !root.isDirectory) return null
    return runCatching { countFilesRecursively(root) }.getOrNull()
}

private fun countFilesRecursively(directory: DocumentFile): Int {
    var total = 0
    directory.listFiles().forEach { child ->
        when {
            child.isDirectory -> total += countFilesRecursively(child)
            child.isFile -> total += 1
        }
    }
    return total
}

private fun clearFolderTreeContents(
    context: Context,
    folderUri: String,
): Int {
    val treeUri = runCatching { Uri.parse(folderUri) }.getOrNull() ?: throw IllegalStateException("本地绑定路径无效。")
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: throw IllegalStateException("无法访问本地绑定文件夹。")
    if (!root.exists() || !root.isDirectory) throw IllegalStateException("本地绑定路径不是文件夹。")
    return deleteDocumentChildren(root)
}

private fun deleteDocumentChildren(directory: DocumentFile): Int {
    var deletedFileCount = 0
    directory.listFiles().forEach { child ->
        when {
            child.isDirectory -> {
                deletedFileCount += deleteDocumentChildren(child)
                if (!child.delete()) {
                    throw IllegalStateException("无法删除目录: ${child.uri}")
                }
            }
            child.isFile -> {
                if (!child.delete()) {
                    throw IllegalStateException("无法删除文件: ${child.uri}")
                }
                deletedFileCount += 1
            }
            else -> {
                if (!child.delete()) {
                    throw IllegalStateException("无法删除条目: ${child.uri}")
                }
            }
        }
    }
    return deletedFileCount
}

private fun copyFolderTree(
    context: Context,
    resolver: ContentResolver,
    treeUri: Uri,
    destinationDir: File,
    setting: ImageCompressionSetting,
): CopyStats {
    val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalStateException("无法访问所选文件夹。")
    if (!root.isDirectory) throw IllegalStateException("所选路径不是文件夹。")
    return copyDocumentChildren(
        sourceDir = root,
        destinationDir = destinationDir,
        resolver = resolver,
        setting = setting,
    )
}

private fun copyDocumentChildren(
    sourceDir: DocumentFile,
    destinationDir: File,
    resolver: ContentResolver,
    setting: ImageCompressionSetting,
): CopyStats {
    if (!destinationDir.exists() && !destinationDir.mkdirs()) {
        throw IllegalStateException("无法创建目录: ${destinationDir.absolutePath}")
    }

    var stats = CopyStats()
    sourceDir.listFiles().forEach { doc ->
        val name = doc.name ?: return@forEach
        val target = File(destinationDir, name)
        when {
            doc.isDirectory -> {
                stats += copyDocumentChildren(doc, target, resolver, setting)
            }
            doc.isFile -> {
                stats += copySingleFile(doc, target, resolver, setting)
            }
        }
    }
    return stats
}

private fun copySingleFile(
    doc: DocumentFile,
    target: File,
    resolver: ContentResolver,
    setting: ImageCompressionSetting,
): CopyStats {
    val shouldCompress = shouldCompressImage(doc)
    val parentDir = target.parentFile ?: throw IllegalStateException("目标文件路径无效: ${target.absolutePath}")
    val targetFile =
        if (shouldCompress) {
            File(parentDir, buildCompressedImageName(target.name))
        } else {
            target
        }

    if (!targetFile.parentFile.exists()) {
        targetFile.parentFile.mkdirs()
    }

    if (targetFile.exists()) {
        return CopyStats(skippedFiles = 1)
    }

    if (shouldCompress && compressImageToJpeg(doc, targetFile, resolver, setting)) {
        return CopyStats(copiedFiles = 1, compressedImages = 1)
    }

    if (target.exists()) {
        return CopyStats(skippedFiles = 1)
    }

    resolver.openInputStream(doc.uri).use { input ->
        if (input == null) throw IllegalStateException("无法读取文件: ${doc.uri}")
        FileOutputStream(target).use { output -> input.copyTo(output) }
    }
    return CopyStats(copiedFiles = 1, compressedImages = 0)
}

private fun shouldCompressImage(doc: DocumentFile): Boolean {
    val mime = doc.type.orEmpty().lowercase(Locale.ROOT)
    if (mime.startsWith("image/")) return true
    val name = doc.name.orEmpty().lowercase(Locale.ROOT)
    return name.endsWith(".jpg") ||
        name.endsWith(".jpeg") ||
        name.endsWith(".png") ||
        name.endsWith(".webp") ||
        name.endsWith(".gif") ||
        name.endsWith(".bmp")
}

private fun buildCompressedImageName(originalName: String): String {
    val lower = originalName.lowercase(Locale.ROOT)
    return if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
        "${originalName.substringBeforeLast('.', originalName)}.jpg"
    } else {
        "$originalName.jpg"
    }
}

private fun compressImageToJpeg(
    doc: DocumentFile,
    targetFile: File,
    resolver: ContentResolver,
    setting: ImageCompressionSetting,
): Boolean {
    val decoded =
        resolver.openInputStream(doc.uri).use { input ->
            if (input == null) return false
            BitmapFactory.decodeStream(input)
        } ?: return false

    var scaled: Bitmap? = null
    var rgbBitmap: Bitmap? = null

    return try {
        val scale = setting.scalePercent.coerceIn(1, 100)
        val targetWidth = max(1, decoded.width * scale / 100)
        val targetHeight = max(1, decoded.height * scale / 100)
        scaled =
            if (targetWidth == decoded.width && targetHeight == decoded.height) {
                decoded
            } else {
                Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true)
            }

        val outputBitmap =
            if (scaled!!.hasAlpha()) {
                Bitmap.createBitmap(scaled!!.width, scaled!!.height, Bitmap.Config.RGB_565).also { rgb ->
                    val canvas = Canvas(rgb)
                    canvas.drawColor(Color.WHITE)
                    canvas.drawBitmap(scaled!!, 0f, 0f, null)
                    rgbBitmap = rgb
                }
            } else {
                scaled!!
            }

        FileOutputStream(targetFile).use { output ->
            outputBitmap.compress(
                Bitmap.CompressFormat.JPEG,
                setting.jpegQuality.coerceIn(1, 100),
                output,
            )
        }
        true
    } catch (_: Throwable) {
        false
    } finally {
        rgbBitmap?.let {
            if (it != scaled && !it.isRecycled) it.recycle()
        }
        scaled?.let {
            if (it != decoded && !it.isRecycled) it.recycle()
        }
        if (!decoded.isRecycled) decoded.recycle()
    }
}

private fun credentials(config: RemoteSyncConfig): UsernamePasswordCredentialsProvider =
    UsernamePasswordCredentialsProvider(
        config.gitUsername.ifBlank { "oauth2" },
        config.gitToken,
    )
