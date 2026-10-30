package com.okuyuki.quest

import kotlinx.serialization.json.*
import java.net.URI

internal data class UpdateRelease(val version: String, val url: String, val digest: String, val size: Long)

/** GitHub's /latest excludes prereleases. Compare versions instead of relying on API order. */
internal object ReleaseCatalog {
    private val pattern = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-(alpha|beta|rc)\\.(\\d+)(?:-preview)?)?$")
    private fun version(value: String): List<Long>? {
        val match = pattern.matchEntire(value.removePrefix("v")) ?: return null
        val parts = match.groupValues
        val stage = when (parts[4]) { "alpha" -> 0L; "beta" -> 1L; "rc" -> 2L; else -> 3L }
        val revision = if (parts[4].isEmpty()) 0L else parts[5].toLongOrNull() ?: return null
        return listOf(parts[1].toLongOrNull() ?: return null, parts[2].toLongOrNull() ?: return null,
            parts[3].toLongOrNull() ?: return null, stage, revision)
    }
    private fun newer(a: List<Long>, b: List<Long>): Boolean {
        for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }
    /** The public Atom feed remains available when the unauthenticated REST quota is exhausted. */
    fun latestFeedTag(feed: String, current: String, repo: String): String? {
        require(feed.contains("<feed ") && feed.contains("</feed>") && feed.contains("<id>tag:github.com,2008:https://github.com/lingmulongtai/Okuyuki-$repo/releases</id>"))
        var best = version(current) ?: return null
        var result: String? = null
        val prefix = Regex.escape("https://github.com/lingmulongtai/Okuyuki-$repo/releases/tag/")
        val link = Regex("<link\\s+[^>]*href=\"" + prefix + "(v[0-9A-Za-z.\\-]+)\"[^>]*/?>")
        var matchedLink = false
        for (match in link.findAll(feed)) {
            matchedLink = true
            val tag = match.groupValues[1]
            val candidate = version(tag) ?: continue
            if (newer(candidate, best)) { best = candidate; result = tag }
        }
        require(!feed.contains("<entry>") || matchedLink)
        return result
    }

    fun fromChecksums(tag: String, checksums: String, repo: String): UpdateRelease? {
        if (version(tag) == null || !tag.startsWith("v")) return null
        val name = "Okuyuki-$repo-$tag.apk"
        val lines = checksums.lineSequence().mapNotNull { line ->
            val match = Regex("^([0-9a-fA-F]{64})  \\*?(.+)$").matchEntire(line.trimEnd()) ?: return@mapNotNull null
            if (match.groupValues[2] == name) match.groupValues[1].lowercase() else null
        }.toList()
        if (lines.size != 1) return null
        return UpdateRelease(tag.removePrefix("v"), "https://github.com/lingmulongtai/Okuyuki-$repo/releases/download/$tag/$name", lines.single(), 0)
    }

    fun find(json: String, current: String, repo: String): UpdateRelease? {
        val installed = version(current) ?: return null
        var best = installed
        var result: UpdateRelease? = null
        for (element in Json.parseToJsonElement(json).jsonArray) {
            val item = element.jsonObject
            if (item["draft"]?.jsonPrimitive?.booleanOrNull != false) continue
            val tag = item["tag_name"]?.jsonPrimitive?.contentOrNull ?: continue
            val candidate = version(tag) ?: continue
            if (!newer(candidate, best)) continue
            val expectedName = "Okuyuki-$repo-$tag.apk"
            val asset = item["assets"]?.jsonArray?.map { it.jsonObject }?.singleOrNull {
                it["name"]?.jsonPrimitive?.contentOrNull == expectedName && it["state"]?.jsonPrimitive?.contentOrNull == "uploaded"
            } ?: continue
            val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: continue
            val uri = runCatching { URI(url) }.getOrNull() ?: continue
            if (uri.scheme != "https" || uri.host != "github.com" || uri.userInfo != null || uri.port != -1 ||
                uri.rawPath != "/lingmulongtai/Okuyuki-$repo/releases/download/$tag/$expectedName" || uri.rawQuery != null || uri.rawFragment != null) continue
            val digest = asset["digest"]?.jsonPrimitive?.contentOrNull ?: continue
            if (!Regex("sha256:[0-9a-f]{64}").matches(digest)) continue
            val size = asset["size"]?.jsonPrimitive?.longOrNull ?: continue
            if (size !in 1..536_870_912) continue
            best = candidate
            result = UpdateRelease(tag.removePrefix("v"), url, digest.removePrefix("sha256:"), size)
        }
        return result
    }
}
