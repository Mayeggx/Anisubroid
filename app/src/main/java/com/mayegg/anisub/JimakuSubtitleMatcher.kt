package com.mayegg.anisub

import android.content.Context
import android.net.Uri
import android.text.Html
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.PatternSyntaxException
import kotlin.math.max

data class SubtitleDownloadResult(
    val savedFileName: String,
    val seriesTitle: String,
    val episode: Int?,
    val originalSubtitleName: String,
)

class JimakuSubtitleMatcher(
    private val context: Context,
) : SubtitleMatcher {
    companion object {
        private const val TAG = "JimakuMatcher"
    }

    override suspend fun findCandidates(video: VideoItem): List<SubtitleCandidate> =
        withContext(Dispatchers.IO) {
            val parsed = SubtitleNameHeuristics.parseVideo(video.title)
            val entry = findBestEntry(parsed)
            findEpisodeCandidates(entry, parsed)
        }

    override suspend fun downloadCandidate(
        video: VideoItem,
        candidate: SubtitleCandidate,
    ): SubtitleDownloadResult =
        withContext(Dispatchers.IO) {
            val download = DownloadItem(url = candidate.downloadUrl, name = candidate.originalSubtitleName)
            val saved = saveToVideoFolder(video.folderUri, video.title, download)
            SubtitleDownloadResult(
                savedFileName = saved,
                seriesTitle = candidate.seriesTitle,
                episode = candidate.episode,
                originalSubtitleName = candidate.originalSubtitleName,
            )
        }

    override suspend fun matchAndDownload(video: VideoItem): SubtitleDownloadResult =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "matchAndDownload start title=${video.title}")
            val parsed = SubtitleNameHeuristics.parseVideo(video.title)
            Log.i(TAG, "parsed title base=${parsed.baseTitle}, season=${parsed.season}, episode=${parsed.episode}, queries=${parsed.queryTitles}")
            val entry = findBestEntry(parsed)
            Log.i(TAG, "entry matched id=${entry.id}, title=${entry.title}")
            val download = findBestDownload(entry, parsed)
            Log.i(TAG, "download matched name=${download.name}, url=${download.url}")
            val saved = saveToVideoFolder(video.folderUri, video.title, download)
            Log.i(TAG, "saved subtitle file=$saved")
            SubtitleDownloadResult(
                savedFileName = saved,
                seriesTitle = entry.title,
                episode = parsed.episode,
                originalSubtitleName = download.name,
            )
        }

    private fun findBestEntry(parsed: ParsedVideo): EntryItem {
        val homeHtml = getText("https://jimaku.cc/")
        val entries = parseIndexEntries(homeHtml)
        if (entries.isEmpty()) error("Jimaku 首页解析失败，未找到任何条目。")

        val picked =
            entries
                .asSequence()
                .map { entry -> entry to scoreEntry(entry.title, parsed) }
                .maxByOrNull { it.second }
                ?: error("未找到可匹配的剧集条目。")

        if (picked.second < 0.32) {
            error("未找到足够接近的视频条目（最佳得分 ${"%.2f".format(picked.second)}）。")
        }
        return picked.first
    }

    private fun findBestDownload(
        entry: EntryItem,
        parsed: ParsedVideo,
    ): DownloadItem {
        val episodeMatches = findEpisodeCandidates(entry, parsed)
        return episodeMatches
            .map { DownloadItem(it.downloadUrl, it.originalSubtitleName) }
            .maxByOrNull { scoreSubtitleFile(parsed, it.name) }
            ?: error("字幕筛选失败。")
    }

    private fun findEpisodeCandidates(
        entry: EntryItem,
        parsed: ParsedVideo,
    ): List<SubtitleCandidate> {
        val html = getText("https://jimaku.cc/entry/${entry.id}")
        val files = parseDownloadItems(html)
        if (files.isEmpty()) error("目标条目内没有可下载字幕文件。")

        val episodeMatches =
            if (parsed.episode != null) {
                files.filter { item ->
                    val ep = SubtitleNameHeuristics.extractEpisode(item.name)
                    ep == parsed.episode
                }
            } else {
                files
            }
        if (episodeMatches.isEmpty()) error("已进入条目，但没有可用字幕。")

        return episodeMatches
            .sortedByDescending { scoreSubtitleFile(parsed, it.name) }
            .map {
                SubtitleCandidate(
                    seriesTitle = entry.title,
                    episode = parsed.episode,
                    originalSubtitleName = it.name,
                    downloadUrl = it.url,
                )
            }
    }

    private fun saveToVideoFolder(
        folderUri: Uri,
        videoTitle: String,
        download: DownloadItem,
    ): String {
        val parent = DocumentFile.fromTreeUri(context, folderUri)
            ?: error("无法访问视频所在文件夹。")

        val subFolder = parent.findFile("sub")?.takeIf { it.isDirectory }
            ?: parent.createDirectory("sub")
            ?: error("无法创建 sub 目录。")

        val outputName = buildSubtitleName(videoTitle, download.name)
        subFolder.findFile(outputName)?.delete()

        val newFile =
            subFolder.createFile(guessMimeType(outputName), outputName)
                ?: error("无法在目标文件夹创建字幕文件。")

        downloadToUri(download.url, newFile.uri)
        return "sub/$outputName"
    }

    private fun buildSubtitleName(videoTitle: String, sourceSubtitleName: String): String {
        val videoBase = videoTitle.substringBeforeLast('.', videoTitle)
        val ext = sourceSubtitleName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val suffix = if (ext.isBlank()) "srt" else ext
        return "$videoBase.$suffix"
    }

    private fun parseIndexEntries(html: String): List<EntryItem> {
        val regex = compileRegex("parseIndexEntries", """<a href="/entry/(\d+)" class="table-data file-name">([^<]+)</a>""")
        return regex.findAll(html)
            .map {
                EntryItem(
                    id = it.groupValues[1],
                    title = decodeHtml(it.groupValues[2]),
                )
            }
            .toList()
    }

    private fun parseDownloadItems(html: String): List<DownloadItem> {
        val regex = compileRegex("parseDownloadItems", """<a href="(/entry/\d+/download/[^"]+)" class="table-data file-name">([^<]+)</a>""")
        return regex.findAll(html)
            .map {
                DownloadItem(
                    url = "https://jimaku.cc${it.groupValues[1]}",
                    name = decodeHtml(it.groupValues[2]),
                )
            }
            .toList()
    }

    private fun scoreEntry(
        entryTitle: String,
        parsed: ParsedVideo,
    ): Double {
        val normalizedEntry = SubtitleNameHeuristics.normalize(entryTitle)
        var best = 0.0
        for (query in parsed.queryTitles) {
            val normalizedQuery = SubtitleNameHeuristics.normalize(query)
            val tokenScore = tokenOverlap(normalizedEntry, normalizedQuery)
            val containsBonus = when {
                normalizedEntry.contains(normalizedQuery) -> 0.25
                normalizedQuery.contains(normalizedEntry) -> 0.12
                else -> 0.0
            }
            best = max(best, tokenScore + containsBonus)
        }

        val seasonBonus =
            parsed.season?.let { season ->
                if (containsSeason(entryTitle, season)) 0.2 else 0.0
            } ?: 0.0
        return best + seasonBonus
    }

    private fun scoreSubtitleFile(
        parsed: ParsedVideo,
        fileName: String,
    ): Int {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val extScore =
            when (ext) {
                "srt" -> 50
                "ass" -> 45
                "ssa" -> 40
                "vtt" -> 35
                "sup" -> 20
                "7z", "zip", "rar" -> 12
                else -> 10
            }
        val titleScore =
            (tokenOverlap(
                SubtitleNameHeuristics.normalize(fileName),
                SubtitleNameHeuristics.normalize(parsed.baseTitle),
            ) * 40.0).toInt()
        val seasonScore =
            parsed.season?.let { if (containsSeason(fileName, it)) 8 else 0 } ?: 0
        return extScore + titleScore + seasonScore
    }

    private fun containsSeason(
        text: String,
        season: Int,
    ): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return compileRegex("containsSeason:s-season", """\bs(?:eason)?\s*0?$season\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            compileRegex("containsSeason:ordinal", """\b$season(?:st|nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower) ||
            compileRegex("containsSeason:part", """\bpart\s*0?$season\b""", RegexOption.IGNORE_CASE).containsMatchIn(lower)
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

    private fun getText(url: String): String {
        val connection = openConnection(url)
        return connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use(BufferedReader::readText)
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

    private fun compileRegex(
        label: String,
        pattern: String,
        option: RegexOption? = null,
    ): Regex =
        try {
            if (option == null) Regex(pattern) else Regex(pattern, option)
        } catch (e: PatternSyntaxException) {
            Log.e(TAG, "Regex compile failed [$label], pattern=$pattern", e)
            throw e
        }

    private fun decodeHtml(text: String): String =
        Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()

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

private data class EntryItem(
    val id: String,
    val title: String,
)

private data class DownloadItem(
    val url: String,
    val name: String,
)

data class ParsedVideo(
    val baseTitle: String,
    val season: Int?,
    val episode: Int?,
    val queryTitles: List<String>,
)

object SubtitleNameHeuristics {
    private const val TAG = "SubtitleHeuristics"

    private val seasonRegexes =
        listOf(
            compileRegex("season:s", """\bS(?:eason)?\s*0?([1-9]\d?)\b""", RegexOption.IGNORE_CASE),
            compileRegex("season:ordinal", """\b([1-9]\d?)(?:st|nd|rd|th)\s+Season\b""", RegexOption.IGNORE_CASE),
            compileRegex("season:part", """\bPart\s*0?([1-9]\d?)\b""", RegexOption.IGNORE_CASE),
        )
    private val resolutionLike = setOf(360, 480, 540, 576, 720, 900, 1080, 1440, 2160)

    fun parseVideo(name: String): ParsedVideo {
        val stem = removeExtension(name)
        val season = extractSeason(stem)
        val episode = extractEpisode(stem)
        val base = cleanBaseTitle(stem)
        if (base.isBlank()) error("无法从文件名中识别剧名：$name")

        val queries = mutableListOf<String>()
        queries += base
        if (season != null) {
            queries += "$base Season $season"
            queries += "$base ${ordinal(season)} Season"
            queries += "$base S$season"
        }
        queries += base.replace(compileRegex("parseVideo:removeSeason", """\b\d+(st|nd|rd|th)?\s+season\b""", RegexOption.IGNORE_CASE), "").trim()

        return ParsedVideo(
            baseTitle = base,
            season = season,
            episode = episode,
            queryTitles = queries.map { normalize(it) }.filter { it.isNotBlank() }.distinct(),
        )
    }

    fun extractEpisode(name: String): Int? {
        val stem = removeExtension(name)
        compileRegex("extractEpisode:sxe", """\bS\d{1,2}\s*E(\d{1,3})\b""", RegexOption.IGNORE_CASE)
            .find(stem)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        compileRegex("extractEpisode:episode", """\b(?:E|EP|Episode)\s*0?(\d{1,3})\b""", RegexOption.IGNORE_CASE)
            .find(stem)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        compileRegex("extractEpisode:cnjp", """第\s*([0-9]{1,3})\s*[话話集]""")
            .find(stem)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        val generic =
            compileRegex("extractEpisode:generic", """(?:^|[\s._-])0*([0-9]{1,3})(?=$|[\s._\-\[\(])""")
                .findAll(stem)
                .mapNotNull { match ->
                    val value = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    if (value !in 1..300 || value in resolutionLike) return@mapNotNull null
                    value to match.range.first
                }
                .toList()
        return generic.maxByOrNull { it.second }?.first
    }

    fun extractSeason(name: String): Int? {
        for (regex in seasonRegexes) {
            val hit = regex.find(name) ?: continue
            val value = hit.groupValues.getOrNull(1)?.toIntOrNull()
            if (value != null && value in 1..99) return value
        }
        return null
    }

    fun normalize(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace(compileRegex("normalize:token", """[^\p{L}\d]+"""), " ")
            .replace(compileRegex("normalize:spaces", """\s+"""), " ")
            .trim()

    private fun cleanBaseTitle(name: String): String {
        var t = name
        t = t.replace(compileRegex("cleanBaseTitle:square", """\[[^\]]*]"""), " ")
        t = t.replace(compileRegex("cleanBaseTitle:paren", """\([^\)]*\)"""), " ")
        t = t.replace(compileRegex("cleanBaseTitle:brace", """\{[^}]*\}"""), " ")
        t = t.replace(compileRegex("cleanBaseTitle:season", """\bS(?:eason)?\s*\d+\b""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(compileRegex("cleanBaseTitle:ordinalSeason", """\b\d+(st|nd|rd|th)\s+season\b""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(compileRegex("cleanBaseTitle:episode", """\b(?:E|EP|Episode)\s*\d+\b""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(compileRegex("cleanBaseTitle:cnjpEpisode", """第\s*\d+\s*[话話集]"""), " ")
        t = t.replace(compileRegex("cleanBaseTitle:genericNumber", """(?:^|[\s._-])0*\d{1,3}(?=$|[\s._\-\[\(])"""), " ")
        t = t.replace(
            compileRegex("cleanBaseTitle:codec", """\b(HEVC|x265|x264|10bit|8bit|AAC|FLAC|WEBRip|BDRip|BluRay|WEB|TV|AT-X|AMZN|NF)\b""", RegexOption.IGNORE_CASE),
            " ",
        )
        t = t.replace(compileRegex("cleanBaseTitle:resolution", """\b(360p|480p|540p|576p|720p|1080p|1440p|2160p)\b""", RegexOption.IGNORE_CASE), " ")
        t = t.replace('_', ' ').replace('.', ' ').replace('-', ' ')
        return t.replace(compileRegex("cleanBaseTitle:spaces", """\s+"""), " ").trim()
    }

    private fun removeExtension(name: String): String = name.substringBeforeLast('.', name)

    private fun ordinal(value: Int): String {
        val suffix =
            if (value % 100 in 11..13) {
                "th"
            } else {
                when (value % 10) {
                    1 -> "st"
                    2 -> "nd"
                    3 -> "rd"
                    else -> "th"
                }
            }
        return "$value$suffix"
    }

    private fun compileRegex(
        label: String,
        pattern: String,
        option: RegexOption? = null,
    ): Regex =
        try {
            if (option == null) Regex(pattern) else Regex(pattern, option)
        } catch (e: PatternSyntaxException) {
            Log.e(TAG, "Regex compile failed [$label], pattern=$pattern", e)
            throw e
        }
}
