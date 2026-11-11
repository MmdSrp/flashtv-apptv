package tv.flashplayer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.*
import android.view.View
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ---------- Theme ----------
val Bg = Color(0xFF0B0D12); val CardC = Color(0xFF161A23); val Hi = Color(0xFF2A3047)
val Accent = Color(0xFF8B6CFF); val Cyan = Color(0xFF22D3EE); val Dim = Color(0xFF9AA3B8)
lateinit var loader: ImageLoader

// ---------- Model ----------
enum class Kind(val icon: String, val label: String) {
    DIR("📁", "پوشه"), VIDEO("🎬", "ویدیو"), AUDIO("🎵", "موسیقی"), IMAGE("🖼️", "تصویر"), OTHER("📄", "فایل")
}
enum class Sec(val icon: String, val label: String, val kind: Kind?) {
    ALL("🗂️", "همه فایل‌ها", null), VIDEO("🎬", "ویدیوها", Kind.VIDEO),
    AUDIO("🎵", "موسیقی", Kind.AUDIO), IMAGE("🖼️", "عکس‌ها", Kind.IMAGE)
}
fun File.kind(): Kind = if (isDirectory) Kind.DIR else when (extension.lowercase()) {
    "mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "wmv", "3gp", "mpg", "mpeg" -> Kind.VIDEO
    "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma" -> Kind.AUDIO
    "jpg", "jpeg", "png", "webp", "bmp", "gif" -> Kind.IMAGE
    else -> Kind.OTHER
}
fun fmtSize(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    else -> "%d KB".format(b / 1024)
}
fun detail(f: File): String {
    val d = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date(f.lastModified()))
    return if (f.isDirectory) d else "${fmtSize(f.length())}  •  ${f.extension.uppercase()}  •  $d"
}
fun drives(ctx: Context): List<Pair<String, File>> {
    val sm = ctx.getSystemService(StorageManager::class.java)
    return sm.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }.mapNotNull { v ->
        val dir = if (Build.VERSION.SDK_INT >= 30) v.directory else v.uuid?.let { File("/storage/$it") }
        dir?.takeIf { it.exists() }?.let { (v.getDescription(ctx) ?: "USB") to it }
    }
}
fun scan(root: File, k: Kind): List<File> =
    root.walkTopDown().maxDepth(8).onEnter { !it.name.startsWith(".") && it.name != "Android" }
        .filter { it.isFile && !it.name.startsWith(".") && it.kind() == k }.take(4000)
        .sortedBy { it.name.lowercase() }.toList()
fun askAccess(a: Activity) {
    try {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager())
                a.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${a.packageName}")))
        } else a.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
    } catch (e: Exception) {
        try { a.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1) } catch (_: Exception) {}
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        loader = ImageLoader.Builder(applicationContext).components { add(VideoFrameDecoder.Factory()) }.crossfade(true).build()
        askAccess(this)
        val ff = try {
            val l = assets.list("fonts")?.toList() ?: emptyList()
            if ("Vazirmatn-Regular.ttf" in l && "Vazirmatn-Bold.ttf" in l)
                FontFamily(Font("fonts/Vazirmatn-Regular.ttf", assets, FontWeight.Normal), Font("fonts/Vazirmatn-Bold.ttf", assets, FontWeight.Bold))
            else FontFamily.Default
        } catch (e: Exception) { FontFamily.Default }
        setContent {
            val t = Typography()
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = CardC),
                typography = t.copy(bodyLarge = t.bodyLarge.copy(fontFamily = ff))) {
                CompositionLocalProvider(LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize(), color = Bg) { App() }
                }
            }
        }
    }
}

// ---------- TV focus helper ----------
@Composable
fun Modifier.tv(base: Color = CardC, shape: RoundedCornerShape = RoundedCornerShape(20.dp), onClick: () -> Unit): Modifier {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) 1.04f else 1f, label = "s")
    return this.scale(s).onFocusChanged { f = it.isFocused }
        .clip(shape).background(if (f) Hi else base)
        .border(if (f) 3.dp else 0.dp, if (f) Accent else Color.Transparent, shape)
        .clickable(onClick = onClick)
}

// ---------- App ----------
@Composable
fun App() {
    val ctx = LocalContext.current
    var drv by remember { mutableStateOf(listOf<Pair<String, File>>()) }
    var root by remember { mutableStateOf<Pair<String, File>?>(null) }
    var sec by remember { mutableStateOf(Sec.ALL) }
    var dir by remember { mutableStateOf<File?>(null) }
    var viewer by remember { mutableStateOf<Pair<List<File>, Int>?>(null) }
    LaunchedEffect(Unit) { while (true) { drv = withContext(Dispatchers.IO) { drives(ctx) }; delay(2500) } }
    BackHandler(viewer != null) { viewer = null }
    BackHandler(viewer == null && root != null) {
        if (sec == Sec.ALL && dir != null && dir != root!!.second) dir = dir!!.parentFile else root = null
    }
    val v = viewer; val r = root
    when {
        v != null -> when (v.first[v.second].kind()) {
            Kind.VIDEO -> VideoScreen(v.first, v.second)
            Kind.AUDIO -> AudioScreen(v.first, v.second)
            else -> PhotoScreen(v.first, v.second)
        }
        r == null -> Home(drv) { root = it; dir = it.second; sec = Sec.ALL }
        else -> Browser(r.first, r.second, sec, dir ?: r.second,
            { sec = it; dir = r.second }, { dir = it }, { root = null }, { l, i -> viewer = l to i })
    }
}

@Composable
fun Home(drv: List<Pair<String, File>>, onPick: (Pair<String, File>) -> Unit) {
    val ctx = LocalContext.current
    val req = remember { FocusRequester() }
    LaunchedEffect(drv.size) { delay(100); try { req.requestFocus() } catch (_: Exception) {} }
    Box(Modifier.fillMaxSize().aurora(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Logo(96, true)
            Text("Flash Player", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.padding(top = 14.dp))
            Text("فلش خود را انتخاب کنید", fontSize = 22.sp, color = Dim, modifier = Modifier.padding(top = 8.dp, bottom = 48.dp))
            if (drv.isEmpty()) {
                Column(Modifier.focusRequester(req).tv(onClick = { askAccess(ctx as Activity) }).padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔌", fontSize = 56.sp)
                    Text("هیچ فلشی پیدا نشد", fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    Text("فلش را وصل کنید (OK = درخواست اجازه دسترسی)", color = Dim, fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                drv.forEachIndexed { i, d ->
                    Column(Modifier.enter(i).width(300.dp).then(if (i == 0) Modifier.focusRequester(req) else Modifier)
                        .tv(onClick = { onPick(d) }).padding(28.dp)) {
                        Text("💾", fontSize = 56.sp)
                        Text(d.first, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${fmtSize(d.second.freeSpace)} آزاد از ${fmtSize(d.second.totalSpace)}", color = Dim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
                        LinearProgressIndicator(progress = { 1f - d.second.freeSpace.toFloat() / d.second.totalSpace.coerceAtLeast(1) },
                            color = Cyan, trackColor = Color(0xFF2A3047), modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                    }
                }
            }
        }
    }
}

@Composable
fun Thumb(f: File, mod: Modifier, fs: Int) {
    val k = f.kind()
    Box(mod.background(Color(0xFF1E2333)), contentAlignment = Alignment.Center) {
        if (k == Kind.IMAGE || k == Kind.VIDEO)
            AsyncImage(model = f, imageLoader = loader, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(k.icon, fontSize = fs.sp)
        if (k == Kind.VIDEO) Text("▶", fontSize = (fs / 2).sp, color = Color.White.copy(alpha = .85f))
    }
}

@Composable
fun SideItem(text: String, selected: Boolean, req: FocusRequester? = null, onClick: () -> Unit) {
    Text(text, fontSize = 20.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth().then(if (req != null) Modifier.focusRequester(req) else Modifier)
            .tv(if (selected) Accent.copy(alpha = .35f) else Color.Transparent, RoundedCornerShape(14.dp), onClick).padding(horizontal = 18.dp, vertical = 14.dp))
}

@Composable
fun Browser(name: String, root: File, sec: Sec, dir: File, onSec: (Sec) -> Unit, onDir: (File) -> Unit,
            onChange: () -> Unit, onOpen: (List<File>, Int) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); try { first.requestFocus() } catch (_: Exception) {} }
    var items by remember(sec, dir) { mutableStateOf<List<File>?>(null) }
    LaunchedEffect(sec, dir) {
        items = withContext(Dispatchers.IO) {
            if (sec.kind == null) (dir.listFiles()?.filter { !it.name.startsWith(".") }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList())
            else scan(root, sec.kind)
        }
    }
    Row(Modifier.fillMaxSize().aurora()) {
        Column(Modifier.width(290.dp).fillMaxHeight().background(Color(0xFF10131A)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Logo(44); Text("Flash Player", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text("💾 $name", color = Dim, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(bottom = 18.dp))
            Sec.values().forEach { s -> SideItem("${s.icon}   ${s.label}", s == sec, if (s == sec) first else null) { onSec(s) } }
            Spacer(Modifier.weight(1f))
            SideItem("⏏   تغییر فلش", false, null, onChange)
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 32.dp, vertical = 24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(sec.label, fontSize = 34.sp, fontWeight = FontWeight.Bold); Clock()
            }
            val path = if (sec == Sec.ALL) "/" + dir.relativeTo(root).path else "همه پوشه‌ها"
            Text("$path   •   ${items?.size ?: "…"} مورد", color = Dim, fontSize = 15.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            val list = items
            if (list == null) Text("در حال بارگذاری…", color = Dim, fontSize = 20.sp)
            else if (list.isEmpty() && dir == root) Text("چیزی پیدا نشد", color = Dim, fontSize = 20.sp)
            else if (sec == Sec.VIDEO || sec == Sec.IMAGE) {
                LazyVerticalGrid(GridCells.Adaptive(210.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(8.dp)) {
                    itemsIndexed(list) { i, f ->
                        Column(Modifier.enter(i).tv { onOpen(list, i) }.padding(10.dp)) {
                            Thumb(f, Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(12.dp)), 44)
                            Text(f.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(8.dp)) {
                if (sec == Sec.ALL && dir != root) item {
                    Text("⬆️   بازگشت به پوشه بالاتر", fontSize = 20.sp, modifier = Modifier.fillMaxWidth().tv { onDir(dir.parentFile!!) }.padding(20.dp))
                }
                itemsIndexed(list) { n, f ->
                    FileRow(f, n) {
                        val k = f.kind()
                        if (k == Kind.DIR) onDir(f)
                        else if (k != Kind.OTHER) {
                            val pl = list.filter { it.kind() == k }
                            onOpen(pl, pl.indexOf(f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FileRow(f: File, idx: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().enter(idx).tv(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Thumb(f, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), 30)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail(f), color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Text(f.kind().label, color = Cyan, fontSize = 15.sp)
    }
}

// ---------- Players ----------
@Composable
fun VideoScreen(list: List<File>, start: Int) {
    val ctx = LocalContext.current
    val p = remember { ExoPlayer.Builder(ctx).build().apply {
        setMediaItems(list.map { MediaItem.fromUri(Uri.fromFile(it)) }); seekTo(start, 0); prepare(); playWhenReady = true } }
    DisposableEffect(Unit) { onDispose { p.release() } }
    AndroidView(factory = { c -> PlayerView(c).apply {
        layoutDirection = View.LAYOUT_DIRECTION_LTR; player = p; useController = true; controllerShowTimeoutMs = 4000; keepScreenOn = true
        isFocusable = true; post { requestFocus() } } }, modifier = Modifier.fillMaxSize().background(Color.Black))
}

@Composable
fun Ctl(label: String, big: Boolean = false, onClick: () -> Unit) {
    Text(label, fontSize = if (big) 34.sp else 24.sp, textAlign = TextAlign.Center,
        modifier = Modifier.tv(if (big) Accent else CardC, RoundedCornerShape(50), onClick).padding(horizontal = if (big) 40.dp else 26.dp, vertical = 14.dp))
}

fun mmss(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

@Composable
fun AudioScreen(list: List<File>, start: Int) {
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(start) }
    var playing by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var rot by remember { mutableFloatStateOf(0f) }
    val p = remember { ExoPlayer.Builder(ctx).build().apply {
        setMediaItems(list.map { MediaItem.fromUri(Uri.fromFile(it)) }); seekTo(start, 0); prepare(); playWhenReady = true
        addListener(object : Player.Listener {
            override fun onMediaItemTransition(m: MediaItem?, r: Int) { idx = currentMediaItemIndex }
            override fun onIsPlayingChanged(b: Boolean) { playing = b }
        }) } }
    DisposableEffect(Unit) { onDispose { p.release() } }
    LaunchedEffect(playing) { while (playing) { rot = (rot + 0.6f) % 360f; delay(16) } }
    LaunchedEffect(Unit) { while (true) { pos = p.currentPosition; dur = p.duration.coerceAtLeast(0); delay(400) } }
    val f = list[idx.coerceIn(0, list.lastIndex)]
    Box(Modifier.fillMaxSize().aurora().padding(56.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(280.dp).graphicsLayer { rotationZ = rot }.clip(RoundedCornerShape(50))
                    .background(Brush.sweepGradient(listOf(Accent, Cyan, Accent, Color(0xFF3B2A8F), Accent))), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(90.dp).clip(RoundedCornerShape(50)).background(Bg))
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(Cyan))
                }
                Eq(playing)
            }
            Spacer(Modifier.width(52.dp))
            Column(Modifier.weight(1f)) {
                Text("NOW PLAYING  •  ${idx + 1}/${list.size}", color = Cyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(f.nameWithoutExtension, fontSize = 38.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(f.parentFile?.name ?: "", color = Dim, fontSize = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 28.dp))
                LinearProgressIndicator(progress = { if (dur > 0) pos.toFloat() / dur else 0f }, color = Cyan, trackColor = Color(0x33FFFFFF),
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(mmss(pos), color = Dim, fontSize = 14.sp); Text(mmss(dur), color = Dim, fontSize = 14.sp)
                }
                Row(Modifier.padding(vertical = 26.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Ctl("⏮") { p.seekToPreviousMediaItem() }
                    Ctl("−10s") { p.seekTo((p.currentPosition - 10000).coerceAtLeast(0)) }
                    Ctl(if (playing) "⏸" else "▶", true) { if (p.isPlaying) p.pause() else p.play() }
                    Ctl("+10s") { p.seekTo(p.currentPosition + 10000) }
                    Ctl("⏭") { p.seekToNextMediaItem() }
                }
                list.drop(idx + 1).take(3).forEach { Text("▸ ${it.nameWithoutExtension}", color = Dim, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
    }
}

@Composable
fun PhotoScreen(list: List<File>, start: Int) {
    var i by remember { mutableIntStateOf(start) }
    var auto by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    LaunchedEffect(auto, i) { if (auto) { delay(4000); i = (i + 1) % list.size } }
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(fr).focusable().onKeyEvent {
        if (it.type != KeyEventType.KeyDown) false else when (it.key) {
            Key.DirectionLeft, Key.MediaNext -> { i = (i + 1) % list.size; true }
            Key.DirectionRight, Key.MediaPrevious -> { i = (i - 1 + list.size) % list.size; true }
            Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> { auto = !auto; true }
            else -> false
        }
    }) {
        AsyncImage(model = list[i], imageLoader = loader, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(28.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(list[i].name, fontSize = 18.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text("${if (auto) "▶ اسلایدشو" else "⏸"}   ${i + 1} / ${list.size}   ◀ ▶", color = Cyan, fontSize = 16.sp)
        }
    }
}

@Composable
fun Logo(sz: Int, pulse: Boolean = false) {
    val tr = rememberInfiniteTransition(label = "l")
    val sc by tr.animateFloat(1f, if (pulse) 1.08f else 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
    Box(Modifier.graphicsLayer { scaleX = sc; scaleY = sc }.size(sz.dp).clip(RoundedCornerShape((sz / 4).dp)).background(Brush.linearGradient(listOf(Accent, Cyan))), contentAlignment = Alignment.Center) {
        Text("▶", fontSize = (sz / 2).sp, color = Color.White)
    }
}

@Composable
fun Clock() {
    var t by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { t = SimpleDateFormat("HH:mm", Locale.US).format(Date()); delay(10000) } }
    Text(t, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Cyan)
}

@Composable
fun Eq(on: Boolean) {
    val t = rememberInfiniteTransition(label = "eq")
    Row(Modifier.height(60.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 6) {
            val h by t.animateFloat(0.2f, 1f, infiniteRepeatable(tween(450 + i * 90, easing = LinearEasing), RepeatMode.Reverse), label = "b$i")
            Box(Modifier.width(8.dp).fillMaxHeight(if (on) h else 0.15f).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .9f)))
        }
    }
}

@Composable
fun Modifier.aurora(): Modifier {
    val t = rememberInfiniteTransition(label = "au")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    return this.background(Bg).drawBehind {
        val w = this.size.width; val h = this.size.height
        drawRect(Brush.radialGradient(listOf(Accent.copy(alpha = .30f), Color.Transparent), center = Offset(w * (0.2f + 0.5f * a), h * 0.1f), radius = w * 0.55f))
        drawRect(Brush.radialGradient(listOf(Cyan.copy(alpha = .18f), Color.Transparent), center = Offset(w * (0.9f - 0.5f * a), h * 0.95f), radius = w * 0.5f))
    }
}

@Composable
fun Modifier.enter(i: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(((i % 8) * 45).toLong()); a.animateTo(1f, tween(450)) }
    return this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 60f }
}
