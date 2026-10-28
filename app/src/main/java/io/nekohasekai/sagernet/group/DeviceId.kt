package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import android.os.Build
import android.provider.Settings
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app
import libcore.HTTPRequest
import java.security.MessageDigest
import java.util.UUID

/**
 * STRIDER: device identification for panels with an HWID device limit
 * (3x-ui, Remnawave, ...). The headers are sent only with subscription
 * downloads, never with proxy traffic, so they can't affect connections.
 */
object DeviceId {

    // a known broken ANDROID_ID shared by many old devices
    private const val BROKEN_ANDROID_ID = "9774d56d682e549c"

    /**
     * Stable per device (survives reinstalls: ANDROID_ID is fixed for the
     * same signing key and user). Only a hash is sent, never the raw ID.
     * 32 hex characters: valid for 3x-ui and Remnawave.
     */
    val hwid: String by lazy {
        val base = try {
            @SuppressLint("HardwareIds")
            val id = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
            id?.takeIf { it.isNotBlank() && it != BROKEN_ANDROID_ID }
        } catch (_: Exception) {
            null
        } ?: fallbackId()
        sha256Hex("strider-hwid:$base").take(32)
    }

    private fun fallbackId(): String {
        return try {
            DataStore.hwidFallback.ifBlank {
                UUID.randomUUID().toString().also { DataStore.hwidFallback = it }
            }
        } catch (_: Exception) {
            "${Build.MANUFACTURER}-${Build.MODEL}-${Build.FINGERPRINT}"
        }
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private val deviceModel: String
        get() {
            val maker = Build.MANUFACTURER.orEmpty().trim()
            val model = Build.MODEL.orEmpty().trim()
            val name = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
            return name.trim().replaceFirstChar { it.uppercase() }.ifBlank { "Android" }
        }

    /**
     * Adds X-HWID, X-Device-OS, X-Ver-OS and X-Device-Model to a request.
     * Locked subscriptions also send the STRIDER proof for the sub guard;
     * normal ones never do, so a plain subscription URL can't get past it.
     */
    fun applyTo(request: HTTPRequest, locked: Boolean = false) {
        try {
            request.setHeader("X-HWID", hwid)
            request.setHeader("X-Device-OS", "Android")
            request.setHeader("X-Ver-OS", Build.VERSION.RELEASE.orEmpty())
            request.setHeader("X-Device-Model", deviceModel)
            // proof for the sub guard that this request comes from STRIDER
            if (locked) LockedSubs.authTag(hwid).takeIf { it.isNotEmpty() }?.let {
                request.setHeader("X-Strider-Auth", it)
            }
        } catch (_: Exception) {
            // never let device info break a subscription update
        }
    }
}
