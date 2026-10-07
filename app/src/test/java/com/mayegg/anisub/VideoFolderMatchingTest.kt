package com.mayegg.anisub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFolderMatchingTest {
    @Test
    fun pathToTreeDocId_mapsPrimaryStoragePaths() {
        assertEquals("primary:Download", pathToTreeDocId("/storage/emulated/0/Download"))
        assertEquals("primary:Movies/Anime", pathToTreeDocId("/sdcard/Movies/Anime"))
        assertEquals("primary:Movies/Anime", pathToTreeDocId("/mnt/sdcard/Movies/Anime/"))
        assertEquals("primary:", pathToTreeDocId("/storage/emulated/0"))
    }

    @Test
    fun pathToTreeDocId_mapsExternalSdCardPaths() {
        assertEquals("1234-ABCD:Videos", pathToTreeDocId("/storage/1234-ABCD/Videos"))
        assertEquals("1234-ABCD:", pathToTreeDocId("/storage/1234-ABCD"))
    }

    @Test
    fun pathToTreeDocId_rejectsUnknownPaths() {
        assertNull(pathToTreeDocId(""))
        assertNull(pathToTreeDocId("   "))
        assertNull(pathToTreeDocId("/data/data/com.mayegg.anisub"))
        assertNull(pathToTreeDocId("/storage/emulated/1/Download"))
        assertNull(pathToTreeDocId("content://com.android.externalstorage.documents/tree/primary%3ADownload"))
    }

    @Test
    fun treeDocIdToDisplayPath_roundTripsPrimaryPaths() {
        assertEquals("/storage/emulated/0/Download", treeDocIdToDisplayPath("primary:Download"))
        assertEquals("/storage/emulated/0", treeDocIdToDisplayPath("primary:"))
        assertEquals("/storage/1234-ABCD/Videos", treeDocIdToDisplayPath("1234-ABCD:Videos"))
    }

    @Test
    fun videoNameMatchesQuery_matchesFansubDecoratedNames() {
        val query = "Mushoku Tensei"

        assertTrue(videoNameMatchesQuery("[LoliHouse] Mushoku Tensei - 01 [1080p].mkv", query))
        assertTrue(videoNameMatchesQuery("Mushoku.Tensei.S3.EP01.1080p.WEBRip.mp4", query))
        assertTrue(videoNameMatchesQuery("mushoku_tensei_01.ass.mkv", query))
    }

    @Test
    fun videoNameMatchesQuery_requiresEveryQueryToken() {
        assertTrue(videoNameMatchesQuery("[Loli] Mushoku Tensei - 01 [1080p].mkv", "Mushoku Tensei 1080p"))
        assertFalse(videoNameMatchesQuery("[Loli] Mushoku Tensei - 01 [720p].mkv", "Mushoku Tensei 1080p"))
    }

    @Test
    fun videoNameMatchesQuery_ignoresExtensionAndCase() {
        assertTrue(videoNameMatchesQuery("FRIEREN - 01.MP4", "frieren"))
        assertFalse(videoNameMatchesQuery("Frieren - 01.srt", "srt"))
    }

    @Test
    fun videoNameMatchesQuery_rejectsUnrelatedOrBlankInput() {
        assertFalse(videoNameMatchesQuery("[Loli] Other Show - 01.mkv", "Mushoku Tensei"))
        assertFalse(videoNameMatchesQuery("anything.mkv", ""))
        assertFalse(videoNameMatchesQuery("", "frieren"))
    }
}
