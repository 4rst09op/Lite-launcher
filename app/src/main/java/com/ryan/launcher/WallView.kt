package com.ryan.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Procedural animated wallpapers (night lake, aurora, drifting colour) plus still images. */
class WallView(ctx: Context) : View(ctx) {
    var scene = "lake"
        private set
    var anim = true
    var image: Bitmap? = null

    private var base: Bitmap? = null
    private var bw = 0
    private var bh = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()
    private val t0 = System.currentTimeMillis()
    private var running = false
    private val stars = Array(70) {
        floatArrayOf(Math.random().toFloat(), Math.random().toFloat() * 0.4f, 0.8f + Math.random().toFloat() * 1.6f, Math.random().toFloat() * 6.28f)
    }
    private var blobs: Array<Bitmap>? = null
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            if (running && anim) postDelayed(this, 40)
        }
    }

    fun setScene(s: String) {
        if (s == scene) return
        scene = s
        base?.recycle()
        base = null
        go()
    }

    fun go() {
        running = true
        removeCallbacks(tick)
        if (anim && scene != "image" && scene != "black") post(tick) else invalidate()
    }

    fun halt() {
        running = false
        removeCallbacks(tick)
    }

    override fun onDetachedFromWindow() {
        halt()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val t = if (anim) (System.currentTimeMillis() - t0) / 1000f else 0f
        when (scene) {
            "image" -> {
                val im = image
                if (im == null) c.drawColor(0xFF05070D.toInt()) else drawCrop(c, im, w, h)
            }
            "black" -> c.drawColor(Color.BLACK)
            "aurora" -> aurora(c, w, h, t)
            "drift" -> drift(c, w, h, t)
            else -> lake(c, w, h, t)
        }
    }

    private fun drawCrop(c: Canvas, im: Bitmap, w: Int, h: Int) {
        val sa = im.width.toFloat() / im.height
        val da = w.toFloat() / h
        val src = if (sa > da) {
            val nw = (im.height * da).toInt()
            Rect((im.width - nw) / 2, 0, (im.width + nw) / 2, im.height)
        } else {
            val nh = (im.width / da).toInt()
            Rect(0, (im.height - nh) / 2, im.width, (im.height + nh) / 2)
        }
        dst.set(0, 0, w, h)
        c.drawBitmap(im, src, dst, bmpPaint)
    }

    private fun drawBase(c: Canvas, w: Int, h: Int) {
        if (base == null || bw != w / 2 || bh != h / 2) {
            base?.recycle()
            bw = w / 2
            bh = h / 2
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b)
            if (scene == "lake") buildLake(cv, bw.toFloat(), bh.toFloat()) else buildNight(cv, bw.toFloat(), bh.toFloat())
            base = b
        }
        dst.set(0, 0, w, h)
        c.drawBitmap(base!!, null, dst, bmpPaint)
    }

    private fun ridge(c: Canvas, w: Float, baseY: Float, amp: Float, seed: Float, top: Int, bottom: Int, bottomY: Float) {
        val path = Path()
        path.moveTo(0f, bottomY)
        var x = 0f
        while (x <= w + 4f) {
            val u = x / w * 6.283f
            val n = 0.6f * (1f - abs(sin(u * 1.7f + seed))) + 0.3f * (1f - abs(sin(u * 3.9f + seed * 2f))) + 0.1f * sin(u * 9f + seed)
            path.lineTo(x, baseY - amp * n)
            x += 3f
        }
        path.lineTo(w, bottomY)
        path.close()
        p.shader = LinearGradient(0f, baseY - amp, 0f, bottomY, top, bottom, Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
    }

    private fun buildLake(c: Canvas, w: Float, h: Float) {
        val hz = h * 0.62f
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, hz, intArrayOf(0xFF050B22.toInt(), 0xFF123A7A.toInt(), 0xFF6B97CC.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, hz, p)
        val mx = w * 0.62f
        val my = h * 0.2f
        p.shader = RadialGradient(mx, my, h * 0.3f, 0x66C8DFFF, 0x00C8DFFF, Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, h * 0.3f, p)
        p.shader = null
        p.color = 0xFFF6F9FF.toInt()
        c.drawCircle(mx, my, h * 0.045f, p)
        ridge(c, w, hz - h * 0.04f, h * 0.2f, 1.3f, 0xFFDCE8F8.toInt(), 0xFF2A4A7C.toInt(), hz + 2f)
        ridge(c, w, hz + h * 0.005f, h * 0.15f, 4.1f, 0xFF9DB6D8.toInt(), 0xFF15294F.toInt(), hz + 2f)
        p.shader = LinearGradient(0f, hz, 0f, h, 0xFF1B4E92.toInt(), 0xFF050E26.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, hz, w, h, p)
        p.shader = null
    }

    private fun buildNight(c: Canvas, w: Float, h: Float) {
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, h, 0xFF02030C.toInt(), 0xFF0A1A33.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        ridge(c, w, h * 0.92f, h * 0.1f, 2f, 0xFF0A1424.toInt(), 0xFF02040A.toInt(), h)
    }

    private fun starfield(c: Canvas, w: Int, h: Int, t: Float) {
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        for (s in stars) {
            p.alpha = (140 + 110 * sin(t * 1.4f + s[3])).toInt().coerceIn(0, 255)
            c.drawCircle(s[0] * w, s[1] * h, s[2], p)
        }
        p.alpha = 255
    }

    private fun lake(c: Canvas, w: Int, h: Int, t: Float) {
        drawBase(c, w, h)
        starfield(c, w, h, t)
        val hz = h * 0.62f
        val mx = w * 0.62f
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.5f
        p.color = Color.WHITE
        for (i in 0 until 16) {
            val d = (i + 1) / 16f
            val y = hz + (h - hz) * d * d
            val half = w * (0.03f + 0.11f * d)
            val cx = mx + sin(t * 0.6f + i * 1.7f) * w * 0.02f * d
            p.alpha = (80 * (1f - d * 0.6f)).toInt()
            c.drawLine(cx - half, y, cx + half, y, p)
        }
        p.alpha = 255
        p.style = Paint.Style.FILL
    }

    private fun aurora(c: Canvas, w: Int, h: Int, t: Float) {
        drawBase(c, w, h)
        starfield(c, w, h, t)
        val cols = intArrayOf(0xAA3CFFB0.toInt(), 0x8855C8FF.toInt(), 0x77B07CFF.toInt())
        p.style = Paint.Style.FILL
        for (k in 0 until 3) {
            val top0 = h * (0.2f + 0.07f * k)
            val path = Path()
            path.moveTo(0f, h * 0.72f)
            var x = 0f
            while (x <= w + 1f) {
                val u = x / w
                path.lineTo(x, top0 + h * 0.06f * sin(u * 5f + t * 0.35f + k * 2f) + h * 0.03f * sin(u * 11f - t * 0.5f + k))
                x += w / 48f
            }
            path.lineTo(w.toFloat(), h * 0.72f)
            path.close()
            p.shader = LinearGradient(0f, h * 0.15f, 0f, h * 0.72f, cols[k], cols[k] and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawPath(path, p)
        }
        p.shader = null
    }

    private fun blob(color: Int): Bitmap {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val q = Paint(Paint.ANTI_ALIAS_FLAG)
        q.shader = RadialGradient(128f, 128f, 128f, color, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
        Canvas(b).drawCircle(128f, 128f, 128f, q)
        return b
    }

    private fun drift(c: Canvas, w: Int, h: Int, t: Float) {
        c.drawColor(0xFF070A14.toInt())
        val bl = blobs ?: arrayOf(blob(0xFF5B3DF0.toInt()), blob(0xFF1E88FF.toInt()), blob(0xFFFF4F9A.toInt())).also { blobs = it }
        val r = h * 0.8f
        val rect = RectF()
        for (i in 0 until 3) {
            val cx = w * (0.5f + 0.35f * sin(t * 0.11f + i * 2.1f))
            val cy = h * (0.5f + 0.3f * cos(t * 0.09f + i * 1.3f))
            rect.set(cx - r, cy - r, cx + r, cy + r)
            c.drawBitmap(bl[i], null, rect, bmpPaint)
        }
    }
}
