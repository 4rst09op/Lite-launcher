package com.ryan.launcher

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.ResolveInfo
import android.graphics.*
import android.graphics.drawable.*
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.view.animation.OvershootInterpolator
import android.widget.*
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val MATCH = -1
private const val WRAP = -2
private const val ACT = "com.ryan.launcher.INSTALL_STATUS"
private const val APK = "https://github.com/4rst09op/Lite-launcher/releases/download/latest/app-debug.apk"
private const val REL = "https://api.github.com/repos/4rst09op/Lite-launcher/releases/tags/latest"
private const val DEF = "com.google.android.youtube.tv,com.netflix.ninja"
private val FONTS = listOf(
    "Poppins Light" to "pop", "Poppins Bold" to "popb", "Bebas Neue" to "bebas", "Audiowide" to "audio",
    "Pacifico" to "pacifico", "Thin" to "thin", "Light" to "light", "Regular" to "sys",
    "Condensed" to "cond", "Serif" to "serif"
)

/** Clock text that can render as translucent "glass" (outline + soft gradient fill). */
class GlassClock(ctx: Context) : TextClock(ctx) {
    var glass = false
    override fun onDraw(c: Canvas) {
        if (!glass) { super.onDraw(c); return }
        val pt = paint
        val base = currentTextColor and 0xFFFFFF
        pt.style = Paint.Style.STROKE; pt.strokeWidth = max(1.5f, textSize / 36f); pt.shader = null
        super.onDraw(c)
        pt.style = Paint.Style.FILL
        pt.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), base or (0x99000000).toInt(), base or 0x33000000, Shader.TileMode.CLAMP)
        super.onDraw(c)
        pt.shader = null
    }
}

/** Horizontal rail that never auto-scrolls on focus (we scroll smoothly ourselves). */
class Rail(c: Context) : HorizontalScrollView(c) {
    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean) = false
}

class AppItem(val label: String, val pkg: String, val intent: Intent, val icon: Drawable, val banner: Drawable?)

private val STR = mapOf("vol" to "40", "tile" to "170", "csize" to "64", "dsize" to "200", "tradius" to "18", "gop" to "18",
    "accent" to "#7FB2FF", "cfont" to "pop", "tfont" to "pop", "cclr" to "#FFFFFF", "tclr" to "#FFFFFF", "wclr" to "#FFFFFF",
    "cpos" to "right", "cstyle" to "normal", "wfit" to "fill", "layout" to "row", "tstyle" to "icon",
    "wlist" to "", "wcity" to "", "ntext" to "", "cname" to "", "cdate" to "", "wtz" to "")
private val TXT = setOf("wlist", "wcity", "ntext", "cname", "cdate", "wtz")
private val BOOL = listOf("sound", "music", "showdate", "showlabel", "h24", "glass", "auto")

class MainActivity : Activity() {
    private lateinit var p: SharedPreferences
    private lateinit var rootV: FrameLayout
    private lateinit var dock: LinearLayout
    private var rail: Rail? = null
    private lateinit var img: ImageView
    private lateinit var vid: VideoView
    private lateinit var info: TextView
    private lateinit var clockBox: LinearLayout
    private lateinit var tTime: GlassClock
    private lateinit var tDate: TextClock
    private lateinit var cut: ImageView
    private lateinit var topScrim: View
    private lateinit var widgetBox: LinearLayout
    private lateinit var selName: TextView
    private val tiles = ArrayList<View>()
    private var cutKey = ""
    private var autoAcc = 0xFF7FB2FF.toInt()
    private var autoClk = Color.WHITE
    private var wxText = ""
    private var wxFor = ""
    private var lastWx = 0L
    private val bg = Executors.newSingleThreadExecutor()
    private val net = Executors.newCachedThreadPool()
    private val faces = HashMap<String, Typeface>()
    private var all = listOf<AppItem>()
    private var sig = ""
    private var wallKey = ""
    private var live = false
    private var server: ServerSocket? = null
    private var music: MediaPlayer? = null
    private var musicPos = 0
    private var tick: AudioTrack? = null
    private var accent = 0xFF7FB2FF.toInt()
    private var upd = 0
    private var lastChk = 0L
    private val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
            if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                val x = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                x?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); if (x != null) startActivity(x)
            } else if (st != PackageInstaller.STATUS_SUCCESS) {
                Toast.makeText(c, "Update failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun s(k: String, d: String) = p.getString(k, d) ?: d
    private fun on(k: String) = s(k, "1") == "1"
    private fun file(n: String) = File(filesDir, n)
    private fun col(k: String, d: String) = try { Color.parseColor(s(k, d)) } catch (_: Exception) { Color.WHITE }
    private fun flag(k: String) = s(k, if (k == "h24" || k == "glass" || k == "auto") "0" else "1") == "1"
    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt())
    private fun clockCol() = if (flag("auto")) autoClk else col("cclr", "#FFFFFF")
    private fun textCol() = if (flag("auto")) mix(autoAcc, Color.WHITE, 0.85f) else col("tclr", "#FFFFFF")
    private fun accentCol() = if (flag("auto")) autoAcc else col("accent", "#7FB2FF")
    private fun shadow(t: TextView) = t.setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())

    private fun face(id: String): Typeface = faces.getOrPut(id) {
        try {
            when (id) {
                "pop" -> Typeface.createFromAsset(assets, "fonts/Poppins-Light.ttf")
                "popb" -> Typeface.createFromAsset(assets, "fonts/Poppins-SemiBold.ttf")
                "bebas" -> Typeface.createFromAsset(assets, "fonts/BebasNeue-Regular.ttf")
                "audio" -> Typeface.createFromAsset(assets, "fonts/Audiowide-Regular.ttf")
                "pacifico" -> Typeface.createFromAsset(assets, "fonts/Pacifico-Regular.ttf")
                "thin" -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
                "light" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
                "cond" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                "serif" -> Typeface.SERIF
                else -> Typeface.DEFAULT
            }
        } catch (_: Exception) { Typeface.DEFAULT }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("p", 0)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { file("crash.txt").writeText(android.util.Log.getStackTraceString(e).take(6000)) } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
        rootV = FrameLayout(this)
        rootV.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0E1630.toInt(), 0xFF05070D.toInt()))
        img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        vid = VideoView(this).apply { visibility = View.GONE }
        rootV.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        rootV.addView(vid, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        rootV.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0))
        }, FrameLayout.LayoutParams(MATCH, dp(360), Gravity.BOTTOM))
        topScrim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x88000000.toInt(), 0))
        }
        rootV.addView(topScrim, FrameLayout.LayoutParams(MATCH, dp(240), Gravity.TOP))

        tTime = GlassClock(this)
        tDate = TextClock(this).apply { format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"; textSize = 17f }
        clockBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(32), dp(56), 0)
            addView(tTime); addView(tDate)
        }
        rootV.addView(clockBox, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))
        cut = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        rootV.addView(cut, FrameLayout.LayoutParams(MATCH, MATCH))
        widgetBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rootV.addView(widgetBox, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))

        selName = TextView(this).apply { gravity = Gravity.CENTER; textSize = 22f; maxLines = 1 }
        dock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        rootV.addView(dock, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))

        info = TextView(this).apply {
            visibility = View.GONE; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(0xDD000000.toInt()); setPadding(dp(40), dp(30), dp(40), dp(30))
        }
        rootV.addView(info, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        setContentView(rootV)
        applyStyle(); buildWidgets()
        makeTick()
        startServer()
        val flt = IntentFilter(ACT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, flt, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(rcv, flt)
    }

    override fun onResume() {
        super.onResume()
        live = true
        rootV.alpha = 0f; rootV.animate().alpha(1f).setDuration(300).start()
        applyWall(); applyCut()
        if (vid.visibility == View.VISIBLE) vid.start()
        applyMusic(); loadApps(); checkUpdate(); refreshWeather()
    }

    override fun onPause() {
        super.onPause()
        live = false
        musicPos = try { music?.currentPosition ?: 0 } catch (_: Exception) { 0 }
        try { music?.release() } catch (_: Exception) {}
        music = null
        if (vid.visibility == View.VISIBLE) vid.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { server?.close() } catch (_: Exception) {}
        try { unregisterReceiver(rcv) } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        info.visibility = View.GONE
        tiles.firstOrNull()?.requestFocus()
    }

    @Deprecated("Launcher must not close on Back")
    override fun onBackPressed() { info.visibility = View.GONE }

    // ---------- clock / text style ----------
    private fun applyStyle() {
        val depth = s("cstyle", "normal") == "depth"
        tTime.typeface = face(s("cfont", if (depth) "popb" else "pop")); tTime.setTextColor(clockCol())
        tTime.glass = flag("glass")
        tTime.textSize = (if (depth) s("dsize", "200") else s("csize", "64")).toFloatOrNull() ?: 64f
        tTime.format12Hour = if (flag("h24")) "HH:mm" else "h:mm"; tTime.format24Hour = "HH:mm"
        tDate.typeface = face(s("tfont", "pop")); tDate.setTextColor(textCol())
        tDate.visibility = if (flag("showdate")) View.VISIBLE else View.GONE
        if (flag("glass")) tTime.setShadowLayer(0f, 0f, 0f, 0) else shadow(tTime)
        shadow(tDate)
        topScrim.visibility = if (depth) View.GONE else View.VISIBLE
        val g = if (depth) Gravity.CENTER_HORIZONTAL else when (s("cpos", "right")) {
            "left" -> Gravity.START; "center" -> Gravity.CENTER_HORIZONTAL; else -> Gravity.END
        }
        clockBox.gravity = g
        clockBox.layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or g).apply { if (depth) topMargin = dp(70) }
        tTime.invalidate()
    }

    // ---------- sound ----------
    private fun makeTick() {
        try {
            val n = 2400
            val d = ShortArray(n) { i -> (sin(2 * PI * i * 440 / 22050.0) * exp(-i / 500.0) * 3000).toInt().toShort() }
            val t = AudioTrack(AudioManager.STREAM_MUSIC, 22050, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, n * 2, AudioTrack.MODE_STATIC)
            t.write(d, 0, n); tick = t
        } catch (_: Exception) {}
    }

    private fun ding() {
        if (!on("sound") || !live) return
        try { tick?.let { it.stop(); it.setPlaybackHeadPosition(0); it.play() } } catch (_: Exception) {}
    }

    private fun setVol() {
        val v = (s("vol", "40").toIntOrNull() ?: 40) / 100f
        try { music?.setVolume(v, v) } catch (_: Exception) {}
    }

    private fun applyMusic() {
        try { music?.release() } catch (_: Exception) {}
        music = null
        val f = file("music")
        if (!live || !on("music") || !f.exists()) return
        try {
            val mp = MediaPlayer()
            music = mp
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            mp.setDataSource(f.path); mp.isLooping = true; setVol()
            mp.setOnPreparedListener { if (music === it) { it.seekTo(musicPos); it.start() } }
            mp.setOnErrorListener { m, _, _ ->
                try { m.release() } catch (_: Exception) {}
                if (music === m) music = null
                true
            }
            mp.prepareAsync()
        } catch (_: Exception) { music = null }
    }

    // ---------- wallpaper ----------
    private fun decode(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sm = 1
        while (o.outWidth / (sm * 2) >= 1920 && o.outHeight / (sm * 2) >= 1080) sm *= 2
        o.inJustDecodeBounds = false; o.inSampleSize = sm
        var bmp: Bitmap? = null
        try { bmp = BitmapFactory.decodeFile(f.path, o) } catch (_: OutOfMemoryError) {
            o.inSampleSize = sm * 2
            try { bmp = BitmapFactory.decodeFile(f.path, o) } catch (_: OutOfMemoryError) {}
        }
        if (bmp == null) return null
        val rot = when (try { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) } catch (_: Exception) { 1 }) {
            6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
        }
        return if (rot == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
    }

    private fun applyWall() {
        val f = file("wall")
        val key = f.lastModified().toString() + s("wt", "image") + s("wfit", "fill") + f.length()
        if (key == wallKey) return
        wallKey = key
        vid.stopPlayback()
        autoAcc = 0xFF7FB2FF.toInt(); autoClk = Color.WHITE
        if (!f.exists()) { img.setImageDrawable(null); vid.visibility = View.GONE; applyStyle(); return }
        if (s("wt", "image") == "video") {
            img.setImageDrawable(null); vid.visibility = View.VISIBLE
            vid.setOnPreparedListener { mp ->
                mp.isLooping = true; mp.setVolume(0f, 0f)
                val vw = mp.videoWidth; val vh = mp.videoHeight
                if (vw > 0 && vh > 0 && rootV.width > 0) {
                    val rw = rootV.width.toFloat(); val rh = rootV.height.toFloat()
                    val k = when (s("wfit", "fill")) { "fill" -> max(rw / vw, rh / vh); "fit" -> min(rw / vw, rh / vh); else -> -1f }
                    vid.layoutParams = if (k > 0) FrameLayout.LayoutParams((vw * k).toInt(), (vh * k).toInt(), Gravity.CENTER)
                    else FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER)
                }
            }
            vid.setVideoPath(f.path); vid.start()
        } else {
            vid.visibility = View.GONE
            img.scaleType = when (s("wfit", "fill")) {
                "fit" -> ImageView.ScaleType.FIT_CENTER; "stretch" -> ImageView.ScaleType.FIT_XY
                else -> ImageView.ScaleType.CENTER_CROP
            }
            bg.execute {
                val bmp = decode(f); if (bmp != null) computeAuto(bmp)
                runOnUiThread { img.setImageBitmap(bmp); applyStyle(); if (flag("auto")) { rebuild(); buildWidgets() } }
            }
        }
    }

    private fun computeAuto(bmp: Bitmap) {
        val px = Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3); Color.colorToHSV(px, hsv)
        hsv[1] = min(hsv[1] * 1.4f, 0.55f); hsv[2] = 1f
        autoAcc = Color.HSVToColor(hsv)
        hsv[1] = hsv[1] * 0.35f
        autoClk = Color.HSVToColor(hsv)
    }

    private fun applyCut() {
        val f = file("cut")
        if (s("cstyle", "normal") != "depth" || !f.exists()) { cut.setImageDrawable(null); cut.visibility = View.GONE; cutKey = ""; return }
        cut.scaleType = img.scaleType; cut.visibility = View.VISIBLE
        val key = f.lastModified().toString() + f.length()
        if (key == cutKey) return
        cutKey = key
        bg.execute { val b = decode(f); runOnUiThread { cut.setImageBitmap(b) } }
    }

    // ---------- apps ----------
    private fun loadApps() = bg.execute {
        val pm = packageManager
        val seen = HashSet<String>()
        val found = ArrayList<Pair<String, ResolveInfo>>()
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                found.add(Pair(cat, ri))
            }
        }
        val key = found.joinToString(",") { it.second.activityInfo.packageName }
        if (key == sig) return@execute
        val list = ArrayList<AppItem>()
        for ((cat, ri) in found) {
            val pkg = ri.activityInfo.packageName
            val launch = Intent(Intent.ACTION_MAIN).addCategory(cat)
                .setClassName(pkg, ri.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            list.add(AppItem(ri.loadLabel(pm).toString(), pkg, launch, ri.loadIcon(pm), ri.activityInfo.loadBanner(pm)))
        }
        runOnUiThread { all = list; sig = key; rebuild() }
    }

    private fun rebuild() {
        accent = accentCol()
        val hide = s("hide", "").split(",")
        val star = s("star", DEF).split(",")
        val list = all.filter { it.pkg !in hide }.sortedWith(compareBy<AppItem>(
            { val i = star.indexOf(it.pkg); if (i < 0) 99 else i }, { it.label.lowercase() }))
        dock.removeAllViews(); tiles.clear(); rail = null
        val lay = s("layout", "row")
        val base = dp(s("tile", "170").toIntOrNull() ?: 170)
        dock.layoutParams = FrameLayout.LayoutParams(MATCH, WRAP, if (lay == "row") Gravity.BOTTOM else Gravity.CENTER)
            .apply { if (lay == "row") bottomMargin = dp(16) }
        selName.typeface = face(s("tfont", "pop")); selName.setTextColor(textCol()); shadow(selName); selName.text = ""
        if (flag("showlabel")) dock.addView(selName, LinearLayout.LayoutParams(MATCH, WRAP))
        if (upd > 0) tiles.add(pill(base, "Update v" + upd + "\nPress OK") { doUpdate() })
        for (a in list) tiles.add(tile(a, a.pkg in star, base))
        tiles.add(pill(base, "Customize") { showInfo() })
        if (lay == "grid") {
            val gl = GridLayout(this).apply { columnCount = 6; for (v in tiles) addView(v) }
            val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(gl) }
            dock.addView(sv, LinearLayout.LayoutParams(MATCH, dp(340)))
        } else {
            val rl = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false
                setPadding(dp(56), dp(44), dp(56), dp(44)); for (v in tiles) addView(v)
            }
            val rv = Rail(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(rl) }
            rail = rv
            dock.addView(rv, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        tiles.firstOrNull()?.requestFocus()
    }

    /** Selected tile grows and lifts; neighbours follow like a wave. */
    private fun wave(c: Int) {
        val hz = s("layout", "row") != "grid"
        tiles.forEachIndexed { i, v ->
            val d = abs(i - c)
            val sc = if (!hz) (if (d == 0) 1.2f else 1f) else when (d) { 0 -> 1.38f; 1 -> 1.14f; 2 -> 1.05f; else -> 1f }
            val ty = if (!hz) 0f else when (d) { 0 -> -dp(26).toFloat(); 1 -> -dp(10).toFloat(); 2 -> -dp(3).toFloat(); else -> 0f }
            v.animate().scaleX(sc).scaleY(sc).translationY(ty).translationZ(if (d == 0) dp(8).toFloat() else 0f)
                .alpha(if (d == 0) 1f else 0.7f).setDuration(260).setInterpolator(OvershootInterpolator(1.1f)).start()
        }
    }

    private fun focused(v: View) {
        ding()
        selName.alpha = 0f; selName.text = (v.tag as? String) ?: ""
        selName.animate().alpha(1f).setDuration(240).start()
        wave(tiles.indexOf(v))
        rail?.let { r -> r.smoothScrollTo(max(0, v.left + v.width / 2 - r.width / 2), 0) }
    }

    private fun glassBg(): GradientDrawable {
        val a = (s("gop", "18").toIntOrNull() ?: 18).coerceIn(0, 80) * 255 / 100
        return GradientDrawable().apply {
            setColor((a shl 24) or 0xFFFFFF); cornerRadius = dp(s("tradius", "18").toIntOrNull() ?: 18).toFloat()
            setStroke(dp(1), 0x33FFFFFF)
        }
    }

    private fun tile(a: AppItem, big: Boolean, base: Int): View {
        val iconStyle = s("tstyle", "icon") == "icon"
        val full = !iconStyle && a.banner != null
        val w = if (iconStyle) (base * 0.56f * (if (big) 1.25f else 1f)).toInt() else (base * (if (big) 1.35f else 1f)).toInt()
        val h = if (iconStyle) w else w * 9 / 16
        val rad = dp(s("tradius", "18").toIntOrNull() ?: 18).toFloat()
        return ImageView(this).apply {
            setImageDrawable(if (full) a.banner else a.icon)
            scaleType = if (full) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            if (!full) { val pd = dp(if (iconStyle) 11 else 14); setPadding(pd, pd, pd, pd) }
            background = glassBg()
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, rad) }
            }
            isFocusable = true; isClickable = true; alpha = 0.7f; tag = a.label
            layoutParams = ViewGroup.MarginLayoutParams(w, h).apply { setMargins(dp(14), 0, dp(14), 0) }
            setOnFocusChangeListener { v, f -> if (f) focused(v) }
            setOnClickListener { try { startActivity(a.intent) } catch (_: Exception) {} }
            setOnLongClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
                true
            }
        }
    }

    private fun pill(base: Int, label: String, go: () -> Unit): View = TextView(this).apply {
        text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(textCol())
        typeface = face(s("tfont", "pop")); shadow(this)
        isFocusable = true; isClickable = true; alpha = 0.7f; tag = label.substringBefore("\n")
        background = glassBg()
        val w = (base * 0.56f).toInt()
        layoutParams = ViewGroup.MarginLayoutParams(w, w).apply { setMargins(dp(14), 0, dp(14), 0) }
        setOnFocusChangeListener { v, f -> if (f) focused(v) }
        setOnClickListener { go() }
    }

    // ---------- widgets ----------
    private fun buildWidgets() {
        widgetBox.removeAllViews()
        val depth = s("cstyle", "normal") == "depth"
        val side = if (!depth && s("cpos", "right") == "left") Gravity.END else Gravity.START
        widgetBox.layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or side).apply { setMargins(dp(40), dp(28), dp(40), 0) }
        val on = s("wlist", "").split(",")
        val tint = if (flag("auto")) autoAcc else col("wclr", "#FFFFFF")
        fun txt(x: String) = TextView(this).apply {
            text = x; textSize = 16f; setTextColor(textCol()); typeface = face(s("tfont", "pop")); shadow(this)
            maxWidth = dp(300); maxLines = 3
        }
        fun card(title: String, body: View) {
            val head = TextView(this).apply { text = title; textSize = 11f; setTextColor(textCol()); alpha = 0.65f; typeface = face(s("tfont", "pop")) }
            widgetBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12))
                background = GradientDrawable().apply { setColor((0x40 shl 24) or (tint and 0xFFFFFF)); cornerRadius = dp(20).toFloat() }
                addView(head); addView(body)
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(8), 0, dp(8), 0) }
            })
        }
        if ("weather" in on && wxText.isNotEmpty()) card("WEATHER", txt(wxText))
        if ("notes" in on && s("ntext", "").isNotEmpty()) card("NOTE", txt(s("ntext", "")))
        if ("count" in on) {
            val d = try { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(s("cdate", "")) } catch (_: Exception) { null }
            if (d != null) {
                val days = ((d.time - System.currentTimeMillis()) / 86400000L).toInt() + 1
                card(s("cname", "COUNTDOWN").uppercase(), txt(if (days >= 0) days.toString() + " days" else "Passed"))
            }
        }
        if ("world" in on && s("wtz", "").isNotEmpty()) {
            val z = TextClock(this).apply {
                timeZone = s("wtz", ""); format12Hour = "h:mm a"; format24Hour = "HH:mm"
                textSize = 16f; setTextColor(textCol()); typeface = face(s("tfont", "pop")); shadow(this)
            }
            card(s("wtz", "").substringAfter('/').replace('_', ' ').uppercase(), z)
        }
    }

    private fun httpGet(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        return c.inputStream.bufferedReader().readText()
    }

    private fun refreshWeather() {
        val city = s("wcity", "")
        if (city.isEmpty() || "weather" !in s("wlist", "").split(",")) return
        if (System.currentTimeMillis() - lastWx < 1800000 && wxFor == city) return
        lastWx = System.currentTimeMillis(); wxFor = city
        bg.execute {
            try {
                val g = JSONObject(httpGet("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(city, "UTF-8")))
                    .getJSONArray("results").getJSONObject(0)
                val w = JSONObject(httpGet("https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code&latitude=" +
                    g.getDouble("latitude") + "&longitude=" + g.getDouble("longitude"))).getJSONObject("current")
                val code = w.getInt("weather_code")
                val name = when { code == 0 -> "Clear"; code < 4 -> "Cloudy"; code < 50 -> "Fog"; code < 70 -> "Rain"; code < 80 -> "Snow"; code < 90 -> "Showers"; else -> "Storm" }
                wxText = Math.round(w.getDouble("temperature_2m")).toString() + "\u00B0 " + name + " \u00B7 " + g.getString("name")
            } catch (_: Exception) { wxText = "Weather unavailable"; lastWx = 0L }
            runOnUiThread { buildWidgets() }
        }
    }

    private fun showInfo() {
        info.text = "Customize from your phone\n(same Wi-Fi) \u2014 open:\nhttp://" + ip() + ":8080"
        info.visibility = if (info.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun ip(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("p2p") && !it.name.startsWith("dummy") }
            .sortedBy { if (it.name == "wlan0" || it.name == "eth0") 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address }?.hostAddress ?: "TV-IP"
    } catch (_: Exception) { "TV-IP" }

    // ---------- self-update ----------
    private fun checkUpdate() {
        if (System.currentTimeMillis() - lastChk < 600000) return
        lastChk = System.currentTimeMillis()
        bg.execute {
            try {
                val c = URL(REL).openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val n = JSONObject(c.inputStream.bufferedReader().readText()).optString("body").trim().toIntOrNull() ?: 0
                val cur = packageManager.getPackageInfo(packageName, 0).versionCode
                runOnUiThread { val u = if (n > cur) n else 0; if (u != upd) { upd = u; rebuild() } }
            } catch (_: Exception) {}
        }
    }

    private fun doUpdate() {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow installs for Lite Launcher, then press Update again", Toast.LENGTH_LONG).show()
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + packageName))) } catch (_: Exception) {}
            return
        }
        Toast.makeText(this, "Downloading update...", Toast.LENGTH_LONG).show()
        net.execute {
            try {
                val f = File(cacheDir, "u.apk")
                val c = URL(APK).openConnection() as HttpURLConnection
                c.inputStream.use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
                val pi = packageManager.packageInstaller
                val id = pi.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                pi.openSession(id).use { ses ->
                    FileInputStream(f).use { i -> ses.openWrite("app", 0, f.length()).use { o -> i.copyTo(o); ses.fsync(o) } }
                    val fl = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    ses.commit(PendingIntent.getBroadcast(this, id, Intent(ACT).setPackage(packageName), fl).intentSender)
                }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this, "Update failed", Toast.LENGTH_LONG).show() }
            }
        }
    }

    // ---------- phone customize server ----------
    private fun startServer() = net.execute {
        try {
            val ss = ServerSocket(8080); server = ss
            while (true) { val c = ss.accept(); net.execute { try { handle(c) } catch (_: Exception) {} } }
        } catch (_: Exception) {}
    }

    private fun handle(c: Socket) {
        c.use {
            val inp = BufferedInputStream(c.getInputStream()); val out = c.getOutputStream()
            fun line(): String {
                val sb = StringBuilder()
                while (true) { val x = inp.read(); if (x < 0 || x == 10) break; if (x != 13) sb.append(x.toChar()) }
                return sb.toString()
            }
            val rq = line().split(" ")
            if (rq.size < 2) return
            var len = 0L
            while (true) { val h = line(); if (h.isEmpty()) break; if (h.startsWith("content-length", true)) len = h.substringAfter(":").trim().toLong() }
            val path = rq[1].substringBefore("?")
            val q = rq[1].substringAfter("?", "").split("&").filter { it.isNotEmpty() }
                .associate { kv -> kv.split("=", limit = 2).let { it[0] to URLDecoder.decode(it.getOrElse(1) { "" }, "UTF-8") } }
            fun send(type: String, body: String) {
                val b = body.toByteArray()
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " + b.size + "\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(b); out.flush()
            }
            when (path) {
                "/api" -> send("application/json", api())
                "/log" -> send("text/plain", try { file("crash.txt").readText() } catch (_: Exception) { "No crash recorded" })
                "/set" -> { set(q); send("text/plain", "ok") }
                "/up" -> {
                    val kind = q["kind"] ?: "wall"
                    val tmp = file("tmp" + kind)
                    FileOutputStream(tmp).use { fo ->
                        val buf = ByteArray(65536); var left = len
                        while (left > 0) {
                            val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            fo.write(buf, 0, n); left -= n
                        }
                    }
                    tmp.renameTo(file(if (kind == "music") "music" else if (kind == "cut") "cut" else "wall"))
                    if (kind == "wall") { file("cut").delete(); p.edit().putString("wt", if (q["type"] == "video") "video" else "image").apply() }
                    runOnUiThread { musicPos = 0; applyWall(); applyCut(); applyMusic() }
                    send("text/plain", "ok")
                }
                else -> send("text/html", PAGE.replace("@@F@@", FONTS.joinToString("") { "<option value=" + it.second + ">" + it.first + "</option>" }))
            }
        }
    }

    private fun toggle(csv: String, x: String): String {
        val l = csv.split(",").filter { it.isNotEmpty() }.toMutableList()
        if (!l.remove(x)) l.add(x)
        return l.joinToString(",")
    }

    private fun set(q: Map<String, String>) {
        val e = p.edit()
        q["star"]?.let { e.putString("star", toggle(s("star", DEF), it)) }
        q["hide"]?.let { e.putString("hide", toggle(s("hide", ""), it)) }
        for (k in STR.keys + BOOL) q[k]?.let { v ->
            val ok = if (k in TXT) v.filter { c -> c.isLetterOrDigit() || c in " .,:-!?'/_+()#" }.take(80)
            else v.filter { c -> c.isLetterOrDigit() || c == '#' }.take(16)
            e.putString(k, ok)
        }
        e.apply()
        runOnUiThread {
            applyStyle(); rebuild(); buildWidgets(); refreshWeather(); applyCut()
            if (q.containsKey("wfit")) applyWall()
            if (q.containsKey("music")) applyMusic()
            if (q.containsKey("vol")) setVol()
        }
    }

    private fun api(): String {
        val star = s("star", DEF).split(","); val hide = s("hide", "").split(",")
        val sb = StringBuilder("{")
        for ((k, d) in STR) sb.append("\"" + k + "\":\"" + s(k, d) + "\",")
        for (k in BOOL) sb.append("\"" + k + "\":" + flag(k) + ",")
        sb.append("\"apps\":[")
        all.sortedBy { it.label.lowercase() }.forEachIndexed { i, a ->
            if (i > 0) sb.append(',')
            sb.append("{\"p\":\"" + a.pkg + "\",\"l\":\"" + a.label.replace("\\", "").replace("\"", "'") +
                "\",\"s\":" + (a.pkg in star) + ",\"h\":" + (a.pkg in hide) + "}")
        }
        return sb.append("]}").toString()
    }
}

private const val PAGE = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>Lite Launcher</title>
<style>body{font:16px sans-serif;background:#0b0f1a;color:#e6e9f2;margin:0;padding:18px;max-width:560px}h2{font-weight:300}h3{margin:24px 0 6px;font-weight:400;color:#8a93ab}.r{display:flex;justify-content:space-between;align-items:center;padding:9px 0;border-bottom:1px solid #1d2438;gap:8px}button,select,input[type=text],input[type=date]{background:#1d2438;color:#e6e9f2;border:0;border-radius:8px;padding:7px 12px}input[type=text]{width:50%}button.on{background:#3d7bff}input[type=range]{width:50%}input[type=file]{max-width:55%}a{color:#7fb2ff}.sw{width:44px;height:30px;border:2px solid #fff3;border-radius:15px}small{color:#8a93ab;display:block;padding:6px 0}#cw{display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:#000d;flex-direction:column;align-items:center;justify-content:center;gap:12px;z-index:9}#hx{padding:6px 18px;border-radius:12px;color:#000;background:#fff}</style>
<h2>Lite Launcher</h2>
<h3>Clock</h3>
<div class=r>Style<select id=cst><option value=normal>Normal</option><option value=depth>Depth (behind subject)</option></select></div>
<div class=r>Glass clock<input type=checkbox id=gl></div>
<div class=r>Match wallpaper colors<input type=checkbox id=au></div>
<div class=r>Clock font<select id=cf>@@F@@</select></div>
<div class=r>Clock color<button class=sw data-k=cclr></button></div>
<div class=r>Clock size<input type=range id=cs min=40 max=140></div>
<div class=r>Depth clock size<input type=range id=ds min=120 max=320></div>
<div class=r>Position<select id=cp><option value=left>Left</option><option value=center>Center</option><option value=right>Right</option></select></div>
<div class=r>24-hour<input type=checkbox id=h></div>
<div class=r>Show date<input type=checkbox id=sd></div>
<h3>Text</h3>
<div class=r>Text font<select id=tf>@@F@@</select></div>
<div class=r>Text color<button class=sw data-k=tclr></button></div>
<h3>Wallpaper</h3>
<div class=r>Image or mp4<input type=file id=w accept="image/*,video/mp4"></div>
<div class=r>Crop focus<select id=fo><option value=center>Center</option><option value=top>Top</option><option value=bottom>Bottom</option><option value=left>Left</option><option value=right>Right</option></select></div>
<div class=r>Mode<select id=md><option value=fill>Fill (crop)</option><option value=blur>Whole image + blur</option></select></div>
<div class=r><button id=re>Re-apply last image</button></div>
<div id=msg><small>Pick an image: it is resized to 1920x1080 on your phone.</small></div>
<div class=r>Depth cutout (PNG)<input type=file id=cf2 accept="image/png"></div>
<div class=r><button id=ac>Auto-make cutout (experimental)</button></div>
<small>Depth needs a cutout: the same photo with the background removed. Upload it, or try Auto (needs internet, can take a minute).</small>
<div class=r>Video fit<select id=wf><option value=fill>Fill</option><option value=fit>Fit</option><option value=stretch>Stretch</option></select></div>
<h3>Layout and tiles</h3>
<div class=r>Layout<select id=ly><option value=row>Bottom row</option><option value=center>Center row</option><option value=grid>Grid</option></select></div>
<div class=r>Tile style<select id=ts><option value=icon>Icons</option><option value=banner>Banners</option></select></div>
<div class=r>Tile size<input type=range id=t min=110 max=240></div>
<div class=r>Corner radius<input type=range id=tr min=0 max=40></div>
<div class=r>Glass strength<input type=range id=go min=0 max=60></div>
<div class=r>Accent<button class=sw data-k=accent></button></div>
<div class=r>Show app name<input type=checkbox id=sl></div>
<h3>Widgets</h3>
<div class=r>Weather<input type=checkbox class=wg data-w=weather></div>
<div class=r>City<input type=text id=wc></div>
<div class=r>Note<input type=checkbox class=wg data-w=notes></div>
<div class=r><input type=text id=nt style="width:100%" placeholder="Note text"></div>
<div class=r>Countdown<input type=checkbox class=wg data-w=count></div>
<div class=r><input type=text id=cn placeholder="Name"><input type=date id=cd></div>
<div class=r>World clock<input type=checkbox class=wg data-w=world></div>
<div class=r>Time zone<input type=text id=tz placeholder="Europe/London"></div>
<div class=r>Widget tint<button class=sw data-k=wclr></button></div>
<h3>Sound</h3>
<div class=r>Navigation sounds<input type=checkbox id=s></div>
<div class=r>Background music<input type=checkbox id=m></div>
<div class=r>Music file (mp3)<input type=file id=f accept="audio/*"></div>
<div class=r>Volume<input type=range id=v min=0 max=100></div>
<h3>Apps (pin first / hide)</h3><div id=l></div>
<p><a href=/log>Last crash log</a></p>
<div id=cw><canvas id=cv width=260 height=260></canvas><input type=range id=br min=15 max=100 value=100 style="width:260px"><div id=hx>#ffffff</div><div><button id=ok class=on>Apply</button> <button id=no>Cancel</button></div></div>
<script>
var g=function(i){return document.getElementById(i)},cur='#ffffff',tgt=null,R=130,lastWall=null;
var M=[['cst','cstyle'],['cf','cfont'],['cs','csize'],['ds','dsize'],['cp','cpos'],['tf','tfont'],['wf','wfit'],['ly','layout'],['ts','tstyle'],['t','tile'],['tr','tradius'],['go','gop'],['v','vol'],['wc','wcity'],['nt','ntext'],['cn','cname'],['cd','cdate'],['tz','wtz']];
var B=[['gl','glass'],['au','auto'],['h','h24'],['sd','showdate'],['sl','showlabel'],['s','sound'],['m','music']];
function set(q){return fetch('/set?'+q,{method:'POST'}).then(load)}
function send(kind,type,blob){return fetch('/up?kind='+kind+'&type='+type,{method:'POST',body:blob})}
function say(t){g('msg').innerHTML='<small>'+t+'</small>'}
function proc(file,png,cb){var im=new Image();im.onload=function(){var W=1920,H=1080,c=document.createElement('canvas');c.width=W;c.height=H;var x=c.getContext('2d');x.imageSmoothingQuality='high';var iw=im.naturalWidth,ih=im.naturalHeight,blur=g('md').value=='blur',k=blur?Math.min(W/iw,H/ih):Math.max(W/iw,H/ih),w=iw*k,h=ih*k,f=g('fo').value;
if(blur&&!png){var k0=Math.max(W/iw,H/ih);x.filter='blur(30px)';x.drawImage(im,(W-iw*k0)/2,(H-ih*k0)/2,iw*k0,ih*k0);x.filter='none';x.fillStyle='rgba(0,0,0,.25)';x.fillRect(0,0,W,H)}
var fx=blur?.5:(f=='left'?0:f=='right'?1:.5),fy=blur?.5:(f=='top'?0:f=='bottom'?1:.5);x.drawImage(im,(W-w)*fx,(H-h)*fy,w,h);
say(iw+'x'+ih+(k>1.15?' - upscaled '+Math.round(k*100)+'%, will look soft. Use a bigger original.':' - good size'));
c.toBlob(cb,png?'image/png':'image/jpeg',.93)};im.src=URL.createObjectURL(file)}
function wall(file){if(file.type.indexOf('video')==0){send('wall','video',file).then(function(){say('Video saved')});return}lastWall=file;proc(file,false,function(b){send('wall','image',b).then(function(){say('Wallpaper saved')})})}
function load(){return fetch('/api').then(function(r){return r.json()}).then(function(d){
M.forEach(function(m){g(m[0]).value=d[m[1]]});B.forEach(function(b){g(b[0]).checked=d[b[1]]});
document.querySelectorAll('.sw').forEach(function(b){b.style.background=d[b.dataset.k];b.dataset.v=d[b.dataset.k]});
var on=d.wlist.split(',');document.querySelectorAll('.wg').forEach(function(c){c.checked=on.indexOf(c.dataset.w)>=0});
g('l').innerHTML='';d.apps.forEach(function(a){var r=document.createElement('div');r.className='r';r.textContent=a.l;
var s=document.createElement('button');s.textContent='Pin';s.className=a.s?'on':'';s.onclick=function(){set('star='+encodeURIComponent(a.p))};
var h=document.createElement('button');h.textContent='Hide';h.className=a.h?'on':'';h.onclick=function(){set('hide='+encodeURIComponent(a.p))};
var b=document.createElement('span');b.append(s,h);r.append(b);g('l').append(r)})})}
M.forEach(function(m){g(m[0]).onchange=function(){set(m[1]+'='+encodeURIComponent(this.value))}});
B.forEach(function(b){g(b[0]).onchange=function(){set(b[1]+'='+(this.checked?1:0))}});
document.querySelectorAll('.wg').forEach(function(c){c.onchange=function(){var l=[];document.querySelectorAll('.wg').forEach(function(x){if(x.checked)l.push(x.dataset.w)});set('wlist='+encodeURIComponent(l.join(',')))}});
g('w').onchange=function(){if(this.files[0])wall(this.files[0])};
g('re').onclick=function(){if(lastWall)wall(lastWall);else say('Pick an image first')};
g('cf2').onchange=function(){var f=this.files[0];if(f)proc(f,true,function(b){send('cut','image',b).then(function(){say('Cutout saved')})})};
g('f').onchange=function(){if(this.files[0])send('music','audio',this.files[0]).then(function(){alert('Music saved')})};
g('ac').onclick=function(){if(!lastWall){say('Pick the wallpaper first');return}say('Working... this can take a minute');
proc(lastWall,false,function(b){import('https://cdn.jsdelivr.net/npm/@imgly/background-removal@1.5.5/+esm').then(function(m){return m.removeBackground(b)}).then(function(o){return send('cut','image',o)}).then(function(){say('Cutout saved')}).catch(function(e){say('Auto cutout failed ('+e+'). Upload a cutout PNG instead.')})})};
function h2r(h,s,v){var c=v*s,x=c*(1-Math.abs((h/60)%2-1)),m=v-c,r,q,b;if(h<60){r=c;q=x;b=0}else if(h<120){r=x;q=c;b=0}else if(h<180){r=0;q=c;b=x}else if(h<240){r=0;q=x;b=c}else if(h<300){r=x;q=0;b=c}else{r=c;q=0;b=x}return[Math.round((r+m)*255),Math.round((q+m)*255),Math.round((b+m)*255)]}
function hex(a){return '#'+a.map(function(n){return('0'+n.toString(16)).slice(-2)}).join('')}
function draw(){var x=g('cv').getContext('2d'),d=x.createImageData(260,260),v=g('br').value/100;for(var y=0;y<260;y++)for(var i=0;i<260;i++){var dx=i-R,dy=y-R,r=Math.sqrt(dx*dx+dy*dy),o=(y*260+i)*4;if(r<=R){var c=h2r((Math.atan2(dy,dx)*180/Math.PI+360)%360,r/R,v);d.data[o]=c[0];d.data[o+1]=c[1];d.data[o+2]=c[2];d.data[o+3]=255}}x.putImageData(d,0,0)}
function pick(e){var b=g('cv').getBoundingClientRect(),t=e.touches?e.touches[0]:e,dx=(t.clientX-b.left)*260/b.width-R,dy=(t.clientY-b.top)*260/b.height-R,r=Math.min(Math.sqrt(dx*dx+dy*dy),R);cur=hex(h2r((Math.atan2(dy,dx)*180/Math.PI+360)%360,r/R,g('br').value/100));g('hx').textContent=cur;g('hx').style.background=cur;e.preventDefault()}
document.querySelectorAll('.sw').forEach(function(b){b.onclick=function(){tgt=b;cur=b.dataset.v;g('cw').style.display='flex';draw();g('hx').textContent=cur;g('hx').style.background=cur}});
var cv=g('cv');cv.ontouchstart=cv.ontouchmove=pick;cv.onmousedown=function(e){cv.dr=1;pick(e)};cv.onmousemove=function(e){if(cv.dr)pick(e)};document.onmouseup=function(){cv.dr=0};
g('br').oninput=draw;g('no').onclick=function(){g('cw').style.display='none'};g('ok').onclick=function(){g('cw').style.display='none';set(tgt.dataset.k+'='+encodeURIComponent(cur))};
load();
</script>"""
