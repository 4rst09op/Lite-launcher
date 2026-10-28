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
import android.view.animation.DecelerateInterpolator
import android.widget.*
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.concurrent.Executors
import kotlin.math.PI
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

class AppItem(val label: String, val pkg: String, val intent: Intent, val art: Drawable, val banner: Boolean)

class MainActivity : Activity() {
    private lateinit var p: SharedPreferences
    private lateinit var rootV: FrameLayout
    private lateinit var row: LinearLayout
    private lateinit var img: ImageView
    private lateinit var vid: VideoView
    private lateinit var info: TextView
    private lateinit var clockBox: LinearLayout
    private lateinit var tTime: TextClock
    private lateinit var tDate: TextClock
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
        rootV.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x88000000.toInt(), 0))
        }, FrameLayout.LayoutParams(MATCH, dp(240), Gravity.TOP))

        tTime = TextClock(this)
        tDate = TextClock(this).apply { format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"; textSize = 17f }
        clockBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(32), dp(56), 0)
            addView(tTime); addView(tDate)
        }
        rootV.addView(clockBox, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END))

        row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(dp(56), dp(40), dp(56), dp(40))
        }
        val sc = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(row)
        }
        rootV.addView(sc, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(24) })

        info = TextView(this).apply {
            visibility = View.GONE; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(0xDD000000.toInt()); setPadding(dp(40), dp(30), dp(40), dp(30))
        }
        rootV.addView(info, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        setContentView(rootV)
        applyStyle()
        makeTick()
        startServer()
        val flt = IntentFilter(ACT)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, flt, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(rcv, flt)
    }

    override fun onResume() {
        super.onResume()
        live = true
        rootV.alpha = 0f; rootV.animate().alpha(1f).setDuration(300).start()
        applyWall()
        if (vid.visibility == View.VISIBLE) vid.start()
        applyMusic(); loadApps(); checkUpdate()
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
        row.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Launcher must not close on Back")
    override fun onBackPressed() { info.visibility = View.GONE }

    // ---------- clock / text style ----------
    private fun applyStyle() {
        val pos = s("cpos", "right")
        tTime.typeface = face(s("cfont", "pop")); tTime.setTextColor(col("cclr", "#FFFFFF"))
        tTime.textSize = s("csize", "64").toFloatOrNull() ?: 64f
        tTime.format12Hour = if (s("h24", "0") == "1") "HH:mm" else "h:mm"; tTime.format24Hour = "HH:mm"
        tDate.typeface = face(s("tfont", "pop")); tDate.setTextColor(col("tclr", "#FFFFFF"))
        tDate.visibility = if (on("showdate")) View.VISIBLE else View.GONE
        shadow(tTime); shadow(tDate)
        val g = when (pos) { "left" -> Gravity.START; "center" -> Gravity.CENTER_HORIZONTAL; else -> Gravity.END }
        clockBox.gravity = g
        clockBox.layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or g)
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
        if (!f.exists()) { img.setImageDrawable(null); vid.visibility = View.GONE; return }
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
            bg.execute { val bmp = decode(f); runOnUiThread { img.setImageBitmap(bmp) } }
        }
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
            val banner = ri.activityInfo.loadBanner(pm)
            list.add(AppItem(ri.loadLabel(pm).toString(), pkg, launch, banner ?: ri.loadIcon(pm), banner != null))
        }
        runOnUiThread { all = list; sig = key; rebuild() }
    }

    private fun rebuild() {
        accent = try { Color.parseColor(s("accent", "#7FB2FF")) } catch (_: Exception) { 0xFF7FB2FF.toInt() }
        val hide = s("hide", "").split(",")
        val star = s("star", DEF).split(",")
        val list = all.filter { it.pkg !in hide }.sortedWith(compareBy<AppItem>(
            { val i = star.indexOf(it.pkg); if (i < 0) 99 else i }, { it.label.lowercase() }))
        row.removeAllViews()
        val base = dp(s("tile", "170").toIntOrNull() ?: 170)
        if (upd > 0) row.addView(pill(base, "Update v" + upd + "\nPress OK to install") { doUpdate() })
        for (a in list) row.addView(tile(a, a.pkg in star, base))
        row.addView(pill(base, "Customize") { showInfo() })
        if (!row.hasFocus()) row.getChildAt(0)?.requestFocus()
    }

    private fun focusAnim(v: View, f: Boolean) {
        val sc = if (f) 1.14f else 1f
        v.animate().scaleX(sc).scaleY(sc).alpha(if (f) 1f else 0.78f)
            .setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        if (f) ding()
    }

    private fun tile(a: AppItem, big: Boolean, base: Int): View {
        val w = if (big) (base * 1.35f).toInt() else base
        val h = w * 9 / 16
        val iv = ImageView(this).apply {
            setImageDrawable(a.art)
            scaleType = if (a.banner) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            if (!a.banner) setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(14).toFloat() }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(14).toFloat()) }
            }
        }
        val lb = TextView(this).apply {
            text = a.label; textSize = 14f; setTextColor(col("tclr", "#FFFFFF")); typeface = face(s("tfont", "pop"))
            alpha = 0f; maxLines = 1; gravity = Gravity.CENTER; shadow(this)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true; isClickable = true; alpha = 0.78f
            addView(iv, LinearLayout.LayoutParams(w, h))
            addView(lb, LinearLayout.LayoutParams(w, WRAP).apply { topMargin = dp(10) })
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(10), 0, dp(10), 0) }
            setOnFocusChangeListener { v, f ->
                focusAnim(v, f); lb.animate().alpha(if (f) 1f else 0f).setDuration(180).start()
                (iv.background as GradientDrawable).setStroke(if (f) dp(3) else 0, accent)
            }
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
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTextColor(col("tclr", "#FFFFFF"))
        typeface = face(s("tfont", "pop")); shadow(this)
        isFocusable = true; isClickable = true; alpha = 0.78f
        background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(14).toFloat() }
        layoutParams = LinearLayout.LayoutParams(base, base * 9 / 16).apply { setMargins(dp(10), 0, dp(10), 0) }
        setOnFocusChangeListener { v, f -> focusAnim(v, f) }
        setOnClickListener { go() }
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
                    tmp.renameTo(file(if (kind == "music") "music" else "wall"))
                    if (kind != "music") p.edit().putString("wt", if (q["type"] == "video") "video" else "image").apply()
                    runOnUiThread { musicPos = 0; applyWall(); applyMusic() }
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
        for (k in listOf("sound", "music", "vol", "tile", "accent", "cfont", "tfont", "cclr", "tclr", "csize", "cpos", "h24", "showdate", "wfit"))
            q[k]?.let { v -> e.putString(k, v.filter { c -> c.isLetterOrDigit() || c == '#' }.take(16)) }
        e.apply()
        runOnUiThread {
            applyStyle(); rebuild()
            if (q.containsKey("wfit")) applyWall()
            if (q.containsKey("music")) applyMusic()
            if (q.containsKey("vol")) setVol()
        }
    }

    private fun api(): String {
        val star = s("star", DEF).split(","); val hide = s("hide", "").split(",")
        fun j(k: String, d: String) = "\"" + k + "\":\"" + s(k, d) + "\""
        val sb = StringBuilder("{\"sound\":" + on("sound") + ",\"music\":" + on("music") + ",\"vol\":" + s("vol", "40") +
            ",\"tile\":" + s("tile", "170") + ",\"csize\":" + s("csize", "64") + ",\"h24\":" + (s("h24", "0") == "1") +
            ",\"showdate\":" + on("showdate") + "," + j("accent", "#7FB2FF") + "," + j("cfont", "pop") + "," + j("tfont", "pop") +
            "," + j("cclr", "#FFFFFF") + "," + j("tclr", "#FFFFFF") + "," + j("cpos", "right") + "," + j("wfit", "fill") + ",\"apps\":[")
        all.sortedBy { it.label.lowercase() }.forEachIndexed { i, a ->
            if (i > 0) sb.append(',')
            sb.append("{\"p\":\"" + a.pkg + "\",\"l\":\"" + a.label.replace("\\", "").replace("\"", "'") +
                "\",\"s\":" + (a.pkg in star) + ",\"h\":" + (a.pkg in hide) + "}")
        }
        return sb.append("]}").toString()
    }
}

private const val PAGE = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>Lite Launcher</title>
<style>body{font:16px sans-serif;background:#0b0f1a;color:#e6e9f2;margin:0;padding:18px;max-width:560px}h2{font-weight:300}h3{margin:22px 0 8px;font-weight:400;color:#8a93ab}.r{display:flex;justify-content:space-between;align-items:center;padding:9px 0;border-bottom:1px solid #1d2438}button,select{background:#1d2438;color:#e6e9f2;border:0;border-radius:8px;padding:7px 12px;margin-left:6px}button.on{background:#3d7bff}input[type=range]{width:50%}input[type=file]{max-width:50%}a{color:#7fb2ff}</style>
<h2>Lite Launcher</h2>
<h3>Clock and text</h3>
<div class=r>Clock font<select id=cf>@@F@@</select></div>
<div class=r>Text font<select id=tf>@@F@@</select></div>
<div class=r>Clock color<input type=color id=cc></div>
<div class=r>Text color<input type=color id=tc></div>
<div class=r>Clock size<input type=range id=cs min=40 max=140></div>
<div class=r>Position<select id=cp><option value=left>Left</option><option value=center>Center</option><option value=right>Right</option></select></div>
<div class=r>24-hour clock<input type=checkbox id=h></div>
<div class=r>Show date<input type=checkbox id=sd></div>
<h3>Wallpaper</h3>
<div class=r>Image or mp4<input type=file id=w accept="image/*,video/mp4"></div>
<div class=r>Fit<select id=wf><option value=fill>Fill (crop)</option><option value=fit>Fit (no crop)</option><option value=stretch>Stretch</option></select></div>
<h3>Tiles</h3>
<div class=r>Tile size<input type=range id=t min=110 max=240></div>
<div class=r>Accent<input type=color id=a></div>
<h3>Sound</h3>
<div class=r>Navigation sounds<input type=checkbox id=s></div>
<div class=r>Background music<input type=checkbox id=m></div>
<div class=r>Music file (mp3)<input type=file id=f accept="audio/*"></div>
<div class=r>Volume<input type=range id=v min=0 max=100></div>
<h3>Apps (pin first / hide)</h3><div id=l></div>
<p><a href=/log>Last crash log</a></p>
<script>
var g=function(i){return document.getElementById(i)};
function set(q){return fetch('/set?'+q,{method:'POST'}).then(load)}
function up(el,k){var f=el.files[0];if(!f)return;fetch('/up?kind='+k+'&type='+(f.type.indexOf('video')==0?'video':'image'),{method:'POST',body:f}).then(function(){alert('Saved')})}
function load(){return fetch('/api').then(function(r){return r.json()}).then(function(d){
g('s').checked=d.sound;g('m').checked=d.music;g('v').value=d.vol;g('t').value=d.tile;g('a').value=d.accent;
g('cf').value=d.cfont;g('tf').value=d.tfont;g('cc').value=d.cclr;g('tc').value=d.tclr;g('cs').value=d.csize;g('cp').value=d.cpos;g('h').checked=d.h24;g('sd').checked=d.showdate;g('wf').value=d.wfit;
g('l').innerHTML='';d.apps.forEach(function(a){var r=document.createElement('div');r.className='r';r.textContent=a.l;
var s=document.createElement('button');s.textContent='Pin';s.className=a.s?'on':'';s.onclick=function(){set('star='+encodeURIComponent(a.p))};
var h=document.createElement('button');h.textContent='Hide';h.className=a.h?'on':'';h.onclick=function(){set('hide='+encodeURIComponent(a.p))};
var b=document.createElement('span');b.append(s,h);r.append(b);g('l').append(r)})})}
function bind(id,key,box){g(id).onchange=function(){set(key+'='+encodeURIComponent(box?(this.checked?1:0):this.value))}}
bind('cf','cfont');bind('tf','tfont');bind('cc','cclr');bind('tc','tclr');bind('cs','csize');bind('cp','cpos');bind('h','h24',1);bind('sd','showdate',1);bind('wf','wfit');
bind('t','tile');bind('a','accent');bind('s','sound',1);bind('m','music',1);bind('v','vol');
g('w').onchange=function(){up(this,'wall')};g('f').onchange=function(){up(this,'music')};
load();
</script>"""
