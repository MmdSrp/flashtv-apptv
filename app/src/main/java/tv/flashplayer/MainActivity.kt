package tv.flashplayer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaMetadataRetriever
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
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
import kotlin.math.exp

// ---------- Theme: flat dark gray + white ----------
val Bg = Color(0xFF191919); val Panel = Color(0xFF242424); val Bar = Color(0xFF2D2D2D)
val Line = Color(0xFF3D3D3D); val Sel = Color(0xFF3F3F3F); val Dim = Color(0xFF9A9A9A); val Ic = Color(0xFFDADADA)
var HeadFont: FontFamily = FontFamily.Default
lateinit var loader: ImageLoader

object Pref {
    private var sp: SharedPreferences? = null
    private val sfxState = mutableStateOf(true)
    val sfx: Boolean get() = sfxState.value
    fun init(c: Context) {
        sp = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        sfxState.value = sp?.getBoolean("sfx", true) ?: true
    }
    fun setSfx(b: Boolean) {
        sfxState.value = b
        sp?.edit()?.putBoolean("sfx", b)?.apply()
    }
}

// ---------- Sound effects (generated, no files) ----------
object Sfx {
    private fun tone(f: Float, ms: Int, vol: Float): ShortArray {
        val n = 44100 * ms / 1000
        return ShortArray(n) { i -> (sin(2 * PI * f * i / 44100) * Short.MAX_VALUE * vol * (1 - i.toFloat() / n)).toInt().toShort() }
    }
    private fun sweep(f0: Float, f1: Float, ms: Int, vol: Float): ShortArray {
        val n = 44100 * ms / 1000; var ph = 0.0
        return ShortArray(n) { i -> ph += 2 * PI * (f0 + (f1 - f0) * i / n) / 44100; (sin(ph) * Short.MAX_VALUE * vol * sin(PI * i / n)).toInt().toShort() }
    }
    private fun chime(): ShortArray {
        val sr = 44100; val out = FloatArray(sr * 1200 / 1000)
        listOf(523.25f, 659.25f, 783.99f, 1046.5f).forEachIndexed { k, f ->
            val st = sr * k * 180 / 1000
            for (i in 0 until sr * 750 / 1000) {
                val idx = st + i; if (idx >= out.size) break
                val t = i / sr.toFloat(); val env = exp(-5.0f * t) * minOf(1f, i / 250f)
                out[idx] += (sin(2 * PI * f * t) + 0.35 * sin(4 * PI * f * t)).toFloat() * env * 0.22f
            }
        }
        return ShortArray(out.size) { (out[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
    }
    private val snd: Map<String, ShortArray> by lazy {
        mapOf("move" to tone(1100f, 22, .12f), "ok" to tone(1400f, 70, .35f), "back" to tone(700f, 90, .35f), "whoosh" to chime(),
            "play" to tone(900f, 60, .35f) + tone(1500f, 100, .35f))
    }
    fun play(k: String) {
        if (!Pref.sfx) return
        val d = snd[k] ?: return
        Thread {
            try {
                val t = AudioTrack(AudioManager.STREAM_MUSIC, 44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, d.size * 2, AudioTrack.MODE_STATIC)
                t.write(d, 0, d.size); t.play(); Thread.sleep(d.size * 1000L / 44100 + 80); t.release()
            } catch (_: Exception) {}
        }.start()
    }
}

// ---------- Updater (GitHub Releases) ----------
object Upd {
    data class Info(val code: Int, val name: String, val url: String)
    var lastErr = ""
    private val repo: String get() = BuildConfig.GH_REPO.ifBlank { "MmdSrp/flashtv-apptv" }
    private fun fromRedirect(): Info {
        val c = URL("https://github.com/$repo/releases/latest").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false; c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "FlashTV")
        val loc = c.getHeaderField("Location") ?: throw Exception("HTTP ${c.responseCode}")
        val tag = loc.substringAfterLast("/tag/", "")
        val code = tag.filter { it.isDigit() }.toIntOrNull() ?: throw Exception("tag not found")
        return Info(code, tag, "https://github.com/$repo/releases/download/$tag/FlashTV.apk")
    }
    private fun fromApi(): Info {
        val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json"); c.setRequestProperty("User-Agent", "FlashTV")
        val j = JSONObject(c.inputStream.bufferedReader().readText())
        val tag = j.getString("tag_name"); val code = tag.filter { it.isDigit() }.toIntOrNull() ?: throw Exception("tag not found")
        val a = j.getJSONArray("assets"); var url = ""
        for (i in 0 until a.length()) { val o = a.getJSONObject(i); if (o.getString("name").endsWith(".apk")) url = o.getString("browser_download_url") }
        if (url.isEmpty()) throw Exception("apk not found")
        return Info(code, tag, url)
    }
    fun latest(): Info? {
        lastErr = ""
        try { return fromRedirect() } catch (e: Exception) { lastErr = e.message ?: e.javaClass.simpleName }
        try { return fromApi() } catch (e: Exception) { lastErr = e.message ?: e.javaClass.simpleName }
        return null
    }
    fun download(ctx: Context, url: String, prog: (Int) -> Unit): File? {
        return try {
            val out = File(File(ctx.cacheDir, "updates").apply { mkdirs() }, "FlashTV.apk")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 20000; c.setRequestProperty("User-Agent", "FlashTV")
            val total = c.contentLengthLong
            c.inputStream.use { inp -> out.outputStream().use { o ->
                val buf = ByteArray(65536); var done = 0L; var last = -1
                while (true) {
                    val n = inp.read(buf); if (n <= 0) break
                    o.write(buf, 0, n); done += n
                    if (total > 0) { val pc = (done * 100 / total).toInt(); if (pc != last) { last = pc; prog(pc) } }
                }
            } }
            out
        } catch (e: Exception) { lastErr = e.message ?: e.javaClass.simpleName; null }
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
        "image" -> { drawRoundRect(c, Offset(w * .12f, h * .18f), Size(w * .76f, h * .64f), CornerRadius(w * .08f), style = Stroke(sw))
            drawPath(Path().apply { moveTo(w * .18f, h * .72f); lineTo(w * .42f, h * .46f); lineTo(w * .58f, h * .62f); lineTo(w * .7f, h * .5f); lineTo(w * .82f, h * .72f) }, c, style = Stroke(sw))
            drawCircle(c, w * .07f, Offset(w * .68f, h * .34f)) }
        "download" -> { drawLine(c, Offset(w * .5f, h * .12f), Offset(w * .5f, h * .66f), sw); drawLine(c, Offset(w * .5f, h * .66f), Offset(w * .3f, h * .46f), sw)
            drawLine(c, Offset(w * .5f, h * .66f), Offset(w * .7f, h * .46f), sw); drawLine(c, Offset(w * .2f, h * .86f), Offset(w * .8f, h * .86f), sw) }
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
fun Splash(onDone: () -> Unit) {
    val prog = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    val tr = rememberInfiniteTransition(label = "ring")
    val ring by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = LinearEasing)), label = "r")
    LaunchedEffect(Unit) {
        Sfx.play("play"); prog.animateTo(1f, tween(3200, easing = FastOutSlowInEasing))
        delay(1800); Sfx.play("whoosh"); exit.animateTo(1f, tween(1500, easing = FastOutSlowInEasing)); onDone()
    }
    fun rv(a: Float, b: Float) = ((prog.value - a) / (b - a)).coerceIn(0f, 1f)
    val st = if (prog.value < .35f) "در حال آماده‌سازی…" else if (prog.value < .75f) "بارگذاری رابط کاربری…" else "آماده است"
    Box(Modifier.fillMaxSize().graphicsLayer { translationX = -exit.value * size.width * .6f; alpha = 1f - ((exit.value - .45f) / .55f).coerceIn(0f, 1f) }.dots()) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(210.dp)) {
                    val r = this.size.minDimension / 2
                    drawCircle(Color.White.copy(alpha = (1f - ring) * .25f), r * (0.45f + 0.55f * ring), style = Stroke(2f))
                    val r2 = (ring + .5f) % 1f
                    drawCircle(Color.White.copy(alpha = (1f - r2) * .15f), r * (0.45f + 0.55f * r2), style = Stroke(2f))
                }
                Box(Modifier.graphicsLayer { alpha = (prog.value * 4f).coerceAtMost(1f); scaleX = .8f + .2f * prog.value; scaleY = .8f + .2f * prog.value }
                    .size(100.dp).border(3.dp, Color.White, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Text("▶\uFE0E", fontSize = 46.sp, color = Color.White)
                }
            }
            Text("Flash Explorer", fontFamily = HeadFont, fontSize = 58.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp).graphicsLayer { alpha = rv(.05f, .25f); translationY = (1f - rv(.05f, .25f)) * 30f })
            Text("توسط تیم شاعرپور", fontFamily = HeadFont, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Ic,
                modifier = Modifier.graphicsLayer { alpha = rv(.2f, .4f); translationY = (1f - rv(.2f, .4f)) * 30f })
            Text("مرورگر فایل فلش برای تلویزیون", color = Dim, fontSize = 17.sp,
                modifier = Modifier.padding(top = 6.dp).graphicsLayer { alpha = rv(.35f, .5f); translationY = (1f - rv(.35f, .5f)) * 20f })
            Row(Modifier.padding(top = 22.dp, bottom = 28.dp).graphicsLayer { alpha = rv(.45f, .65f); translationY = (1f - rv(.45f, .65f)) * 20f },
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Kind.VIDEO to "ویدیو", Kind.AUDIO to "موسیقی", Kind.IMAGE to "عکس", Kind.DIR to "فایل‌ها").forEach { (k, t) ->
                    Row(Modifier.border(1.dp, Line, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph(k, 18.dp); Spacer(Modifier.width(8.dp)); Text(t, fontSize = 14.sp)
                    }
                }
            }
            Box(Modifier.width(400.dp).height(4.dp).background(Line)) { Box(Modifier.fillMaxWidth(prog.value).fillMaxHeight().background(Color.White)) }
            Text(st, color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
            Text("نسخه ${BuildConfig.VERSION_NAME}", color = Dim.copy(alpha = .7f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
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
    var upd by remember { mutableStateOf<Upd.Info?>(null) }
    var updMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun check(manual: Boolean) { scope.launch {
        updMsg = if (manual) "در حال بررسی…" else ""
        val i = withContext(Dispatchers.IO) { Upd.latest() }
        if (i == null) updMsg = if (manual) "بررسی ناموفق بود: ${Upd.lastErr}" else ""
        else if (i.code > BuildConfig.VERSION_CODE) { upd = i; updMsg = "نسخه جدید ${i.name} آماده است" }
        else { upd = null; updMsg = if (manual) "برنامه به‌روز است" else "" }
    } }
    fun install(i: Upd.Info) { scope.launch {
        val act = ctx as Activity
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            updMsg = "اجازه «نصب برنامه» را فعال کنید و دوباره بزنید"
            try { act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}"))) } catch (_: Exception) {}
            return@launch
        }
        updMsg = "در حال دانلود…"
        val f = withContext(Dispatchers.IO) { Upd.download(ctx, i.url) { pc -> updMsg = "در حال دانلود ${pc}٪" } }
        if (f == null) { updMsg = "دانلود ناموفق بود: ${Upd.lastErr}"; return@launch }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fp", f)
        act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    } }
    LaunchedEffect(Unit) { delay(3500); check(false) }
    LaunchedEffect(Unit) { while (true) { drv = withContext(Dispatchers.IO) { drives(ctx) }; delay(2500) } }
    BackHandler(viewer != null) { Sfx.play("back"); viewer = null }
    BackHandler(viewer == null && root != null) {
        Sfx.play("back")
        if (sec == Sec.ALL && dir != null && dir != root!!.second) dir = dir!!.parentFile else root = null
    }
    val v = viewer; val r = root
    Box(Modifier.fillMaxSize()) {
    when {
        v != null -> when (v.first[v.second].kind()) {
            Kind.VIDEO -> VideoScreen(v.first, v.second)
            Kind.AUDIO -> AudioScreen(v.first, v.second)
            else -> PhotoScreen(v.first, v.second)
        }
        r == null -> Home(drv, upd, updMsg, { check(true) }, { upd?.let { install(it) } }) { root = it; dir = it.second; sec = Sec.ALL }
        else -> Browser(r.first, r.second, sec, dir ?: r.second,
            { sec = it; dir = r.second }, { dir = it }, { root = null }, { l, i -> viewer = l to i })
    }
    if (splash) Splash { splash = false }
    }
}

@Composable
fun Home(drv: List<Pair<String, File>>, upd: Upd.Info?, updMsg: String, onCheck: () -> Unit, onInstall: () -> Unit, onPick: (Pair<String, File>) -> Unit) {
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
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (upd != null) Tbtn("download", "بروزرسانی به ${upd.name}", onInstall)
                Tbtn("download", "بررسی بروزرسانی", onCheck)
                if (updMsg.isNotEmpty()) Text(updMsg, color = Dim, fontSize = 14.sp)
            }
        }
        StatusBar("نسخه ${BuildConfig.VERSION_NAME}")
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
    DisposableEffect(Unit) { onDispose { p.release() } }
    LaunchedEffect(playing) { while (playing) { rot = (rot + 0.5f) % 360f; delay(16) } }
    LaunchedEffect(Unit) { while (true) { pos = p.currentPosition; dur = p.duration.coerceAtLeast(0); delay(400) } }
    val ls = rememberLazyListState()
    LaunchedEffect(idx) { ls.animateScrollToItem(idx.coerceIn(0, list.lastIndex)) }
    val f = list[idx.coerceIn(0, list.lastIndex)]
    var showImg by remember { mutableStateOf(false) }
    var cover by remember(idx) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(idx) {
        cover = withContext(Dispatchers.IO) {
            try {
                val r = MediaMetadataRetriever(); r.setDataSource(f.absolutePath); val b = r.embeddedPicture; r.release()
                if (b != null) BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() else null
            } catch (e: Exception) { null }
        }
    }
    Window("پخش‌کننده موسیقی") {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(36.dp), verticalArrangement = Arrangement.Center) {
                Text("در حال پخش   •   ${idx + 1} از ${list.size}", color = Dim, fontSize = 15.sp)
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (showImg) { val cv = cover
                        if (cv != null) Image(bitmap = cv, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(250.dp).clip(RoundedCornerShape(8.dp)))
                        else Canvas(Modifier.size(250.dp).graphicsLayer { rotationZ = rot }) {
                            val r = this.size.minDimension / 2
                            drawCircle(Color(0xFF0E0E0E), r)
                            for (i in 1..6) drawCircle(Color(0xFF2E2E2E), r * (0.36f + 0.09f * i), style = Stroke(2f))
                            drawArc(Color(0x44FFFFFF), -35f, 45f, false, Offset(r * .08f, r * .08f), Size(r * 1.84f, r * 1.84f), style = Stroke(7f))
                            drawCircle(Color(0xFF8C8C8C), r * .3f); drawCircle(Color(0xFFBDBDBD), r * .3f, style = Stroke(3f)); drawCircle(Bg, r * .05f)
                        } }
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
                            Ctl("image", false, showImg) { showImg = !showImg }
                        }
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Line))
            Column(Modifier.width(360.dp).fillMaxHeight().background(Bar)) {
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

