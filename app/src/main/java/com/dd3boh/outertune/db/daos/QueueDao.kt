package com.dd3boh.outertune.db.daos

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.dd3boh.outertune.db.entities.QueueEntity
import com.dd3boh.outertune.db.entities.QueueSong
import com.dd3boh.outertune.db.entities.QueueSongMap
import com.dd3boh.outertune.models.MultiQueueObject
import com.dd3boh.outertune.models.toMediaMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

@Dao
interface QueueDao {

    // region Gets
    @Query("SELECT * from queue ORDER BY `index`")
    fun getAllQueues(): Flow<List<QueueEntity>>

    @Transaction
    @Query("SELECT song.*, queue_song_map.shuffledIndex from queue_song_map JOIN song ON queue_song_map.songId = song.id WHERE queueId = :queueId ORDER BY `index`")
    fun getQueueSongs(queueId: Long): Flow<List<QueueSong>>

    suspend fun readQueue(): List<MultiQueueObject> {
        val resultQueues = ArrayList<MultiQueueObject>()
        val queues = getAllQueues().first()

        queues.forEach { queue ->
            val shuffledSongs = getQueueSongs(queue.id).first()
            if (shuffledSongs.isEmpty()) return@forEach
            resultQueues.add(
                MultiQueueObject(
                    id = queue.id,
                    title = queue.title,
                    queue = shuffledSongs.map {
                        val s = it.song.toMediaMetadata()
                        s.shuffleIndex = it.shuffledIndex
                        s
                    }.toMutableList(),
                    shuffled = queue.shuffled,
                    queuePos = queue.queuePos,
                    lastSongPos = queue.lastSongPos,
                    index = queue.index,
                    playlistId = queue.playlistId
                )
            )
        }

        return resultQueues
    }

    suspend fun getResumptionQueue(): MultiQueueObject? {
        val queues = getAllQueues().first()
        if (queues.isEmpty()) return null
        val q = queues.last()
        val shuffledSongs = getQueueSongs(q.id).first()
        if (shuffledSongs.isEmpty()) return null

        return MultiQueueObject(
            id = q.id,
            title = q.title,
            queue = shuffledSongs.map {
                val s = it.song.toMediaMetadata()
                s.shuffleIndex = it.shuffledIndex
                s
            }.toMutableList(),
            shuffled = q.shuffled,
            queuePos = q.queuePos,
            lastSongPos = q.lastSongPos,
            index = q.index,
            playlistId = q.playlistId
        )
    }
    // endregion

    // region Inserts
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(queue: QueueEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(queueSong: QueueSongMap)
    // endregion

    // region Updates
    @Update
    fun update(queue: QueueEntity)

    @Transaction
    fun updateQueue(mq: MultiQueueObject) {
        update(
            QueueEntity(
                id = mq.id,
                title = mq.title,
                shuffled = mq.shuffled,
                queuePos = mq.queuePos,
                lastSongPos = mq.lastSongPos,
                index = mq.index,
                playlistId = mq.playlistId
            )
        )
    }

    @Transaction
    fun updateAllQueues(mqs: List<MultiQueueObject>) {
        val mqs = mqs.toList() // please no more ConcurrentModificationException I beg you
        // 调用方负责切到 IO；事务返回前必须完成写入，不能另起协程逃逸事务。
        nukeAliens(mqs.map { it.id })
        mqs.forEachIndexed { index, q -> updateQueue(q.copy(index = index)) }
    }

    // endregion

    // region Deletes
    @Delete
    fun delete(mq: QueueEntity)

    @Query("DELETE FROM queue_song_map WHERE queueId = :id")
    fun deleteAllQueueSongs(id: Long)

    @Query("DELETE FROM queue WHERE id = :id")
    fun deleteQueue(id: Long)

    @Query("DELETE FROM queue WHERE id NOT IN (:ids)")
    fun nukeAliens(ids: List<Long>)
    // endregion
}