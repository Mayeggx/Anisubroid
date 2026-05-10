package com.mayegg.anisub.wordnote

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DraftCard(
    val id: String,
    val image: AnkiDroidClient.SelectedImage,
    val targetWord: String = "",
    val status: String = "wait",
    val selected: Boolean = false,
)

data class MainUiState(
    val installed: Boolean? = null,
    val providerAvailable: Boolean? = null,
    val permissionGranted: Boolean? = null,
    val loading: Boolean = false,
    val statusMessage: String = "请选择图片并配置参数。",
    val currentFolderUri: String? = null,
    val currentFolderLabel: String = "未选择文件夹",
    val drafts: List<DraftCard> = emptyList(),
    val config: AppConfig = AppConfig(),
)

class MainViewModel(
    private val client: AnkiDroidClient,
    private val configRepository: ConfigRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState(config = configRepository.config().value))
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        refreshStatus()
    }

    fun refreshStatus() {
        val status = client.status()
        _uiState.update {
            it.copy(
                installed = status.installed,
                providerAvailable = status.providerAvailable,
                permissionGranted = status.permissionGranted,
            )
        }
    }

    fun saveConfig(config: AppConfig) {
        configRepository.save(config)
        _uiState.update { it.copy(config = config) }
    }

    fun updatePromptMode(promptMode: String) {
        saveConfig(_uiState.value.config.copy(promptMode = promptMode))
    }

    fun updateStatusMessage(message: String) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    fun setCurrentFolder(
        folderUri: String,
        folderLabel: String,
    ) {
        _uiState.update { it.copy(currentFolderUri = folderUri, currentFolderLabel = folderLabel) }
    }

    fun onImagesSelected(uris: List<Uri>) {
        val current = _uiState.value.drafts.associateBy { it.id }
        val incoming =
            client.toSelectedImages(uris).map { image ->
                current[image.uri.toString()] ?: DraftCard(id = image.uri.toString(), image = image)
            }
        val drafts = (_uiState.value.drafts + incoming).distinctBy { it.id }
        _uiState.update { it.copy(drafts = drafts, statusMessage = "已载入 ${drafts.size} 张图片。") }
    }

    fun updateTargetWord(
        id: String,
        value: String,
    ) {
        _uiState.update { state ->
            state.copy(drafts = state.drafts.map { if (it.id == id) it.copy(targetWord = value) else it })
        }
    }

    fun updateSelected(
        id: String,
        selected: Boolean,
    ) {
        _uiState.update { state ->
            state.copy(drafts = state.drafts.map { if (it.id == id) it.copy(selected = selected) else it })
        }
    }

    fun selectAll() {
        _uiState.update { state -> state.copy(drafts = state.drafts.map { it.copy(selected = true) }) }
    }

    fun selectNone() {
        _uiState.update { state -> state.copy(drafts = state.drafts.map { it.copy(selected = false) }) }
    }

    fun removeDraft(id: String) {
        _uiState.update { state ->
            val drafts = state.drafts.filterNot { it.id == id }
            state.copy(drafts = drafts, statusMessage = "已移除 1 项。")
        }
    }

    fun clearDrafts() {
        _uiState.update { it.copy(drafts = emptyList(), statusMessage = "已清空当前列表。") }
    }

    fun onDraftsCleared(message: String) {
        _uiState.update { it.copy(drafts = emptyList(), statusMessage = message) }
    }

    fun querySimpleData() {
        runTask {
            client.querySummary()
                .onSuccess { _uiState.update { state -> state.copy(statusMessage = it) } }
                .onFailure { _uiState.update { state -> state.copy(statusMessage = it.message ?: "查询失败") } }
        }
    }

    fun processSelected() {
        val selected = _uiState.value.drafts.filter { it.selected }
        processDrafts(selected, "批量处理完成，共 ${selected.size} 项。")
    }

    fun processSingle(id: String) {
        val draft = _uiState.value.drafts.firstOrNull { it.id == id } ?: return
        processDrafts(listOf(draft), "已处理 1 项。")
    }

    private fun processDrafts(
        drafts: List<DraftCard>,
        successMessage: String,
    ) {
        val processingIds = drafts.map { it.id }.toSet()
        val config = _uiState.value.config
        _uiState.update { state ->
            state.copy(
                drafts =
                    state.drafts.map { draft ->
                        if (draft.id in processingIds) draft.copy(status = "proceed") else draft
                    },
            )
        }
        runTask {
            client.processBatch(config, drafts.map { AnkiDroidClient.ProcessingInput(it.image, it.targetWord) })
                .onSuccess { results ->
                    _uiState.update { state ->
                        val resultMap = drafts.map { it.id }.zip(results).toMap()
                        state.copy(
                            drafts =
                                state.drafts.map { draft ->
                                    resultMap[draft.id]?.let { draft.copy(status = normalizeDraftStatus(it), selected = false) } ?: draft
                                },
                            statusMessage = successMessage,
                        )
                    }
                }
                .onFailure { _uiState.update { state -> state.copy(statusMessage = it.message ?: "处理失败") } }
        }
    }

    private fun normalizeDraftStatus(raw: String): String {
        val text = raw.lowercase()
        return when {
            "updated" in text -> "updated"
            "created" in text -> "created"
            "proceed" in text -> "proceed"
            else -> "wait"
        }
    }

    private fun runTask(task: suspend () -> Unit) {
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                task()
            } finally {
                refreshStatus()
                _uiState.update { it.copy(loading = false) }
            }
        }
    }

    companion object {
        fun factory(
            client: AnkiDroidClient,
            configRepository: ConfigRepository,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(client, configRepository) as T
            }
    }
}
