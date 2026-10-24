package io.github.stateofme

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog

private suspend fun request(server: String, path: String, body: JSONObject, token: String = ""): JSONObject = withContext(Dispatchers.IO) {
    val c = java.net.URL(server.trimEnd('/') + path).openConnection() as javax.net.ssl.HttpsURLConnection
    try {
        c.requestMethod = "POST"; c.connectTimeout = 10000; c.readTimeout = 10000; c.doOutput = true; c.instanceFollowRedirects = false
        c.setRequestProperty("Content-Type", "application/json")
        if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
        c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val ok = c.responseCode in 200..299
        val result = JSONObject((if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}")
        if (!ok) throw IllegalStateException(result.optString("detail", "连接失败"))
        result
    } finally { c.disconnect() }
}

@Composable fun MainActivity.ConnectionPage() {
    val prefs = remember { getSharedPreferences("som", 0) }
    var connected by remember { mutableStateOf(prefs.getString("token", "").orEmpty().isNotEmpty()) }
    var server by remember { mutableStateOf(prefs.getString("url", "").orEmpty()) }
    var code by remember { mutableStateOf(if (prefs.getLong("pair_expires", 0) > System.currentTimeMillis()) prefs.getString("pair_code", "").orEmpty() else "") }; var secret by remember { mutableStateOf(prefs.getString("pair_secret", "").orEmpty()) }
    var approved by remember { mutableStateOf<JSONObject?>(null) }; var expires by remember { mutableLongStateOf(prefs.getLong("pair_expires", 0)) }
    var busy by remember { mutableStateOf(false) }; var message by remember { mutableStateOf("") }
    var disconnect by remember { mutableStateOf(false) }; var advanced by remember { mutableStateOf(false) }; var legacy by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun clearPair() { prefs.edit().remove("pair_code").remove("pair_secret").remove("pair_expires").apply() }
    fun browser() { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(server.trimEnd('/') + "/account" + if (code.isNotEmpty()) "?pair=$code" else ""))) } catch (_: Exception) { message = "请用浏览器打开服务地址下的 /account 页面" } }
    LaunchedEffect(code) {
        if (code.isNotEmpty()) while (System.currentTimeMillis() < expires && approved == null) {
            delay(3000)
            try {
                val result = request(server, "/v1/pairings/poll", JSONObject().put("code", code).put("secret", secret))
                if (result.optString("status") == "approved") { approved = result; message = "请核对账号，再完成连接" }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { message = "连接暂时中断，将自动重试；也可重新生成连接码" }
        }
        if (code.isNotEmpty() && approved == null) { code = ""; secret = ""; clearPair(); message = "连接码已过期，请重新连接" }
    }
    Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (connected && prefs.getBoolean("connection_invalid", false)) "连接已失效，请断开后重新连接" else if (connected) "✓ 已连接 · " + prefs.getString("account", "原有设备连接") else "连接你的账号")
        Text(if (connected) "${prefs.getString("url", "")}\n分享由状态页开关控制。" else "在浏览器注册或登录，确认这台设备即可，无需填写密钥。")
    } }
    if (!connected) {
        TextField(value = server, onValueChange = { if (code.isEmpty()) server = it }, label = "HTTPS 服务地址", singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy && approved == null, onClick = { scope.launch {
            if (!server.trim().startsWith("https://")) { message = "请填写 HTTPS 服务地址"; return@launch }
            server = server.trim().trimEnd('/'); busy = true; code = ""; clearPair()
            try {
                val result = request(server, "/v1/pairings", JSONObject().put("label", android.os.Build.MODEL.take(60)).put("platform", "android"))
                secret = result.getString("secret"); expires = System.currentTimeMillis() + 600000; code = result.getString("code"); prefs.edit().putString("url", server).putString("pair_code", code).putString("pair_secret", secret).putLong("pair_expires", expires).apply(); message = "在浏览器确认设备后，请返回这里"; browser()
            } catch (_: Exception) { message = "无法生成连接码，请检查服务地址和网络" } finally { busy = false }
        } }, colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在连接…" else if (code.isEmpty()) "连接账号 / 注册" else "重新生成连接码") }
        if (code.isNotEmpty()) { Text("连接码：$code · 10 分钟内有效"); Button(onClick = { browser() }) { Text("打开账号页面") } }
        approved?.let { result ->
            Text("确认连接账号：${result.getString("account")}")
            Text("仅在这是你的账号时确认。连接后先选择分享内容，再手动开始分享。")
            Button(enabled = !busy, onClick = {
                prefs.edit().putString("url", server).putString("token", result.getString("token")).putString("account", result.getString("account")).putBoolean("enabled", false).apply()
                approved = null; code = ""; secret = ""; clearPair(); connected = true; message = "连接成功！请在分享页选择内容，在状态页开始分享。"
            }, colors = ButtonDefaults.buttonColorsPrimary()) { Text("这是我的账号，完成连接") }
            Button(enabled = !busy, onClick = { scope.launch {
                busy = true
                try { request(server, "/v1/device/disconnect", JSONObject(), result.getString("token")) } catch (_: Exception) { }
                approved = null; code = ""; secret = ""; clearPair(); busy = false; message = "已取消，可重新连接。没有上传任何状态。"
            } }) { Text("不是我的账号，取消") }
        }
        ArrowPreference("高级：已有设备令牌", summary = "供自建服务或旧版迁移使用", onClick = { advanced = !advanced })
        if (advanced) {
            TextField(value = legacy, onValueChange = { legacy = it }, label = "设备令牌", visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), singleLine = true)
            Button(onClick = { if (server.startsWith("https://") && legacy.trim().length >= 24) { prefs.edit().putString("url", server.trimEnd('/')).putString("token", legacy.trim()).remove("account").remove("connection_invalid").putBoolean("enabled", false).apply(); legacy = ""; approved = null; code = ""; secret = ""; clearPair(); connected = true } else message = "请填写有效的 HTTPS 地址和令牌" }) { Text("保存旧版连接") }
        }
    } else {
        Button(onClick = { server = prefs.getString("url", "").orEmpty(); browser() }, modifier = Modifier.fillMaxWidth()) { Text("管理账号、QQ 绑定与公开范围") }
        Button(onClick = { disconnect = true }, modifier = Modifier.fillMaxWidth()) { Text("断开此设备 / 更换账号") }
    }
    if (message.isNotEmpty()) Text(message)
    WindowDialog(show = disconnect, title = "断开此设备？", summary = "停止分享，撤销连接，清除分享选择和歌词索引。音乐文件会保留。系统权限仍由系统管理。", onDismissRequest = { if (!busy) disconnect = false }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !busy, onClick = { disconnect = false }) { Text("取消") }
            Button(enabled = !busy, onClick = { scope.launch {
                busy = true; prefs.edit().putBoolean("enabled", false).apply(); stopService(Intent(this@ConnectionPage, Collector::class.java))
                try { request(prefs.getString("url", "").orEmpty(), "/v1/device/disconnect", JSONObject(), prefs.getString("token", "").orEmpty()); message = "已断开连接" }
                catch (_: Exception) { message = "本机已断开。服务端暂不可达，旧状态会到期消失；请在账号页移除此设备。" }
                MusicLibrary.clear(this@ConnectionPage); prefs.edit().clear().apply(); legacy = ""; approved = null; code = ""; secret = ""; clearPair(); connected = false; disconnect = false; busy = false
            } }) { Text(if (busy) "正在断开…" else "断开并清除") }
        }
    }
}
