import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object CryptoHelper {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val KEY_LENGTH = 256
    private const val ITERATION_COUNT = 65536
    private const val SALT_LENGTH_BYTES = 16
    private const val IV_LENGTH_BYTES = 16

    fun generateSalt(): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTES)
        random.nextBytes(salt)
        return salt
    }

    fun generateIv(): ByteArray {
        val random = SecureRandom()
        val iv = ByteArray(IV_LENGTH_BYTES)
        random.nextBytes(iv)
        return iv
    }

    fun getKeyFromPassword(password: CharArray, salt: ByteArray): SecretKey {
        val pbeKeySpec = PBEKeySpec(password, salt, ITERATION_COUNT, KEY_LENGTH)
        val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secretKey = secretKeyFactory.generateSecret(pbeKeySpec)
        return SecretKeySpec(secretKey.encoded, ALGORITHM)
    }

    fun encrypt(inputStream: InputStream, outputStream: OutputStream, key: SecretKey, iv: ByteArray) {
        val ivSpec = IvParameterSpec(iv)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec)

        val cipherInputStream = CipherInputStream(inputStream, cipher)
        cipherInputStream.use {
            it.copyTo(outputStream)
        }
    }
}

fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }