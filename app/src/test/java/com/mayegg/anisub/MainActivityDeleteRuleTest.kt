package com.mayegg.anisub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityDeleteRuleTest {
    @Test
    fun isEpisodeEligibleForDelete_returnsTrueAtOrBelowThreshold() {
        assertTrue(isEpisodeEligibleForDelete(1, maxEpisode = 3))
        assertTrue(isEpisodeEligibleForDelete(3, maxEpisode = 3))
    }

    @Test
    fun isEpisodeEligibleForDelete_returnsFalseForUnknownOrAboveThreshold() {
        assertFalse(isEpisodeEligibleForDelete(null, maxEpisode = 3))
        assertFalse(isEpisodeEligibleForDelete(5, maxEpisode = 3))
    }

    @Test
    fun episodeFiltering_usesInclusiveThreshold() {
        val episodes = listOf(1, 3, 5, null)

        val result = episodes.filter { isEpisodeEligibleForDelete(it, maxEpisode = 3) }

        assertEquals(listOf(1, 3), result)
    }
}
