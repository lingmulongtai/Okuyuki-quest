package com.okuyuki.stereo

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class StereoProbeTest {
    private fun metadata(left: Int = 7, right: Int = 3) = ByteBuffer.allocate(40)
        .putInt(0).putInt(36).put("grpl".toByteArray())
        .putInt(28).put("ster".toByteArray()).putInt(0).putInt(1).putInt(2).putInt(left).putInt(right).array()

    @Test fun `standard ster group determines eyes independently of primary image`() {
        assertEquals(StereoIds(7, 3), StereoProbe.parseMetadata(metadata()))
    }
    @Test fun `malformed and incomplete groups never guess eyes`() {
        assertNull(StereoProbe.parseMetadata(metadata().copyOf(32)))
        assertNull(StereoProbe.parseMetadata(metadata(3, 3)))
        assertNull(StereoProbe.parseMetadata(metadata(0, 3)))
        assertNull(StereoProbe.parseMetadata(byteArrayOf(0, 0, 0, 0)))
        val malicious = metadata(); ByteBuffer.wrap(malicious).putInt(4, Int.MAX_VALUE)
        assertNull(StereoProbe.parseMetadata(malicious))
    }
}
