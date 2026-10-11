package com.okuyuki.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
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
