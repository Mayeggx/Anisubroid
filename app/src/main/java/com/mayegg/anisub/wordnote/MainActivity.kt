
package com.mayegg.anisub.wordnote

import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mayegg.anisub.wordnote.ui.PstankidroidTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PstankidroidTheme {
                val viewModel: MainViewModel =
                    viewModel(
                        factory =
                            MainViewModel.factory(
                                AnkiDroidClient(applicationContext),
                                ConfigRepository(applicationContext),
                            ),
                    )
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val logs by AppLogger.entries.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }
                var showStatus by remember { mutableStateOf(false) }
                var showLogs by remember { mutableStateOf(false) }
                var previewUri by remember { mutableStateOf<Uri?>(null) }

                val permissionLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                        viewModel.refreshStatus()
                    }
                val folderLauncher =
                    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                        uri?.let {
                            persistUri(it)
                            val folderLabel = resolveFolderLabel(it)
                            viewModel.setCurrentFolder(it.toString(), folderLabel)
                            val imageUris = collectImageUrisFromTree(it)
                            if (imageUris.isEmpty()) {
                                AppLogger.w("FolderImport", "No images found in $it")
                                viewModel.updateStatusMessage("所选文件夹内没有图片")
                            } else {
                                imageUris.forEach(::persistUri)
                                viewModel.onImagesSelected(imageUris)
                            }
                        }
                    }

                MainScreen(
                    state = state,
                    onOpenSettings = { showSettings = true },
                    onOpenStatus = { showStatus = true },
                    onOpenLogs = { showLogs = true },
                    onPickFolder = { folderLauncher.launch(null) },
                    onPromptModeChange = viewModel::updatePromptMode,
                    onSelectAll = viewModel::selectAll,
                    onSelectNone = viewModel::selectNone,
                    onProcessSelected = viewModel::processSelected,
                    onClearDrafts = {
                        val message = clearCurrentFolderImages(state.currentFolderUri)
                        viewModel.onDraftsCleared(message)
                    },
                    onWordChange = viewModel::updateTargetWord,
                    onSelectChange = viewModel::updateSelected,
                    onProcessSingle = viewModel::processSingle,
                    onPreviewImage = { previewUri = it },
                )

                if (showSettings) {
                    SettingsDialog(
                        initial = state.config,
                        onDismiss = { showSettings = false },
                        onSave = {
                            viewModel.saveConfig(it)
                            showSettings = false
                        },
                    )
                }

                if (showStatus) {
                    StatusDialog(
                        state = state,
                        onDismiss = { showStatus = false },
                        onRefresh = viewModel::refreshStatus,
                        onGrantPermission = { permissionLauncher.launch(AnkiDroidClient.readWritePermission) },
                        onQuery = viewModel::querySimpleData,
                    )
                }

                if (showLogs) {
                    LogDialog(
                        logs = logs,
                        onDismiss = { showLogs = false },
                        onClear = AppLogger::clear,
                    )
                }

                previewUri?.let { uri ->
                    ImagePreviewDialog(uri = uri, onDismiss = { previewUri = null })
                }
            }
        }
    }

    private fun persistUri(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    private fun resolveFolderLabel(uri: Uri): String =
        DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: uri.toString()

    private fun collectImageUrisFromTree(treeUri: Uri): List<Uri> {
        val root = DocumentFile.fromTreeUri(this, treeUri) ?: return emptyList()
        return collectImageFiles(root)
            .sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }
            .map { it.uri }
    }

    private fun collectImageFiles(directory: DocumentFile): List<DocumentFile> =
        directory.listFiles().flatMap { file ->
            when {
                file.isDirectory -> collectImageFiles(file)
                file.isFile && (file.type?.startsWith("image/") == true || isImageByName(file.name)) -> listOf(file)
                else -> emptyList()
            }
        }

    private fun clearCurrentFolderImages(folderUri: String?): String {
        if (folderUri.isNullOrBlank()) return "未选择文件夹，已仅清空列表。"
        val root = DocumentFile.fromTreeUri(this, Uri.parse(folderUri)) ?: return "无法访问当前文件夹，已仅清空列表。"
        val files = collectImageFiles(root)
        var deletedCount = 0
        files.forEach { file ->
            if (file.delete()) deletedCount += 1
        }
        return "已清空列表，并删除当前文件夹中 $deletedCount 张图片。"
    }

    private fun isImageByName(name: String?): Boolean {
        val lower = name?.lowercase(Locale.ROOT) ?: return false
        return lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp") ||
            lower.endsWith(".gif")
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    state: MainUiState,
    onOpenSettings: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenLogs: () -> Unit,
    onPickFolder: () -> Unit,
    onPromptModeChange: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onProcessSelected: () -> Unit,
    onClearDrafts: () -> Unit,
    onWordChange: (String, String) -> Unit,
    onSelectChange: (String, Boolean) -> Unit,
    onProcessSingle: (String) -> Unit,
    onPreviewImage: (Uri) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusChip("已选", "${state.drafts.count { it.selected }} 项")
                        PromptModeChip(
                            selectedMode = state.config.promptMode,
                            onModeSelected = onPromptModeChange,
                        )
                    }
                },
                actions = {
                    ActionsDropdown(
                        state = state,
                        onPickFolder = onPickFolder,
                        onSelectAll = onSelectAll,
                        onSelectNone = onSelectNone,
                        onProcessSelected = onProcessSelected,
                        onClearDrafts = onClearDrafts,
                    )
                    MoreDropdown(
                        onOpenStatus = onOpenStatus,
                        onOpenLogs = onOpenLogs,
                        onOpenSettings = onOpenSettings,
                    )
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
            if (state.loading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            items(state.drafts, key = { it.id }) { draft ->
                DraftRow(
                    draft = draft,
                    loading = state.loading,
                    onWordChange = { onWordChange(draft.id, it) },
                    onSelectedChange = { onSelectChange(draft.id, it) },
                    onCreateClick = { onProcessSingle(draft.id) },
                    onPreviewClick = { onPreviewImage(draft.image.uri) },
                )
            }
        }
    }
}

@Composable
private fun StatusContent(
    state: MainUiState,
    selectedCount: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("运行状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("AnkiDroid: ${state.installed.toStatusText()}")
        Text("Provider: ${state.providerAvailable.toStatusText()}")
        Text("权限: ${state.permissionGranted.toStatusText()}")
        Text("当前文件夹: ${state.currentFolderLabel}")
        Text("当前列表: ${state.drafts.size} 项，已选 $selectedCount 项")
        Text(state.statusMessage, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionsDropdown(
    state: MainUiState,
    onPickFolder: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onProcessSelected: () -> Unit,
    onClearDrafts: () -> Unit,
) {
    val selectedCount = state.drafts.count { it.selected }
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { expanded = true }) { Text("操作") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("选择文件夹") }, onClick = { expanded = false; onPickFolder() })
            DropdownMenuItem(text = { Text("全选") }, enabled = state.drafts.isNotEmpty(), onClick = { expanded = false; onSelectAll() })
            DropdownMenuItem(text = { Text("取消全选") }, enabled = selectedCount > 0, onClick = { expanded = false; onSelectNone() })
            DropdownMenuItem(text = { Text("批量制卡") }, enabled = selectedCount > 0 && !state.loading, onClick = { expanded = false; onProcessSelected() })
            DropdownMenuItem(text = { Text("清空列表") }, enabled = state.drafts.isNotEmpty() && !state.loading, onClick = { expanded = false; onClearDrafts() })
        }
    }
}

@Composable
private fun MoreDropdown(
    onOpenStatus: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { expanded = true }) { Text("更多") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("状态") }, onClick = { expanded = false; onOpenStatus() })
            DropdownMenuItem(text = { Text("日志") }, onClick = { expanded = false; onOpenLogs() })
            DropdownMenuItem(text = { Text("设置") }, onClick = { expanded = false; onOpenSettings() })
        }
    }
}

@Composable
private fun StatusChip(
    label: String,
    value: String,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = "$label: $value",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun PromptModeChip(
    selectedMode: String,
    onModeSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Text(
                text = "模式: ${promptModeLabel(selectedMode)}",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("auto", "jp", "en").forEach { mode ->
                DropdownMenuItem(
                    text = { Text("模式: ${promptModeLabel(mode)}") },
                    onClick = {
                        expanded = false
                        onModeSelected(mode)
                    },
                )
            }
        }
    }
}

private fun promptModeLabel(mode: String): String =
    when (mode) {
        "jp" -> "jp"
        "en" -> "en"
        else -> "auto"
    }

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun DraftRow(
    draft: DraftCard,
    loading: Boolean,
    onWordChange: (String) -> Unit,
    onSelectedChange: (Boolean) -> Unit,
    onCreateClick: () -> Unit,
    onPreviewClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Thumbnail(
                    uri = draft.image.uri,
                    modifier =
                        Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    onClick = onPreviewClick,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = draft.image.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        "状态: ${draft.status.lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = draft.selected, onCheckedChange = onSelectedChange)
                OutlinedTextField(
                    value = draft.targetWord,
                    onValueChange = onWordChange,
                    label = { Text("目标词") },
                    placeholder = { Text("输入要制卡的词") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                )
                Button(
                    onClick = onCreateClick,
                    enabled = draft.targetWord.isNotBlank() && !loading,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("制卡")
                }
            }
        }
    }
}
@Composable
private fun Thumbnail(
    uri: Uri,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
        value =
            withContext(Dispatchers.IO) {
                decodeSampledBitmap(context, uri, 256)
            }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        onClick = onClick,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text("预览", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun decodeSampledBitmap(
    context: android.content.Context,
    uri: Uri,
    maxSize: Int,
): android.graphics.Bitmap? {
    val bounds =
        BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
    var sampleSize = 1
    while (largestSide / sampleSize > maxSize) {
        sampleSize *= 2
    }

    val options =
        BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
        }
    return context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream, null, options)
    }
}

@Composable
private fun ImagePreviewDialog(
    uri: Uri,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val title by produceState(initialValue = "图片预览", uri) {
        value =
            withContext(Dispatchers.IO) {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                    } else {
                        "图片预览"
                    }
                } ?: "图片预览"
            }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text(title) },
        text = {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setImageURI(uri)
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .clip(RoundedCornerShape(16.dp)),
                update = { imageView -> imageView.setImageURI(uri) },
            )
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusDialog(
    state: MainUiState,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onGrantPermission: () -> Unit,
    onQuery: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("状态") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusContent(
                    state = state,
                    selectedCount = state.drafts.count { it.selected },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = onRefresh) { Text("刷新状态") }
                    OutlinedButton(onClick = onGrantPermission) { Text("申请权限") }
                    OutlinedButton(onClick = onQuery) { Text("查询牌组") }
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogDialog(
    logs: List<AppLogger.LogEntry>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val llmLogs = remember(logs) { logs.filter { it.scope == "LLM" } }
    val ankiLogs = remember(logs) { logs.filter { it.scope == "Anki" } }
    val llmGroups = remember(llmLogs) { buildLlmLogGroups(llmLogs) }
    val ankiGroups = remember(ankiLogs) { buildAnkiLogGroups(ankiLogs) }
    val otherLogs = remember(logs) { logs.filter { it.scope != "LLM" && it.scope != "Anki" }.reversed() }
    var selectedGroup by remember(logs) { mutableStateOf<LogRequestGroup?>(null) }
    val currentGroup = selectedGroup
    val closeCurrentLayer = {
        if (selectedGroup == null) {
            onDismiss()
        } else {
            selectedGroup = null
        }
    }

    BackHandler(onBack = closeCurrentLayer)

    Dialog(
        onDismissRequest = closeCurrentLayer,
        properties =
            DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = if (currentGroup == null) "应用日志" else "请求详情",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(420.dp),
                ) {
                    when {
                        currentGroup != null -> {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(currentGroup.entries, key = { "${it.timestamp}-${it.level}-${it.message.hashCode()}" }) { entry ->
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = "${timeFormat.format(Date(entry.timestamp))} [${entry.level}] ${entry.scope}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(entry.message, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                        llmGroups.isEmpty() && ankiGroups.isEmpty() && otherLogs.isEmpty() -> {
                            Text("还没有日志")
                        }
                        else -> {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (llmGroups.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "LLM 请求",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                items(llmGroups, key = { it.id }) { group ->
                                    Card(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .combinedClickable(
                                                    onClick = { selectedGroup = group },
                                                    onLongClick = { selectedGroup = group },
                                                ),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = "${timeFormat.format(Date(group.timestamp))} [${group.level}]",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                text = group.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "共 ${group.entries.size} 条记录",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                if (ankiGroups.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "Anki 请求",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                items(ankiGroups, key = { it.id }) { group ->
                                    Card(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .combinedClickable(
                                                    onClick = { selectedGroup = group },
                                                    onLongClick = { selectedGroup = group },
                                                ),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = "${timeFormat.format(Date(group.timestamp))} [${group.level}]",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                text = group.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = "共 ${group.entries.size} 条记录",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                if (otherLogs.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "其他日志",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                items(otherLogs, key = { "${it.timestamp}-${it.level}-${it.scope}-${it.message.hashCode()}" }) { entry ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(
                                                text = "${timeFormat.format(Date(entry.timestamp))} [${entry.level}] ${entry.scope}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                text = entry.message.lineSequence().firstOrNull().orEmpty(),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (currentGroup == null) {
                        TextButton(onClick = onClear, enabled = logs.isNotEmpty()) {
                            Text("清空")
                        }
                    }
                    TextButton(onClick = closeCurrentLayer) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}
private enum class SettingsSectionId {
    LLM,
    ANKI,
    IMAGE,
}

@Composable
private fun SettingsDialog(
    initial: AppConfig,
    onDismiss: () -> Unit,
    onSave: (AppConfig) -> Unit,
) {
    var config by remember { mutableStateOf(initial) }
    var selectedSection by remember { mutableStateOf<SettingsSectionId?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedSection == null) {
                        onSave(config)
                    } else {
                        selectedSection = null
                    }
                },
            ) {
                Text(if (selectedSection == null) "保存" else "返回")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(if (selectedSection == null) "设置" else settingsSectionTitle(selectedSection!!)) },
        text = {
            when (selectedSection) {
                null -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsSectionCard(
                            title = "LLM 设置",
                            summary = "API Key、Base URL、模型和 Prompt 模式",
                            onClick = { selectedSection = SettingsSectionId.LLM },
                        )
                        SettingsSectionCard(
                            title = "Anki 设置",
                            summary = "牌组、模板和字段配置",
                            onClick = { selectedSection = SettingsSectionId.ANKI },
                        )
                        SettingsSectionCard(
                            title = "图片设置",
                            summary = "压缩宽度、高度和图片质量",
                            onClick = { selectedSection = SettingsSectionId.IMAGE },
                        )
                    }
                }
                SettingsSectionId.LLM -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { ConfigField("API Key", config.apiKey) { config = config.copy(apiKey = it) } }
                        item { ConfigField("Base URL", config.baseUrl) { config = config.copy(baseUrl = it) } }
                        item { ConfigField("模型名", config.modelName) { config = config.copy(modelName = it) } }
                        item { ConfigField("Prompt 模式(auto/jp/en)", config.promptMode) { config = config.copy(promptMode = it) } }
                    }
                }
                SettingsSectionId.ANKI -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { ConfigField("日语牌组", config.jpDeck) { config = config.copy(jpDeck = it) } }
                        item { ConfigField("英语牌组", config.enDeck) { config = config.copy(enDeck = it) } }
                        item { ConfigField("Anki 模板", config.ankiModelName) { config = config.copy(ankiModelName = it) } }
                        item { ConfigField("单词字段", config.wordField) { config = config.copy(wordField = it) } }
                        item { ConfigField("音标字段", config.pronunciationField) { config = config.copy(pronunciationField = it) } }
                        item { ConfigField("释义字段", config.meaningField) { config = config.copy(meaningField = it) } }
                        item { ConfigField("笔记字段", config.noteField) { config = config.copy(noteField = it) } }
                        item { ConfigField("例句字段", config.exampleField) { config = config.copy(exampleField = it) } }
                        item { ConfigField("发音字段", config.voiceField) { config = config.copy(voiceField = it) } }
                    }
                }
                SettingsSectionId.IMAGE -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            ConfigField("最大宽度", config.maxWidth.toString()) {
                                config = config.copy(maxWidth = it.toIntOrNull() ?: config.maxWidth)
                            }
                        }
                        item {
                            ConfigField("最大高度", config.maxHeight.toString()) {
                                config = config.copy(maxHeight = it.toIntOrNull() ?: config.maxHeight)
                            }
                        }
                        item {
                            ConfigField("图片质量", config.imageQuality.toString()) {
                                config = config.copy(imageQuality = it.toIntOrNull() ?: config.imageQuality)
                            }
                        }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsSectionCard(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AboutDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val packageInfo =
        remember {
            try {
                context.packageManager.getPackageInfo(context.packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    val versionName = packageInfo?.versionName ?: "未知"
    val versionCode =
        packageInfo?.let {
            it.longVersionCode.toString()
        } ?: "未知"

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("关于") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pstankidroid", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("作者: mayegg")
                Text("版本: $versionName ($versionCode)")
                Text("用于从图片批量生成词卡内容，并写入 AnkiDroid。", style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}

private fun settingsSectionTitle(section: SettingsSectionId): String =
    when (section) {
        SettingsSectionId.LLM -> "LLM 设置"
        SettingsSectionId.ANKI -> "Anki 设置"
        SettingsSectionId.IMAGE -> "图片设置"
    }

@Composable
private fun ConfigField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
    )
}

private data class LogRequestGroup(
    val id: String,
    val timestamp: Long,
    val level: String,
    val title: String,
    val entries: List<AppLogger.LogEntry>,
)

private fun buildLlmLogGroups(logs: List<AppLogger.LogEntry>): List<LogRequestGroup> =
    buildScopedLogGroups(
        logs = logs,
        isStart = { it.message.startsWith("Request") },
        isEnd = {
            it.message.startsWith("Response") ||
                it.message.startsWith("HTTP ") ||
                it.message.startsWith("Request failed")
        },
        titleExtractor = { entries ->
            entries.firstOrNull { it.message.startsWith("Request") }
                ?.message
                ?.lineSequence()
                ?.firstOrNull { it.startsWith("endpoint=") || it.startsWith("body=") }
                ?.removePrefix("endpoint=")
                ?.removePrefix("body=")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: entries.first().message.lineSequence().firstOrNull().orEmpty()
        },
    )

private fun buildAnkiLogGroups(logs: List<AppLogger.LogEntry>): List<LogRequestGroup> =
    buildScopedLogGroups(
        logs = logs,
        isStart = { it.message.startsWith("Prepare note") },
        isEnd = {
            it.message.startsWith("Bulk insert result") ||
                it.message.startsWith("Update note result")
        },
        titleExtractor = { entries ->
            val word =
                entries.firstNotNullOfOrNull { entry ->
                    entry.message.lineSequence().firstOrNull { it.startsWith("word=") }?.removePrefix("word=")
                }.orEmpty()
            val deck =
                entries.firstNotNullOfOrNull { entry ->
                    entry.message.lineSequence().firstOrNull { it.startsWith("deck=") }?.removePrefix("deck=")
                }.orEmpty()
            listOf(word, deck).filter { it.isNotBlank() }.joinToString(" · ").ifBlank {
                entries.first().message.lineSequence().firstOrNull().orEmpty()
            }
        },
    )

private fun buildScopedLogGroups(
    logs: List<AppLogger.LogEntry>,
    isStart: (AppLogger.LogEntry) -> Boolean,
    isEnd: (AppLogger.LogEntry) -> Boolean,
    titleExtractor: (List<AppLogger.LogEntry>) -> String,
): List<LogRequestGroup> {
    if (logs.isEmpty()) return emptyList()

    val groups = mutableListOf<MutableList<AppLogger.LogEntry>>()
    var current = mutableListOf<AppLogger.LogEntry>()

    logs.forEach { entry ->
        if (isStart(entry) && current.isNotEmpty()) {
            groups += current
            current = mutableListOf()
        }
        current += entry

        if (isEnd(entry)) {
            groups += current
            current = mutableListOf()
        }
    }
    if (current.isNotEmpty()) groups += current

    return groups.reversed().mapIndexed { index, entries ->
        LogRequestGroup(
            id = "${entries.first().timestamp}-$index",
            timestamp = entries.first().timestamp,
            level = entries.last().level,
            title = titleExtractor(entries),
            entries = entries,
        )
    }
}

private fun Boolean?.toStatusText(): String =
    when (this) {
        true -> "可用"
        false -> "不可用"
        null -> "未知"
    }

@Composable
fun WordNotePage() {
    val context = LocalContext.current
    PstankidroidTheme {
        val viewModel: MainViewModel =
            viewModel(
                factory =
                    MainViewModel.factory(
                        AnkiDroidClient(context.applicationContext),
                        ConfigRepository(context.applicationContext),
                    ),
            )
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        val logs by AppLogger.entries.collectAsStateWithLifecycle()
        var showSettings by remember { mutableStateOf(false) }
        var showStatus by remember { mutableStateOf(false) }
        var showLogs by remember { mutableStateOf(false) }
        var previewUri by remember { mutableStateOf<Uri?>(null) }

        val permissionLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                viewModel.refreshStatus()
            }
        val folderLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                uri?.let {
                    persistUriForWordNote(context, it)
                    val folderLabel = resolveFolderLabelForWordNote(context, it)
                    viewModel.setCurrentFolder(it.toString(), folderLabel)
                    val imageUris = collectImageUrisFromTreeForWordNote(context, it)
                    if (imageUris.isEmpty()) {
                        AppLogger.w("FolderImport", "No images found in $it")
                        viewModel.updateStatusMessage("所选文件夹内没有图片")
                    } else {
                        imageUris.forEach { imageUri -> persistUriForWordNote(context, imageUri) }
                        viewModel.onImagesSelected(imageUris)
                    }
                }
            }

        MainScreen(
            state = state,
            onOpenSettings = { showSettings = true },
            onOpenStatus = { showStatus = true },
            onOpenLogs = { showLogs = true },
            onPickFolder = { folderLauncher.launch(null) },
            onPromptModeChange = viewModel::updatePromptMode,
            onSelectAll = viewModel::selectAll,
            onSelectNone = viewModel::selectNone,
            onProcessSelected = viewModel::processSelected,
            onClearDrafts = {
                val message = clearCurrentFolderImagesForWordNote(context, state.currentFolderUri)
                viewModel.onDraftsCleared(message)
            },
            onWordChange = viewModel::updateTargetWord,
            onSelectChange = viewModel::updateSelected,
            onProcessSingle = viewModel::processSingle,
            onPreviewImage = { previewUri = it },
        )

        if (showSettings) {
            SettingsDialog(
                initial = state.config,
                onDismiss = { showSettings = false },
                onSave = {
                    viewModel.saveConfig(it)
                    showSettings = false
                },
            )
        }

        if (showStatus) {
            StatusDialog(
                state = state,
                onDismiss = { showStatus = false },
                onRefresh = viewModel::refreshStatus,
                onGrantPermission = { permissionLauncher.launch(AnkiDroidClient.readWritePermission) },
                onQuery = viewModel::querySimpleData,
            )
        }

        if (showLogs) {
            LogDialog(
                logs = logs,
                onDismiss = { showLogs = false },
                onClear = AppLogger::clear,
            )
        }

        previewUri?.let { uri ->
            ImagePreviewDialog(uri = uri, onDismiss = { previewUri = null })
        }
    }
}

private fun persistUriForWordNote(
    context: android.content.Context,
    uri: Uri,
) {
    try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    } catch (_: SecurityException) {
    }
}

private fun resolveFolderLabelForWordNote(
    context: android.content.Context,
    uri: Uri,
): String = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: uri.toString()

private fun collectImageUrisFromTreeForWordNote(
    context: android.content.Context,
    treeUri: Uri,
): List<Uri> {
    val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
    return collectImageFilesForWordNote(root)
        .sortedBy { it.name.orEmpty().lowercase(Locale.ROOT) }
        .map { it.uri }
}

private fun collectImageFilesForWordNote(directory: DocumentFile): List<DocumentFile> =
    directory.listFiles().flatMap { file ->
        when {
            file.isDirectory -> collectImageFilesForWordNote(file)
            file.isFile && (file.type?.startsWith("image/") == true || isImageByNameForWordNote(file.name)) -> listOf(file)
            else -> emptyList()
        }
    }

private fun clearCurrentFolderImagesForWordNote(
    context: android.content.Context,
    folderUri: String?,
): String {
    if (folderUri.isNullOrBlank()) return "未选择文件夹，已仅清空列表。"
    val root = DocumentFile.fromTreeUri(context, Uri.parse(folderUri)) ?: return "无法访问当前文件夹，已仅清空列表。"
    val files = collectImageFilesForWordNote(root)
    var deletedCount = 0
    files.forEach { file ->
        if (file.delete()) deletedCount += 1
    }
    return "已清空列表，并删除当前文件夹中 $deletedCount 张图片。"
}

private fun isImageByNameForWordNote(name: String?): Boolean {
    val lower = name?.lowercase(Locale.ROOT) ?: return false
    return lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") ||
        lower.endsWith(".png") ||
        lower.endsWith(".webp") ||
        lower.endsWith(".gif")
}
