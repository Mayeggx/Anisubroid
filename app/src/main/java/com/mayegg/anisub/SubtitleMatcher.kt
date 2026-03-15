package com.mayegg.anisub

data class SubtitleCandidate(
    val seriesTitle: String,
    val episode: Int?,
    val originalSubtitleName: String,
    val downloadUrl: String,
)

interface SubtitleMatcher {
    suspend fun findCandidates(video: VideoItem): List<SubtitleCandidate>

    suspend fun downloadCandidate(
        video: VideoItem,
        candidate: SubtitleCandidate,
    ): SubtitleDownloadResult

    suspend fun matchAndDownload(video: VideoItem): SubtitleDownloadResult
}
