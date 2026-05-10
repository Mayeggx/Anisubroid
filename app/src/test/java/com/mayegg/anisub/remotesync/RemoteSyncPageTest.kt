package com.mayegg.anisub.remotesync

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteSyncPageTest {
    @Test
    fun sanitizePathSegment_keepsSafeCharacters() {
        assertEquals("abc-123._-z", sanitizePathSegment("ABC 123._-z"))
    }

    @Test
    fun sanitizePathSegment_replacesUnsafeCharacters() {
        assertEquals("hello-world", sanitizePathSegment(" hello@世界 world "))
    }

    @Test
    fun sanitizePathSegment_fallbackWhenEmpty() {
        assertEquals("entry", sanitizePathSegment("  ###  "))
    }
}
