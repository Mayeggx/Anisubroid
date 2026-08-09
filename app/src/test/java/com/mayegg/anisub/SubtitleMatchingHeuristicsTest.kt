package com.mayegg.anisub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleMatchingHeuristicsTest {
    @Test
    fun scoreEntryTitle_prefersCompactTitleOverParentSeries() {
        val parsed =
            SubtitleNameHeuristics.parseVideo(
                "[ASW] BanG Dream! Yumemita - 04 [1080p HEVC][DEB0EE13].mkv",
            )

        val expected = SubtitleNameHeuristics.scoreEntryTitle("BanG Dream! Yume∞Mita", parsed)
        val parentSeries = SubtitleNameHeuristics.scoreEntryTitle("BanG Dream!", parsed)

        assertTrue("紧凑标题应优先于父系列条目", expected > parentSeries)
    }

    @Test
    fun scoreEntryTitle_recognizesRomanNumeralSeasonAndPenalizesOtherSeasons() {
        val parsed =
            SubtitleNameHeuristics.parseVideo(
                "[ASW] Mushoku Tensei S3 - 04 [1080p HEVC][75699BC3].mkv",
            )

        val expected =
            SubtitleNameHeuristics.scoreEntryTitle(
                "Mushoku Tensei III: Isekai Ittara Honki Dasu",
                parsed,
            )
        val genericSeries = SubtitleNameHeuristics.scoreEntryTitle("Mushoku Tensei", parsed)
        val secondSeason =
            SubtitleNameHeuristics.scoreEntryTitle(
                "Mushoku Tensei II: Isekai Ittara Honki Dasu",
                parsed,
            )

        assertEquals(3, parsed.season)
        assertEquals(3, SubtitleNameHeuristics.extractSeason("Mushoku Tensei III"))
        assertTrue("第三季罗马数字标题应优先于泛系列条目", expected > genericSeries)
        assertTrue("第三季不应匹配到第二季", expected > secondSeason)
    }

    @Test
    fun buildJimakuDownloadUrl_decodesEscapedAmpersandWithoutReencodingPath() {
        val href =
            "/entry/12239/download/%5BStudio%20GreenTea&amp;LoliHouse%5D%20Sayonara%20Lara%20-%2001%20%5BWebRip%201080p%20HEVC-10bit%20AAC%20ASSx2%5D%5BJPN%5D.ass"

        val actual = buildJimakuDownloadUrl(href)

        assertEquals(
            "https://jimaku.cc/entry/12239/download/%5BStudio%20GreenTea&LoliHouse%5D%20Sayonara%20Lara%20-%2001%20%5BWebRip%201080p%20HEVC-10bit%20AAC%20ASSx2%5D%5BJPN%5D.ass",
            actual,
        )
    }
}
