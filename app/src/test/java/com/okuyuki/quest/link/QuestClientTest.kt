package com.okuyuki.quest.link

import com.okuyuki.protocol.Hello
import com.okuyuki.protocol.Photo
import com.okuyuki.protocol.Protocol
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuestClientTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun `download resumes at the exact offset and carries the pairing token`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val bytes = ByteArray(100) { it.toByte() }
            val cache = TemporaryPhotoCache(folder.newFolder())
            val photo = Photo("1", "photo.heic", 123, 4000, 3000, 100)
            cache.partial("phone:1:123:100").writeBytes(bytes.copyOfRange(0, 40))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 40-99/100").setBody(Buffer().write(bytes.copyOfRange(40, 100))))
            QuestClient(Endpoint(server.hostName, server.port), cache).use { client ->
                client.token = "paired-token"
                assertArrayEquals(bytes, client.original("phone", photo).readBytes())
                val request = server.takeRequest()
                assertEquals("bytes=40-", request.getHeader("Range"))
                assertEquals("Bearer paired-token", request.getHeader("Authorization"))
                assertArrayEquals(bytes, client.original("phone", photo).readBytes())
                assertEquals(1, server.requestCount)
            }
        }
    }
    @Test fun `mismatched partial responses never become completed photos`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val cache = TemporaryPhotoCache(folder.newFolder())
            val photo = Photo("1", "photo.heic", 1, 4000, 3000, 100)
            cache.partial("phone:1:1:100").writeBytes(ByteArray(20))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 21-99/100").setBody("bad"))
            QuestClient(Endpoint(server.hostName, server.port), cache).use { client ->
                assertTrue(runCatching { client.original("phone", photo) }.isFailure)
                assertNull(cache.get("phone:1:1:100"))
            }
        }
    }
    @Test fun `the phone's own explanation is shown, ending like the app's messages`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"写真が見つかりません"}"""))
            server.enqueue(MockResponse().setResponseCode(500).setBody("<html>oops</html>"))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"スマホ側で接続を許可してください"}"""))
            QuestClient(Endpoint(server.hostName, server.port), TemporaryPhotoCache(folder.newFolder())).use { client ->
                assertEquals("写真が見つかりません。", describe(runCatching { client.library() }.exceptionOrNull()!!))
                assertEquals("スマホが応答できませんでした（500）。", describe(runCatching { client.library() }.exceptionOrNull()!!))
                val refused = runCatching { client.library() }.exceptionOrNull() as LinkError
                assertTrue(refused.unauthorized)
                assertEquals("スマホで接続を許可してください。", describe(refused))
            }
        }
    }
    @Test fun `a phone on another protocol version asks for both apps to be updated`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(Protocol.json.encodeToString(Hello("phone", "SM-S938Q", "9.0", 1, protocolVersion = 9))))
            QuestClient(Endpoint(server.hostName, server.port), TemporaryPhotoCache(folder.newFolder())).use { client ->
                assertEquals("Quest とスマホの Okuyuki を、どちらも最新にしてください。", describe(runCatching { client.hello() }.exceptionOrNull()!!))
            }
        }
    }
    @Test fun `opening a photo reports the bytes as they arrive`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val bytes = ByteArray(300_000) { (it % 251).toByte() }
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val photo = Photo("7", "photo.heic", 5, 4000, 3000, bytes.size.toLong())
            QuestClient(Endpoint(server.hostName, server.port), TemporaryPhotoCache(folder.newFolder())).use { client ->
                val seen = mutableListOf<Long>()
                assertArrayEquals(bytes, client.original("phone", photo) { seen += it }.readBytes())
                assertEquals(0L, seen.first())
                assertEquals(bytes.size.toLong(), seen.last())
                assertEquals(seen.sorted(), seen)
            }
        }
    }
    @Test fun `the event socket tells a removed pairing from a phone that went away`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"library_changed"}""")
                    webSocket.close(1008, "pairing removed")
                }
            }))
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(1001, "going away") }
            }))
            server.enqueue(MockResponse().setResponseCode(401))
            QuestClient(Endpoint(server.hostName, server.port), TemporaryPhotoCache(folder.newFolder())).use { client ->
                var opened = 0
                var changed = 0
                assertTrue(withTimeout(10_000) { client.watch(opened = { opened++ }, changed = { changed++ }) })
                assertEquals(1, opened)
                assertEquals(1, changed)
                assertFalse(withTimeout(10_000) { client.watch {} })
                assertTrue(withTimeout(10_000) { client.watch {} })
            }
        }
    }
}
