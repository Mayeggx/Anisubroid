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

    @Test
    fun shouldShowPushButton_trueWhenSameDeviceAndHasLocalBinding() {
        val entry =
            SyncEntryUi(
                id = "id",
                displayName = "entry",
                deviceId = "device-a",
                deviceName = "Device A",
                repoPath = "entries/device-a/entry",
                updatedAt = 1L,
                folderUri = "content://folder",
                folderLabel = "folder",
            )

        assertEquals(true, shouldShowPushButton(entry, "device-a"))
    }

    @Test
    fun shouldShowPushButton_falseWhenDifferentDevice() {
        val entry =
            SyncEntryUi(
                id = "id",
                displayName = "entry",
                deviceId = "device-a",
                deviceName = "Device A",
                repoPath = "entries/device-a/entry",
                updatedAt = 1L,
                folderUri = "content://folder",
                folderLabel = "folder",
            )

        assertEquals(false, shouldShowPushButton(entry, "device-b"))
    }

    @Test
    fun shouldShowPushButton_falseWhenNoLocalBindingFolder() {
        val entry =
            SyncEntryUi(
                id = "id",
                displayName = "entry",
                deviceId = "device-a",
                deviceName = "Device A",
                repoPath = "entries/device-a/entry",
                updatedAt = 1L,
                folderUri = null,
                folderLabel = null,
            )

        assertEquals(false, shouldShowPushButton(entry, "device-a"))
    }
}
