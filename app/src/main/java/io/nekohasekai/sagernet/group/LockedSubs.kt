package io.nekohasekai.sagernet.group

import android.util.Base64
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyGroup
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * STRIDER locked subscriptions ("strider://lock/..." links).
 *
 * A locked link hides the subscription URL. STRIDER opens it, but never
 * shows or exports the URL or the servers of that subscription (no share,
 * QR, export, edit, backup). Same format as STRIDER for Windows and the sub
 * guard; the key comes from the GitHub secret STRIDER_LOCK_KEY at build time.
 * Without it the feature is off and nothing else changes.
 */
object LockedSubs {

    const val PREFIX = "strider://lock/"
    private const val LINK_VERSION: Byte = 1

    private val secret: String = BuildConfig.STRIDER_LOCK_KEY.trim()

    val enabled: Boolean get() = secret.length >= 16

    class Sub(val url: String, val name: String)

    fun isLink(text: String?) = text?.trim()?.startsWith(PREFIX) == true

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun derive(purpose: String): ByteArray =
        sha256("strider-$purpose-v1|$secret".toByteArray())

    private fun b64urlDecode(s: String): ByteArray =
        Base64.decode(s.trimEnd('='), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun b64urlEncode(b: ByteArray): String =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /** Opens a locked link. */
    fun decode(link: String): Sub {
        if (!enabled) error("Locked subscriptions are not enabled in this build")
        val raw = try {
            b64urlDecode(link.trim().removePrefix(PREFIX))
        } catch (_: Exception) {
            error("Invalid or damaged locked link")
        }
        if (raw.size < 1 + 12 + 16 || raw[0] != LINK_VERSION) error("Invalid or damaged locked link")
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(derive("lock"), "AES"), GCMParameterSpec(128, raw, 1, 12))
                updateAAD("strider-lock".toByteArray())
                doFinal(raw, 13, raw.size - 13)
            }
        } catch (_: Exception) {
            error("Invalid or damaged locked link")
        }
        val json = JSONObject(String(plain))
        val url = json.optString("u")
        if (!url.startsWith("http://") && !url.startsWith("https://")) error("Invalid or damaged locked link")
        return Sub(url, json.optString("n"))
    }

    /** Builds a locked link for a subscription URL (for admins). */
    fun encode(url: String, name: String?): String {
        if (!enabled) error("Locked subscriptions are not enabled in this build")
        val plain = JSONObject().apply {
            put("u", url.trim())
            if (!name.isNullOrBlank()) put("n", name.trim())
        }.toString().toByteArray()
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val enc = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive("lock"), "AES"), GCMParameterSpec(128, nonce))
            updateAAD("strider-lock".toByteArray())
            doFinal(plain)
        }
        return PREFIX + b64urlEncode(byteArrayOf(LINK_VERSION) + nonce + enc)
    }

    /** Proof for the sub guard that a request comes from STRIDER on this device. */
    fun authTag(hwid: String): String {
        if (!enabled || hwid.isBlank()) return ""
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(derive("auth"), "HmacSHA256"))
        return mac.doFinal(hwid.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    // ---- which subscriptions are locked (hash of the URL, kept in settings)

    @Volatile
    private var cache: Set<String>? = null

    private fun hashOf(url: String) = sha256(url.trim().toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private fun lockedHashes(): Set<String> = cache ?: try {
        DataStore.lockedSubscriptions.split(',').filter { it.isNotBlank() }.toSet()
    } catch (_: Exception) {
        emptySet()
    }.also { cache = it }

    fun isLocked(url: String?): Boolean = !url.isNullOrBlank() && hashOf(url) in lockedHashes()

    fun isLocked(group: ProxyGroup?): Boolean =
        group != null && group.type == GroupType.SUBSCRIPTION && isLocked(group.subscription?.link)

    /** Marks a subscription URL as locked (before its group is created). */
    @Synchronized
    fun markLocked(url: String) {
        val set = lockedHashes() + hashOf(url)
        DataStore.lockedSubscriptions = set.joinToString(",")
        cache = set
    }

    /** Removes the subscription URL (and its host) from an error message. */
    fun hide(message: String?, url: String): String {
        var msg = message ?: ""
        val parts = mutableListOf(url)
        try {
            val u = java.net.URI(url)
            u.host?.let { parts.add(it) }
            u.rawAuthority?.let { parts.add(it) }
            u.rawPath?.let { parts.add(it) }
        } catch (_: Exception) {
        }
        for (p in parts) if (p.length > 3) msg = msg.replace(p, "(hidden)")
        return msg
    }
}
