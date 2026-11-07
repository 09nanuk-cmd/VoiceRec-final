package com.example.voicerec

import android.content.Context
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

object LockState {
    val unlocked = MutableStateFlow(false)
}

/** Chiave principale nell'Android Keystore: non esportabile, legata a questo telefono (StrongBox se presente). */
object KeyVault : FileCrypto.KeyWrapper {
    private const val ALIAS = "voicerec_master_v1"

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        fun spec(strong: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
            if (strong && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
            return b.build()
        }
        return try {
            kg.init(spec(true))
            kg.generateKey()
        } catch (e: Exception) {
            kg.init(spec(false))
            kg.generateKey()
        }
    }

    override fun wrap(dek: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(dek)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
        return c.doFinal(wrapped, 12, wrapped.size - 12)
    }
}

object Secure {
    fun tmpDir(c: Context): File = File(c.cacheDir, "tmp").apply { mkdirs() }

    fun encryptTo(plain: File, enc: File) {
        plain.inputStream().buffered().use { i ->
            enc.outputStream().buffered().use { o -> FileCrypto.encryptDevice(i, o, KeyVault) }
        }
    }

    fun decryptTo(enc: File, plain: File) {
        enc.inputStream().buffered().use { i ->
            plain.outputStream().buffered().use { o -> FileCrypto.decryptDevice(i, o, KeyVault) }
        }
    }

    fun decryptToTemp(c: Context, enc: File, ext: String): File {
        val t = File.createTempFile("dec", ".$ext", tmpDir(c))
        try {
            decryptTo(enc, t)
        } catch (e: Exception) {
            wipe(t)
            throw e
        }
        return t
    }

    fun readText(enc: File): String {
        val o = ByteArrayOutputStream()
        enc.inputStream().buffered().use { FileCrypto.decryptDevice(it, o, KeyVault) }
        return o.toString("UTF-8")
    }

    fun writeText(enc: File, text: String) {
        enc.outputStream().buffered().use { FileCrypto.encryptDevice(ByteArrayInputStream(text.toByteArray()), it, KeyVault) }
    }

    /** Sovrascrive con zeri e poi elimina il file in chiaro. */
    fun wipe(f: File) {
        try {
            if (f.exists()) {
                RandomAccessFile(f, "rw").use { r ->
                    val z = ByteArray(1 shl 16)
                    var left = r.length()
                    r.seek(0)
                    while (left > 0) {
                        val n = minOf(left, z.size.toLong()).toInt()
                        r.write(z, 0, n)
                        left -= n
                    }
                    r.fd.sync()
                }
            }
        } catch (e: Exception) {
        }
        f.delete()
    }

    fun cleanup(c: Context) {
        if (TranscribeState.busyPath.value == null) tmpDir(c).listFiles()?.forEach { wipe(it) }
        File(c.cacheDir, "exports").listFiles()?.forEach { it.delete() }
    }
}

/** Codice di accesso a 6 cifre: salvato solo come hash PBKDF2 con sale, con blocco dopo troppi errori. */
object Pin {
    sealed class Result {
        object Ok : Result()
        data class Wrong(val fails: Int) : Result()
        data class Locked(val seconds: Long) : Result()
    }

    private fun prefs(c: Context) = c.getSharedPreferences("sec", Context.MODE_PRIVATE)
    fun isSet(c: Context) = prefs(c).contains("hash")

    private fun derive(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded

    fun set(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(c).edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(derive(pin, salt), Base64.NO_WRAP))
            .putInt("fails", 0).putLong("until", 0L).apply()
    }

    fun verify(c: Context, pin: String): Result {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val until = p.getLong("until", 0L)
        if (now < until) return Result.Locked((until - now + 999) / 1000)
        val salt = Base64.decode(p.getString("salt", "") ?: "", Base64.NO_WRAP)
        val expected = Base64.decode(p.getString("hash", "") ?: "", Base64.NO_WRAP)
        if (MessageDigest.isEqual(derive(pin, salt), expected)) {
            p.edit().putInt("fails", 0).putLong("until", 0L).apply()
            return Result.Ok
        }
        val fails = p.getInt("fails", 0) + 1
        val shift = (fails - 5).coerceIn(0, 7)
        val wait = if (fails >= 5) minOf(30L shl shift, 3600L) else 0L
        p.edit().putInt("fails", fails).putLong("until", if (wait > 0) now + wait * 1000 else 0L).apply()
        return if (wait > 0) Result.Locked(wait) else Result.Wrong(fails)
    }
}

/** Condivisione protetta: file .vrec apribile solo con questa app e la frase segreta scelta. */
object Exporter {
    fun export(c: Context, enc: File, pass: CharArray): File {
        val plain = Secure.decryptToTemp(c, enc, "m4a")
        try {
            val dir = File(c.cacheDir, "exports").apply { mkdirs() }
            val out = File(dir, Storage.baseName(enc) + ".vrec")
            plain.inputStream().buffered().use { i ->
                out.outputStream().buffered().use { o ->
                    FileCrypto.encryptExport(i, o, pass, Storage.baseName(enc), true)
                }
            }
            return out
        } finally {
            Secure.wipe(plain)
        }
    }

    /** Importa un file .vrec: viene ricifrato con la chiave di questo telefono e segnato come "ricevuto". */
    fun importFile(c: Context, uri: Uri, pass: CharArray): String {
        val plain = File(Secure.tmpDir(c), "imp_" + System.nanoTime() + ".m4a")
        try {
            val stream = c.contentResolver.openInputStream(uri) ?: error("File non leggibile")
            val info = stream.buffered().use { i ->
                plain.outputStream().buffered().use { o -> FileCrypto.decryptExport(i, o, pass) }
            }
            val safe = info.name.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(60)
            val base = Storage.uniqueBase(c, "Ric_$safe")
            Storage.store(c, plain, base, info.noReshare)
            return base
        } catch (e: Exception) {
            Secure.wipe(plain)
            throw e
        }
    }
}
