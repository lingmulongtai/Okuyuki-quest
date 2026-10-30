package com.okuyuki.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    @Test fun `panorama shares require opaque ids and bounded nonempty images`() {
        val valid = PanoramaPhotoShare("0123456789abcdef0123456789abcdef", Protocol.MAX_PANORAMA_PHOTO_BYTES)
        assertTrue(valid.isValid())
        assertEquals(valid, Protocol.json.decodeFromString<PanoramaPhotoShare>(Protocol.json.encodeToString(valid)))
        assertFalse(valid.copy(id = "../1").isValid())
        assertFalse(valid.copy(id = "A".repeat(32)).isValid())
        assertFalse(valid.copy(sizeBytes = 0).isValid())
        assertFalse(valid.copy(sizeBytes = Protocol.MAX_PANORAMA_PHOTO_BYTES + 1).isValid())
    }
    @Test fun `video shares accept large files and reject unsafe identifiers or sizes`() {
        val video = PanoramaVideoShare("0123456789abcdef0123456789abcdef", 5L * 1024 * 1024 * 1024)
        assertTrue(video.isValid())
        assertEquals(video, Protocol.json.decodeFromString<PanoramaVideoShare>(Protocol.json.encodeToString(video)))
        assertFalse(video.copy(id = "../video").isValid())
        assertFalse(video.copy(sizeBytes = 0).isValid())
        assertFalse(video.copy(sizeBytes = Protocol.MAX_PANORAMA_VIDEO_BYTES + 1).isValid())
    }
    @Test fun `video format is explicit and old metadata remains readable`() {
        val old = Protocol.json.decodeFromString<PanoramaVideoShare>("""{"id":"0123456789abcdef0123456789abcdef","sizeBytes":10}""")
        assertEquals(PanoramaVideoFormat.EQUIRECTANGULAR_MP4, old.format)
        for (format in PanoramaVideoFormat.entries) {
            val share = old.copy(format = format)
            assertEquals(share, Protocol.json.decodeFromString<PanoramaVideoShare>(Protocol.json.encodeToString(share)))
        }
        assertThrows(Exception::class.java) {
            Protocol.json.decodeFromString<PanoramaVideoShare>("""{"id":"0123456789abcdef0123456789abcdef","sizeBytes":10,"format":"UNRECOGNIZED"}""")
        }
    }
    @Test fun `raw lens calibration round trips and rejects malformed values`() {
        val lens = PanoramaLensCalibration(listOf(1200f, 1200f, 1920f, 1920f), List(5) { 0f }, listOf(1f, 0f, 0f, 0f))
        val calibration = PanoramaStereoCalibration(lens, lens)
        val share = PanoramaVideoShare("0123456789abcdef0123456789abcdef", 10, PanoramaVideoFormat.OSMO_LRF, calibration)
        assertTrue(share.isValid())
        assertEquals(share, Protocol.json.decodeFromString<PanoramaVideoShare>(Protocol.json.encodeToString(share)))
        assertFalse(share.copy(format = PanoramaVideoFormat.EQUIRECTANGULAR_MP4).isValid())
        assertFalse(lens.copy(intrinsics = emptyList()).isValid())
        assertFalse(lens.copy(distortion = listOf(Float.NaN, 0f, 0f, 0f, 0f)).isValid())
        assertFalse(lens.copy(distortion = listOf(11f, 0f, 0f, 0f, 0f)).isValid())
        assertFalse(lens.copy(quaternion = listOf(0f, 0f, 0f, 0f)).isValid())
        assertFalse(share.copy(calibration = calibration.copy(right = lens.copy(intrinsics = listOf(0f, 1f, 1f, 1f)))).isValid())
    }
    @Test fun `resume and suffix ranges are bounded to the source`() {
        assertEquals(ByteRange(4, 9), ByteRange.parse("bytes=4-", 10))
        assertEquals(ByteRange(8, 9), ByteRange.parse("bytes=-2", 10))
        assertEquals(ByteRange(0, 9), ByteRange.parse("bytes=-99", 10))
        assertEquals(ByteRange(2, 9), ByteRange.parse("bytes=2-99", 10))
        assertEquals(8L, ByteRange.parse("bytes=2-99", 10)!!.length)
    }

    @Test fun `invalid intervals do not expose data`() {
        listOf("bytes=10-", "bytes=5-4", "bytes=-0", "bytes=0-1,3-4", "bytes=-", "bytes=999999999999999999999-")
            .forEach { assertNull(it, ByteRange.parse(it, 10)) }
        assertNull(ByteRange.parse(null, 0))
    }

    @Test fun `unknown fields permit companion evolution`() {
        val hello = Protocol.json.decodeFromString<Hello>("""{"deviceId":"id","deviceName":"Galaxy","appVersion":"alpha","photoCount":0,"future":true}""")
        assertEquals(1, hello.protocolVersion)
        val page = LibraryPage(listOf(Photo("42", "photo.heic", 100, 4000, 3000, 1234)))
        assertEquals(page, Protocol.json.decodeFromString<LibraryPage>(Protocol.json.encodeToString(page)))
    }
}
