package io.github.stateofme

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONArray?.strings(): Set<String> = if (this == null) emptySet() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }.toSet()
private data class ScopeOption(val key: String, val label: String, val group: String, val description: String)
@Composable private fun AccountNote(value: String) { Text(value, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }

/** Native account management; the browser remains an optional public preview. */
@Composable fun MainActivity.ConnectionPage(page: Int, openSettings: () -> Unit) {
    val prefs = remember { getSharedPreferences("som", 0) }
    val stored = remember { NativeAccountStore.load(this) }
    val pendingRecovery = remember { NativeAccountStore.recovery(this) }
    var server by remember { mutableStateOf(prefs.getString("url", "").orEmpty().ifBlank { stored?.server ?: pendingRecovery?.server.orEmpty() }) }
    var session by remember { mutableStateOf(stored?.takeIf { it.server == server && (prefs.getString("account", "").isNullOrBlank() || it.name == prefs.getString("account", "")) }) }
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var me by remember { mutableStateOf<JSONObject?>(null) }
    var username by remember { mutableStateOf(prefs.getString("account", "").orEmpty()) }
    var password by remember { mutableStateOf("") }
    var recoveryInput by remember { mutableStateOf("") }
    var authMode by remember { mutableStateOf("login") }
    var recovery by remember { mutableStateOf(pendingRecovery?.takeIf { it.server == server }) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var deviceVersion by remember { mutableIntStateOf(0) }
    val refreshDevice = deviceVersion
    val connected = prefs.getString("token", "").orEmpty().length >= 24 && !prefs.getString("account", "").isNullOrBlank()
    var group by rememberSaveable { mutableStateOf("") }
    var bindCommand by rememberSaveable { mutableStateOf("") }
    var bindExpires by rememberSaveable { mutableLongStateOf(0) }
    var confirmAction by remember { mutableStateOf("") }
    var confirmId by remember { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf("") }
    var scopeVisible by rememberSaveable { mutableStateOf(false) }
    var draftAccount by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable(stateSaver = Saver<Set<String>, ArrayList<String>>(save = { ArrayList(it) }, restore = { it.toSet() })) { mutableStateOf(emptySet<String>()) }
    var editingRevision by rememberSaveable { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var conflict by rememberSaveable { mutableStateOf(false) }
    var enrollmentConflict by remember { mutableStateOf(false) }
    val coroutine = rememberCoroutineScope()
    val catalog = info?.optJSONArray("scopes").objects().map { ScopeOption(it.getString("key"), it.optString("label", it.getString("key")), it.optString("group", "其他"), it.optString("description")) }
    fun notify(value: String) { message = value; Toast.makeText(this, value, Toast.LENGTH_SHORT).show() }
    fun runAction(work: suspend () -> Unit) {
        if (busy) return
        coroutine.launch {
            busy = true
            try { work() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: AccountRequestException) {
                if (error.status == 401 && session != null) { NativeAccountStore.clearSession(this@ConnectionPage); session = null; me = null; editing = ""; notify("账号登录已失效，请重新登录；本机采集连接不受影响") }
                else { if (error.status == 409 && editing.isNotEmpty()) conflict = true; notify(error.message ?: "操作未完成") }
            }
            catch (error: IllegalArgumentException) { notify(error.message ?: "请检查填写内容") }
            catch (_: Exception) { notify("暂时无法连接服务，请检查地址和网络后重试。未保存的选择仍保留。") }
            finally { busy = false }
        }
    }
    suspend fun loadInfo() {
        val base = NativeAccountApi.normalizeServer(server)
        val result = NativeAccountApi.request(base, "/v1/accounts/info")
        require(result.optJSONArray("scopes") != null) { "此服务尚未支持原生账号管理，请维护者升级服务后再试" }
        server = base; info = result
    }
    suspend fun refreshAccount(current: NativeSession) {
        val result = NativeAccountApi.request(current.server, "/v1/accounts/me", session = current)
        require(result.getString("name") == current.name) { "账号发生变化，请重新登录" }
        me = result
    }
    suspend fun enroll(current: NativeSession) {
        val oldServer = prefs.getString("url", "").orEmpty()
        val oldName = prefs.getString("account", "").orEmpty()
        val oldToken = prefs.getString("token", "").orEmpty()
        require(oldToken.isEmpty() || (oldServer == current.server && oldName == current.name)) { "本机仍连接着其他服务或账号，请先断开本机" }
        val result = try { NativeAccountApi.request(current.server, "/v1/accounts/devices/enroll", JSONObject()
            .put("label", android.os.Build.MODEL.take(60)).put("platform", "android").put("existing_token", oldToken)
            .put("enrollment_id", NativeAccountStore.enrollmentId(this@ConnectionPage, current.server, current.name)), current) }
        catch (error: AccountRequestException) { if (error.status == 409) enrollmentConflict = true; throw error }
        require(result.getString("account") == current.name && result.getString("token").length >= 24)
        val editor = prefs.edit().putString("url", current.server).putString("account", current.name)
            .putString("token", result.getString("token")).putString("device_id", result.getString("device"))
            .remove("connection_invalid")
        if (oldToken.isEmpty()) editor.putBoolean("enabled", false)
        check(editor.commit())
        NativeAccountStore.clearEnrollment(this@ConnectionPage, current.server, current.name)
        enrollmentConflict = false
        deviceVersion++

    }
    // Disconnect explicitly removes local collection data and credentials.
    fun clearLocalDevice() {
        editing = ""; scopeVisible = false; selected = emptySet(); bindCommand = ""
        val previousServer = prefs.getString("url", "").orEmpty()
        val previousAccount = prefs.getString("account", "").orEmpty()
        if (previousServer.isNotEmpty() && previousAccount.isNotEmpty()) NativeAccountStore.clearEnrollment(this, previousServer, previousAccount)
        prefs.edit().putBoolean("enabled", false).commit()
        stopService(Intent(this, Collector::class.java))
        UsageHistory.clear(this); getSystemService(android.app.job.JobScheduler::class.java).cancel(1401)
        MusicLibrary.clear(this); prefs.edit().clear().commit(); deviceVersion++
    }
    fun copy(label: String, value: String) { getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value)); notify("已复制$label") }
    fun beginScope(target: String, replaceDraft: Boolean = false) {
        if (editing.isNotEmpty() && !replaceDraft) { if (target != editing) notify("请先保存或放弃当前范围草稿"); scopeVisible = true; return }
        scopeVisible = true; draftAccount = session?.let { it.server + "|" + it.name }.orEmpty()
        editing = target; editingRevision = me?.optInt("scope_revision") ?: 0
        selected = if (target == "public") me?.optJSONArray("public_fields").strings() else me?.optJSONObject("group_scopes")?.optJSONArray(target).strings()
        expanded = emptySet(); conflict = false
    }
    LaunchedEffect(session?.server, session?.name) {
        val identity = session?.let { it.server + "|" + it.name }.orEmpty()
        if (editing.isNotEmpty() && draftAccount != identity) { editing = ""; selected = emptySet(); scopeVisible = false; bindCommand = "" }
    }
    LaunchedEffect(Unit) {
        if (server.isBlank()) return@LaunchedEffect
        busy = true
        try {
            loadInfo()
            val current = session
            if (current != null && recovery == null) refreshAccount(current)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: AccountRequestException) { if (error.status == 401) { NativeAccountStore.clearSession(this@ConnectionPage); session = null }; message = error.message.orEmpty() }
        catch (error: IllegalArgumentException) { message = error.message ?: "请检查服务地址" }
        catch (_: Exception) { message = "连接信息暂未读取，请检查服务地址后刷新" }
        finally { busy = false }
    }

    // Keep this composable at a stable call site even when another destination is visible.
    if (page !in 2..3) return
    if (page == 2 && session == null) {
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("登录后设置分享范围", style = MiuixTheme.textStyles.title3)
            AccountNote("公开访客与 QQ 群分别授权。设置会应用于同一账号的所有设备。")
            Button(onClick = openSettings, colors = ButtonDefaults.buttonColorsPrimary()) { Text("前往设置登录") }
        } }
        return
    }
    if (page == 3) {
        Text(session?.name ?: "服务与账号", style = MiuixTheme.textStyles.title3)
        if (server.isNotBlank()) AccountNote(server)
        AccountNote(if (connected && prefs.getBoolean("connection_invalid", false)) "本机连接已失效，请先断开后重新连接。" else if (connected) "本机已连接 ${prefs.getString("account", "")}，采集内容在“采集”页设置。" else "登录后连接本机，再选择要同步的内容。")
    } else {
        AccountNote("${session!!.name} · 以下权限影响此账号所有设备")
    }
    if (message.isNotBlank()) AccountNote(message)
    if (session == null) {
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!connected) TextField(value = server, onValueChange = { server = it; info = null; password = ""; editing = ""; scopeVisible = false; bindCommand = "" }, label = "HTTPS 服务地址", singleLine = true, enabled = !busy)
            Button(enabled = !busy, onClick = { runAction { loadInfo(); notify("服务已确认，可在这里登录或注册") } }) { Text(if (busy) "正在连接…" else "检查服务") }
            if (info != null) {
                TextField(value = username, onValueChange = { username = it }, label = "账号名", singleLine = true, enabled = !busy && !connected)
                TextField(value = password, onValueChange = { password = it }, label = if (authMode == "recover") "新密码（至少 10 个字符）" else "密码（至少 10 个字符）", visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, enabled = !busy)
                if (authMode == "recover") TextField(value = recoveryInput, onValueChange = { recoveryInput = it }, label = "账号恢复码", visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true, enabled = !busy)
                Button(enabled = !busy, colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.fillMaxWidth(), onClick = { runAction {
                    val base = NativeAccountApi.normalizeServer(server)
                    require(username.matches(Regex("[A-Za-z0-9_-]{3,32}"))) { "账号名需为 3–32 位字母、数字、下划线或短横线" }
                    require(password.length in 10..128) { "密码需为 10–128 个字符" }
                    if (authMode == "register") require(info?.optBoolean("registration") == true) { "此服务暂未开放注册" }
                    val body = JSONObject().put("name", username.trim()).put("password", password).put("client", "native")
                    if (authMode == "recover") body.put("recovery", recoveryInput.trim())
                    val result = try { NativeAccountApi.request(base, "/v1/accounts/$authMode", body) } finally { password = ""; recoveryInput = "" }
                    if (authMode == "recover") {
                        NativeAccountStore.clearSession(this@ConnectionPage)
                        if (connected) { prefs.edit().putBoolean("enabled", false).putBoolean("connection_invalid", true).commit(); stopService(Intent(this@ConnectionPage, Collector::class.java)); deviceVersion++ }
                        recovery = NativeRecovery(base, username.lowercase(), result.getString("recovery")); NativeAccountStore.saveRecovery(this@ConnectionPage, recovery!!)
                        authMode = "login"; notify("密码已重设，旧设备已撤销。保存恢复码后，先断开本机再重新登录")
                    } else {
                        val token = result.getString("access_token"); require(token.length >= 24)
                        val current = NativeSession(base, result.getString("name"), token, System.currentTimeMillis() + result.getLong("expires_in") * 1000)
                        if (result.optString("recovery").isNotBlank()) { recovery = NativeRecovery(base, current.name, result.getString("recovery")); NativeAccountStore.saveRecovery(this@ConnectionPage, recovery!!) }
                        NativeAccountStore.save(this@ConnectionPage, current); session = current; me = null
                        if (recovery == null) { enroll(current); refreshAccount(current); notify("已登录并连接本机，原采集偏好已保留") }
                    }
                } }) { Text(when(authMode) { "register" -> "注册账号"; "recover" -> "重设密码并撤销旧设备"; else -> "登录并连接本机" }) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (authMode != "login" || (!connected && info?.optBoolean("registration") == true)) Button(enabled = !busy, onClick = { authMode = if (authMode == "login" && !connected && info?.optBoolean("registration") == true) "register" else "login"; password = ""; recoveryInput = "" }) { Text(if (authMode == "login" && !connected && info?.optBoolean("registration") == true) "注册新账号" else "返回登录") }
                    Button(enabled = !busy, onClick = { authMode = "recover"; password = "" }) { Text("忘记密码") }
                }
                if (info?.optBoolean("registration") != true) AccountNote("此服务暂未开放注册。已有账号可登录；新用户请联系维护者。")
                if (authMode == "recover") AccountNote("重设密码会撤销此账号的所有登录和设备连接。")
            }
        } }
        if (connected) Button(enabled = !busy, onClick = { confirmAction = "disconnect" }) { Text("断开本机 / 更换账号") }
    } else {
        val current = session!!
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { runAction { loadInfo(); refreshAccount(current); notify("账号设置已刷新") } }) { Text(if (busy) "正在读取…" else "刷新账号") }
            if (page == 3 && (!connected || prefs.getBoolean("connection_invalid", false) || prefs.getString("device_id", "").isNullOrBlank())) Button(enabled = !busy, onClick = { runAction { enroll(current); refreshAccount(current); notify("本机已连接") } }) { Text("连接本机") }
        }
        if (enrollmentConflict && page == 3) {
            AccountNote(if (connected) "本机旧连接已失效或归属不符，请先显式断开，再连接本机。" else "登记请求无法继续。可以重新登记本机；设备数量已满时请先移除不用的设备。")
            Button(enabled = !busy, onClick = { if (connected) confirmAction = "disconnect" else confirmAction = "reenroll" }) { Text(if (connected) "断开失效连接" else "重新登记本机") }
        }
        if (page == 2) {
                if (editing.isNotEmpty()) Card(Modifier.fillMaxWidth()) { ArrowPreference("继续编辑未保存范围", summary = if (editing == "public") "公开访客 · ${selected.size} 项已选" else "群 $editing · ${selected.size} 项已选", onClick = { scopeVisible = true }) }
                val account = me
                if (account == null) AccountNote("请先刷新账号，读取当前分享范围。")
                else {
                    Card(Modifier.fillMaxWidth()) {
                        ArrowPreference("公开访客", summary = "已选 ${account.optJSONArray("public_fields").strings().size} 项 · 博客卡片和公开链接使用同一范围", enabled = !busy, onClick = { beginScope("public") })
                    }
                    if (account.optJSONArray("public_fields").strings().isNotEmpty()) Button(onClick = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(current.server + "/?user=" + java.net.URLEncoder.encode(current.name, "UTF-8")))) }) { Text("查看访客页面") }
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("QQ 身份与群", style = MiuixTheme.textStyles.title3)
                        val qq = account.optJSONArray("qq").strings()
                        Text(if (qq.isEmpty()) "尚未绑定 QQ" else "✓ 已验证 QQ：${qq.joinToString("、")}")
                        AccountNote("新群验证后默认不分享。选择范围后，该群成员才可查询；其他群不受影响。")
                        TextField(value = group, onValueChange = { group = it; bindCommand = "" }, label = "输入已接入的 QQ 群号", singleLine = true, enabled = !busy)
                        val available = info?.optJSONArray("groups").strings().toList()
                        if (available.isNotEmpty()) AccountNote("已接入：${available.take(5).joinToString("、")}${if (available.size > 5) " 等 ${available.size} 个群" else ""}")
                        else AccountNote("维护者尚未接入 QQ 群。可先使用本机与公开分享。")
                        Button(enabled = !busy, onClick = { runAction {
                            require(group.matches(Regex("[0-9]{5,12}"))) { "请输入正确的 QQ 群号" }
                            require(group in available) { "此群尚未接入服务，请联系机器人维护者添加" }
                            val result = NativeAccountApi.request(current.server, "/v1/accounts/bind-code", JSONObject().put("group_id", group).put("consent", true), current)
                            bindCommand = "/状态绑定 " + result.getString("code"); bindExpires = System.currentTimeMillis() + result.optLong("expires_in", 600) * 1000
                            notify("验证命令已生成，请在填写的 QQ 群发送，然后返回这里核对")
                        } }) { Text(if (qq.isEmpty()) "生成 QQ 验证命令" else "验证并添加此群") }
                        if (bindCommand.isNotBlank()) {
                            Text(bindCommand)
                            AccountNote("有效至 ${java.text.SimpleDateFormat("HH:mm", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(java.util.Date(bindExpires))}。不要使用别人转发的命令。")
                            Button(onClick = { copy("验证命令", bindCommand) }) { Text("复制命令") }
                            Button(enabled = !busy, onClick = { runAction { refreshAccount(current); notify("已刷新，请核对下方实际 QQ 和群号") } }) { Text("我已发送，刷新核对") }
                        }
                        for (pending in account.optJSONArray("pending").objects()) {
                            Text("待确认：QQ ${pending.getString("qq")}，群 ${pending.getString("group_id")}")
                            Button(enabled = !busy, onClick = { runAction {
                                NativeAccountApi.request(current.server, "/v1/accounts/bind-confirm", JSONObject().put("id", pending.getString("id")), current)
                                bindCommand = ""; refreshAccount(current); beginScope(pending.getString("group_id")); notify("身份已确认，请选择此群可见范围")
                            } }) { Text("这是我的 QQ，继续设置范围") }
                        }
                    } }
                    for (id in account.optJSONArray("groups").strings()) {
                        val count = account.optJSONObject("group_scopes")?.optJSONArray(id).strings().size
                        Card(Modifier.fillMaxWidth()) {
                            ArrowPreference("QQ群 $id", summary = if (count == 0) "尚未分享 · 点击选择可见内容" else "已分享 $count 项 · 影响此账号所有设备", enabled = !busy, onClick = { beginScope(id) })
                            ArrowPreference("移除此群授权", summary = "保留 QQ 身份及其他群的设置", enabled = !busy, onClick = { confirmAction = "group"; confirmId = id })
                        }
                    }
                    if (account.optJSONArray("qq").strings().isNotEmpty()) Button(enabled = !busy, onClick = { confirmAction = "qq" }) { Text("解除 QQ 绑定及全部群授权") }
                }
        } else {
                Text("我的设备", style = MiuixTheme.textStyles.title3)
                val devices = me?.optJSONArray("devices").objects()
                if (devices.isEmpty()) AccountNote("尚未连接设备。点击“连接本机”即可登记这台手机。")
                for (device in devices) {
                    val own = device.getString("id") == prefs.getString("device_id", "")
                    Card(Modifier.fillMaxWidth()) {
                        ArrowPreference(device.getString("label") + if (own) "（本机）" else "", summary = device.optString("platform") + " · " + when(device.optString("phase")) { "synced" -> "已接收过数据"; "waiting" -> "等待连接"; else -> "已连接，尚未接收数据" }, onClick = { confirmAction = "device"; confirmId = device.getString("id") })
                    }
                }
                AccountNote("点击设备可移除连接。这里的“已接收”是连接记录，不代表设备此刻在线。")
                if (connected) Button(enabled = !busy, onClick = { confirmAction = "disconnect" }) { Text("停止并断开本机") }
                Button(enabled = !busy, onClick = { runAction {
                    var revoked = false
                    try { NativeAccountApi.request(current.server, "/v1/accounts/logout", JSONObject(), current); revoked = true }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* Local logout remains available while offline. */ }
                    finally {
                        NativeAccountStore.clearSession(this@ConnectionPage); session = null; me = null; editing = ""
                        notify(if (revoked) "已退出账号管理，本机采集连接保留" else "已清除本机账号登录，服务端撤销未确认；本机采集连接保留")
                    }
                } }) { Text("退出账号管理") }
                AccountNote("State of Me · " + packageManager.getPackageInfo(packageName, 0).versionName)
        }
    }

    WindowDialog(show = recovery != null && page == 3, title = "保存账号恢复码", summary = "忘记密码时可用它恢复账号。请私密保存，不要发到群里。", onDismissRequest = {}) {
        val value = recovery
        if (value != null) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("账号：${value.name}"); Text(value.code)
            Button(onClick = { copy("恢复码", value.code) }) { Text("复制恢复码") }
            Button(enabled = !busy, colors = ButtonDefaults.buttonColorsPrimary(), onClick = { runAction {
                NativeAccountStore.clearRecovery(this@ConnectionPage); recovery = null
                session?.let { enroll(it); refreshAccount(it) }
                notify(if (session == null) "恢复码已确认，请断开已撤销的本机连接，再使用新密码登录" else "账号已就绪，本机已连接。请在采集页选择内容")
            } }) { Text("我已保存，继续") }
        }
    }
    WindowDialog(show = scopeVisible && editing.isNotEmpty() && page == 2, title = if (editing == "public") "公开分享范围" else "群 $editing 的可见范围", onDismissRequest = { if (!busy) scopeVisible = false }) {
        Column(Modifier.heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 200).coerceAtLeast(260).dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AccountNote(if (editing == "public") "任何访客都能读取勾选的数据，包括此账号以后连接的设备。只勾选你愿意公开的内容。" else "此群成员可以查询勾选的数据，影响此账号所有设备。")
            AccountNote("已选 ${selected.size} 项。范围已允许但设备未上传的内容不会凭空出现。新增字段默认不选中。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { selected = catalog.map { it.key }.toSet() }) { Text("选择当前全部") }
                Button(enabled = !busy, onClick = { selected = emptySet() }) { Text("全部取消") }
            }
            for ((category, fields) in catalog.groupBy { it.group }) {
                ArrowPreference(category, summary = "${fields.count { it.key in selected }} / ${fields.size} 项已选" + if (category in expanded) " · 收起" else " · 展开", onClick = { expanded = if (category in expanded) expanded - category else expanded + category })
                if (category in expanded) for (field in fields) CheckboxPreference(title = field.label, summary = field.description, checked = field.key in selected, enabled = !busy, onCheckedChange = { checked -> selected = if (checked) selected + field.key else selected - field.key })
            }
            if (conflict) {
                AccountNote("其他客户端可能已修改范围。你的草稿仍保留；可重新读取当前范围后再编辑。")
                Button(enabled = !busy, onClick = { runAction { val current = session ?: return@runAction; refreshAccount(current); beginScope(editing, replaceDraft = true); notify("已读取服务器范围，未保存草稿已替换") } }) { Text("重新读取并替换草稿") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { scopeVisible = false }) { Text("稍后继续") }
                Button(enabled = !busy && !conflict && catalog.isNotEmpty(), colors = ButtonDefaults.buttonColorsPrimary(), onClick = { runAction {
                    val current = session ?: return@runAction
                    val body = JSONObject().put("fields", JSONArray(selected.sorted())).put("expected_revision", editingRevision)
                    val path = if (editing == "public") "/v1/accounts/public" else "/v1/accounts/groups/scope"
                    if (editing != "public") body.put("group_id", editing)
                    NativeAccountApi.request(current.server, path, body, current)
                    editing = ""; scopeVisible = false; refreshAccount(current); notify("分享范围已保存，其他客户端刷新后会同步显示")
                } }) { Text(if (busy) "正在保存…" else "保存范围") }
            }
            Button(enabled = !busy, onClick = { editing = ""; selected = emptySet(); scopeVisible = false }) { Text("放弃修改") }
        }
    }
    WindowDialog(show = confirmAction.isNotEmpty(), title = when(confirmAction) { "reenroll" -> "重新登记本机？"; "qq" -> "解除 QQ 绑定？"; "group" -> "移除此群授权？"; "device" -> "移除此设备？"; else -> "停止并断开本机？" }, summary = when(confirmAction) { "reenroll" -> "将结束上一次登记请求，创建新的设备连接。已撤销的连接不会自动恢复。"; "qq" -> "将撤回所有群查询权限。设备和公开分享保持当前设置。"; "group" -> "此群将无法查询你的数据，QQ 身份和其他群权限保留。"; "device" -> "此设备的数据和连接将被移除，需要重新登录连接才能恢复上传。"; else -> "停止本机采集，撤销本机连接，并清除本机采集选择和歌词索引。音乐文件保留。" }, onDismissRequest = { if (!busy) confirmAction = "" }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { confirmAction = "" }) { Text("取消") }
            Button(enabled = !busy, onClick = { runAction {
                val action = confirmAction; val current = session
                when (action) {
                    "reenroll" -> { NativeAccountStore.clearEnrollment(this@ConnectionPage, current!!.server, current.name); enroll(current) }
                    "qq" -> NativeAccountApi.request(current!!.server, "/v1/accounts/qq/unbind", JSONObject(), current)
                    "group" -> NativeAccountApi.request(current!!.server, "/v1/accounts/groups/revoke", JSONObject().put("id", confirmId), current)
                    "device" -> {
                        NativeAccountApi.request(current!!.server, "/v1/accounts/devices/revoke", JSONObject().put("id", confirmId), current)
                        if (confirmId == prefs.getString("device_id", "")) clearLocalDevice()
                    }
                    else -> {
                        try { NativeAccountApi.request(prefs.getString("url", "").orEmpty(), "/v1/device/disconnect", JSONObject(), deviceToken = prefs.getString("token", "").orEmpty()) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { notify("本机将停止连接。服务端未确认撤销，请在已登录账号的设备列表移除它") }
                        clearLocalDevice()
                    }
                }
                confirmAction = ""; bindCommand = ""
                if (current != null) refreshAccount(current)
                if (action != "disconnect") notify("设置已更新")
            } }) { Text(if (busy) "正在处理…" else "确认") }
        }
    }
}
