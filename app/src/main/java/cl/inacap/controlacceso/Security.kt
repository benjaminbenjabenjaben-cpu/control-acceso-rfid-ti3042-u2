package cl.inacap.controlacceso

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

object Security {
    fun salt(): String = encode(ByteArray(16).also { SecureRandom().nextBytes(it) })
    fun hash(password: String, salt: String): String {
        val spec = PBEKeySpec(password.toCharArray(), decode(salt), 120_000, 256)
        return try { encode(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded) }
        finally { spec.clearPassword() }
    }
    fun matches(password: String, salt: String, expected: String) =
        MessageDigest.isEqual(decode(hash(password, salt)), decode(expected))
    fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    @Synchronized private fun localKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "rfid_local_uid"
        if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    // Guardamos el UID cifrado; la clave permanece en Android Keystore.
    fun encryptUid(uid: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, localKey())
        return encode(cipher.iv + cipher.doFinal(uid.toByteArray(Charsets.UTF_8)))
    }
    fun decryptUid(value: String): String {
        val bytes = decode(value)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, localKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}
