package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicServiceInitializationTest {
    @Test
    fun `constructor does not require an attached context or injected dependencies`() {
        // Android 先构造 Service，再绑定 Context；此时不能读取 DataStore 或注入对象。
        val service = MusicService()

        assertNull(service.baseContext)
        assertEquals(1f, service.playerVolume.value, 0f)
    }
}
