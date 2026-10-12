package com.okuyuki.quest.link

import com.okuyuki.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointTest {
    @Test fun `the address the phone shows is read as is`() {
        assertEquals(Endpoint("192.168.1.23", 38765), Endpoint.parse("192.168.1.23:38765"))
        assertEquals(Endpoint("10.0.0.8", 40001), Endpoint.parse(" http://10.0.0.8:40001/ "))
    }

    @Test fun `the usual port may be left out`() {
        assertEquals(Endpoint("172.20.10.2", Protocol.PORT), Endpoint.parse("172.20.10.2"))
    }

    @Test fun `full-width digits and Japanese full stops are accepted`() {
        assertEquals(Endpoint("192.168.1.23", 38765), Endpoint.parse("１９２．１６８．１．２３：３８７６５"))
        assertEquals(Endpoint("192.168.1.23", Protocol.PORT), Endpoint.parse("192。168。1。23"))
    }

    @Test fun `only addresses on the home network are accepted`() {
        listOf("8.8.8.8:38765", "192.168.1.300", "192.168.1.23:0", "192.168.1.23:70000", "phone.local", "", "192.168.1").forEach {
            assertNull(it, Endpoint.parse(it))
        }
    }

    @Test fun `the label is what the phone shows`() {
        assertEquals("192.168.1.23:38765", Endpoint("192.168.1.23", 38765).label)
    }
}
