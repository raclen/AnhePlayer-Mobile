package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MultiQueueObject

/** 仅复制队列元数据用于落盘，不修改播放中的队列顺序和断点。 */
internal fun snapshotQueuesForPersistence(
    queues: List<MultiQueueObject>,
    currentQueueId: Long?,
    currentPosition: Long?,
): List<MultiQueueObject> {
    val currentQueue = queues.firstOrNull { it.id == currentQueueId }
    // 现有恢复逻辑读取列表末尾；只调整持久化快照，确保恢复的仍是实际当前队列。
    val orderedQueues = if (currentQueue != null) {
        queues.filterNot { it.id == currentQueueId } + currentQueue
    } else {
        queues
    }
    return orderedQueues.mapIndexed { index, queue ->
        queue.copy(
            index = index,
            lastSongPos = if (queue.id == currentQueueId && currentPosition != null && currentPosition >= 0) {
                currentPosition
            } else {
                queue.lastSongPos
            },
        )
    }
}
