package dev.codexops.client

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.preferences by preferencesDataStore("connection")

class LocalStore(private val context: Context) {
    private val db =
        Room.databaseBuilder(context, LocalDatabase::class.java, "remote-codex.db").build()
    private val lock = Mutex()

    suspend fun get(id: String): String =
        lock.withLock { withContext(Dispatchers.IO) { db.records().get(id) ?: "" } }

    suspend fun put(id: String, value: String) =
        lock.withLock { withContext(Dispatchers.IO) { db.records().put(Record(id, value)) } }

    suspend fun remove(id: String) =
        lock.withLock { withContext(Dispatchers.IO) { db.records().remove(id) } }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (ks.getKey("remote-codex-connection", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                "remote-codex-connection",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    suspend fun token(): String =
        withContext(Dispatchers.IO) {
            val data =
                context.preferences.data.first()[stringPreferencesKey("credential")]
                    ?: return@withContext ""
            val bytes = Base64.decode(data, Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            }
        }

    suspend fun saveToken(value: String) {
        val encrypted =
            withContext(Dispatchers.IO) {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.ENCRYPT_MODE, key())
                    Base64.encodeToString(iv + doFinal(value.toByteArray()), Base64.NO_WRAP)
                }
            }
        context.preferences.edit { it[stringPreferencesKey("credential")] = encrypted }
    }
}
