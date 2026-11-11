package tv.flashplayer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.Equalizer
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.PI
import kotlin.math.sin

// ---------- Theme: flat dark gray + white ----------
val Bg = Color(0xFF191919); val Panel = Color(0xFF242424); val Bar = Color(0xFF2D2D2D)
val Line = Color(0xFF3D3D3D); val Sel = Color(0xFF3F3F3F); val Dim = Color(0xFF9A9A9A); val Ic = Color(0xFFDADADA)
var HeadFont: FontFamily = FontFamily.Default
lateinit var loader: ImageLoader

object Pref {
    private var sp: SharedPreferences? = null
    var sfx by mutableStateOf(true); private set
    var eq by mutableStateOf(false); private set
    fun init(c: Context) { sp = c.getSharedPreferences("prefs", Context.MODE_PRIVATE); sfx = sp!!.getBoolean("sfx", true); eq = sp!!.getBoolean("eq", false) }
    fun setSfx(b: Boolean) { sfx = b; sp?.edit()?.putBoolean("sfx", b)?.apply() }
    fun setEq(b: Boolean) { eq = b; sp?.edit()?.putBoolean("eq", b)?.apply() }
}

// ---------- Sound effects (generated, no files) ----------
object Sfx {
    private fun tone(f: Float, ms: Int, vol: Float): ShortArray {
        val n = 44100 * ms / 1000
        return ShortArray(n) { i -> (sin(2 * PI * f * i / 44100) * Short.MAX_VALUE * vol * (1 - i.toFloat() / n)).toInt().toShort() }
    }
    private val snd: Map<String, ShortArray> by lazy {
        mapOf("move" to tone(1100f, 22, .12f), "ok" to tone(1400f, 70, .35f), "back" to tone(700f, 90, .35f),
            "play" to tone(900f, 60, .35f) + tone(1500f, 100, .35f))
    }
    fun play(k: String) {
        if (!Pref.sfx) return
        val d = snd[k] ?: return
        Thread {
            try {
                val t = AudioTrack(AudioManager.STREAM_MUSIC, 44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, d.size * 2, AudioTrack.MODE_STATIC)
                t.write(d, 0, d.size); t.play(); Thread.sleep(260); t.release()
            } catch (_: Exception) {}
        }.start()
    }
}

// ---------- Model ----------
enum class Kind(val label: String) { DIR("پوشه"), VIDEO("ویدیو"), AUDIO("موسیقی"), IMAGE("تصویر"), OTHER("فایل") }
enum class Sec(val label: String, val kind: Kind?) { ALL("همه فایل‌ها", null), VIDEO("ویدیوها", Kind.VIDEO), AUDIO("موسیقی", Kind.AUDIO), IMAGE("عکس‌ها", Kind.IMAGE) }
fun File.kind(): Kind = if (isDirectory) Kind.DIR else extKind(name)
fun extKind(n: String): Kind = when (n.substringAfterLast('.', "").lowercase()) {
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
fun dateOf(f: File): String = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date(f.lastModified()))
fun drives(ctx: Context): List<Pair<String, File>> {
    val sm = ctx.getSystemService(StorageManager::class.java)
    return sm.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }.mapNotNull { v ->
        val dir = if (Build.VERSION.SDK_INT >= 30) v.directory else v.uuid?.let { File("/storage/$it") }
        dir?.takeIf { it.exists() }?.let { (v.getDescription(ctx) ?: "USB") to it }
    }
}
fun walkSafe(root: File): Sequence<File> = root.walkTopDown().maxDepth(7).onEnter {
    val n = it.name
    it == root || !(n.startsWith(".") || n == "Android" || n == "System Volume Information" || n == "LOST.DIR" || n == "Recovery" || n.startsWith("\$RECYCLE"))
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
fun fam(a: AssetManager, vararg n: Pair<String, FontWeight>): FontFamily? = try {
    val l = a.list("fonts")?.toList() ?: emptyList()
    if (n.all { it.first in l }) FontFamily(n.map { Font("fonts/" + it.first, a, it.second) }) else null
} catch (e: Exception) { null }

class MainActivity : ComponentActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        loader = ImageLoader.Builder(applicationContext).components { add(VideoFrameDecoder.Factory()) }.crossfade(true).build()
        Pref.init(applicationContext)
        askAccess(this)
        val body = fam(assets, "Vazirmatn-Regular.ttf" to FontWeight.Normal, "Vazirmatn-Bold.ttf" to FontWeight.Bold) ?: FontFamily.Default
        HeadFont = fam(assets, "ArefRuqaa-Regular.ttf" to FontWeight.Normal, "ArefRuqaa-Bold.ttf" to FontWeight.Bold) ?: body
        setContent {
            val t = Typography()
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.White, background = Bg, surface = Panel),
                typography = t.copy(bodyLarge = t.bodyLarge.copy(fontFamily = body))) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize(), color = Bg) { App() }
                }
            }
        }
    }
}

// ---------- Helpers ----------
@Composable
fun Modifier.tv(base: Color = Color.Transparent, shape: RoundedCornerShape = RoundedCornerShape(6.dp), grow: Boolean = false, onClick: () -> Unit): Modifier {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f && grow) 1.03f else 1f, tween(120), label = "s")
    return this.scale(s).onFocusChanged { if (it.isFocused && !f) Sfx.play("move"); f = it.isFocused }
        .clip(shape).background(if (f) Sel else base)
        .border(if (f) 2.dp else 0.dp, if (f) Color.White else Color.Transparent, shape)
        .clickable { Sfx.play("ok"); onClick() }
}

@Composable
fun Modifier.enter(i: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(((i % 8) * 40).toLong()); a.animateTo(1f, tween(350)) }
    return this.graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 40f }
}

fun Modifier.dots(): Modifier = this.background(Bg).drawBehind {
    val g = 44.dp.toPx(); var x = g / 2
    while (x < this.size.width) { var y = g / 2; while (y < this.size.height) { drawCircle(Color(0xFF2B2B2B), 2.dp.toPx(), Offset(x, y)); y += g }; x += g }
}

@Composable
fun Glyph(k: Kind?, sz: Dp) = Canvas(Modifier.size(sz)) {
    val w = this.size.width; val h = this.size.height; val c = Ic
    when (k) {
        Kind.DIR -> { drawRoundRect(c, Offset(0f, h * .22f), Size(w, h * .62f), CornerRadius(w * .08f)); drawRoundRect(c, Offset(0f, h * .12f), Size(w * .42f, h * .2f), CornerRadius(w * .06f)) }
        Kind.VIDEO -> { drawRoundRect(c, Offset(0f, h * .2f), Size(w, h * .6f), CornerRadius(w * .1f))
            drawPath(Path().apply { moveTo(w * .4f, h * .35f); lineTo(w * .66f, h * .5f); lineTo(w * .4f, h * .65f); close() }, Panel) }
        Kind.AUDIO -> { drawCircle(c, w * .17f, Offset(w * .35f, h * .75f)); drawRect(c, Offset(w * .46f, h * .15f), Size(w * .1f, h * .6f)); drawRect(c, Offset(w * .46f, h * .15f), Size(w * .32f, h * .12f)) }
        Kind.IMAGE -> { drawRoundRect(c, Offset(0f, h * .15f), Size(w, h * .7f), CornerRadius(w * .08f))
            drawPath(Path().apply { moveTo(w * .1f, h * .8f); lineTo(w * .4f, h * .45f); lineTo(w * .6f, h * .68f); lineTo(w * .75f, h * .55f); lineTo(w * .92f, h * .8f); close() }, Panel)
            drawCircle(Panel, w * .07f, Offset(w * .75f, h * .33f)) }
        Kind.OTHER -> { drawRoundRect(c, Offset(w * .15f, h * .08f), Size(w * .7f, h * .84f), CornerRadius(w * .06f))
            for (i in 0..2) drawRect(Panel, Offset(w * .27f, h * (.3f + i * .17f)), Size(w * .46f, h * .06f)) }
        null -> { drawRoundRect(c, Offset(0f, h * .3f), Size(w, h * .4f), CornerRadius(w * .08f)); drawRect(Panel, Offset(w * .1f, h * .5f), Size(w * .5f, h * .07f)); drawCircle(Panel, w * .05f, Offset(w * .85f, h * .5f)) }
    }
}

@Composable
fun Ico(n: String, sz: Dp = 24.dp, c: Color = Color.White) = Canvas(Modifier.size(sz)) {
    val w = this.size.width; val h = this.size.height; val sw = w * .09f
    fun tri(l: Boolean, x: Float, wd: Float) = drawPath(Path().apply {
        if (l) { moveTo(x + wd, h * .22f); lineTo(x, h * .5f); lineTo(x + wd, h * .78f) } else { moveTo(x, h * .22f); lineTo(x + wd, h * .5f); lineTo(x, h * .78f) }; close() }, c)
    when (n) {
        "play" -> tri(false, w * .25f, w * .55f)
        "pause" -> { drawRect(c, Offset(w * .22f, h * .2f), Size(w * .18f, h * .6f)); drawRect(c, Offset(w * .6f, h * .2f), Size(w * .18f, h * .6f)) }
        "prev" -> { drawRect(c, Offset(w * .2f, h * .22f), Size(w * .12f, h * .56f)); tri(true, w * .36f, w * .44f) }
        "next" -> { tri(false, w * .2f, w * .44f); drawRect(c, Offset(w * .68f, h * .22f), Size(w * .12f, h * .56f)) }
        "rew" -> { tri(true, w * .08f, w * .4f); tri(true, w * .5f, w * .4f) }
        "ff" -> { tri(false, w * .1f, w * .4f); tri(false, w * .52f, w * .4f) }
        "back" -> { drawLine(c, Offset(w * .15f, h * .5f), Offset(w * .85f, h * .5f), sw); drawLine(c, Offset(w * .85f, h * .5f), Offset(w * .55f, h * .2f), sw); drawLine(c, Offset(w * .85f, h * .5f), Offset(w * .55f, h * .8f), sw) }
        "home" -> { drawPath(Path().apply { moveTo(w * .1f, h * .5f); lineTo(w * .5f, h * .14f); lineTo(w * .9f, h * .5f) }, c, style = Stroke(sw))
            drawRect(c, Offset(w * .24f, h * .5f), Size(w * .52f, h * .34f), style = Stroke(sw)) }
        "shuffle" -> { drawLine(c, Offset(w * .12f, h * .3f), Offset(w * .88f, h * .7f), sw); drawLine(c, Offset(w * .12f, h * .7f), Offset(w * .88f, h * .3f), sw)
            drawCircle(c, w * .08f, Offset(w * .88f, h * .7f)); drawCircle(c, w * .08f, Offset(w * .88f, h * .3f)) }
        "repeat" -> { drawRoundRect(c, Offset(w * .12f, h * .27f), Size(w * .76f, h * .46f), CornerRadius(w * .2f), style = Stroke(sw))
            drawPath(Path().apply { moveTo(w * .62f, h * .1f); lineTo(w * .82f, h * .27f); lineTo(w * .62f, h * .44f); close() }, c) }
        "volume" -> { drawRect(c, Offset(w * .1f, h * .38f), Size(w * .2f, h * .24f)); drawPath(Path().apply { moveTo(w * .3f, h * .38f); lineTo(w * .52f, h * .2f); lineTo(w * .52f, h * .8f); lineTo(w * .3f, h * .62f); close() }, c)
            drawArc(c, -45f, 90f, false, Offset(w * .4f, h * .3f), Size(w * .4f, h * .4f), style = Stroke(sw)) }
        "mute" -> { drawRect(c, Offset(w * .1f, h * .38f), Size(w * .2f, h * .24f)); drawPath(Path().apply { moveTo(w * .3f, h * .38f); lineTo(w * .52f, h * .2f); lineTo(w * .52f, h * .8f); lineTo(w * .3f, h * .62f); close() }, c)
            drawLine(c, Offset(w * .65f, h * .36f), Offset(w * .92f, h * .64f), sw); drawLine(c, Offset(w * .92f, h * .36f), Offset(w * .65f, h * .64f), sw) }
        "eq" -> { val ys = listOf(.58f, .3f, .66f)
            for (i in 0..2) { val x = w * (.2f + .3f * i); drawLine(c, Offset(x, h * .15f), Offset(x, h * .85f), sw * .6f); drawRect(c, Offset(x - w * .1f, h * ys[i]), Size(w * .2f, h * .12f)) } }
        else -> {}
    }
}

@Composable
fun Thumb(f: File, mod: Modifier) {
    val k = f.kind()
    Box(mod.background(Bar), contentAlignment = Alignment.Center) {
        if (k == Kind.IMAGE || k == Kind.VIDEO)
            AsyncImage(model = f, imageLoader = loader, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Glyph(k, 24.dp)
        if (k == Kind.VIDEO) Box(Modifier.align(Alignment.BottomStart).padding(3.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xAA000000)).padding(2.dp)) { Ico("play", 12.dp) }
    }
}

@Composable
fun Clock() {
    var t by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { t = SimpleDateFormat("HH:mm", Locale.US).format(Date()); delay(10000) } }
    Text(t, fontSize = 16.sp, color = Color.White)
}

@Composable
fun Window(title: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().dots()) {
        Column(Modifier.fillMaxSize().padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 30.dp).clip(RoundedCornerShape(10.dp)).background(Panel).border(1.dp, Line, RoundedCornerShape(10.dp))) {
            Row(Modifier.fillMaxWidth().background(Bar).padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(Kind.DIR, 18.dp); Spacer(Modifier.width(10.dp))
                Text(title, fontFamily = HeadFont, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                Box(Modifier.size(34.dp).tv(Color.Transparent, RoundedCornerShape(6.dp)) { Pref.setSfx(!Pref.sfx) }, contentAlignment = Alignment.Center) { Ico(if (Pref.sfx) "volume" else "mute", 20.dp, if (Pref.sfx) Color.White else Dim) }
                Spacer(Modifier.width(12.dp)); Clock(); Spacer(Modifier.width(18.dp)); Text("—     ▢     ✕", color = Dim, fontSize = 14.sp)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            content()
        }
        Text("Created By Mohammad Shaerpour", color = Dim.copy(alpha = .7f), fontSize = 10.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
    }
}

@Composable
fun StatusBar(text: String) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
    Row(Modifier.fillMaxWidth().background(Bar).padding(horizontal = 16.dp, vertical = 7.dp)) {
        Text(text, color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text("OK  باز کردن        Back  بازگشت", color = Dim, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Splash() {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { Sfx.play("play"); a.animateTo(1f, tween(2000, easing = FastOutSlowInEasing)) }
    Box(Modifier.fillMaxSize().dots()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.graphicsLayer { alpha = a.value; scaleX = .8f + .2f * a.value; scaleY = .8f + .2f * a.value }
                .size(100.dp).border(3.dp, Color.White, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text("▶\uFE0E", fontSize = 46.sp, color = Color.White)
            }
            Text("Flash Explorer", fontFamily = HeadFont, fontSize = 46.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 22.dp))
            Text("مرورگر فایل فلش برای تلویزیون", color = Dim, fontSize = 17.sp, modifier = Modifier.padding(top = 4.dp, bottom = 34.dp))
            Box(Modifier.width(340.dp).height(4.dp).background(Line)) { Box(Modifier.fillMaxWidth(a.value).fillMaxHeight().background(Color.White)) }
            Text("در حال آماده‌سازی…", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
        }
        Text("Created By Mohammad Shaerpour", color = Dim, fontSize = 13.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp))
    }
}

// ---------- App ----------
@Composable
fun App() {
    val ctx = LocalContext.current
    var splash by remember { mutableStateOf(true) }
    var drv by remember { mutableStateOf(listOf<Pair<String, File>>()) }
    var root by remember { mutableStateOf<Pair<String, File>?>(null) }
    var sec by remember { mutableStateOf(Sec.ALL) }
    var dir by remember { mutableStateOf<File?>(null) }
    var viewer by remember { mutableStateOf<Pair<List<File>, Int>?>(null) }
    LaunchedEffect(Unit) { delay(2600); splash = false }
    LaunchedEffect(Unit) { while (true) { drv = withContext(Dispatchers.IO) { drives(ctx) }; delay(2500) } }
    BackHandler(viewer != null) { Sfx.play("back"); viewer = null }
    BackHandler(viewer == null && root != null) {
        Sfx.play("back")
        if (sec == Sec.ALL && dir != null && dir != root!!.second) dir = dir!!.parentFile else root = null
    }
    val v = viewer; val r = root
    when {
        splash -> Splash()
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
    LaunchedEffect(drv.size) { delay(150); try { req.requestFocus() } catch (_: Exception) {} }
    Window("این رایانه") {
        Column(Modifier.weight(1f).fillMaxWidth().padding(32.dp)) {
            Text("درایوها و دستگاه‌ها (${drv.size})", fontFamily = HeadFont, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(22.dp))
            if (drv.isEmpty()) {
                Row(Modifier.focusRequester(req).tv(Bar) { askAccess(ctx as Activity) }.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(null, 56.dp); Spacer(Modifier.width(18.dp))
                    Column {
                        Text("هیچ فلشی پیدا نشد", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("فلش را وصل کنید  •  OK برای درخواست اجازه دسترسی", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                drv.forEachIndexed { i, d ->
                    Row(Modifier.enter(i).width(400.dp).then(if (i == 0) Modifier.focusRequester(req) else Modifier).tv(Bar) { onPick(d) }.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Glyph(null, 64.dp); Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.first, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val used = 1f - d.second.freeSpace.toFloat() / d.second.totalSpace.coerceAtLeast(1)
                            Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(8.dp).background(Line)) { Box(Modifier.fillMaxWidth(used).fillMaxHeight().background(Ic)) }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${fmtSize(d.second.freeSpace)} آزاد از ${fmtSize(d.second.totalSpace)}", color = Dim, fontSize = 14.sp)
                                Text("${(used * 100).toInt()}٪ پر", color = Ic, fontSize = 12.sp, modifier = Modifier.border(1.dp, Line, RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 1.dp))
                            }
                        }
                    }
                }
            }
        }
        StatusBar("OK: باز کردن   •   Back: بازگشت")
    }
}

@Composable
fun NavItem(k: Kind?, text: String, sel: Boolean, req: FocusRequester? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().then(if (req != null) Modifier.focusRequester(req) else Modifier)
        .tv(base = if (sel) Sel else Color.Transparent, onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(k, 22.dp); Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 17.sp, maxLines = 1, modifier = Modifier.weight(1f))
        if (sel) Box(Modifier.width(3.dp).height(18.dp).background(Color.White))
    }
}

@Composable
fun Tbtn(icon: String, label: String, onClick: () -> Unit) {
    Row(Modifier.tv(Bar, RoundedCornerShape(6.dp), onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Ico(icon, 20.dp); Spacer(Modifier.width(8.dp)); Text(label, fontSize = 15.sp)
    }
}

@Composable
fun Browser(name: String, root: File, sec: Sec, dir: File, onSec: (Sec) -> Unit, onDir: (File) -> Unit,
            onHome: () -> Unit, onOpen: (List<File>, Int) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(150); try { first.requestFocus() } catch (_: Exception) {} }
    var items by remember(sec, dir) { mutableStateOf<List<File>?>(null) }
    var loading by remember(sec, dir) { mutableStateOf(false) }
    LaunchedEffect(sec, dir) {
        val target = sec.kind
        if (target == null) {
            items = withContext(Dispatchers.IO) {
                dir.listFiles()?.filter { !it.name.startsWith(".") }
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
            }
        } else {
            loading = true; items = emptyList()
            val acc = ArrayList<File>()
            try {
                withContext(Dispatchers.IO) {
                    var last = System.currentTimeMillis()
                    for (f in walkSafe(root)) {
                        ensureActive()
                        if (extKind(f.name) == target && f.isFile) { acc.add(f); if (acc.size >= 4000) break }
                        val now = System.currentTimeMillis()
                        if (now - last > 400) {
                            last = now
                            val snap = acc.sortedBy { it.name.lowercase() }
                            withContext(Dispatchers.Main) { items = snap }
                        }
                    }
                }
            } catch (e: Exception) { if (e is CancellationException) throw e }
            items = acc.sortedBy { it.name.lowercase() }; loading = false
        }
    }
    val list = items
    Window("Flash Explorer  —  $name") {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tbtn("back", "بازگشت") { if (sec == Sec.ALL && dir != root) onDir(dir.parentFile!!) else onHome() }
            Tbtn("home", "این رایانه") { onHome() }
            val path = if (sec == Sec.ALL) name + dir.relativeTo(root).path.let { if (it.isEmpty()) "" else "  ›  " + it.replace("/", "  ›  ") } else "$name  ›  ${sec.label}"
            Row(Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(Bg).border(1.dp, Line, RoundedCornerShape(4.dp)).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Glyph(Kind.DIR, 18.dp); Spacer(Modifier.width(10.dp)); Text(path, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(250.dp).fillMaxHeight().background(Bar).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("دسترسی سریع", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                Sec.values().forEach { s -> NavItem(s.kind ?: Kind.DIR, s.label, s == sec, if (s == sec) first else null) { onSec(s) } }
                Spacer(Modifier.height(14.dp))
                Text("این رایانه", color = Dim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                NavItem(null, name, false) { onHome() }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Line))
            Column(Modifier.weight(1f).fillMaxHeight().padding(12.dp)) {
                if (list == null) Text("در حال بارگذاری…", color = Dim, fontSize = 18.sp, modifier = Modifier.padding(16.dp))
                else if (list.isEmpty()) Text(if (loading) "در حال جستجوی فایل‌ها…  (روی فلش‌های بزرگ کمی طول می‌کشد)" else "این پوشه خالی است", color = Dim, fontSize = 18.sp, modifier = Modifier.padding(16.dp))
                else if (sec == Sec.VIDEO || sec == Sec.IMAGE) {
                    LazyVerticalGrid(GridCells.Adaptive(200.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(6.dp)) {
                        itemsIndexed(list) { i, f ->
                            Column(Modifier.enter(i).tv(grow = true) { onOpen(list, i) }.padding(8.dp)) {
                                Thumb(f, Modifier.fillMaxWidth().aspectRatio(1.6f).clip(RoundedCornerShape(4.dp)))
                                Text(f.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text("نام", color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 54.dp))
                        Text("تاریخ تغییر", color = Dim, fontSize = 13.sp, modifier = Modifier.width(190.dp))
                        Text("نوع", color = Dim, fontSize = 13.sp, modifier = Modifier.width(110.dp))
                        Text("اندازه", color = Dim, fontSize = 13.sp, modifier = Modifier.width(100.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                        itemsIndexed(list) { n, f ->
                            Row(Modifier.fillMaxWidth().enter(n).tv {
                                val k = f.kind()
                                if (k == Kind.DIR) onDir(f)
                                else if (k != Kind.OTHER) { val pl = list.filter { it.kind() == k }; onOpen(pl, pl.indexOf(f)) }
                            }.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                Thumb(f, Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))); Spacer(Modifier.width(14.dp))
                                Text(f.name, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(dateOf(f), color = Dim, fontSize = 14.sp, modifier = Modifier.width(190.dp))
                                Text(if (f.isDirectory) "پوشه" else f.extension.uppercase() + " فایل", color = Dim, fontSize = 14.sp, maxLines = 1, modifier = Modifier.width(110.dp))
                                Text(if (f.isDirectory) "" else fmtSize(f.length()), color = Dim, fontSize = 14.sp, modifier = Modifier.width(100.dp))
                            }
                        }
                    }
                }
            }
        }
        StatusBar("${list?.size ?: "…"} مورد" + if (loading) "   •   در حال جستجو…" else "")
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
fun Ctl(icon: String, primary: Boolean = false, active: Boolean = true, onClick: () -> Unit) {
    val sh = RoundedCornerShape(8.dp)
    Box(Modifier.size(if (primary) 68.dp else 52.dp).tv(Bar, sh, onClick = onClick)
        .border(if (primary) 2.dp else 1.dp, if (primary) Color.White else Line, sh), contentAlignment = Alignment.Center) {
        Ico(icon, if (primary) 34.dp else 26.dp, if (active) Color.White else Dim)
    }
}

@Composable
fun Eq(on: Boolean) {
    val t = rememberInfiniteTransition(label = "eq")
    Row(Modifier.height(44.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 8) {
            val h by t.animateFloat(0.2f, 1f, infiniteRepeatable(tween(400 + i * 80, easing = LinearEasing), RepeatMode.Reverse), label = "b$i")
            Box(Modifier.width(7.dp).fillMaxHeight(if (on) h else 0.12f).background(Color.White.copy(alpha = .85f)))
        }
    }
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
    var shuf by remember { mutableStateOf(false) }
    var rep by remember { mutableStateOf(false) }
    val p = remember { ExoPlayer.Builder(ctx).build().apply {
        setMediaItems(list.map { MediaItem.fromUri(Uri.fromFile(it)) }); seekTo(start, 0); prepare(); playWhenReady = true
        addListener(object : Player.Listener {
            override fun onMediaItemTransition(m: MediaItem?, r: Int) { idx = currentMediaItemIndex }
            override fun onIsPlayingChanged(b: Boolean) { playing = b }
        }) } }
    var showEq by remember { mutableStateOf(false) }
    var eqOn by remember { mutableStateOf(Pref.eq) }
    val eq = remember { try { Equalizer(0, p.audioSessionId).apply { enabled = Pref.eq } } catch (e: Exception) { null } }
    DisposableEffect(Unit) { onDispose { try { eq?.release() } catch (_: Exception) {}; p.release() } }
    LaunchedEffect(playing) { while (playing) { rot = (rot + 0.5f) % 360f; delay(16) } }
    LaunchedEffect(Unit) { while (true) { pos = p.currentPosition; dur = p.duration.coerceAtLeast(0); delay(400) } }
    val ls = rememberLazyListState()
    LaunchedEffect(idx) { ls.animateScrollToItem(idx.coerceIn(0, list.lastIndex)) }
    val f = list[idx.coerceIn(0, list.lastIndex)]
    Window("پخش‌کننده موسیقی") {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(36.dp), verticalArrangement = Arrangement.Center) {
                Text("در حال پخش   •   ${idx + 1} از ${list.size}", color = Dim, fontSize = 15.sp)
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Canvas(Modifier.size(250.dp).graphicsLayer { rotationZ = rot }) {
                            val r = this.size.minDimension / 2
                            drawCircle(Color(0xFF0E0E0E), r)
                            for (i in 1..6) drawCircle(Color(0xFF2E2E2E), r * (0.36f + 0.09f * i), style = Stroke(2f))
                            drawArc(Color(0x44FFFFFF), -35f, 45f, false, Offset(r * .08f, r * .08f), Size(r * 1.84f, r * 1.84f), style = Stroke(7f))
                            drawCircle(Color(0xFF8C8C8C), r * .3f); drawCircle(Color(0xFFBDBDBD), r * .3f, style = Stroke(3f)); drawCircle(Bg, r * .05f)
                        }
                        Eq(playing)
                    }
                    Spacer(Modifier.width(36.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.nameWithoutExtension, fontFamily = HeadFont, fontSize = 36.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 6.dp, bottom = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(f.parentFile?.name ?: "", color = Dim, fontSize = 16.sp, maxLines = 1)
                            Text("${f.extension.uppercase()}  •  ${fmtSize(f.length())}", color = Ic, fontSize = 12.sp, modifier = Modifier.border(1.dp, Line, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).background(Line)) { Box(Modifier.fillMaxWidth(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f).fillMaxHeight().background(Color.White)) }
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(mmss(pos), color = Dim, fontSize = 13.sp); Text(mmss(dur), color = Dim, fontSize = 13.sp)
                        }
                        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Ctl("prev") { p.seekToPreviousMediaItem() }
                            Ctl("rew") { p.seekTo((p.currentPosition - 10000).coerceAtLeast(0)) }
                            Ctl(if (playing) "pause" else "play", true) { Sfx.play("play"); if (p.isPlaying) p.pause() else p.play() }
                            Ctl("ff") { p.seekTo(p.currentPosition + 10000) }
                            Ctl("next") { p.seekToNextMediaItem() }
                        }
                        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Ctl("shuffle", false, shuf) { shuf = !shuf; p.shuffleModeEnabled = shuf }
                            Ctl("repeat", false, rep) { rep = !rep; p.repeatMode = if (rep) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF }
                            Ctl("eq", false, showEq) { showEq = !showEq }
                        }
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Line))
            if (showEq) EqPanel(eq, eqOn) { eqOn = !eqOn; Pref.setEq(eqOn); try { eq?.enabled = eqOn } catch (_: Exception) {} }
            else Column(Modifier.width(360.dp).fillMaxHeight().background(Bar)) {
                Text("لیست پخش (${list.size})", fontFamily = HeadFont, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
                LazyColumn(state = ls, contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(list) { i, t ->
                        Row(Modifier.fillMaxWidth().tv(base = if (i == idx) Sel else Color.Transparent) { p.seekTo(i, 0); p.play() }.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (i == idx) "▶\uFE0E" else "${i + 1}", color = if (i == idx) Color.White else Dim, fontSize = 13.sp, modifier = Modifier.width(32.dp))
                            Text(t.nameWithoutExtension, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (i == idx) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
        StatusBar(if (playing) "در حال پخش" else "مکث")
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
            Key.DirectionLeft, Key.MediaNext -> { Sfx.play("move"); i = (i + 1) % list.size; true }
            Key.DirectionRight, Key.MediaPrevious -> { Sfx.play("move"); i = (i - 1 + list.size) % list.size; true }
            Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> { Sfx.play("play"); auto = !auto; true }
            else -> false
        }
    }) {
        AsyncImage(model = list[i], imageLoader = loader, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000)).padding(horizontal = 28.dp, vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(list[i].name, fontSize = 16.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text("${if (auto) "اسلایدشو روشن" else "OK: اسلایدشو"}     ${i + 1} / ${list.size}", color = Dim, fontSize = 15.sp)
        }
    }
}

@Composable
fun EqPanel(eq: Equalizer?, on: Boolean, onToggle: () -> Unit) {
    Column(Modifier.width(360.dp).fillMaxHeight().background(Bar).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("اکولایزر", fontFamily = HeadFont, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (on) "روشن" else "خاموش", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                modifier = Modifier.tv(Bar, RoundedCornerShape(6.dp), onClick = onToggle).border(1.dp, if (on) Color.White else Line, RoundedCornerShape(6.dp)).padding(horizontal = 16.dp, vertical = 7.dp))
        }
        if (eq == null) { Text("اکولایزر روی این دستگاه پشتیبانی نمی‌شود", color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp)); return@Column }
        val n = eq.numberOfBands.toInt(); val lo = eq.bandLevelRange[0].toInt(); val hi = eq.bandLevelRange[1].toInt()
        val lv = remember { mutableStateListOf<Int>().apply { for (i in 0 until n) add(eq.getBandLevel(i.toShort()).toInt()) } }
        fun set(i: Int, v: Int) { val x = v.coerceIn(lo, hi); lv[i] = x; try { eq.setBandLevel(i.toShort(), x.toShort()) } catch (_: Exception) {} }
        Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf<Pair<String, (Float) -> Float>>("تخت" to { _ -> 0f }, "بیس" to { f -> 0.8f * (1 - f) }, "ووکال" to { f -> 0.7f * sin(PI * f).toFloat() }, "تربل" to { f -> 0.8f * f }).forEach { (name, fn) ->
                Text(name, fontSize = 14.sp, modifier = Modifier.tv(Bar, RoundedCornerShape(6.dp)) { for (i in 0 until n) set(i, (hi * fn(if (n > 1) i / (n - 1f) else 0f)).toInt()) }
                    .border(1.dp, Line, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0 until n) {
                    val hz = eq.getCenterFreq(i.toShort()) / 1000
                    Row(Modifier.fillMaxWidth().alpha(if (on) 1f else .5f).onKeyEvent {
                        if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                            Key.DirectionLeft -> { set(i, lv[i] - 100); true }
                            Key.DirectionRight -> { set(i, lv[i] + 100); true }
                            else -> false
                        }
                    }.tv(Bar) { }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hz >= 1000) "%.1f kHz".format(hz / 1000f) else "$hz Hz", color = Dim, fontSize = 12.sp, modifier = Modifier.width(62.dp))
                        Box(Modifier.weight(1f).height(6.dp).background(Line)) { Box(Modifier.fillMaxWidth(((lv[i] - lo).toFloat() / (hi - lo).coerceAtLeast(1)).coerceIn(0f, 1f)).fillMaxHeight().background(Color.White)) }
                        Text("%+.0f dB".format(lv[i] / 100f), fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
                    }
                }
            }
        }
        Text("چپ / راست: کم و زیاد کردن هر باند", color = Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
    }
}
