package com.ryan.launcher

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.*
import android.graphics.drawable.*
import android.media.*
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import java.io.*
import java.net.*
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

private const val MATCH = -1
private const val WRAP = -2
private const val DEF = "com.google.android.youtube.tv,com.netflix.ninja"

class AppItem(val label: String, val pkg: String, val intent: Intent, val art: Drawable, val banner: Boolean)

class MainActivity : Activity() {
    private lateinit var p: SharedPreferences
    private lateinit var row: LinearLayout
    private lateinit var img: ImageView
    private lateinit var vid: VideoView
    private lateinit var info: TextView
    private val bg = Executors.newSingleThreadExecutor()
    private val net = Executors.newCachedThreadPool()
    private var all = listOf<AppItem>()
    private var server: ServerSocket? = null
    private var music: MediaPlayer? = null
    private var tick: AudioTrack? = null
    private var accent = 0xFF7FB2FF.toInt()

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun s(k: String, d: String) = p.getString(k, d) ?: d
    private fun on(k: String) = s(k, "1") == "1"
    private fun file(n: String) = File(filesDir, n)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        p = getSharedPreferences("p", 0)
        val root = FrameLayout(this)
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0E1630.toInt(), 0xFF05070D.toInt()))
        img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        vid = VideoView(this).apply { visibility = View.GONE }
        root.addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(vid, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xDD000000.toInt(), 0))
        }, FrameLayout.LayoutParams(MATCH, dp(360), Gravity.BOTTOM))

        val clock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            setPadding(dp(56), dp(32), dp(56), 0)
            addView(TextClock(context).apply {
                format12Hour = "h:mm"; format24Hour = "HH:mm"; textSize = 64f
                typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); setTextColor(Color.WHITE)
            })
            addView(TextClock(context).apply {
                format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"; textSize = 16f
                setTextColor(0xCCFFFFFF.toInt())
            })
        }
        root.addView(clock, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(dp(56), dp(40), dp(56), dp(40))
        }
        val sc = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(row)
        }
        root.addView(sc, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(24) })

        info = TextView(this).apply {
            visibility = View.GONE; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setBackgroundColor(0xDD000000.toInt()); setPadding(dp(40), dp(30), dp(40), dp(30))
        }
        root.addView(info, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        setContentView(root)
        makeTick()
        startServer()
    }

    override fun onResume() {
        super.onResume()
        root().alpha = 0f; root().animate().alpha(1f).setDuration(350).start()
        applyWall(); applyMusic(); loadApps()
    }

    override fun onPause() {
        super.onPause()
        try { music?.release() } catch (_: Exception) {}
        music = null; vid.stopPlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { server?.close() } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        info.visibility = View.GONE
        row.getChildAt(0)?.requestFocus()
    }

    @Deprecated("Launcher must not close on Back")
    override fun onBackPressed() { info.visibility = View.GONE }

    private fun root(): View = row.parent.parent as View

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
        if (!on("sound")) return
        try { tick?.let { it.stop(); it.setPlaybackHeadPosition(0); it.play() } } catch (_: Exception) {}
    }

    private fun applyMusic() {
        try { music?.release() } catch (_: Exception) {}
        music = null
        val f = file("music")
        if (!on("music") || !f.exists()) return
        try {
            music = MediaPlayer().apply {
                setDataSource(f.path); isLooping = true
                val v = s("vol", "40").toInt() / 100f
                setVolume(v, v); setOnPreparedListener { it.start() }; prepareAsync()
            }
        } catch (_: Exception) {}
    }

    // ---------- wallpaper ----------
    private fun applyWall() {
        val f = file("wall")
        vid.stopPlayback()
        if (!f.exists()) { img.setImageDrawable(null); vid.visibility = View.GONE; return }
        if (s("wt", "image") == "video") {
            img.setImageDrawable(null); vid.visibility = View.VISIBLE
            vid.setOnPreparedListener { it.isLooping = true; it.setVolume(0f, 0f) }
            vid.setVideoPath(f.path); vid.start()
        } else {
            vid.visibility = View.GONE
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            o.inSampleSize = max(1, o.outWidth / 1920); o.inJustDecodeBounds = false
            img.setImageBitmap(BitmapFactory.decodeFile(f.path, o))
        }
    }

    // ---------- apps ----------
    private fun loadApps() = bg.execute {
        val pm = packageManager
        val seen = HashSet<String>()
        val list = ArrayList<AppItem>()
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val launch = Intent(Intent.ACTION_MAIN).addCategory(cat)
                    .setClassName(pkg, ri.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val banner = ri.activityInfo.loadBanner(pm)
                list.add(AppItem(ri.loadLabel(pm).toString(), pkg, launch, banner ?: ri.loadIcon(pm), banner != null))
            }
        }
        runOnUiThread { all = list; rebuild() }
    }

    private fun rebuild() {
        accent = try { Color.parseColor(s("accent", "#7FB2FF")) } catch (_: Exception) { 0xFF7FB2FF.toInt() }
        val hide = s("hide", "").split(",")
        val star = s("star", DEF).split(",")
        val list = all.filter { it.pkg !in hide }.sortedWith(compareBy<AppItem>(
            { val i = star.indexOf(it.pkg); if (i < 0) 99 else i }, { it.label.lowercase() }))
        row.removeAllViews()
        val base = dp(s("tile", "170").toInt())
        for (a in list) row.addView(tile(a, a.pkg in star, base))
        row.addView(gear(base))
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
            text = a.label; textSize = 14f; setTextColor(Color.WHITE); alpha = 0f; maxLines = 1; gravity = Gravity.CENTER
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

    private fun gear(base: Int): View = TextView(this).apply {
        text = "Customize"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        isFocusable = true; isClickable = true; alpha = 0.78f
        background = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = dp(14).toFloat() }
        layoutParams = LinearLayout.LayoutParams(base, base * 9 / 16).apply { setMargins(dp(10), 0, dp(10), 0) }
        setOnFocusChangeListener { v, f -> focusAnim(v, f) }
        setOnClickListener {
            info.text = "Customize from your phone\n(same Wi-Fi) — open:\nhttp://" + ip() + ":8080"
            info.visibility = if (info.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    private fun ip(): String = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: "TV-IP"
    } catch (_: Exception) { "TV-IP" }

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
                "/set" -> { set(q); send("text/plain", "ok") }
                "/up" -> {
                    val kind = q["kind"] ?: "wall"
                    val tmp = file("tmp")
                    FileOutputStream(tmp).use { fo ->
                        val buf = ByteArray(65536); var left = len
                        while (left > 0) {
                            val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) break
                            fo.write(buf, 0, n); left -= n
                        }
                    }
                    tmp.renameTo(file(if (kind == "music") "music" else "wall"))
                    if (kind != "music") p.edit().putString("wt", q["type"] ?: "image").apply()
                    runOnUiThread { applyWall(); applyMusic() }
                    send("text/plain", "ok")
                }
                else -> send("text/html", PAGE)
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
        for (k in listOf("sound", "music", "vol", "tile", "accent")) q[k]?.let { e.putString(k, it) }
        e.apply()
        runOnUiThread { rebuild(); applyMusic() }
    }

    private fun api(): String {
        val star = s("star", DEF).split(","); val hide = s("hide", "").split(",")
        val sb = StringBuilder("{\"sound\":" + on("sound") + ",\"music\":" + on("music") + ",\"vol\":" + s("vol", "40") +
            ",\"tile\":" + s("tile", "170") + ",\"accent\":\"" + s("accent", "#7FB2FF") + "\",\"apps\":[")
        all.sortedBy { it.label.lowercase() }.forEachIndexed { i, a ->
            if (i > 0) sb.append(',')
            sb.append("{\"p\":\"" + a.pkg + "\",\"l\":\"" + a.label.replace("\\", "").replace("\"", "'") +
                "\",\"s\":" + (a.pkg in star) + ",\"h\":" + (a.pkg in hide) + "}")
        }
        return sb.append("]}").toString()
    }
}

private const val PAGE = """<!doctype html><meta name=viewport content="width=device-width,initial-scale=1"><title>Lite Launcher</title>
<style>body{font:16px sans-serif;background:#0b0f1a;color:#e6e9f2;margin:0;padding:18px;max-width:560px}h2{font-weight:300}h3{margin:22px 0 8px;font-weight:400;color:#8a93ab}.r{display:flex;justify-content:space-between;align-items:center;padding:9px 0;border-bottom:1px solid #1d2438}button{background:#1d2438;color:#e6e9f2;border:0;border-radius:8px;padding:7px 12px;margin-left:6px}button.on{background:#3d7bff}input[type=range]{width:50%}input[type=file]{max-width:50%}</style>
<h2>Lite Launcher</h2>
<h3>Look</h3>
<div class=r>Wallpaper (image or mp4)<input type=file id=w accept="image/*,video/mp4"></div>
<div class=r>Tile size<input type=range id=t min=110 max=240></div>
<div class=r>Accent<input type=color id=a></div>
<h3>Sound</h3>
<div class=r>Navigation sounds<input type=checkbox id=s></div>
<div class=r>Background music<input type=checkbox id=m></div>
<div class=r>Music file (mp3)<input type=file id=f accept="audio/*"></div>
<div class=r>Volume<input type=range id=v min=0 max=100></div>
<h3>Apps (pin first / hide)</h3><div id=l></div>
<script>
var g=function(i){return document.getElementById(i)};
function set(q){return fetch('/set?'+q,{method:'POST'}).then(load)}
function up(el,k){var f=el.files[0];if(!f)return;fetch('/up?kind='+k+'&type='+(f.type.indexOf('video')==0?'video':'image'),{method:'POST',body:f}).then(function(){alert('Saved')})}
function load(){return fetch('/api').then(function(r){return r.json()}).then(function(d){
g('s').checked=d.sound;g('m').checked=d.music;g('v').value=d.vol;g('t').value=d.tile;g('a').value=d.accent;
g('l').innerHTML='';d.apps.forEach(function(a){var r=document.createElement('div');r.className='r';r.textContent=a.l;
var s=document.createElement('button');s.textContent='Pin';s.className=a.s?'on':'';s.onclick=function(){set('star='+encodeURIComponent(a.p))};
var h=document.createElement('button');h.textContent='Hide';h.className=a.h?'on':'';h.onclick=function(){set('hide='+encodeURIComponent(a.p))};
var b=document.createElement('span');b.append(s,h);r.append(b);g('l').append(r)})})}
g('w').onchange=function(){up(this,'wall')};g('f').onchange=function(){up(this,'music')};
g('s').onchange=function(){set('sound='+(this.checked?1:0))};g('m').onchange=function(){set('music='+(this.checked?1:0))};
g('v').onchange=function(){set('vol='+this.value)};g('t').onchange=function(){set('tile='+this.value)};g('a').onchange=function(){set('accent='+encodeURIComponent(this.value))};
load();
</script>"""
