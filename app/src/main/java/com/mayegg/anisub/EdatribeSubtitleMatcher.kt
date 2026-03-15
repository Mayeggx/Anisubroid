package com.mayegg.anisub

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class EdatribeSubtitleMatcher(
    private val context: Context,
) : SubtitleMatcher {
    companion object {
        private const val TAG = "EdatribeMatcher"
        private const val BASE = "https://cc.edatribe.com"
        private const val TV_SERIES_PATH = "TV series"
    }

    override suspend fun matchAndDownload(video: VideoItem): SubtitleDownloadResult =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "matchAndDownload start title=${video.title}")
            val parsed = SubtitleNameHeuristics.parseVideo(video.title)
            Log.i(TAG, "parsed title base=${parsed.baseTitle}, episode=${parsed.episode}")

            val seriesDir = findBestSeriesDirectory(parsed)
            Log.i(TAG, "series matched dir=${seriesDir.name}")

            val subtitle = findEpisodeSubtitle(seriesDir.name, parsed.episode)
            Log.i(TAG, "subtitle matched name=${subtitle.name}")

            val downloadUrl = buildFileUrl(TV_SERIES_PATH, seriesDir.name, subtitle.name)
            val saved = saveToVideoFolder(video.folderUri, video.title, subtitle.name, downloadUrl)
            Log.i(TAG, "saved subtitle file=$saved")
            SubtitleDownloadResult(
                savedFileName = saved,
                seriesTitle = seriesDir.name,
                episode = parsed.episode,
                originalSubtitleName = subtitle.name,
            )
        }

    private fun findBestSeriesDirectory(parsed: ParsedVideo): FileListItem {
        val listUrl = buildDirectoryUrl(TV_SERIES_PATH)
        val entries = parseFileList(getText(listUrl))
            .filter { it.type == "directory" }
        if (entries.isEmpty()) error("EdaTribe TV series 目录为空。")

        val picked =
            entries
                .asSequence()
                .map { item -> item to scoreDirectory(item.name, parsed) }
                .maxByOrNull { it.second }
                ?: error("未找到可匹配的剧集目录。")

        if (picked.second < 0.28) {
            error("未找到足够接近的视频条目（最佳得分 ${"%.2f".format(picked.second)}）。")
        }
        return picked.first
    }

    private fun findEpisodeSubtitle(
        seriesDirectoryName: String,
        episode: Int,
    ): FileListItem {
        val listUrl = buildDirectoryUrl(TV_SERIES_PATH, seriesDirectoryName)
        val files = parseFileList(getText(listUrl)).filter { it.type == "file" }
        if (files.isEmpty()) error("目标目录内没有可下载字幕文件。")

        val episodeMatches =
            files.filter { item ->
                val ep = SubtitleNameHeuristics.extractEpisode(item.name)
                ep == episode
            }
        if (episodeMatches.isEmpty()) {
            error("已进入目录，但没有匹配到第 $episode 集字幕。")
        }

        return episodeMatches.maxByOrNull { scoreSubtitleFile(it.name) }
            ?: error("第 $episode 集字幕筛选失败。")
    }

    private fun scoreDirectory(
        entryName: String,
        parsed: ParsedVideo,
    ): Double {
        val cleaned = entryName.replace(Regex("""^\s*\[\d+]\s*"""), "")
        val normalizedEntry = SubtitleNameHeuristics.normalize(cleaned)
        var best = 0.0
        for (query in parsed.queryTitles) {
            val normalizedQuery = SubtitleNameHeuristics.normalize(query)
            val tokenScore = tokenOverlap(normalizedEntry, normalizedQuery)
            val containsBonus = when {
                normalizedEntry.contains(normalizedQuery) -> 0.25
                normalizedQuery.contains(normalizedEntry) -> 0.12
                else -> 0.0
            }
            best = maxOf(best, tokenScore + containsBonus)
        }
        return best
    }

    private fun scoreSubtitleFile(fileName: String): Int {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "srt" -> 50
            "ass" -> 45
            "ssa" -> 40
            "vtt" -> 35
            else -> 10
        }
    }

    private fun tokenOverlap(
        left: String,
        right: String,
    ): Double {
        val leftTokens = left.split(' ').filter { it.length >= 2 }.toSet()
        val rightTokens = right.split(' ').filter { it.length >= 2 }.toSet()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        val inter = leftTokens.intersect(rightTokens).size.toDouble()
        return inter / rightTokens.size.toDouble()
    }

    private fun parseFileList(jsonText: String): List<FileListItem> {
        val result = mutableListOf<FileListItem>()
        val array = JSONArray(jsonText)
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            result +=
                FileListItem(
                    name = obj.optString("name", ""),
                    type = obj.optString("type", ""),
                )
        }
        return result
    }

    private fun buildDirectoryUrl(vararg segments: String): String {
        val path = segments.joinToString("/") { encodePathSegment(it) }
        return "$BASE/files/$path/"
    }

    private fun buildFileUrl(vararg segments: String): String {
        val path = segments.joinToString("/") { encodePathSegment(it) }
        return "$BASE/files/$path"
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            .replace("+", "%20")

    private fun getText(url: String): String {
        val connection = openConnection(url)
        return connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use(BufferedReader::readText)
    }

    private fun saveToVideoFolder(
        folderUri: Uri,
        videoTitle: String,
        sourceSubtitleName: String,
        sourceUrl: String,
    ): String {
        val parent = DocumentFile.fromTreeUri(context, folderUri)
            ?: error("无法访问视频所在文件夹。")

        val subFolder = parent.findFile("sub")?.takeIf { it.isDirectory }
            ?: parent.createDirectory("sub")
            ?: error("无法创建 sub 目录。")

        val outputName = buildSubtitleName(videoTitle, sourceSubtitleName)
        subFolder.findFile(outputName)?.delete()

        val newFile =
            subFolder.createFile(guessMimeType(outputName), outputName)
                ?: error("无法在目标文件夹创建字幕文件。")

        downloadToUri(sourceUrl, newFile.uri)
        return "sub/$outputName"
    }

    private fun buildSubtitleName(videoTitle: String, sourceSubtitleName: String): String {
        val videoBase = videoTitle.substringBeforeLast('.', videoTitle)
        val ext = sourceSubtitleName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val suffix = if (ext.isBlank()) "srt" else ext
        return "$videoBase.$suffix"
    }

    private fun downloadToUri(
        sourceUrl: String,
        outputUri: Uri,
    ) {
        val connection = openConnection(sourceUrl)
        BufferedInputStream(connection.inputStream).use { input ->
            context.contentResolver.openOutputStream(outputUri, "w")?.use { output ->
                input.copyTo(output)
            } ?: error("无法写入字幕文件。")
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        Log.d(TAG, "HTTP GET $url")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Anisubroid/1.0")
        connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        connection.connect()
        if (connection.responseCode !in 200..299) {
            error("网络请求失败：HTTP ${connection.responseCode}")
        }
        return connection
    }

    private fun guessMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "srt" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            "vtt" -> "text/vtt"
            "sup", "txt" -> "application/octet-stream"
            "zip" -> "application/zip"
            "7z" -> "application/x-7z-compressed"
            "rar" -> "application/vnd.rar"
            else -> "application/octet-stream"
        }
    }
}

private data class FileListItem(
    val name: String,
    val type: String,
)
