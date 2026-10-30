package com.okuyuki.quest

import org.junit.Assert.*
import org.junit.Test

class OsmoProjectionSizeTest {
    @Test fun `high resolution retries the standard surface when unavailable`() {
        val attempts = mutableListOf<Pair<Int, Int>>()
        val size = selectProjectionSize(true) { width, height ->
            attempts += width to height
            width == 2048
        }
        assertEquals(listOf(4096 to 2048, 2048 to 1024), attempts)
        assertEquals(2048 to 1024, size)
        assertEquals(4096 to 2048, selectProjectionSize(true) { _, _ -> true })
    }

    @Test fun `standard mode does not allocate high resolution`() {
        val attempts = mutableListOf<Pair<Int, Int>>()
        assertEquals(2048 to 1024, selectProjectionSize(false) { width, height ->
            attempts += width to height; true
        })
        assertEquals(listOf(2048 to 1024), attempts)
        assertNull(selectProjectionSize(true) { _, _ -> false })
    }
}
