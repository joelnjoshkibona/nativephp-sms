package com.blutrixx.plugins.nativephp_sms

import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.GenericHash
import com.goterl.lazysodium.interfaces.KeyExchange

/**
 * The end-to-end crypto with a paired browser (the browser's side is FRONTEND/src/sync/crypto.ts; both agree byte for
 * byte, which selfTest() checks against the same vectors as crypto.spec.ts):
 *  - the phone is the crypto_kx client, the browser the server;
 *  - each session key is BLAKE2b-256 keyed by the QR code's one-time secret over "sms-relay-v1" + the kx key, so the
 *    relay, which passes the public keys but never sees the secret, cannot stand in for either side;
 *  - a frame is XChaCha20-Poly1305 (IETF), random 24-byte nonce, "from>to" as associated data, base64(nonce + ct).
 * Tests swap in a stand-in (Robolectric cannot load libsodium's native library).
 */
interface SyncCrypto {
    /** (public key, private key); from a seed when one is given (only the self-test does). */
    fun keypair(seed: ByteArray? = null): Pair<ByteArray, ByteArray>
    /** (rx, tx) for the phone (kx client), mixed with the QR secret. */
    fun sessionKeys(publicKey: ByteArray, privateKey: ByteArray, webPublicKey: ByteArray, secret: ByteArray): Pair<ByteArray, ByteArray>
    fun seal(key: ByteArray, plaintext: String, ad: String, nonce: ByteArray? = null): String
    /** Null when the frame was not sealed with this key for this direction, or was changed. */
    fun open(key: ByteArray, blob: String, ad: String): String?
    /** BLAKE2b-256, unkeyed, over the label then the parts. */
    fun hash(label: String, vararg parts: ByteArray): ByteArray
    fun random(n: Int): ByteArray

    companion object {
        const val CONTEXT = "sms-relay-v1"
        fun ad(from: String, to: String) = "$from>$to"
        @Volatile var current: SyncCrypto = SodiumSync
    }
}

object SodiumSync : SyncCrypto {
    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()) }

    override fun keypair(seed: ByteArray?): Pair<ByteArray, ByteArray> {
        val pk = ByteArray(KeyExchange.PUBLICKEYBYTES)
        val sk = ByteArray(KeyExchange.SECRETKEYBYTES)
        val kx = sodium as KeyExchange.Native
        check(if (seed != null) kx.cryptoKxSeedKeypair(pk, sk, seed) else kx.cryptoKxKeypair(pk, sk)) { "crypto_kx_keypair failed" }
        return pk to sk
    }

    override fun sessionKeys(publicKey: ByteArray, privateKey: ByteArray, webPublicKey: ByteArray, secret: ByteArray): Pair<ByteArray, ByteArray> {
        val rx = ByteArray(KeyExchange.SESSIONKEYBYTES)
        val tx = ByteArray(KeyExchange.SESSIONKEYBYTES)
        check((sodium as KeyExchange.Native).cryptoKxClientSessionKeys(rx, tx, publicKey, privateKey, webPublicKey)) { "the browser's key is not a valid public key" }
        val keys = mix(rx, secret) to mix(tx, secret)
        rx.fill(0); tx.fill(0)
        return keys
    }

    private fun mix(key: ByteArray, secret: ByteArray): ByteArray {
        val input = SyncCrypto.CONTEXT.toByteArray(Charsets.UTF_8) + key
        val out = ByteArray(32)
        check((sodium as GenericHash.Native).cryptoGenericHash(out, out.size, input, input.size.toLong(), secret, secret.size)) { "crypto_generichash failed" }
        return out
    }

    override fun seal(key: ByteArray, plaintext: String, ad: String, nonce: ByteArray?): String {
        val n = nonce ?: sodium.randomBytesBuf(AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES)
        val m = plaintext.toByteArray(Charsets.UTF_8)
        val a = ad.toByteArray(Charsets.UTF_8)
        val c = ByteArray(m.size + AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        check((sodium as AEAD.Native).cryptoAeadXChaCha20Poly1305IetfEncrypt(c, LongArray(1), m, m.size.toLong(), a, a.size.toLong(), null, n, key)) { "seal failed" }
        return Base64.encodeToString(n + c, Base64.NO_WRAP)
    }

    override fun open(key: ByteArray, blob: String, ad: String): String? {
        val raw = runCatching { Base64.decode(blob, Base64.DEFAULT) }.getOrNull() ?: return null
        val nl = AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES
        if (raw.size <= nl + AEAD.XCHACHA20POLY1305_IETF_ABYTES) return null
        val c = raw.copyOfRange(nl, raw.size)
        val a = ad.toByteArray(Charsets.UTF_8)
        val m = ByteArray(c.size - AEAD.XCHACHA20POLY1305_IETF_ABYTES)
        val ok = (sodium as AEAD.Native).cryptoAeadXChaCha20Poly1305IetfDecrypt(m, LongArray(1), null, c, c.size.toLong(), a, a.size.toLong(), raw.copyOfRange(0, nl), key)
        return if (ok) String(m, Charsets.UTF_8) else null
    }

    override fun hash(label: String, vararg parts: ByteArray): ByteArray {
        val input = parts.fold(label.toByteArray(Charsets.UTF_8)) { acc, p -> acc + p }
        val out = ByteArray(32)
        check((sodium as GenericHash.Native).cryptoGenericHash(out, out.size, input, input.size.toLong())) { "crypto_generichash failed" }
        return out
    }

    override fun random(n: Int): ByteArray = sodium.randomBytesBuf(n)

    /**
     * The same vectors as FRONTEND/src/sync/crypto.spec.ts. Answers what failed, or an empty list: run on a phone by
     * Sms.RelaySelfTest, since unit tests cannot load libsodium.
     */
    fun selfTest(): List<String> {
        fun fill(b: Int, n: Int = 32) = ByteArray(n) { b.toByte() }
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        val failed = ArrayList<String>()
        val (ppk, psk) = keypair(fill(0x11))
        val (wpk, _) = keypair(fill(0x22))
        if (hex(ppk) != "cb8403d9e0857b55c47ff233b6b4379588c9644aababb2588027bf7188b98217") failed += "phone public key"
        if (hex(wpk) != "89e2e17b0a384fecb8057084b5b1ba5b5c3c0eca440388432103a9c6e152d81f") failed += "web public key"
        val (rx, tx) = sessionKeys(ppk, psk, wpk, fill(0x33))
        if (hex(tx) != "f7f2cfc862f48b55676c4505f87b59323d3fb5600dd5a69773d4e2c6ecf81a34") failed += "tx"
        if (hex(rx) != "236c141c9225d21218ae419ef2383be5cec4d62f58337dc9f0b28be478b5d71f") failed += "rx"
        if (seal(tx, "{\"v\":1,\"t\":\"hello\",\"name\":\"Phone\",\"seq\":7}", "P>W", fill(0x55, 24)) !=
            "VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVBB2nGX32So3HZP65hSt9tucUhCOvYFboDTJoTqepQBykr7vEs0wcRK72tIOx0Hg1zDe07KiNNS2F7g==") failed += "seal"
        if (open(rx, "REREREREREREREREREREREREREREREREA9dwk2Q7OrlitJxdKwuXNYKR3+Nvj4ycgagIPAz+empVvx4Zy8Keitw/pz15dWHyFQ==", "W>P") !=
            "{\"v\":1,\"t\":\"hello\",\"name\":\"Test\"}") failed += "open"
        if (open(rx, "REREREREREREREREREREREREREREREREA9dwk2Q7OrlitJxdKwuXNYKR3+Nvj4ycgagIPAz+empVvx4Zy8Keitw/pz15dWHyFQ==", "P>W") != null) failed += "wrong direction opened"
        val (a, b, na, nb) = listOf(fill(1), fill(2), fill(3, 16), fill(4, 16))
        if (hex(PairCode.commitment(this, a, b, nb)) != "c6a6d9b30eb63049a58750950e60e0f41aeae630b835e4bb95027cf3affdcc9a") failed += "commitment"
        if (PairCode.digits(this, a, b, na, nb) != "571213") failed += "digits"
        if (hex(PairCode.secret(this, na, nb, "k7q2 9xmf")) != "1c529fa977ba1d6c5aa14bd9c9493b92a1b6422ff178774dd322c4a759d4a48a") failed += "code secret"
        return failed
    }
}

/**
 * Pairing by a typed code (FRONTEND/src/sync/crypto.ts has the same): the phone commits to its nonce before it sees the
 * browser's, then both show six digits made from both keys and both nonces. A server in the middle gets one guess in a
 * million. The secret mixed into the session keys comes from the nonces and the code.
 */
object PairCode {
    fun normalize(typed: String): String =
        typed.uppercase().filter { it.isLetterOrDigit() && it.code < 128 }.replace('O', '0').replace('I', '1').replace('L', '1')

    fun commitment(c: SyncCrypto, phonePk: ByteArray, webPk: ByteArray, nb: ByteArray) = c.hash("sms-relay-commit", phonePk, webPk, nb)

    fun digits(c: SyncCrypto, phonePk: ByteArray, webPk: ByteArray, na: ByteArray, nb: ByteArray): String {
        val d = c.hash("sms-relay-digits", phonePk, webPk, na, nb)
        val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
        return (n % 1_000_000).toString().padStart(6, '0')
    }

    fun secret(c: SyncCrypto, na: ByteArray, nb: ByteArray, code: String) = c.hash("sms-relay-code", na, nb, normalize(code).toByteArray(Charsets.UTF_8))
}
