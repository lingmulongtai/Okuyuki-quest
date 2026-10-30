package com.okuyuki.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

/** The fixtures are shared with the TV app (Okuyuki-webos `protocol/fixtures/v2`), which reads them in its own tests. */
class ProtocolV2Test {
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/fixtures/v2/$name.json")) { name }.readText()
    private inline fun <reified T> decode(name: String): T = Protocol.json.decodeFromString(fixture(name))

    /** The TV reads a missing field and null alike, so nulls are dropped before comparing. */
    private fun withoutNulls(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.filterValues { it != JsonNull }.mapValues { withoutNulls(it.value) })
        is JsonArray -> JsonArray(element.map(::withoutNulls))
        else -> element
    }

    private inline fun <reified T> assertSends(name: String) {
        val sent = Protocol.json.parseToJsonElement(Protocol.json.encodeToString(decode<T>(name)))
        assertEquals(name, withoutNulls(Protocol.json.parseToJsonElement(fixture(name))), withoutNulls(sent))
    }

    @Test fun `what the TV sends decodes`() {
        val request = decode<PairRequest>("pair-request")
        assertEquals("tv", request.clientKind)
        assertNull(request.requestId)
        val capabilities = decode<Capabilities>("capabilities")
        assertEquals(Screen(1920, 1080, 3840, 2160), capabilities.screen)
        assertTrue("hevc-main10" in capabilities.video)
        assertEquals(listOf("hdr10", "hlg"), capabilities.hdr)
        assertEquals("1000123", decode<Event>("event-viewing").itemId)
    }

    @Test fun `what the phone sends matches the TV fixtures`() {
        assertSends<Hello>("hello")
        assertSends<PairResponse>("pair-pending")
        assertSends<PairResponse>("pair-approved")
        assertSends<MediaKey>("session")
        assertSends<Item>("item")
        assertSends<ItemPage>("library-page")
        assertSends<Prepared>("prepare-ready")
        assertSends<Prepared>("prepare-preparing")
        assertSends<Prepared>("prepare-unsupported")
        listOf("event-heartbeat", "event-library-changed", "event-prepared", "event-session-closed", "event-show")
            .forEach { assertSends<Event>(it) }
        assertSends<ApiError>("error")

        assertEquals(listOf(1, 2), decode<Hello>("hello").protocolVersions)
        val page = decode<ItemPage>("library-page")
        assertEquals(1532, page.total)
        assertEquals(61233L, page.items.single { it.kind == "video" }.durationMs)
        assertNull(decode<Item>("item").durationMs)
        assertEquals(Event("prepared", "1000123", "still", "ready"), decode<Event>("event-prepared"))
    }

    @Test fun `headsets and hosts from before v2 still decode`() {
        val request = Protocol.json.decodeFromString<PairRequest>("""{"clientId":"quest","clientName":"Quest 3S"}""")
        assertEquals("headset", request.clientKind)
        val hello = Protocol.json.decodeFromString<Hello>("""{"deviceId":"id","deviceName":"Galaxy","appVersion":"alpha","photoCount":0,"protocolVersion":1}""")
        assertEquals(listOf(1), hello.protocolVersions)
        assertEquals(Event("viewing", "42"), Protocol.json.decodeFromString<Event>("""{"type":"viewing","itemId":"42"}"""))
    }
}
