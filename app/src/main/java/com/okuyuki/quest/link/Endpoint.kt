package com.okuyuki.quest.link

data class Endpoint(val host: String, val port: Int) {
    val url get() = "http://$host:$port"
    companion object {
        fun parse(text: String): Endpoint? {
            val match = Regex("(?:http://)?(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{1,5})/?").matchEntire(text.trim()) ?: return null
            val host = match.groupValues[1]
            val octets = host.split('.').map { it.toInt() }
            if (octets.any { it !in 0..255 }) return null
            val local = octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168) || (octets[0] == 169 && octets[1] == 254)
            val port = match.groupValues[2].toInt().takeIf { it in 1..65535 } ?: return null
            return if (local) Endpoint(host, port) else null
        }
    }
}
