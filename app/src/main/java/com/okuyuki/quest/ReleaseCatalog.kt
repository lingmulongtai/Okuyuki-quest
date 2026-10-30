package com.okuyuki.quest

import kotlinx.serialization.json.*
import java.net.URI

internal data class UpdateRelease(val version: String, val url: String, val digest: String, val size: Long)

/** GitHub's /latest excludes prereleases. Compare versions instead of relying on API order. */
internal object ReleaseCatalog {
    private val pattern = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-alpha\\.(\\d+)(?:-preview)?)?$")
    private fun version(value: String): List<Long>? {
        val match = pattern.matchEntire(value.removePrefix("v")) ?: return null
        val parts = match.groupValues
        return listOf(parts[1].toLongOrNull() ?: return null, parts[2].toLongOrNull() ?: return null,
            parts[3].toLongOrNull() ?: return null, if (parts[4].isEmpty()) Long.MAX_VALUE else parts[4].toLongOrNull() ?: return null)
    }
    private fun newer(a: List<Long>, b: List<Long>): Boolean {
        for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
        return false
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
