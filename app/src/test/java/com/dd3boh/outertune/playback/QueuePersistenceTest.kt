package com.dd3boh.outertune.playback

import androidx.media3.common.C
import com.dd3boh.outertune.models.MultiQueueObject
import org.junit.Assert.*
import org.junit.Test

class QueuePersistenceTest {
    private fun queue(id: Long, position: Long, index: Int) = MultiQueueObject(
        id = id,
        title = "queue-$id",
        queue = mutableListOf(),
        queuePos = 2,
        lastSongPos = position,
        index = index,
        playlistId = "playlist-$id",
    )

    @Test fun `empty queue snapshot is safe`() {
        assertTrue(snapshotQueuesForPersistence(emptyList(), null, null).isEmpty())
    }

    @Test fun `reordered current queue receives position and becomes resumption queue`() {
        val current = queue(1, 10_000, 0)
        val other = queue(2, 30_000, 1)

        val snapshot = snapshotQueuesForPersistence(listOf(current, other), current.id, 90_000)

        assertEquals(listOf(2L, 1L), snapshot.map { it.id })
        assertEquals(listOf(0, 1), snapshot.map { it.index })
        assertEquals(30_000L, snapshot.first().lastSongPos)
        assertEquals(90_000L, snapshot.last().lastSongPos)
        assertEquals(2, snapshot.last().queuePos)
        assertEquals("playlist-1", snapshot.last().playlistId)
        assertNotSame(current, snapshot.last())
        assertEquals(10_000L, current.lastSongPos)
        assertEquals(0, current.index)
        assertEquals(1, other.index)
    }

    @Test fun `snapshot does not change live list order`() {
        val queues = mutableListOf(queue(1, 10_000, 0), queue(2, 20_000, 1), queue(3, 30_000, 2))

        val snapshot = snapshotQueuesForPersistence(queues, 2, 90_000)

        assertEquals(listOf(1L, 2L, 3L), queues.map { it.id })
        assertEquals(listOf(1L, 3L, 2L), snapshot.map { it.id })
    }

    @Test fun `no active media preserves existing position`() {
        val snapshot = snapshotQueuesForPersistence(listOf(queue(1, 90_000, 0)), 1, null)

        assertEquals(90_000L, snapshot.single().lastSongPos)
    }

    @Test fun `valid zero position is saved`() {
        val snapshot = snapshotQueuesForPersistence(listOf(queue(1, 90_000, 0)), 1, 0)

        assertEquals(0L, snapshot.single().lastSongPos)
    }

    @Test fun `unset position never replaces a valid breakpoint`() {
        val snapshot = snapshotQueuesForPersistence(listOf(queue(1, 90_000, 0)), 1, C.TIME_UNSET)

        assertEquals(90_000L, snapshot.single().lastSongPos)
    }

    @Test fun `unknown current queue does not overwrite another queue`() {
        val queues = listOf(queue(1, 10_000, 0), queue(2, 20_000, 1))
        for (currentId in listOf(null, 999L)) {
            val snapshot = snapshotQueuesForPersistence(queues, currentId, 90_000)
            assertEquals(listOf(1L, 2L), snapshot.map { it.id })
            assertEquals(listOf(10_000L, 20_000L), snapshot.map { it.lastSongPos })
        }
    }

    @Test fun `live metadata changes after capture do not change final snapshot`() {
        val current = queue(1, 10_000, 0)
        val snapshot = snapshotQueuesForPersistence(listOf(current), 1, 90_000)

        current.lastSongPos = 0
        current.queuePos = 0
        current.index = 7
        current.title = "changed"

        assertEquals(90_000L, snapshot.single().lastSongPos)
        assertEquals(2, snapshot.single().queuePos)
        assertEquals(0, snapshot.single().index)
        assertEquals("queue-1", snapshot.single().title)
    }

    @Test fun `second snapshot without media retains saved breakpoint`() {
        val saved = snapshotQueuesForPersistence(listOf(queue(1, 10_000, 0)), 1, 90_000)
        val repeated = snapshotQueuesForPersistence(saved, 1, null)

        assertEquals(90_000L, repeated.single().lastSongPos)
    }
}
