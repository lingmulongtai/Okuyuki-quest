package com.okuyuki.quest.link

import com.okuyuki.protocol.Protocol
import java.text.Normalizer

data class Endpoint(val host: String, val port: Int) {
    val url get() = "http://$host:$port"
    /** How the phone shows its address, and how a typed one is stored. */
    val label get() = "$host:$port"
    companion object {
        /**
         * A private-network address as the phone shows it. The port may be left out when the phone uses its usual one, and
         * full-width digits and 。 from a Japanese keyboard are accepted.
         */
        fun parse(text: String): Endpoint? {
            val plain = Normalizer.normalize(text, Normalizer.Form.NFKC).replace('。', '.').trim()
            val match = Regex("(?:http://)?(\\d{1,3}(?:\\.\\d{1,3}){3})(?::(\\d{1,5}))?/?").matchEntire(plain) ?: return null
            val host = match.groupValues[1]
            val octets = host.split('.').map { it.toInt() }
            if (octets.any { it !in 0..255 }) return null
            val local = octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168) || (octets[0] == 169 && octets[1] == 254)
            val port = match.groupValues[2].ifEmpty { "${Protocol.PORT}" }.toInt().takeIf { it in 1..65535 } ?: return null
            return if (local) Endpoint(host, port) else null
        }
    }
}
