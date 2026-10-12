package com.okuyuki.quest.link

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PairingStore(context: Context) {
    private val preferences = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    val clientId: String = preferences.getString("clientId", null) ?: UUID.randomUUID().toString().also { preferences.edit().putString("clientId", it).apply() }
    fun token(hostId: String): String? = runCatching {
        val encoded = preferences.getString("token:$hostId", null) ?: return null
        val parts = encoded.split(':')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()
    fun save(hostId: String, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ':' + Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)
        preferences.edit().putString("token:$hostId", value).putString("lastHost", hostId).apply()
    }
    fun paired(hostId: String) = preferences.contains("token:$hostId")
    /** The phone this Quest last showed photos from and where it was, so opening the app can go straight back to it. */
    fun last(): Pair<String, Endpoint>? {
        val hostId = preferences.getString("lastHost", null)?.takeIf(::paired) ?: return null
        return Endpoint.parse(preferences.getString("lastEndpoint", null) ?: return null)?.let { hostId to it }
    }
    fun lastName(): String? = preferences.getString("lastName", null)
    fun remember(hostId: String, endpoint: Endpoint, name: String) {
        preferences.edit().putString("lastHost", hostId).putString("lastEndpoint", endpoint.label).putString("lastName", name).apply()
    }
    fun forget(hostId: String) {
        val editor = preferences.edit().remove("token:$hostId")
        if (preferences.getString("lastHost", null) == hostId) editor.remove("lastHost").remove("lastEndpoint").remove("lastName")
        editor.apply()
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("okuyuki-pairing", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("okuyuki-pairing", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
