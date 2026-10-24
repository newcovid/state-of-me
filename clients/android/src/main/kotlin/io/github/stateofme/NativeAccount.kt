package io.github.stateofme

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

internal data class NativeSession(val server: String, val name: String, val token: String, val expiresAt: Long)
internal data class NativeRecovery(val server: String, val name: String, val code: String)
internal class AccountRequestException(val status: Int, message: String) : IOException(message)

/** Account-management credentials are independent of the device upload credential. */
internal object NativeAccountStore {
    private const val KEY = "state-of-me.account-session.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(KEY, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized private fun write(context: Context, slot: String, server: String, value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD("$slot\u0000$server".toByteArray(Charsets.UTF_8)) }
        val bytes = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        if (!context.getSharedPreferences("native_account", 0).edit().putString(slot + "_server", server)
                .putString(slot, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) throw IOException("无法保存账号会话")
    }
    @Synchronized private fun read(context: Context, slot: String): JSONObject? {
        val prefs = context.getSharedPreferences("native_account", 0)
        val blob = prefs.getString(slot, null) ?: return null
        return try {
            val server = prefs.getString(slot + "_server", "").orEmpty()
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                updateAAD("$slot\u0000$server".toByteArray(Charsets.UTF_8))
            }
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)).also { require(it.getString("server") == server) }
        } catch (_: Exception) { clear(context, slot); null }
    }
    private fun clear(context: Context, slot: String) { context.getSharedPreferences("native_account", 0).edit().remove(slot).remove(slot + "_server").apply() }
    fun save(context: Context, value: NativeSession) = write(context, "session", value.server, JSONObject()
        .put("server", value.server).put("name", value.name).put("token", value.token).put("expires", value.expiresAt))
    fun load(context: Context): NativeSession? = read(context, "session")?.let {
        val expires = it.optLong("expires")
        if (expires <= System.currentTimeMillis()) { clearSession(context); null }
        else NativeSession(it.getString("server"), it.getString("name"), it.getString("token"), expires)
    }
    fun clearSession(context: Context) = clear(context, "session")
    fun saveRecovery(context: Context, value: NativeRecovery) = write(context, "recovery", value.server, JSONObject()
        .put("server", value.server).put("name", value.name).put("code", value.code))
    fun recovery(context: Context): NativeRecovery? = read(context, "recovery")?.let { NativeRecovery(it.getString("server"), it.getString("name"), it.getString("code")) }
    fun clearRecovery(context: Context) = clear(context, "recovery")
    private fun enrollmentSlot(server: String, name: String): String = "enroll_" + java.security.MessageDigest.getInstance("SHA-256")
        .digest("$server\u0000$name".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    @Synchronized fun enrollmentId(context: Context, server: String, name: String): String {
        val slot = enrollmentSlot(server, name)
        read(context, slot)?.optString("id")?.takeIf { it.isNotBlank() }?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        write(context, slot, server, JSONObject().put("server", server).put("name", name).put("id", id))
        return id
    }
    fun clearEnrollment(context: Context, server: String, name: String) = clear(context, enrollmentSlot(server, name))
}

internal object NativeAccountApi {
    fun normalizeServer(value: String): String {
        val uri = try { URI(value.trim()) } catch (_: Exception) { throw IllegalArgumentException("请输入完整的 HTTPS 服务地址") }
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.port in -1..65535) { "请输入 HTTPS 服务地址，不要包含账号、查询参数或片段" }
        return URI("https", null, uri.host.lowercase(), uri.port, uri.path.orEmpty().trimEnd('/'), null, null).normalize().toASCIIString().trimEnd('/')
    }
    suspend fun request(server: String, path: String, body: JSONObject? = null, session: NativeSession? = null, deviceToken: String = ""): JSONObject = withContext(Dispatchers.IO) {
        val normalized = normalizeServer(server)
        require(session == null || session.server == normalized) { "服务地址已变化，请重新登录" }
        require(path.startsWith("/v1/") && !path.contains(".."))
        val connection = java.net.URL(normalized + path).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = if (body == null) "GET" else "POST"
            connection.connectTimeout = 10000; connection.readTimeout = 10000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            if (session != null) {
                connection.setRequestProperty("Authorization", "Bearer " + session.token)
                connection.setRequestProperty("X-SOM-Account", session.name)
            } else if (deviceToken.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $deviceToken")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-SOM-CSRF", "1")
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; if (output.size() + count > 1048576) throw IOException("服务返回内容过大"); output.write(buffer, 0, count) }
                output.toByteArray()
            } ?: ByteArray(0)
            val result = try { JSONObject(String(bytes, Charsets.UTF_8).ifEmpty { "{}" }) } catch (_: Exception) { throw IOException("服务返回了无法识别的内容，请检查地址与版本") }
            if (status !in 200..299) {
                val detail = result.opt("detail") as? String
                throw AccountRequestException(status, detail?.take(300) ?: "请求未完成（HTTP $status），请检查填写内容")
            }
            result
        } finally { connection.disconnect() }
    }
}
