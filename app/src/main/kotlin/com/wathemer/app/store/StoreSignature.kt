// The signed catalogue, and the checksums every store file is held to.
package com.wathemer.app.store

import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

object StoreSignature {

    private const val MAGIC = "WATHEMER-STORE"
    private const val ENVELOPE = "1"

    /** The publish tool's own limit; a file with no newline this early is refused unread. */
    const val MAX_HEADER = 256

    sealed interface Opened {
        class Ok(val keyId: String, val payload: ByteArray) : Opened
        data class Refused(val reason: String) : Opened
    }

    fun keyId(spki: ByteArray): String = sha256Hex(spki).take(8)

    /** The bytes after the signature line, once the line names one of [keys] that is not [revoked] and its signature holds. */
    fun open(blob: ByteArray, keys: Map<String, String>, revoked: Set<String>): Opened {
        val newline = (0 until minOf(blob.size, MAX_HEADER + 1)).firstOrNull { blob[it] == '\n'.code.toByte() }
            ?: return Opened.Refused("no signature line")
        val parts = String(blob, 0, newline, Charsets.US_ASCII).split(' ')
        if (parts.size != 4 || parts[0] != MAGIC || parts[1] != ENVELOPE) return Opened.Refused("not a store catalogue")
        val kid = parts[2]
        val key = keys[kid] ?: return Opened.Refused("signed by a key this app does not know")
        if (kid in revoked) return Opened.Refused("signed by a key the store revoked")
        val signature = runCatching { Base64.getDecoder().decode(parts[3]) }.getOrNull()
            ?: return Opened.Refused("the signature is not base64")
        val payload = blob.copyOfRange(newline + 1, blob.size)
        val holds = runCatching {
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key)))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey)
                update(payload)
                verify(signature)
            }
        }.getOrDefault(false)
        return if (holds) Opened.Ok(kid, payload) else Opened.Refused("the signature does not match")
    }
}

fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** Streamed, so even the largest theme is hashed without being held in memory. */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
    }
    return hex(digest.digest())
}

private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
