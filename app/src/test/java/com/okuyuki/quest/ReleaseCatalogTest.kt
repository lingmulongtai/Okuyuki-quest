package com.okuyuki.quest

import org.junit.Assert.*
import org.junit.Test

class ReleaseCatalogTest {
    private fun release(tag: String, url: String = "https://github.com/lingmulongtai/Okuyuki-quest/releases/download/$tag/Okuyuki-quest-$tag.apk", draft: Boolean = false) = """
        {"tag_name":"$tag","draft":$draft,"assets":[{"name":"Okuyuki-quest-$tag.apk","state":"uploaded","size":200,"digest":"sha256:${"a".repeat(64)}","browser_download_url":"$url"}]}
    """.trimIndent()

    @Test fun picksNewestPrereleaseNumerically() {
        val json = "[${release("v0.1.0-alpha.10-preview")},${release("v0.1.0-alpha.9-preview")},${release("v0.1.0-alpha.11-preview", draft = true)}]"
        assertEquals("0.1.0-alpha.10-preview", ReleaseCatalog.find(json, "0.1.0-alpha.9-preview", "quest")?.version)
        assertNull(ReleaseCatalog.find(json, "0.1.0-alpha.10-preview", "quest"))
    }
    @Test fun rejectsUntrustedAssetAndTreatsStableAsNewer() {
        val bad = release("v0.1.0", "https://evil.example/app.apk")
        assertNull(ReleaseCatalog.find("[$bad]", "0.1.0-alpha.9-preview", "quest"))
        assertEquals("0.1.0", ReleaseCatalog.find("[${release("v0.1.0")}]", "0.1.0-alpha.9-preview", "quest")?.version)
    }
    @Test fun findsPublishedPreviewInPublicFeedAndChecksItsExactAsset() {
        val feed = """<feed xmlns="http://www.w3.org/2005/Atom"><id>tag:github.com,2008:https://github.com/lingmulongtai/Okuyuki-quest/releases</id><entry><link rel="alternate" href="https://github.com/lingmulongtai/Okuyuki-quest/releases/tag/v0.1.0-alpha.12-preview"/></entry>
            <entry><link href="https://evil.example/releases/tag/v9.0.0"/></entry></feed>"""
        val tag = ReleaseCatalog.latestFeedTag(feed, "0.1.0-alpha.11-preview", "quest")
        assertEquals("v0.1.0-alpha.12-preview", tag)
        val digest = "b".repeat(64)
        val release = ReleaseCatalog.fromChecksums(tag!!, "$digest  Okuyuki-quest-$tag.apk\n", "quest")
        assertEquals(digest, release?.digest)
        assertEquals(0L, release?.size)
        assertNull(ReleaseCatalog.fromChecksums(tag, "$digest  other.apk\n", "quest"))
        assertNull(ReleaseCatalog.fromChecksums(tag, "$digest  Okuyuki-quest-$tag.apk\n$digest  Okuyuki-quest-$tag.apk\n", "quest"))
        assertNull(ReleaseCatalog.latestFeedTag(feed, "0.1.0-alpha.12-preview", "quest"))
        assertThrows(IllegalArgumentException::class.java) { ReleaseCatalog.latestFeedTag("not a feed", "0.1.0-alpha.11-preview", "quest") }
    }
}
