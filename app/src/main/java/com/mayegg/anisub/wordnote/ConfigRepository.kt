package com.mayegg.anisub.wordnote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppConfig(
    val apiKey: String = "",
    val baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    val modelName: String = "qwen-plus",
    val promptMode: String = "auto",
    val jpDeck: String = "日本語::ランダム::アニメ・マンガ・マスコミ",
    val enDeck: String = "English Vocabulary::A English Daily",
    val ankiModelName: String = "划词助手Antimoon模板",
    val wordField: String = "单词",
    val pronunciationField: String = "音标",
    val meaningField: String = "释义",
    val noteField: String = "笔记",
    val exampleField: String = "例句",
    val voiceField: String = "发音",
    val maxWidth: Int = 320,
    val maxHeight: Int = 240,
    val imageQuality: Int = 60,
)

class ConfigRepository(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("app_config", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    fun config(): StateFlow<AppConfig> = state.asStateFlow()

    fun save(config: AppConfig) {
        prefs.edit()
            .putString("apiKey", config.apiKey)
            .putString("baseUrl", config.baseUrl)
            .putString("modelName", config.modelName)
            .putString("promptMode", config.promptMode)
            .putString("jpDeck", config.jpDeck)
            .putString("enDeck", config.enDeck)
            .putString("ankiModelName", config.ankiModelName)
            .putString("wordField", config.wordField)
            .putString("pronunciationField", config.pronunciationField)
            .putString("meaningField", config.meaningField)
            .putString("noteField", config.noteField)
            .putString("exampleField", config.exampleField)
            .putString("voiceField", config.voiceField)
            .putInt("maxWidth", config.maxWidth)
            .putInt("maxHeight", config.maxHeight)
            .putInt("imageQuality", config.imageQuality)
            .apply()
        state.value = config
    }

    private fun load(): AppConfig =
        AppConfig(
            apiKey = prefs.getString("apiKey", "") ?: "",
            baseUrl = prefs.getString("baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1") ?: "",
            modelName = prefs.getString("modelName", "qwen-plus") ?: "",
            promptMode = prefs.getString("promptMode", "auto") ?: "auto",
            jpDeck = prefs.getString("jpDeck", "日本語::ランダム::アニメ・マンガ・マスコミ") ?: "",
            enDeck = prefs.getString("enDeck", "English Vocabulary::A English Daily") ?: "",
            ankiModelName = prefs.getString("ankiModelName", "划词助手Antimoon模板") ?: "",
            wordField = prefs.getString("wordField", "单词") ?: "",
            pronunciationField = prefs.getString("pronunciationField", "音标") ?: "",
            meaningField = prefs.getString("meaningField", "释义") ?: "",
            noteField = prefs.getString("noteField", "笔记") ?: "",
            exampleField = prefs.getString("exampleField", "例句") ?: "",
            voiceField = prefs.getString("voiceField", "发音") ?: "",
            maxWidth = prefs.getInt("maxWidth", 320),
            maxHeight = prefs.getInt("maxHeight", 240),
            imageQuality = prefs.getInt("imageQuality", 60),
        )
}
