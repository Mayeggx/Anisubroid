package com.mayegg.anisub.wordnote

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class AnkiDroidClient(
    private val context: Context,
) {
    companion object {
        const val ankidroidPackage = "com.ichi2.anki"
        const val readWritePermission = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        private const val authority = "com.ichi2.anki.flashcards"
        private const val fileProviderAuthority = "com.mayegg.anisub.fileprovider"
        private const val fieldSeparator = "\u001f"
    }

    data class Status(
        val installed: Boolean,
        val providerAvailable: Boolean,
        val permissionGranted: Boolean,
    )

    data class SelectedImage(
        val uri: Uri,
        val displayName: String,
        val subtitle: String,
    )

    data class ProcessingInput(
        val image: SelectedImage,
        val key: String,
    )

    data class WordPayload(
        val word: String,
        val pronunciation: String,
        val meaning: String,
        val example: String,
        val note: String,
    )

    private enum class PromptMode {
        AUTO,
        JP,
        EN,
    }

    private data class ModelInfo(
        val id: Long,
        val name: String,
        val fields: List<String>,
    )

    private data class ExistingNote(
        val id: Long,
        val fields: MutableMap<String, String>,
    )

    private data class NoteRow(
        val id: Long,
        val fields: MutableMap<String, String>,
    )

    fun status(): Status {
        val installed =
            try {
                context.packageManager.getPackageInfo(ankidroidPackage, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        val providerAvailable =
            context.packageManager.resolveContentProvider(authority, 0) != null ||
                context.contentResolver.acquireUnstableContentProviderClient(authority)?.let {
                    it.close()
                    true
                } == true
        val permissionGranted =
            ContextCompat.checkSelfPermission(context, readWritePermission) == PackageManager.PERMISSION_GRANTED
        return Status(installed, providerAvailable, permissionGranted)
    }

    fun toSelectedImages(uris: List<Uri>): List<SelectedImage> =
        uris.distinct().map { uri ->
            val name = resolveDisplayName(uri)
            SelectedImage(uri, name, name.substringBeforeLast('.'))
        }

    fun querySummary(): Result<String> = runCatching {
        requireReady()
        val decks = queryNames(Uri.parse("content://$authority/decks"), "deck_name")
        val models = queryNames(Uri.parse("content://$authority/models"), "name")
        buildString {
            appendLine("Decks: ${decks.size}")
            decks.take(5).forEach { appendLine("- $it") }
            appendLine("Models: ${models.size}")
            models.take(5).forEach { appendLine("- $it") }
        }.trim()
    }

    fun processBatch(
        config: AppConfig,
        items: List<ProcessingInput>,
    ): Result<List<String>> = runCatching {
        requireReady()
        require(config.apiKey.isNotBlank()) { "Please fill in API Key first." }
        require(items.isNotEmpty()) { "Please select images first." }
        require(items.all { it.key.isNotBlank() }) { "Some target words are empty." }

        val payloads = explainBatch(config, items)
        items.zip(payloads).map { (input, payload) -> createOrUpdateNote(config, input, payload) }
    }

    private fun createOrUpdateNote(
        config: AppConfig,
        input: ProcessingInput,
        payload: WordPayload,
    ): String {
        val mode = detectMode(input.key)
        val deckName = if (mode == "jp") config.jpDeck else config.enDeck
        val deckId = ensureDeck(deckName)
        val model = requireModel(config.ankiModelName)
        AppLogger.i(
            "Anki",
            "Prepare note\nmode=$mode\ndeck=$deckName\ndeckId=$deckId\nmodel=${model.name}\nmodelId=${model.id}\nword=${payload.word}",
        )
        val imageTag = importImage(config, input.image)
        val exampleHtml = makeExampleHtml(payload.example, imageTag)
        val values =
            mapOf(
                config.wordField to payload.word,
                config.pronunciationField to payload.pronunciation,
                config.meaningField to payload.meaning,
                config.noteField to payload.note,
                config.exampleField to exampleHtml,
                config.voiceField to makeVoiceTag(mode, payload.word, payload.pronunciation),
            )
        val existing = findExisting(model, deckName, config.wordField, payload.word)
        return if (existing != null) {
            AppLogger.i("Anki", "Update existing note\nnoteId=${existing.id}\ndeck=$deckName\ndeckId=$deckId\nword=${payload.word}")
            val merged =
                existing.fields.toMutableMap().apply {
                    put(config.wordField, payload.word)
                    put(config.pronunciationField, payload.pronunciation)
                    put(config.voiceField, makeVoiceTag(mode, payload.word, payload.pronunciation))
                    put(config.meaningField, appendHtml(get(config.meaningField).orEmpty(), payload.meaning))
                    put(config.noteField, appendHtml(get(config.noteField).orEmpty(), payload.note))
                    put(config.exampleField, appendHtml(get(config.exampleField).orEmpty(), exampleHtml))
                }
            updateNote(existing.id, model.fields.map { merged[it].orEmpty() })
            "Updated ${input.image.displayName} -> ${payload.word}"
        } else {
            insertNote(model.id, deckId, model.fields.map { values[it].orEmpty() })
            "Created ${input.image.displayName} -> ${payload.word}"
        }
    }

    private fun appendHtml(
        current: String,
        next: String,
    ): String {
        if (current.isBlank()) return next
        if (current.contains(next)) return current
        return "$current<br>$next"
    }

    private fun makeExampleHtml(
        example: String,
        imageTag: String,
    ): String {
        val normalizedExample = example.trim()
        val prefixedExample =
            if (normalizedExample.startsWith("●")) normalizedExample else "● $normalizedExample"
        return "$prefixedExample<br>$imageTag"
    }

    private fun explainBatch(
        config: AppConfig,
        items: List<ProcessingInput>,
    ): List<WordPayload> {
        val promptMode = resolvePromptMode(config.promptMode, items)
        val prompt = buildPrompt(promptMode, items)
        val endpoint = "${config.baseUrl.trimEnd('/')}/chat/completions"
        val body =
            JSONObject()
                .put("model", config.modelName)
                .put("temperature", 0.1)
                .put(
                    "messages",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("role", "system")
                                .put("content", systemPrompt(promptMode)),
                        )
                        .put(
                            JSONObject()
                                .put("role", "user")
                                .put("content", prompt),
                        ),
                )
        AppLogger.i("LLM", "Request\nendpoint=$endpoint\nbody=${body.toString(2)}")
        val response = postJson(endpoint, config.apiKey, body)
        val content =
            response.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        AppLogger.i("LLM", "Response\ncontent=$content")
        val json = parseJsonContent(content) as? JSONArray ?: error("Model response was not a JSON array.")
        return List(json.length()) { index ->
            val item = json.getJSONObject(index)
            WordPayload(
                word = firstNonBlank(item, "word", "单词"),
                pronunciation = firstNonBlank(item, "pronunciation", "音标"),
                meaning = firstNonBlank(item, "meaning", "释义", "意义"),
                example = firstNonBlank(item, "example", "例句"),
                note = firstNonBlank(item, "note", "笔记"),
            )
        }
    }

    private fun firstNonBlank(
        json: JSONObject,
        vararg keys: String,
    ): String =
        keys.firstNotNullOfOrNull { key ->
            json.optString(key).takeIf { it.isNotBlank() }
        }.orEmpty()

    private fun postJson(
        endpoint: String,
        apiKey: String,
        body: JSONObject,
    ): JSONObject {
        val connection =
            (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if (connection.responseCode !in 200..299) {
                AppLogger.e("LLM", "HTTP ${connection.responseCode}\n$text")
            }
            require(connection.responseCode in 200..299) { "LLM request failed: $text" }
            JSONObject(text)
        } catch (throwable: Throwable) {
            AppLogger.e("LLM", "Request failed for $endpoint", throwable)
            throw throwable
        } finally {
            connection.disconnect()
        }
    }

    private fun parseJsonContent(content: String): Any {
        val clean =
            content.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        return if (clean.startsWith("[")) JSONArray(clean) else JSONObject(clean)
    }

    private fun resolvePromptMode(
        rawMode: String,
        items: List<ProcessingInput>,
    ): PromptMode =
        when (rawMode.lowercase()) {
            "jp" -> PromptMode.JP
            "en" -> PromptMode.EN
            else -> detectPromptMode(items.firstOrNull()?.key.orEmpty())
        }

    private fun detectPromptMode(word: String): PromptMode =
        if (word.isNotBlank() && word.first().code > 10000) PromptMode.JP else PromptMode.EN

    private fun buildPrompt(
        promptMode: PromptMode,
        items: List<ProcessingInput>,
    ): String {
        val templatePath =
            when (promptMode) {
                PromptMode.JP -> "prompts/batch_jp.txt"
                PromptMode.EN -> "prompts/batch_en.txt"
                PromptMode.AUTO -> error("AUTO should be resolved before building prompt.")
            }
        val pairs =
            buildString {
                items.forEachIndexed { index, item ->
                    appendLine("${index + 1}.")
                    appendLine("例句: ${item.image.subtitle}")
                    appendLine("目标词: ${item.key}")
                    appendLine()
                }
            }.trimEnd()
        return readAssetText(templatePath).replace("{{pairs}}", pairs)
    }

    private fun readAssetText(path: String): String =
        context.assets.open(path).use { input ->
            InputStreamReader(input, Charsets.UTF_8).use { reader -> reader.readText() }
        }

    private fun systemPrompt(promptMode: PromptMode): String =
        when (promptMode) {
            PromptMode.JP -> "你是专业的日语词典助手。只返回 JSON 数组。"
            PromptMode.EN -> "你是专业的英语词典助手。只返回 JSON 数组。"
            PromptMode.AUTO -> "只返回 JSON 数组。"
        }

    private fun queryNames(
        uri: Uri,
        column: String,
    ): List<String> {
        val list = mutableListOf<String>()
        context.contentResolver.query(uri, arrayOf(column), null, null, column)?.use { cursor ->
            val index = cursor.getColumnIndexOrThrow(column)
            while (cursor.moveToNext()) list += cursor.getString(index)
        }
        return list
    }

    private fun requireModel(name: String): ModelInfo {
        context.contentResolver.query(
            Uri.parse("content://$authority/models"),
            arrayOf("_id", "name", "field_names"),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == name) {
                    return ModelInfo(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("_id")),
                        name = name,
                        fields = cursor.getString(cursor.getColumnIndexOrThrow("field_names")).split(fieldSeparator),
                    )
                }
            }
        }
        error("Anki model not found: $name")
    }

    private fun ensureDeck(name: String): Long {
        context.contentResolver.query(
            Uri.parse("content://$authority/decks"),
            arrayOf("deck_id", "deck_name"),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("deck_name")) == name) {
                    val deckId = cursor.getLong(cursor.getColumnIndexOrThrow("deck_id"))
                    AppLogger.i("Anki", "Use existing deck\nname=$name\ndeckId=$deckId")
                    return deckId
                }
            }
        }
        val inserted =
            context.contentResolver.insert(
                Uri.parse("content://$authority/decks"),
                ContentValues().apply { put("deck_name", name) },
            )
        val createdDeckId = inserted?.lastPathSegment?.toLong() ?: error("Failed to create deck: $name")
        AppLogger.i("Anki", "Create new deck\nname=$name\ndeckId=$createdDeckId")
        return createdDeckId
    }

    private fun findExisting(
        model: ModelInfo,
        deckName: String,
        wordField: String,
        word: String,
    ): ExistingNote? {
        val normalizedWord = normalizeWordValue(word)
        if (normalizedWord.isBlank()) return null

        val escapedWord = escapeQueryValue(normalizedWord)
        val escapedDeck = escapeQueryValue(deckName)
        val escapedModel = escapeQueryValue(model.name)
        val queries =
            listOf(
                "deck:\"$escapedDeck\" note:\"$escapedModel\" \"$wordField:$escapedWord\"",
                "deck:\"$escapedDeck\" \"$wordField:$escapedWord\"",
            )

        val seenIds = mutableSetOf<Long>()
        queries.forEach { query ->
            val candidates = queryNotes(model, query)
            AppLogger.i("Anki", "Duplicate search query=$query candidates=${candidates.size}")
            candidates.forEach { candidate ->
                if (!seenIds.add(candidate.id)) return@forEach
                val noteWord = normalizeWordValue(candidate.fields[wordField].orEmpty())
                if (noteWord == normalizedWord) {
                    return ExistingNote(candidate.id, candidate.fields)
                }
            }
        }
        return null
    }

    private fun queryNotes(
        model: ModelInfo,
        searchQuery: String,
    ): List<NoteRow> {
        val notes = mutableListOf<NoteRow>()
        context.contentResolver.query(
            Uri.parse("content://$authority/notes"),
            arrayOf("_id", "flds"),
            searchQuery,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("_id")
            val fieldsIndex = cursor.getColumnIndexOrThrow("flds")
            while (cursor.moveToNext()) {
                val values = cursor.getString(fieldsIndex).split(fieldSeparator)
                val paddedValues = values + List(maxOf(0, model.fields.size - values.size)) { "" }
                notes +=
                    NoteRow(
                        id = cursor.getLong(idIndex),
                        fields = model.fields.zip(paddedValues).toMap().toMutableMap(),
                    )
            }
        }
        return notes
    }

    private fun normalizeWordValue(value: String): String = value.trim().split(Regex("\\s+")).joinToString(" ")

    private fun escapeQueryValue(value: String): String = normalizeWordValue(value).replace("\\", "\\\\").replace("\"", "\\\"")

    private fun updateNote(
        noteId: Long,
        fields: List<String>,
    ) {
        val updated =
            context.contentResolver.update(
                Uri.parse("content://$authority/notes/$noteId"),
                ContentValues().apply { put("flds", fields.joinToString(fieldSeparator)) },
                null,
                null,
            )
        AppLogger.i("Anki", "Update note result\nnoteId=$noteId\nupdated=$updated")
        require(updated > 0) { "Failed to update note." }
    }

    private fun insertNote(
        modelId: Long,
        deckId: Long,
        fields: List<String>,
    ) {
        val insertUri = Uri.parse("content://$authority/notes?deckId=$deckId")
        val values =
            ContentValues().apply {
                put("mid", modelId)
                put("flds", fields.joinToString(fieldSeparator))
                put("tags", "pstankidroid pic-sub")
            }
        AppLogger.i("Anki", "Bulk insert note\nuri=$insertUri\nmodelId=$modelId\ndeckId=$deckId\nfieldsCount=${fields.size}")
        val insertedCount = context.contentResolver.bulkInsert(insertUri, arrayOf(values))
        AppLogger.i("Anki", "Bulk insert result\ncount=$insertedCount")
        require(insertedCount > 0) { "Failed to insert note via bulkInsert." }
    }

    private fun importImage(
        config: AppConfig,
        image: SelectedImage,
    ): String {
        val file = compressToTempFile(config, image)
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority, file)
        context.grantUriPermission(ankidroidPackage, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            val inserted =
                context.contentResolver.insert(
                    Uri.parse("content://$authority/media"),
                    ContentValues().apply {
                        put("file_uri", uri.toString())
                        put("preferred_name", file.nameWithoutExtension)
                    },
                ) ?: error("Failed to import image.")
            "<img src=\"${File(inserted.path ?: "").name}\" />"
        } finally {
            context.revokeUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun compressToTempFile(
        config: AppConfig,
        image: SelectedImage,
    ): File {
        val bytes = context.contentResolver.openInputStream(image.uri)?.use { it.readBytes() } ?: error("Failed to read image.")
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Failed to decode image.")
        val ratio = minOf(config.maxWidth.toFloat() / bitmap.width, config.maxHeight.toFloat() / bitmap.height, 1f)
        val width = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val height = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val file = File(context.cacheDir, "${image.subtitle}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, config.imageQuality, output)
        }
        return file
    }

    private fun makeVoiceTag(
        mode: String,
        word: String,
        pronunciation: String,
    ): String =
        if (mode == "jp") {
            "[sound:https://assets.languagepod101.com/dictionary/japanese/audiomp3.php?kanji=$word&kana=$pronunciation]"
        } else {
            "[sound:https://dict.youdao.com/dictvoice?audio=$word]"
        }

    private fun detectMode(word: String): String =
        if (word.isNotBlank() && word.first().code > 10000) "jp" else "en"

    private fun resolveDisplayName(uri: Uri): String =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        } ?: uri.lastPathSegment ?: "image-${System.currentTimeMillis()}.jpg"

    private fun requireReady() {
        val status = status()
        require(status.installed) { "AnkiDroid is not installed." }
        require(status.providerAvailable) { "AnkiDroid provider is not available." }
        require(status.permissionGranted) { "Please grant READ_WRITE_DATABASE permission first." }
    }
}
