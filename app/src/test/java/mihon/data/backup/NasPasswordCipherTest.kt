package mihon.data.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The sealed format is shared with TachiMana, so its layout is pinned here too. */
class NasPasswordCipherTest {

    @Test
    fun `opens with the passphrase it was sealed with`() {
        val sealed = NasPasswordCipher.encrypt("not-a-real-password ü", "correct horse")

        assertEquals("not-a-real-password ü", NasPasswordCipher.decrypt(sealed, "correct horse"))
    }

    @Test
    fun `a wrong passphrase or a damaged blob is null`() {
        val sealed = NasPasswordCipher.encrypt("secret", "correct horse")

        assertNull(NasPasswordCipher.decrypt(sealed, "correct horsE"))
        assertNull(
            NasPasswordCipher.decrypt(
                sealed.copyOf().also {
                    it[it.size - 1] = (it.last() + 1).toByte()
                },
                "correct horse",
            ),
        )
        assertNull(NasPasswordCipher.decrypt(sealed.copyOfRange(0, 20), "correct horse"))
        assertNull(
            NasPasswordCipher.decrypt(
                byteArrayOf('X'.code.toByte()) + sealed.copyOfRange(1, sealed.size),
                "correct horse",
            ),
        )
    }

    @Test
    fun `layout is magic, salt, nonce, then ciphertext and tag`() {
        val sealed = NasPasswordCipher.encrypt("secret", "correct horse")

        assertEquals("TMP1", sealed.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        // 4 + 16 salt + 12 nonce + 6 bytes of password + 16 tag
        assertEquals(4 + 16 + 12 + 6 + 16, sealed.size)
        // A fresh salt and nonce each time
        assertFalse(sealed.contentEquals(NasPasswordCipher.encrypt("secret", "correct horse")))
    }
}
