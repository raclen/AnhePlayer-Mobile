package com.dd3boh.outertune.utils.scanners

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalAudioFileTest {
    @get:Rule val storage = TemporaryFolder()

    @Test fun `stale index drops a deleted file and then an entire directory`() {
        val dir = storage.newFolder("Music", "AnhePlay")
        val first = File(dir, "first.mp3").apply { writeBytes(ByteArray(4096)) }
        val second = File(dir, "second.flac").apply { writeBytes(ByteArray(8192)) }
        val indexed = listOf(first, second)
        fun scan() = indexed.mapNotNull { it.scanSizeBytes() }

        assertEquals(listOf(4096L, 8192L), scan())
        assertTrue(first.delete())
        assertNull(first.readableSizeBytes())
        assertEquals(listOf(8192L), scan())
        assertTrue(dir.deleteRecursively())
        assertTrue(scan().isEmpty())
        assertTrue(scan().isEmpty())
    }

    @Test fun `trash directories zero byte files and directory paths are not songs`() {
        val trash = storage.newFolder("Music", ".Trash")
        val recycled = File(trash, "song.mp3").apply { writeBytes(ByteArray(4096)) }
        val pending = storage.newFile(".trashed-123-song.mp3").apply { writeBytes(ByteArray(4096)) }
        assertNull(recycled.scanSizeBytes())
        assertNull(pending.scanSizeBytes())
        assertNull(trash.scanSizeBytes())
        assertNull(storage.newFile("empty.mp3").scanSizeBytes())
    }

    @Test fun `size comes from the file including an actual file of 268 bytes`() {
        val song = storage.newFile("song.mp3").apply { writeBytes(ByteArray(268)) }
        assertEquals(268L, song.scanSizeBytes())
        song.appendBytes(ByteArray(1024))
        assertEquals(1292L, song.readableSizeBytes())
        assertEquals(1292L, song.scanSizeBytes())
    }
}
