package com.dd3boh.outertune.db.daos

import com.dd3boh.outertune.db.entities.QueueEntity
import com.dd3boh.outertune.db.entities.QueueSong
import com.dd3boh.outertune.db.entities.QueueSongMap
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.playback.snapshotQueuesForPersistence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueueDaoTest {
    private fun queue(id: Long, position: Long, index: Int) = MultiQueueObject(
        id = id,
        title = "queue-$id",
        queue = mutableListOf(
            MediaMetadata(
                id = "kgc_$id",
                title = "song-$id",
                artists = emptyList(),
                duration = 180,
                genre = null,
                source = "kg_concept",
                sourceId = "source-$id",
                sourceData = "synthetic-source-data-$id",
                shuffleIndex = 0,
            )
        ),
        queuePos = 0,
        lastSongPos = position,
        index = index,
        playlistId = "playlist-$id",
    )

    @Test fun `all writes complete before updateAllQueues returns`() {
        val first = queue(1, 10_000, 8)
        val second = queue(2, 20_000, 9)
        val dao = RecordingQueueDao(listOf(first, second, queue(3, 30_000, 10)))

        dao.updateAllQueues(listOf(first, second))

        assertEquals(listOf("prune", "update:1", "update:2"), dao.operations)
        assertEquals(setOf(1L, 2L), dao.rows.keys)
        assertEquals(0, dao.rows.getValue(1).index)
        assertEquals(1, dao.rows.getValue(2).index)
        assertEquals(8, first.index)
        assertEquals(9, second.index)
    }

    @Test fun `write failure is propagated to save caller`() {
        val queue = queue(1, 10_000, 0)
        val dao = RecordingQueueDao(listOf(queue))
        val failure = IllegalStateException("synthetic write failure")
        dao.updateFailure = failure

        val caught = runCatching { dao.updateAllQueues(listOf(queue)) }.exceptionOrNull()

        assertSame(failure, caught)
    }

    @Test fun `saved snapshot is immediately consumable by queue restoration`() = runBlocking {
        val current = queue(1, 10_000, 0)
        val other = queue(2, 30_000, 1)
        val dao = RecordingQueueDao(listOf(current, other))
        val snapshot = snapshotQueuesForPersistence(listOf(current, other), current.id, 90_000)

        dao.updateAllQueues(snapshot)
        val restored = dao.readQueue()
        val resumption = requireNotNull(dao.getResumptionQueue())

        assertEquals(listOf(2L, 1L), restored.map { it.id })
        assertEquals(30_000L, restored.first().lastSongPos)
        assertEquals(90_000L, restored.last().lastSongPos)
        assertEquals(current.id, resumption.id)
        assertEquals(90_000L, resumption.lastSongPos)
        assertEquals(0, resumption.queuePos)
        assertEquals("playlist-1", resumption.playlistId)
        assertEquals("kg_concept", resumption.queue.single().source)
        assertEquals("source-1", resumption.queue.single().sourceId)
        assertEquals("synthetic-source-data-1", resumption.queue.single().sourceData)
    }

    @Test fun `saving a restored queue without active media preserves breakpoint`() = runBlocking {
        val queue = queue(1, 90_000, 0)
        val dao = RecordingQueueDao(listOf(queue))
        val restored = dao.readQueue()

        dao.updateAllQueues(snapshotQueuesForPersistence(restored, queue.id, null))

        assertEquals(90_000L, requireNotNull(dao.getResumptionQueue()).lastSongPos)
    }

    // 只替换底层存储，实际运行 QueueDao 的保存、读取与续播逻辑；不模拟 Room 事务实现。
    private class RecordingQueueDao(initial: List<MultiQueueObject>) : QueueDao {
        val rows = initial.associate { queue ->
            queue.id to QueueEntity(
                id = queue.id,
                title = queue.title,
                shuffled = queue.shuffled,
                queuePos = queue.queuePos,
                lastSongPos = queue.lastSongPos,
                index = queue.index,
                playlistId = queue.playlistId,
            )
        }.toMutableMap()
        private val songs = initial.associate { queue ->
            queue.id to queue.queue.map { metadata ->
                QueueSong(Song(metadata.toSongEntity(), emptyList()), metadata.shuffleIndex)
            }
        }.toMutableMap()
        val operations = mutableListOf<String>()
        var updateFailure: RuntimeException? = null

        override fun getAllQueues(): Flow<List<QueueEntity>> = flowOf(rows.values.sortedBy { it.index })
        override fun getQueueSongs(queueId: Long): Flow<List<QueueSong>> = flowOf(songs[queueId].orEmpty())
        override fun insert(queue: QueueEntity) { rows[queue.id] = queue }
        override fun insert(queueSong: QueueSongMap) { error("Not used by queue metadata persistence") }
        override fun update(queue: QueueEntity) {
            updateFailure?.let { throw it }
            operations.add("update:${queue.id}")
            if (queue.id in rows) rows[queue.id] = queue
        }
        override fun delete(mq: QueueEntity) { deleteQueue(mq.id) }
        override fun deleteAllQueueSongs(id: Long) { songs.remove(id) }
        override fun deleteQueue(id: Long) {
            rows.remove(id)
            songs.remove(id)
        }
        override fun nukeAliens(ids: List<Long>) {
            operations.add("prune")
            rows.keys.filterNot { it in ids }.forEach { deleteQueue(it) }
        }
    }
}
