package mihon.data.backup

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seals the NAS password into a backup under a passphrase the user picks, in the format agreed with
 * TachiMana: `"TMP1"` ‖ salt (16) ‖ nonce (12) ‖ AES-256-GCM ciphertext and tag, with the key from
 * Argon2id(passphrase, salt, m = 64 MiB, t = 3, p = 1, 32 bytes).
 *
 * The backup file is often copied around and left in cloud folders, so the password never goes in
 * without this, and the passphrase itself is never stored anywhere.
 */
object NasPasswordCipher {

    private val MAGIC = "TMP1".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(password: String, passphrase: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, nonce))
        return MAGIC + salt + nonce + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
    }

    /** Null for a wrong passphrase (the GCM tag check fails) or anything not in this format. */
    fun decrypt(sealed: ByteArray, passphrase: String): String? {
        val header = MAGIC.size + SALT_BYTES + NONCE_BYTES
        if (sealed.size < header + TAG_BITS / 8) return null
        if (!sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null

        val salt = sealed.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val nonce = sealed.copyOfRange(MAGIC.size + SALT_BYTES, header)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, nonce))
            cipher.doFinal(sealed, header, sealed.size - header).toString(Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    private fun key(passphrase: String, salt: ByteArray): SecretKeySpec {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(64 * 1024)
            .withIterations(3)
            .withParallelism(1)
            .build()
        val key = ByteArray(32)
        Argon2BytesGenerator().apply { init(params) }
            .generateBytes(passphrase.toByteArray(Charsets.UTF_8), key)
        return SecretKeySpec(key, "AES")
    }
}
