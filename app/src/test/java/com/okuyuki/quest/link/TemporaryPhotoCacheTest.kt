package com.okuyuki.quest.link

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TemporaryPhotoCacheTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun `crash remnants are wiped and oldest photos evict at cap`() {
        val dir = folder.newFolder("photos")
        File(dir, "old.heic").writeBytes(ByteArray(10))
        val cache = TemporaryPhotoCache(dir, 16)
        assertEquals(0L, cache.usage())
        cache.partial("one").writeBytes(ByteArray(10)); val old = cache.commit("one", 10)
        old.setLastModified(1)
        cache.partial("two").writeBytes(ByteArray(10)); cache.commit("two", 10)
        assertNull(cache.get("one")); assertNotNull(cache.get("two"))
        cache.clear(); assertEquals(0L, cache.usage())
    }
    @Test fun `photo keys cannot escape the app directory`() {
        val cache = TemporaryPhotoCache(folder.newFolder())
        assertEquals(cache.directory.canonicalFile, cache.partial("../../private").parentFile!!.canonicalFile)
    }
    @Test fun `space is reserved before incoming bytes can exceed the cap`() {
        val cache = TemporaryPhotoCache(folder.newFolder(), 16)
        cache.partial("old").writeBytes(ByteArray(10)); cache.commit("old", 10)
        cache.reserve("new", 10)
        assertNull(cache.get("old")); assertEquals(0L, cache.usage())
        assertTrue(runCatching { cache.reserve("huge", 17) }.isFailure)
    }
    @Test fun `manual addresses accept only local network endpoints`() {
        assertEquals(Endpoint("192.168.1.2", 1234), Endpoint.parse("http://192.168.1.2:1234"))
        listOf("https://192.168.1.2:80", "8.8.8.8:80", "127.0.0.1:80", "192.168.1.999:80", "192.168.1.2:0", "192.168.1.2:80/secret")
            .forEach { assertNull(it, Endpoint.parse(it)) }
    }
}
