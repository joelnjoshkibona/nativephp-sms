package com.blutrixx.plugins.nativephp_sms

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.Box
import com.goterl.lazysodium.interfaces.GenericHash
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretBox
import com.sun.jna.NativeLong

/**
 * The vault's cryptography, all libsodium:
 *  - an X25519 keypair; texts are sealed to the public key (crypto_box_seal), so sealing needs nothing secret;
 *  - Argon2id (crypto_pwhash) turns the passphrase into a key that wraps the private key (crypto_secretbox);
 *  - the recovery key is 128 random bits; BLAKE2b turns it into a second wrapping key.
 * Tests swap in a stand-in (Robolectric cannot load libsodium's native library) that still fails on a wrong key.
 */
interface VaultCrypto {
    /** (public key, private key) */
    fun keypair(): Pair<ByteArray, ByteArray>
    fun seal(publicKey: ByteArray, message: ByteArray): ByteArray
    /** Null when the private key is not the one it was sealed to. */
    fun sealOpen(publicKey: ByteArray, secretKey: ByteArray, sealed: ByteArray): ByteArray?
    fun pwhash(passphrase: ByteArray, salt: ByteArray, ops: Long, mem: Long): ByteArray
    /** nonce + ciphertext */
    fun secretbox(key: ByteArray, message: ByteArray): ByteArray
    fun secretboxOpen(key: ByteArray, boxed: ByteArray): ByteArray?
    /** A 32-byte key from high-entropy bytes (the recovery key). */
    fun hash(input: ByteArray): ByteArray
    fun random(n: Int): ByteArray

    companion object {
        /** Argon2id cost, kept with each vault so it can change later without breaking an existing one. */
        const val OPS = PwHash.OPSLIMIT_INTERACTIVE
        const val MEM = PwHash.ARGON2ID_MEMLIMIT_INTERACTIVE.toLong()
        const val SALT_BYTES = PwHash.SALTBYTES
    }
}

object SodiumCrypto : VaultCrypto {
    private val sodium by lazy { LazySodiumAndroid(SodiumAndroid()) }

    override fun keypair(): Pair<ByteArray, ByteArray> {
        val pk = ByteArray(Box.PUBLICKEYBYTES)
        val sk = ByteArray(Box.SECRETKEYBYTES)
        check((sodium as Box.Native).cryptoBoxKeypair(pk, sk)) { "crypto_box_keypair failed" }
        return pk to sk
    }

    override fun seal(publicKey: ByteArray, message: ByteArray): ByteArray {
        val out = ByteArray(message.size + Box.SEALBYTES)
        check((sodium as Box.Native).cryptoBoxSeal(out, message, message.size.toLong(), publicKey)) { "crypto_box_seal failed" }
        return out
    }

    override fun sealOpen(publicKey: ByteArray, secretKey: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < Box.SEALBYTES) return null
        val out = ByteArray(sealed.size - Box.SEALBYTES)
        return if ((sodium as Box.Native).cryptoBoxSealOpen(out, sealed, sealed.size.toLong(), publicKey, secretKey)) out else null
    }

    override fun pwhash(passphrase: ByteArray, salt: ByteArray, ops: Long, mem: Long): ByteArray {
        val out = ByteArray(SecretBox.KEYBYTES)
        check((sodium as PwHash.Native).cryptoPwHash(out, out.size, passphrase, passphrase.size, salt, ops, NativeLong(mem), PwHash.Alg.PWHASH_ALG_ARGON2ID13)) { "crypto_pwhash failed" }
        return out
    }

    override fun secretbox(key: ByteArray, message: ByteArray): ByteArray {
        val nonce = random(SecretBox.NONCEBYTES)
        val out = ByteArray(message.size + SecretBox.MACBYTES)
        check((sodium as SecretBox.Native).cryptoSecretBoxEasy(out, message, message.size.toLong(), nonce, key)) { "crypto_secretbox failed" }
        return nonce + out
    }

    override fun secretboxOpen(key: ByteArray, boxed: ByteArray): ByteArray? {
        if (boxed.size < SecretBox.NONCEBYTES + SecretBox.MACBYTES) return null
        val nonce = boxed.copyOfRange(0, SecretBox.NONCEBYTES)
        val ct = boxed.copyOfRange(SecretBox.NONCEBYTES, boxed.size)
        val out = ByteArray(ct.size - SecretBox.MACBYTES)
        return if ((sodium as SecretBox.Native).cryptoSecretBoxOpenEasy(out, ct, ct.size.toLong(), nonce, key)) out else null
    }

    override fun hash(input: ByteArray): ByteArray {
        val out = ByteArray(GenericHash.BYTES)
        check((sodium as GenericHash.Native).cryptoGenericHash(out, out.size, input, input.size.toLong())) { "crypto_generichash failed" }
        return out
    }

    override fun random(n: Int): ByteArray = sodium.randomBytesBuf(n)
}
