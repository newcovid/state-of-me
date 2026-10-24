package io.github.stateofme

import android.content.*
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.*
import top.yukonga.miuix.kmp.theme.*
import top.yukonga.miuix.kmp.window.WindowDialog

/** Real upstream Miuix components; collection and storage remain independent. */
class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("som", MODE_PRIVATE) }
    private var revision by mutableIntStateOf(0)
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { revision++ } }
    private fun value(key: String, default: String = "") = prefs.getString(key, default).orEmpty()
    private fun csv(key: String) = value(key).split(',').filter { it.isNotBlank() }.toSet()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private val folder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("tree", uri.toString()).apply(); MusicLibrary.scan(this, uri)
        } catch (_: Exception) { toast("无法读取这个文件夹，请重新选择") }
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> revision++; toast(if (granted) "通知已授权" else "通知未授权，可稍后在权限页开启") }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); prefs.registerOnSharedPreferenceChangeListener(listener); enableEdgeToEdge()
        if (value("account").isBlank()) prefs.edit().remove("token").putBoolean("enabled", false).apply()
        // Package replacement stops services. Reopen resumes only an existing user opt-in.
        if (prefs.getBoolean("enabled", false) && value("url").startsWith("https://") && value("token").length >= 24 && value("account").isNotBlank()) {
            startForegroundService(Intent(this, Collector::class.java))
        }
        setContent { MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { App() } }
    }
    override fun onResume() { super.onResume(); revision++ }
    override fun onDestroy() { prefs.unregisterOnSharedPreferenceChangeListener(listener); super.onDestroy() }
    @Composable private fun App() {
        val refresh = revision
        var page by rememberSaveable { mutableIntStateOf(0) }
        var dialog by rememberSaveable { mutableStateOf("") }
        Scaffold(topBar = { TopAppBar(title = "State of Me", bottomContent = {
            TabRow(minWidth = 0.dp, tabs = listOf("状态", "分享", "歌词", "连接"), selectedTabIndex = page, onTabSelected = { page = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (page) {
                    0 -> {
                        Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(if (prefs.getBoolean("enabled", false)) "正在分享" else "已暂停")
                            Text(value("status", "选择分享范围后即可开始"))
                        } }
                        Button(onClick = {
                            val enabled = prefs.getBoolean("enabled", false)
                            if (!enabled && (!value("url").startsWith("https://") || value("token").length < 24 || value("account").isBlank())) { page = 3; toast("请先连接账号") }
                            else {
                                prefs.edit().putBoolean("enabled", !enabled).apply()
                                if (!enabled && android.os.Build.VERSION.SDK_INT >= 33 && !notificationGranted() && !prefs.getBoolean("notification_requested", false)) requestNotifications()
                                startForegroundService(Intent(this@MainActivity, Collector::class.java).apply { if (enabled) action = "stop" })
                            }
                        }, colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.fillMaxWidth()) { Text(if (prefs.getBoolean("enabled", false)) "暂停分享" else "开始分享") }
                        Note("只分享你开启的内容。暂停后会清空服务端状态。")
                        Card(modifier = Modifier.fillMaxWidth()) { ArrowPreference("分享范围", summary = "选择应用与设备信息", onClick = { page = 1 }); ArrowPreference("歌词", summary = "从音乐文件夹自动匹配", onClick = { page = 2 }) }
                    }
                    1 -> {
                        Card(modifier = Modifier.fillMaxWidth()) { ArrowPreference("应用", summary = filterSummary("apps"), onClick = { dialog = "apps" }); ArrowPreference("播放器", summary = filterSummary("players"), onClick = { dialog = "players" }); ArrowPreference("所需权限", summary = permissionSummary(), onClick = { dialog = "permissions" }) }
                        Card(modifier = Modifier.fillMaxWidth()) { Toggle("电量与设备状态", "device_state"); Toggle("网络状态", "network"); Toggle("声音设置", "audio"); ArrowPreference("更多信息", summary = "应用时长、存储与采集诊断", onClick = { dialog = "more" }) }
                        Note("修改自动保存，下一次同步生效。公开展示范围仍由服务端控制。")
                    }
                    2 -> {
                        Card(modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(if (MusicLibrary.hasReadPermission(this@MainActivity)) "✓ 音乐文件夹已授权" else "音乐文件夹尚未授权"); Text(value("libraryStatus", "尚未选择音乐文件夹")); Text(lyricLabel()) } }
                        Button(onClick = { folder.launch(null) }, colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.fillMaxWidth()) { Text(if (!MusicLibrary.hasReadPermission(this@MainActivity)) "选择音乐文件夹并授权" else "更换音乐文件夹") }
                        if (value("tree").isNotEmpty()) Card(modifier = Modifier.fillMaxWidth()) {
                            ArrowPreference("刷新音乐库", summary = "添加歌曲后重新扫描", onClick = { MusicLibrary.scan(this@MainActivity, Uri.parse(value("tree"))) })
                            ArrowPreference("移除音乐文件夹", summary = "停止读取并清除本地索引", onClick = { dialog = "remove" })
                        }
                        Note("选择一次音乐所在文件夹，播放时自动读取歌曲标签中的歌词。音乐文件不会上传。")
                    }
                    3 -> ConnectionPage()
                }
            }
        }
        if (dialog == "apps" || dialog == "players") AppPicker(dialog) { dialog = "" }
        WindowDialog(show = dialog == "permissions", title = "所需权限", onDismissRequest = { dialog = "" }) {
            Column(Modifier.heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 180).coerceAtLeast(240).dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PermissionRow("音乐通知访问", musicGranted(), value("players_mode", "whitelist") != "off", "读取播放器的歌曲与进度。进入后选择 State of Me 并允许通知访问。") { openPermission(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                PermissionRow("应用使用情况", usageGranted(), value("apps_mode", "whitelist") != "off", "识别前台应用。进入后选择 State of Me，开启使用情况访问权限。") { openPermission(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))) }
                PermissionRow("运行通知", notificationGranted(), true, "显示分享运行状态，方便随时暂停。") { requestNotifications() }
                Note("返回本页会自动检查授权结果。系统页面因手机品牌而异；已授权项目无需重复操作。")
                Button(onClick = { dialog = "" }) { Text("完成") }
            }
        }
        WindowDialog(show = dialog == "more", title = "更多信息", onDismissRequest = { dialog = "" }) {
            Column { Toggle("应用名称与时长", "app_details"); Toggle("内存、存储与热状态", "resources"); Toggle("采集权限诊断", "collector_health"); Button(onClick = { dialog = "" }) { Text("完成") } }
        }
        WindowDialog(show = dialog == "remove", title = "移除音乐文件夹？", summary = "音乐播放状态仍可分享，内嵌歌词将停止读取。", onDismissRequest = { dialog = "" }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { dialog = "" }, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { MusicLibrary.clear(this@MainActivity); dialog = "" }, modifier = Modifier.weight(1f)) { Text("移除") }
            }
        }
    }
    @Composable private fun Note(text: String) { Text(text, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
    @Composable private fun Toggle(title: String, key: String) {
        val refresh = revision
        SwitchPreference(title = title, checked = prefs.getBoolean(key, false), onCheckedChange = { prefs.edit().putBoolean(key, it).apply() })
    }
    private fun filterSummary(key: String): String {
        val mode = value(key + "_mode", "whitelist")
        return when (mode) { "off" -> "不分享"; "blacklist" -> "黑名单 · 排除 ${csv(key + "_blocked").size} 个"; else -> "白名单 · 仅分享 ${csv(key).size} 个" }
    }
    private fun usageGranted() = getSystemService(android.app.AppOpsManager::class.java).checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == android.app.AppOpsManager.MODE_ALLOWED
    private fun musicGranted(): Boolean {
        val listener = ComponentName(this, MediaAccess::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 27) return getSystemService(android.app.NotificationManager::class.java).isNotificationListenerAccessGranted(listener)
        return Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty().split(':').any { ComponentName.unflattenFromString(it) == listener }
    }
    private fun notificationGranted() = android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED
    private fun requestNotifications() {
        if (android.os.Build.VERSION.SDK_INT < 33 || notificationGranted()) return
        if (prefs.getBoolean("notification_requested", false) && !shouldShowRequestPermissionRationale("android.permission.POST_NOTIFICATIONS")) {
            openPermission(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } else { prefs.edit().putBoolean("notification_requested", true).apply(); notifications.launch("android.permission.POST_NOTIFICATIONS") }
    }
    private fun permissionSummary() = "音乐${if (musicGranted()) " ✓" else if (value("players_mode") == "off") "未启用" else "未授权"} · 应用${if (usageGranted()) " ✓" else if (value("apps_mode") == "off") "未启用" else "未授权"}"
    private fun openPermission(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    }
    @Composable private fun PermissionRow(title: String, granted: Boolean, needed: Boolean, guide: String, request: () -> Unit) {
        if (granted) { Text("✓ $title · 已授权"); Note(if (needed) "无需再次授权" else "当前功能未开启，可在系统设置中管理权限") }
        else if (!needed) { Text("$title · 当前不需要"); Note("开启对应分享功能后再授权") }
        else { Text("$title · 未授权"); Note(guide); Button(onClick = request) { Text("去授权") } }
    }
    private fun lyricLabel() = if (value("tree").isNotEmpty() && !MusicLibrary.hasReadPermission(this)) "文件夹授权已失效，请重新选择" else if (value("tree").isEmpty()) "选择文件夹后自动匹配歌词" else when (value("lyricStatus")) {
        "synced" -> "已匹配同步歌词"; "plain" -> "这首歌的歌词没有时间轴"; "ambiguous" -> "存在重复歌曲，暂时无法确定歌词"; "permission_required" -> "请重新选择音乐文件夹授权"; "unmatched" -> "当前歌曲不在已选文件夹中"; else -> "播放歌曲后显示匹配结果"
    }
    private data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)
    @Composable private fun AppPicker(key: String, dismiss: () -> Unit) {
        var mode by rememberSaveable(key) { mutableStateOf(value(key + "_mode", "whitelist")) }
        var white by remember { mutableStateOf(csv(key)) }; var black by remember { mutableStateOf(csv(key + "_blocked")) }
        var query by rememberSaveable { mutableStateOf("") }; var loaded by remember { mutableStateOf(false) }
        var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
        LaunchedEffect(key) {
            apps = withContext(Dispatchers.IO) {
                val packages = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.activityInfo.packageName }.toMutableSet()
                try { packages.addAll(getSystemService(android.media.session.MediaSessionManager::class.java).getActiveSessions(ComponentName(this@MainActivity, MediaAccess::class.java)).map { it.packageName }) } catch (_: SecurityException) { }
                packages.addAll(white); packages.addAll(black); packages.addAll(csv(key + "_candidates")); packages.remove(packageName)
                packages.map { p ->
                    try {
                        val info = packageManager.getApplicationInfo(p, 0)
                        AppEntry(p, packageManager.getApplicationLabel(info).toString(), packageManager.getApplicationIcon(info).toBitmap(96, 96).asImageBitmap())
                    } catch (_: Exception) { AppEntry(p, p, null) }
                }.sortedWith(compareBy<AppEntry> { !(it.pkg in white || it.pkg in black) }.thenBy { it.label })
            }; loaded = true
        }
        WindowDialog(show = true, title = if (key == "apps") "选择应用" else "选择播放器", onDismissRequest = dismiss) {
            Column(Modifier.heightIn(max = 600.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TabRow(minWidth = 0.dp, tabs = listOf("不分享", "白名单", "黑名单"), selectedTabIndex = listOf("off", "whitelist", "blacklist").indexOf(mode).coerceAtLeast(0), onTabSelected = { mode = listOf("off", "whitelist", "blacklist")[it] })
                if (mode != "off") {
                    TextField(value = query, onValueChange = { query = it }, label = "搜索应用名称", singleLine = true)
                    val visible = apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
                    LazyColumn(Modifier.weight(1f, fill = false).heightIn(min = 80.dp)) {
                        if (!loaded || visible.isEmpty()) item { Text(if (!loaded) "正在读取应用列表…" else "没有匹配的应用", modifier = Modifier.padding(16.dp)) }
                        items(visible, key = { it.pkg }) { app ->
                            val selected = if (mode == "blacklist") black else white
                            CheckboxPreference(title = app.label, startAction = { app.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(36.dp)) } }, checkboxLocation = CheckboxLocation.End, checked = app.pkg in selected, onCheckedChange = { checked ->
                                val next = if (checked) selected + app.pkg else selected - app.pkg
                                if (mode == "blacklist") black = next else white = next
                            })
                        }
                    }
                    Note(if (mode == "blacklist") "分享此列表中未勾选的应用。新安装应用需重新保存。" else "仅分享勾选的应用。应用列表只保存在本机。")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = dismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(enabled = loaded, onClick = {
                        prefs.edit().putString(key + "_mode", mode).putString(key, white.sorted().joinToString(",")).putString(key + "_blocked", black.sorted().joinToString(",")).putString(key + "_candidates", apps.map { it.pkg }.sorted().joinToString(",")).apply()
                        dismiss(); toast("分享范围已保存")
                    }, colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.weight(1f)) { Text("保存") }
                }
            }
        }
    }
}
