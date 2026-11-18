package app.term.one.vnc

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object VncDes {

    fun response(password: String, challenge: ByteArray): ByteArray {
        val key = ByteArray(8)
        val bytes = password.toByteArray(Charsets.ISO_8859_1)
        for (i in 0 until 8) {
            key[i] = if (i < bytes.size) reverseBits(bytes[i]) else 0
        }
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(challenge)
    }

    private fun reverseBits(value: Byte): Byte {
        var v = value.toInt() and 0xFF
        var r = 0
        for (i in 0 until 8) {
            r = (r shl 1) or (v and 1)
            v = v shr 1
        }
        return r.toByte()
    }
}