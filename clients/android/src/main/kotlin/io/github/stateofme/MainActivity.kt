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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.platform.LocalDensity
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
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
        UsageJob.schedule(this)
        setContent { MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { App() } }
    }
    override fun onResume() { super.onResume(); revision++ }
    override fun onDestroy() { prefs.unregisterOnSharedPreferenceChangeListener(listener); super.onDestroy() }
    @Composable private fun App() {
        val refresh = revision
        var page by rememberSaveable { mutableIntStateOf(0) }
        var dialog by rememberSaveable { mutableStateOf("") }
        var overview by rememberSaveable { mutableIntStateOf(0) }
        val scrolls = List(5) { rememberScrollState() }
        val pages = listOf("概览", "采集", "分享", "设置")
        val icons = listOf(MiuixIcons.Home, MiuixIcons.VerticalSplit, MiuixIcons.Contacts, MiuixIcons.Settings)
        val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Scaffold(topBar = { SmallTopAppBar(title = pages[page]) }, bottomBar = {
            if (!keyboardVisible) NavigationBar {
                pages.forEachIndexed { index, label -> NavigationBarItem(selected = page == index, onClick = { page = index }, icon = icons[index], label = label) }
            }
        }) { padding ->
            val scroll = scrolls[if (page == 0 && overview == 1) 4 else page]
            Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (page) {
                    0 -> {
                        TabRow(minWidth = 0.dp, tabs = listOf("实时", "使用统计"), selectedTabIndex = overview, onTabSelected = { overview = it })
                        if (overview == 1) UsagePage { page = 1 }
                        else {
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(if (prefs.getBoolean("enabled", false)) "正在同步到本账号" else "同步已暂停", style = MiuixTheme.textStyles.title3)
                                Text(value("status", "连接账号并选择采集内容后即可开始"))
                                Button(onClick = {
                                    val enabled = prefs.getBoolean("enabled", false)
                                    if (!enabled && (!value("url").startsWith("https://") || value("token").length < 24 || value("account").isBlank() || prefs.getBoolean("connection_invalid", false))) { page = 3; toast("请先在设置中连接账号") }
                                    else {
                                        prefs.edit().putBoolean("enabled", !enabled).apply()
                                        if (!enabled && android.os.Build.VERSION.SDK_INT >= 33 && !notificationGranted() && !prefs.getBoolean("notification_requested", false)) requestNotifications()
                                        startForegroundService(Intent(this@MainActivity, Collector::class.java).apply { if (enabled) action = "stop" })
                                    }
                                }, colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.fillMaxWidth()) { Text(if (prefs.getBoolean("enabled", false)) "暂停同步" else "开始同步") }
                                Note("暂停会撤下本机实时状态，并尝试撤下已同步的统计。访客和群权限在“分享”页设置。")
                            } }
                            SyncPreview()
                        }
                    }
                    1 -> {
                        Text("应用", style = MiuixTheme.textStyles.title3)
                        Card(Modifier.fillMaxWidth()) { ArrowPreference("前台应用", summary = filterSummary("apps"), onClick = { dialog = "apps" }); Toggle("应用名称与时长", "app_details") }
                        PermissionRow("使用情况访问", usageGranted(), value("apps_mode", "whitelist") != "off" || prefs.getBoolean("usage_history", false), "进入后选择 State of Me，开启使用情况访问权限。") { openPermission(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$packageName"))) }
                        Text("音乐与歌词", style = MiuixTheme.textStyles.title3)
                        Card(Modifier.fillMaxWidth()) { ArrowPreference("播放器", summary = filterSummary("players"), onClick = { dialog = "players" }) }
                        PermissionRow("音乐通知访问", musicGranted(), value("players_mode", "whitelist") != "off", "进入后选择 State of Me 并允许通知访问。") { openPermission(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(if (MusicLibrary.hasReadPermission(this@MainActivity)) "✓ 歌词目录已授权" else "歌词目录尚未授权")
                            Note(value("libraryStatus", "选择音乐所在文件夹，自动读取内嵌歌词；音乐文件不会上传。"))
                            if (value("tree").isNotEmpty()) Note(lyricLabel())
                            Button(onClick = { folder.launch(null) }) { Text(if (!MusicLibrary.hasReadPermission(this@MainActivity)) "选择歌词目录并授权" else "更换歌词目录") }
                        }
                            if (value("tree").isNotEmpty()) {
                                ArrowPreference("刷新音乐库", summary = "添加歌曲后重新扫描", onClick = { MusicLibrary.scan(this@MainActivity, Uri.parse(value("tree"))) })
                                ArrowPreference("移除歌词目录", summary = "停止读取并清除本机索引", onClick = { dialog = "remove" })
                            }
                        }
                        Text("设备信息", style = MiuixTheme.textStyles.title3)
                        Card(Modifier.fillMaxWidth()) { Toggle("电量与设备状态", "device_state"); Toggle("网络状态", "network"); Toggle("声音设置", "audio"); Toggle("内存、存储与热状态", "resources"); Toggle("采集权限诊断", "collector_health") }
                        Text("使用统计", style = MiuixTheme.textStyles.title3)
                        Card(Modifier.fillMaxWidth()) { SwitchPreference(title = "同步使用统计", summary = "同步到本账号；公开和群可见范围单独设置。", checked = prefs.getBoolean("usage_history", false), onCheckedChange = { prefs.edit().putBoolean("usage_history", it).apply(); UsageJob.schedule(this@MainActivity) })
                            ArrowPreference("清除本机统计", summary = "从现在重新记录；不影响系统数字健康", onClick = { dialog = "clearStats" })
                        }
                        Note(value("usage_sync_status", "默认仅保存在本机。开启统计同步并开始同步后，每 15 分钟择机更新。"))
                        PermissionRow("运行通知", notificationGranted(), true, "显示同步运行状态，方便随时暂停。") { requestNotifications() }
                        Note("采集选择自动保存。返回本页会刷新权限状态。")
                    }
                }
                // Always composed: account requests and unsaved audience drafts survive navigation.
                ConnectionPage(page = page, openSettings = { page = 3 })
            }
        }
        WindowDialog(show = dialog == "clearStats", title = "清除本机使用统计？", summary = "清除本机汇总并从现在重新记录；下一次成功同步会替换服务端记录。", onDismissRequest = { dialog = "" }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { UsageHistory.clear(this@MainActivity); UsageJob.schedule(this@MainActivity); dialog = ""; toast("本机统计已清除") }) { Text("确认清除") }
                Button(onClick = { dialog = "" }) { Text("取消") }
            }
        }
        if (dialog == "apps" || dialog == "players") AppPicker(dialog) { dialog = "" }
        WindowDialog(show = dialog == "remove", title = "移除音乐文件夹？", summary = "音乐播放状态仍可同步，内嵌歌词将停止读取。", onDismissRequest = { dialog = "" }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { dialog = "" }, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(onClick = { MusicLibrary.clear(this@MainActivity); dialog = "" }, modifier = Modifier.weight(1f)) { Text("移除") }
            }
        }
    }
    @Composable private fun SyncPreview() {
        val refreshed = revision
        var tick by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
        LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5000); tick = android.os.SystemClock.elapsedRealtime() } }
        val raw = Collector.getUiPreview()
        val snapshot = remember(raw) { try { org.json.JSONObject(raw.json) } catch (_: Exception) { null } }
        val fresh = prefs.getBoolean("enabled", false) && !prefs.getBoolean("connection_invalid", false) && snapshot != null && tick - raw.at in 0..45000
        Text("最近同步的内容", style = MiuixTheme.textStyles.title3)
        if (!fresh) Note("暂无新鲜的本机同步预览。开始同步后，成功上传的内容会显示在这里。")
        else Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val app = snapshot.optString("app")
            val details = snapshot.optJSONObject("app_details")
            if (app.isNotEmpty()) Text("应用  " + (details?.optString("label")?.takeIf { it.isNotBlank() } ?: app))
            val music = snapshot.optJSONObject("music")
            if (music?.optBoolean("playing") == true) { Text("正在播放  " + music.optString("title")); music.optString("artist").takeIf { it.isNotBlank() }?.let { Note(it) } }
            val device = snapshot.optJSONObject("device_state")
            if (device?.has("battery_percent") == true) Text("电量  ${device.optInt("battery_percent")}%" + if (device.optBoolean("charging")) " · 充电中" else "")
            if (app.isEmpty() && music?.optBoolean("playing") != true && device == null) Note("本次没有应用、正在播放的媒体或电量信息。")
        } }
        Note("这是本机最近成功同步的预览，不等于公开结果；超过 45 秒不展示。")
    }
    @Composable private fun UsagePage(openCollection: () -> Unit) {
        val refresh = revision
        var range by rememberSaveable { mutableIntStateOf(0) }
        var reload by remember { mutableIntStateOf(0) }
        var busy by remember { mutableStateOf(false) }
        var data by remember { mutableStateOf<org.json.JSONObject?>(null) }
        var error by remember { mutableStateOf("") }
        val granted = usageGranted()
        LaunchedEffect(reload, granted) {
            if (!granted) { data = null; return@LaunchedEffect }
            prefs.edit().putBoolean("usage_local", true).apply(); UsageJob.schedule(this@MainActivity)
            busy = true; error = ""
            try { data = withContext(Dispatchers.IO) {
                val result = UsageHistory.refresh(this@MainActivity, true)
                UsageHistory.retry()
                UsageHistory.sync(this@MainActivity, !prefs.getBoolean("enabled", false))
                result
            } } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: Exception) { error = "暂时无法读取统计，请确认使用情况权限已授权、手机已解锁，再点刷新。" }
            finally { busy = false }
        }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("使用统计", style = MiuixTheme.textStyles.title2)
            Note("按手机时区汇总。屏幕亮着不代表一直在看；应用时长可能重叠。")
            if (!granted) { Text("尚未授权使用情况访问"); Button(onClick = openCollection, colors = ButtonDefaults.buttonColorsPrimary()) { Text("前往采集设置权限") } }
            else { Note("✓ 使用情况已授权"); Button(enabled = !busy, onClick = { reload++ }) { Text(if (busy) "正在更新…" else "刷新统计") } }
            if (error.isNotEmpty()) Note(error)
        } }
        val count = listOf(1, 7, 30)[range]
        TabRow(minWidth = 0.dp, tabs = listOf("今天", "近 7 天", "近 30 天"), selectedTabIndex = range, onTabSelected = { range = it })
        val root = data
        if (root != null) {
            val zone = java.time.ZoneId.of(root.getString("timezone"))
            val today = java.time.LocalDate.now(zone)
            val all = root.getJSONArray("days")
            val rows = (0 until all.length()).map { all.getJSONObject(it) }.filter { !java.time.LocalDate.parse(it.getString("date")).isBefore(today.minusDays((count - 1).toLong())) }
            if (root.optLong("first_event_at") == 0L || rows.isEmpty()) Note("系统暂未提供使用事件。使用手机后再刷新；缺失记录不会当作零。")
            else {
                fun duration(ms: Long): String { val minutes = ms / 60000; return if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分" else "$minutes 分" }
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("屏幕交互  ${duration(rows.sumOf { it.getLong("screen_ms") })}", style = MiuixTheme.textStyles.title3)
                    Text("解锁使用  ${duration(rows.sumOf { it.getLong("unlocked_ms") })}")
                    Text("解锁次数  ${rows.sumOf { it.getInt("unlocks") }} 次")
                    Note("已记录 ${rows.size} 天 · ${root.getString("timezone")}")
                    Note("已知屏幕与锁屏状态覆盖 ${duration(rows.sumOf { it.getLong("known_ms") })}")
                    Note("记录可能不完整。首次最多回补系统仍保留的近 7 天事件，近 30 天需要逐日积累。")
                } }
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (count == 1) "今天的每小时分布" else "每日屏幕交互", style = MiuixTheme.textStyles.title3)
                    val chart = if (count == 1) (0..23).map { hour -> Pair("${hour}时", rows.last().getJSONArray("hourly_screen_ms").getLong(hour)) } else rows.map { Pair(it.getString("date").substring(5), it.getLong("screen_ms")) }
                    val maximum = (chart.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1)
                    val barColor = MiuixTheme.colorScheme.primary
                    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(96.dp)) {
                        val step = size.width / chart.size.coerceAtLeast(1)
                        chart.forEachIndexed { index, item ->
                            val height = size.height * (item.second.toFloat() / maximum)
                            drawRoundRect(barColor, topLeft = androidx.compose.ui.geometry.Offset(index * step, size.height - height), size = androidx.compose.ui.geometry.Size(step * 0.65f, height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(chart.firstOrNull()?.first.orEmpty(), style = MiuixTheme.textStyles.footnote1); Text(chart.lastOrNull()?.first.orEmpty(), style = MiuixTheme.textStyles.footnote1) }
                    Note("最高 ${duration(maximum)}；缺少记录的日期不代表没有使用。")
                } }
                val totals = mutableMapOf<String, Pair<String, Long>>()
                for (row in rows) { val apps = row.getJSONArray("apps"); for (i in 0 until apps.length()) { val a = apps.getJSONObject(i); val key = a.getString("package"); totals[key] = Pair(a.getString("label"), (totals[key]?.second ?: 0) + a.getLong("foreground_ms")) } }
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("应用使用排行", style = MiuixTheme.textStyles.title3)
                    if (totals.isEmpty()) Note("这段时间没有可读取的应用使用记录。")
                    for ((_, item) in totals.entries.sortedByDescending { it.value.second }.take(15)) Text("${item.first}  ${duration(item.second)}")
                    Note("显示前 15 项。本机统计不受应用过滤影响；同步明细遵循采集页的应用名单。")
                } }
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
        return when (mode) { "off" -> "不采集"; "blacklist" -> "黑名单 · 排除 ${csv(key + "_blocked").size} 个"; else -> if (csv(key).isEmpty()) "白名单为空，不同步应用" else "白名单 · 仅同步 ${csv(key).size} 个" }
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
        if (granted) { Text("✓ $title · 已授权"); if (!needed) Note("当前功能未开启，可在系统设置中管理权限") }
        else if (!needed) { Text("$title · 当前不需要"); Note("开启对应采集功能后再授权") }
        else { Text("$title · 未授权"); Note(guide); Button(onClick = request) { Text("去授权") } }
    }
    private fun lyricLabel() = if (value("tree").isNotEmpty() && !MusicLibrary.hasReadPermission(this)) "文件夹授权已失效，请重新选择" else if (value("tree").isEmpty()) "选择文件夹后自动匹配歌词" else when (value("lyricStatus")) {
        "synced" -> "已匹配同步歌词"; "plain" -> "这首歌的歌词没有时间轴"; "ambiguous" -> "存在重复歌曲，暂时无法确定歌词"; "permission_required" -> "请重新选择音乐文件夹授权"; "unmatched" -> "当前歌曲不在已选文件夹中"; else -> "播放歌曲后显示匹配结果"
    }
    private data class AppEntry(val pkg: String, val label: String, val icon: ImageBitmap?)
    @Composable private fun AppPicker(key: String, dismiss: () -> Unit) {
        var mode by rememberSaveable(key) { mutableStateOf(value(key + "_mode", "whitelist")) }
        val selectionSaver = Saver<Set<String>, ArrayList<String>>(save = { ArrayList(it) }, restore = { it.toSet() })
        var white by rememberSaveable(key, stateSaver = selectionSaver) { mutableStateOf(csv(key)) }
        var black by rememberSaveable(key, stateSaver = selectionSaver) { mutableStateOf(csv(key + "_blocked")) }
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
            val density = LocalDensity.current
            val height = with(density) { (androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height - WindowInsets.ime.getBottom(density)).toDp() }
            Column(Modifier.heightIn(max = (height - 160.dp).coerceAtLeast(220.dp)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TabRow(minWidth = 0.dp, tabs = listOf("不采集", "白名单", "黑名单"), selectedTabIndex = listOf("off", "whitelist", "blacklist").indexOf(mode).coerceAtLeast(0), onTabSelected = { mode = listOf("off", "whitelist", "blacklist")[it] })
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
                    Note(if (mode == "blacklist") "同步此列表中未勾选的应用。新安装应用需重新保存。" else "仅同步勾选的应用。应用列表只保存在本机。")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = dismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(enabled = loaded, onClick = {
                        prefs.edit().putString(key + "_mode", mode).putString(key, white.sorted().joinToString(",")).putString(key + "_blocked", black.sorted().joinToString(",")).putString(key + "_candidates", apps.map { it.pkg }.sorted().joinToString(",")).apply()
                        dismiss(); toast("采集范围已保存")
                    }, colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.weight(1f)) { Text("保存") }
                }
            }
        }
    }
}
