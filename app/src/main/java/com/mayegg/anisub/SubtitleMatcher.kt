package com.mayegg.anisub

interface SubtitleMatcher {
    suspend fun matchAndDownload(video: VideoItem): SubtitleDownloadResult
}
