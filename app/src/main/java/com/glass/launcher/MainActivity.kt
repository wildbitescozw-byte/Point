package com.glass.launcher

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.SoundPool
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextUtils
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun sinf(x: Float): Float = Math.sin(x.toDouble()).toFloat()
fun cosf(x: Float): Float = Math.cos(x.toDouble()).toFloat()

class App(val label: String, val pkg: String, val icon: Drawable)

// Lets Glass Home read "now playing" info from other music apps once the user grants notification access.
class NowPlayingService : NotificationListenerService()

object Wall {
    var full: Bitmap? = null
    var prevFull: Bitmap? = null
    var blur: Bitmap? = null
    var prevBlur: Bitmap? = null
    var mix = 1f
    var w = 1920
    var h = 1080
    var ox = 0
    var oy = 0
    var scrim = 0x55000000
}

// Draws the blurred wallpaper slice that sits behind a glass surface (with crossfade support).
fun drawBackdrop(canvas: Canvas, v: View, path: Path, bp: Paint, dst: Rect, loc: IntArray) {
    val bl = Wall.blur ?: return
    v.getLocationInWindow(loc)
    canvas.save()
    canvas.clipPath(path)
    canvas.translate(-(loc[0] - Wall.ox).toFloat(), -(loc[1] - Wall.oy).toFloat())
    dst.set(0, 0, Wall.w, Wall.h)
    val pbl = Wall.prevBlur
    val fading = pbl != null && Wall.mix < 1f
    if (fading && pbl != null) {
        bp.alpha = 255
        canvas.drawBitmap(pbl, null, dst, bp)
    }
    bp.alpha = if (fading) (Wall.mix * 255f).toInt() else 255
    canvas.drawBitmap(bl, null, dst, bp)
    bp.alpha = 255
    canvas.restore()
}

class WallView(c: Context) : View(c) {
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    private val s = Paint()
    private val dst = Rect()
    private val o = IntArray(2)
    override fun onDraw(canvas: Canvas) {
        Wall.w = width
        Wall.h = height
        getLocationInWindow(o)
        Wall.ox = o[0]
        Wall.oy = o[1]
        dst.set(0, 0, width, height)
        val b = Wall.full
        val pb = Wall.prevFull
        val fading = pb != null && Wall.mix < 1f
        if (fading && pb != null) {
            p.alpha = 255
            canvas.drawBitmap(pb, null, dst, p)
        }
        if (b != null) {
            p.alpha = if (fading) (Wall.mix * 255f).toInt() else 255
            canvas.drawBitmap(b, null, dst, p)
        }
        p.alpha = 255
        s.color = Wall.scrim
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), s)
    }
}

// A frosted-glass panel: draws a blurred slice of the wallpaper behind itself.
class GlassBox(c: Context) : LinearLayout(c) {
    private val path = Path()
    private val rect = RectF()
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = Rect()
    private val loc = IntArray(2)
    private val dens = c.resources.displayMetrics.density
    private val radius = 24f * dens

    init {
        setWillNotDraw(false)
        line.style = Paint.Style.STROKE
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        path.reset()
        path.addRoundRect(rect, radius, radius, Path.Direction.CW)
        drawBackdrop(canvas, this, path, bp, dst, loc)
        val f = isFocused
        fill.color = if (f) 0x45FFFFFF else 0x22FFFFFF
        canvas.drawRoundRect(rect, radius, radius, fill)
        val sw = if (f) 3f * dens else 1.2f * dens
        line.strokeWidth = sw
        line.color = if (f) 0xF0B8FFE3.toInt() else 0x44FFFFFF
        rect.inset(sw / 2f, sw / 2f)
        canvas.drawRoundRect(rect, radius, radius, line)
        super.onDraw(canvas)
    }
}

// Big iOS-style glass digits: frosted body, glossy tint and a bright outer rim.
class GlassClock(c: Context) : View(c) {
    var text: String = ""
        set(v) {
            field = v
            invalidate()
        }
    private val dens = c.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val b = Rect()
    private val loc = IntArray(2)
    private val m = Matrix()

    init {
        p.typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
        p.textAlign = Paint.Align.LEFT
        p.strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty() || width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val rim = 2.2f * dens
        val pad = rim + 1.5f * dens
        p.shader = null
        p.clearShadowLayer()
        p.style = Paint.Style.FILL
        var size = h * 1.25f
        p.textSize = size
        val tw = p.measureText(text)
        val maxW = w - 2f * pad
        if (tw > maxW) {
            size *= maxW / tw
            p.textSize = size
        }
        p.getTextBounds(text, 0, text.length, b)
        val x = pad - b.left
        val y = h / 2f - (b.top + b.bottom) / 2f

        p.color = 0x22000000
        p.setShadowLayer(16f * dens, 0f, 6f * dens, 0x66000000)
        canvas.drawText(text, x, y, p)
        p.clearShadowLayer()

        p.style = Paint.Style.STROKE
        p.strokeWidth = rim * 2f
        p.color = 0xCCFFFFFF.toInt()
        canvas.drawText(text, x, y, p)
        p.style = Paint.Style.FILL

        val bl = Wall.blur
        if (bl != null) {
            getLocationInWindow(loc)
            m.reset()
            m.setScale(Wall.w.toFloat() / bl.width, Wall.h.toFloat() / bl.height)
            m.postTranslate(-(loc[0] - Wall.ox).toFloat(), -(loc[1] - Wall.oy).toFloat())
            val sh = BitmapShader(bl, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            sh.setLocalMatrix(m)
            p.shader = sh
            p.color = 0xFFFFFFFF.toInt()
        } else {
            p.shader = null
            p.color = 0xFF6C7A89.toInt()
        }
        canvas.drawText(text, x, y, p)

        p.shader = LinearGradient(0f, y + b.top, 0f, y + b.bottom, 0x77FFFFFF, 0x22FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawText(text, x, y, p)
        p.shader = null
    }
}

class ProgressBarView(c: Context) : View(c) {
    var fraction: Float = 0f
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        p.color = 0x44FFFFFF
        r.set(0f, 0f, w, h)
        canvas.drawRoundRect(r, h / 2f, h / 2f, p)
        p.color = 0xFF3DDC97.toInt()
        r.set(0f, 0f, w * fraction.coerceIn(0f, 1f), h)
        canvas.drawRoundRect(r, h / 2f, h / 2f, p)
    }
}

// Tiny bouncing bars shown while music plays.
class EqView(c: Context) : View(c) {
    var active = false
        set(v) {
            field = v
            invalidate()
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = SystemClock.uptimeMillis() / 1000f
        val n = 4
        val bw = w / (n * 2f - 1f)
        p.color = 0xFF3DDC97.toInt()
        for (i in 0 until n) {
            val k = if (active) 0.30f + 0.70f * Math.abs(sinf(t * (3.1f + i * 0.9f) + i * 1.7f)) else 0.2f
            val bh = h * k
            val x = i * bw * 2f
            r.set(x, h - bh, x + bw, h)
            canvas.drawRoundRect(r, bw / 2f, bw / 2f, p)
        }
        if (active && isShown) postInvalidateDelayed(60L)
    }
}

// Animated sun by day, crescent moon with twinkling stars by night.
class DayNightView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var night = 0f
    private var nightTarget = 0f
    private var lastMs = 0L
    private val moon = Path()
    private val cut = Path()

    fun setNight(n: Boolean) {
        nightTarget = if (n) 1f else 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastMs == 0L) 0f else ((now - lastMs) / 1000f).coerceIn(0f, 0.2f)
        lastMs = now
        if (night < nightTarget) night = Math.min(nightTarget, night + dt / 0.9f)
        else if (night > nightTarget) night = Math.max(nightTarget, night - dt / 0.9f)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = Math.min(w, h) / 2f
        val t = now / 1000f
        val day = 1f - night
        if (day > 0.01f) drawSun(canvas, cx, cy, r, t, day)
        if (night > 0.01f) drawMoon(canvas, cx, cy, r, t, night)
        postInvalidateDelayed(50L)
    }

    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float, a: Float) {
        canvas.save()
        val sc = 0.7f + 0.3f * a
        canvas.scale(sc, sc, cx, cy)
        val pulse = 0.85f + 0.15f * sinf(t * 2f)
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r * pulse, 0x99FFC93C.toInt(), 0x00FFC93C, Shader.TileMode.CLAMP)
        p.alpha = (255 * a).toInt()
        canvas.drawCircle(cx, cy, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.11f
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0xFFFFB02E.toInt()
        p.alpha = (255 * a).toInt()
        canvas.save()
        canvas.rotate(t * 18f, cx, cy)
        for (i in 0 until 8) {
            val ang = (i * Math.PI / 4.0).toFloat()
            val l0 = r * 0.56f
            val l1 = r * (0.76f + 0.1f * sinf(t * 3f + i))
            canvas.drawLine(cx + cosf(ang) * l0, cy + sinf(ang) * l0, cx + cosf(ang) * l1, cy + sinf(ang) * l1, p)
        }
        canvas.restore()
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx - r * 0.1f, cy - r * 0.1f, r * 0.42f, 0xFFFFF1A8.toInt(), 0xFFFF9F1C.toInt(), Shader.TileMode.CLAMP)
        p.alpha = (255 * a).toInt()
        canvas.drawCircle(cx, cy, r * 0.38f, p)
        p.shader = null
        p.alpha = 255
        canvas.restore()
    }

    private fun drawMoon(canvas: Canvas, cx: Float, cy: Float, r: Float, t: Float, a: Float) {
        canvas.save()
        val sc = 0.7f + 0.3f * a
        canvas.scale(sc, sc, cx, cy)
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r, 0x886FA8FF.toInt(), 0x006FA8FF, Shader.TileMode.CLAMP)
        p.alpha = (255 * a).toInt()
        canvas.drawCircle(cx, cy, r, p)
        p.shader = null
        moon.reset()
        moon.addCircle(cx, cy, r * 0.5f, Path.Direction.CW)
        cut.reset()
        cut.addCircle(cx + r * 0.28f, cy - r * 0.12f, r * 0.44f, Path.Direction.CW)
        moon.op(cut, Path.Op.DIFFERENCE)
        p.shader = LinearGradient(cx - r * 0.5f, cy - r * 0.5f, cx + r * 0.3f, cy + r * 0.5f, 0xFFFFFFFF.toInt(), 0xFFB9CCFF.toInt(), Shader.TileMode.CLAMP)
        p.alpha = (255 * a).toInt()
        canvas.drawPath(moon, p)
        p.shader = null
        // twinkling stars
        val sx = floatArrayOf(0.66f, 0.82f, 0.40f)
        val sy = floatArrayOf(-0.55f, 0.10f, -0.80f)
        val col = intArrayOf(0xFFFFE9A8.toInt(), 0xFFFFC8F0.toInt(), 0xFFB8E6FF.toInt())
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 3) {
            val tw = 0.35f + 0.65f * (0.5f + 0.5f * sinf(t * 2.3f + i * 2.1f))
            val s = r * (0.10f + 0.05f * tw)
            val x = cx + sx[i] * r
            val y = cy + sy[i] * r
            p.strokeWidth = r * 0.06f
            p.color = col[i]
            p.alpha = (255 * a * tw).toInt()
            canvas.drawLine(x - s, y, x + s, y, p)
            canvas.drawLine(x, y - s, x, y + s, p)
        }
        p.style = Paint.Style.FILL
        p.alpha = 255
        canvas.restore()
    }
}

// Ambient screensaver: dimmed wallpaper, slow drifting glows and a floating clock.
// Subtle living-forest layer drawn above the wallpaper: birds, light shafts, mist and drifting motes.
class ForestView(c: Context) : View(c) {
    var enabled = true
    var birdsOn = true
    var fxOn = true
    var activity = 1
    var intensity = 1
    var calm = 1f
    private var running = false
    private var unit = 1f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val shaftPath = Path()
    private val oval = RectF()
    private val rnd = java.util.Random()
    private var lastMs = 0L
    private var clock = 0f
    private var nextSpawn = 0f
    private var shaft: LinearGradient? = null
    private var mist: RadialGradient? = null
    private var mistR = 1f
    private val mx = FloatArray(28)
    private val my = FloatArray(28)
    private val mph = FloatArray(28)
    private val msp = FloatArray(28)

    private class Bird {
        var x = 0f
        var y = 0f
        var baseY = 0f
        var vx = 0f
        var size = 0f
        var depth = 0f
        var phase = 0f
        var flapRate = 16f
        var amp = 1f
        var flapping = true
        var timer = 1f
        var wob = 0f
        var wobSpeed = 0.5f
        var alpha = 120
    }

    private val birds = ArrayList<Bird>()

    fun setRunning(r: Boolean) {
        val was = running
        running = r
        lastMs = 0L
        if (r && !was) nextSpawn = clock + 6f + rnd.nextFloat() * 10f
        if (!r) birds.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w <= 0 || h <= 0) return
        shaft = LinearGradient(0f, 0f, 0f, h * 0.9f, 0x66FFF1C2, 0x00FFF1C2, Shader.TileMode.CLAMP)
        mistR = w * 0.32f
        mist = RadialGradient(0f, 0f, mistR, 0x55E6F4EA, 0x00E6F4EA, Shader.TileMode.CLAMP)
        for (i in 0 until 28) {
            mx[i] = rnd.nextFloat() * w
            my[i] = rnd.nextFloat() * h
            mph[i] = rnd.nextFloat() * 6.28f
            msp[i] = 0.4f + rnd.nextFloat() * 0.8f
        }
    }

    private fun schedule() {
        val lo: Float
        val hi: Float
        when (activity) {
            0 -> { lo = 70f; hi = 160f }
            2 -> { lo = 14f; hi = 45f }
            else -> { lo = 35f; hi = 95f }
        }
        nextSpawn = clock + (lo + rnd.nextFloat() * (hi - lo)) / Math.max(0.2f, calm)
    }

    private fun spawn(w: Int, h: Int) {
        val dir = if (rnd.nextBoolean()) 1f else -1f
        val d0 = rnd.nextFloat()
        val depth = d0 * d0
        val size = (6f + depth * 20f) * unit
        val speed = (45f + depth * 75f + rnd.nextFloat() * 30f) * unit
        val y = h * (0.07f + rnd.nextFloat() * 0.5f)
        val count = if (rnd.nextFloat() < 0.22f && birds.size < 3) 2 else 1
        for (i in 0 until count) {
            val b = Bird()
            b.depth = depth
            b.size = size * (0.92f + rnd.nextFloat() * 0.16f)
            b.vx = dir * speed * (0.95f + rnd.nextFloat() * 0.1f)
            b.x = if (dir > 0f) -size * 3f - i * size * 3.5f else w + size * 3f + i * size * 3.5f
            b.baseY = y + (if (i == 0) 0f else (rnd.nextFloat() - 0.5f) * size * 3f)
            b.y = b.baseY
            b.flapRate = 14f + rnd.nextFloat() * 6f
            b.phase = rnd.nextFloat() * 6.28f
            b.flapping = rnd.nextBoolean()
            b.amp = if (b.flapping) 1f else 0.1f
            b.timer = 1f + rnd.nextFloat() * 2f
            b.wob = rnd.nextFloat() * 6.28f
            b.wobSpeed = 0.3f + rnd.nextFloat() * 0.5f
            b.alpha = (60f + depth * 140f).toInt()
            birds.add(b)
        }
    }

    private fun step(dt: Float, w: Int, h: Int) {
        clock += dt
        if (!birdsOn) {
            birds.clear()
            return
        }
        if (clock >= nextSpawn) {
            if (birds.size < 4) spawn(w, h)
            schedule()
        }
        val it = birds.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.x += b.vx * dt
            b.timer -= dt
            if (b.timer <= 0f) {
                b.flapping = !b.flapping
                b.timer = if (b.flapping) 1.2f + rnd.nextFloat() * 1.8f else 1.5f + rnd.nextFloat() * 2.5f
            }
            val target = if (b.flapping) 1f else 0.1f
            b.amp += (target - b.amp) * Math.min(1f, dt * 4f)
            b.phase += dt * b.flapRate * (0.35f + 0.65f * b.amp)
            b.y = b.baseY + sinf(clock * b.wobSpeed + b.wob) * b.size * 1.3f
            if ((b.vx > 0f && b.x > w + b.size * 4f) || (b.vx < 0f && b.x < -b.size * 4f)) it.remove()
        }
    }

    private fun drawBird(canvas: Canvas, b: Bird) {
        val s = b.size
        val wy = sinf(b.phase) * b.amp * s * 0.55f - s * 0.08f
        val dirS = if (b.vx >= 0f) 1f else -1f
        canvas.save()
        canvas.translate(b.x, b.y)
        p.color = (b.alpha shl 24) or 0x0B1712
        path.reset()
        path.moveTo(-s, wy)
        path.quadTo(-s * 0.5f, -s * 0.32f + wy * 0.35f, 0f, -s * 0.02f)
        path.quadTo(s * 0.5f, -s * 0.32f + wy * 0.35f, s, wy)
        path.quadTo(s * 0.5f, s * 0.02f + wy * 0.2f, 0f, s * 0.2f)
        path.quadTo(-s * 0.5f, s * 0.02f + wy * 0.2f, -s, wy)
        path.close()
        canvas.drawPath(path, p)
        oval.set(-s * 0.2f, -s * 0.04f, s * 0.2f, s * 0.2f)
        canvas.drawOval(oval, p)
        canvas.drawCircle(dirS * s * 0.2f, s * 0.02f, s * 0.09f, p)
        path.reset()
        path.moveTo(-dirS * s * 0.12f, s * 0.1f)
        path.lineTo(-dirS * s * 0.42f, s * 0.2f)
        path.lineTo(-dirS * s * 0.12f, s * 0.22f)
        path.close()
        canvas.drawPath(path, p)
        canvas.restore()
    }

    private fun drawFx(canvas: Canvas, w: Int, h: Int, dt: Float, f: Float) {
        val sh = shaft
        if (sh != null) {
            p.shader = sh
            val len = h * 0.9f
            val dx = h * 0.42f
            val xs = floatArrayOf(0.55f, 0.72f, 0.9f)
            val ws = floatArrayOf(0.07f, 0.04f, 0.09f)
            for (i in 0..2) {
                val a = 0.5f + 0.5f * sinf(clock * (0.15f + 0.05f * i) + i * 2.1f)
                p.alpha = (255f * 0.30f * a * f).toInt().coerceIn(0, 255)
                val sx = w * xs[i] + sinf(clock * 0.04f + i) * w * 0.03f
                val bw = w * ws[i]
                shaftPath.reset()
                shaftPath.moveTo(sx, 0f)
                shaftPath.lineTo(sx + bw, 0f)
                shaftPath.lineTo(sx + bw - dx, len)
                shaftPath.lineTo(sx - dx, len)
                shaftPath.close()
                canvas.drawPath(shaftPath, p)
            }
            p.shader = null
        }
        val ms = mist
        if (ms != null) {
            p.shader = ms
            for (i in 0..2) {
                val a = 0.5f + 0.5f * sinf(clock * 0.11f + i * 1.7f)
                p.alpha = (255f * 0.55f * a * f).toInt().coerceIn(0, 255)
                val x = w * (0.2f + 0.3f * i) + sinf(clock * 0.03f + i * 2f) * w * 0.12f
                val y = h * (0.72f + 0.06f * i)
                canvas.save()
                canvas.translate(x, y)
                canvas.scale(1f, 0.38f)
                canvas.drawCircle(0f, 0f, mistR, p)
                canvas.restore()
            }
            p.shader = null
        }
        val n = intArrayOf(10, 18, 28)[intensity.coerceIn(0, 2)]
        p.color = 0xFFFFF4D6.toInt()
        for (i in 0 until n) {
            mx[i] += sinf(clock * msp[i] + mph[i]) * dt * 10f * unit
            my[i] += msp[i] * dt * 4f * unit
            if (my[i] > h) { my[i] = 0f; mx[i] = rnd.nextFloat() * w }
            if (mx[i] < 0f) mx[i] += w.toFloat()
            if (mx[i] > w) mx[i] -= w.toFloat()
            val a = 0.5f + 0.5f * sinf(clock * msp[i] * 2f + mph[i])
            p.alpha = ((50f + 110f * a) * f).toInt().coerceIn(0, 255)
            canvas.drawCircle(mx[i], my[i], (1.2f + msp[i] * 1.3f) * unit, p)
        }
        p.alpha = 255
    }

    override fun onDraw(canvas: Canvas) {
        if (!enabled || !running || width == 0) return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastMs == 0L) 0f else Math.min(0.25f, (now - lastMs) / 1000f)
        lastMs = now
        val w = width
        val h = height
        unit = h / 1080f
        step(dt, w, h)
        val f = floatArrayOf(0.55f, 1f, 1.5f)[intensity.coerceIn(0, 2)] * calm
        if (fxOn) drawFx(canvas, w, h, dt, f)
        for (b in birds) drawBird(canvas, b)
        val d = if (birds.isNotEmpty()) 33L else if (fxOn) 66L else 400L
        postInvalidateDelayed(d)
    }
}

class AmbientView(c: Context) : View(c) {
    var timeText = ""
    var dateText = ""
    var noteText = ""
    var mode = 0
    var art: Bitmap? = null
    private val artPath = Path()
    private val artRect = RectF()
    private val artDst = Rect()
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shaders = arrayOfNulls<RadialGradient>(3)

    init {
        isFocusable = true
        setOnKeyListener { _, _, _ -> true }
        tp.textAlign = Paint.Align.CENTER
        tp.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val rr = Math.min(w, h) * 0.62f
        shaders[0] = RadialGradient(0f, 0f, rr, 0x772EE6A6, 0x002EE6A6, Shader.TileMode.CLAMP)
        shaders[1] = RadialGradient(0f, 0f, rr, 0x772F6BFF, 0x002F6BFF, Shader.TileMode.CLAMP)
        shaders[2] = RadialGradient(0f, 0f, rr, 0x668A4DFF, 0x008A4DFF, Shader.TileMode.CLAMP)
    }

    private fun orb(canvas: Canvas, i: Int, x: Float, y: Float) {
        val s = shaders[i] ?: return
        val rr = Math.min(width, height) * 0.62f
        canvas.save()
        canvas.translate(x, y)
        p.shader = s
        canvas.drawCircle(0f, 0f, rr, p)
        p.shader = null
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val t = SystemClock.uptimeMillis() / 1000f
        if (mode == 1 || mode == 3) return
        if (mode == 2) {
            val cx = w * (0.86f + 0.02f * sinf(t * 0.02f))
            val cy = h * (0.9f + 0.02f * cosf(t * 0.017f))
            tp.textSize = h * 0.07f
            tp.color = 0xAAFFFFFF.toInt()
            canvas.drawText(timeText, cx, cy, tp)
            tp.textSize = h * 0.028f
            tp.color = 0x88FFFFFF.toInt()
            canvas.drawText(dateText, cx, cy + h * 0.04f, tp)
            postInvalidateDelayed(1000L)
            return
        }
        canvas.drawColor(0xB0000000.toInt())
        orb(canvas, 0, w * (0.28f + 0.18f * sinf(t * 0.11f)), h * (0.35f + 0.18f * cosf(t * 0.08f)))
        orb(canvas, 1, w * (0.72f + 0.16f * cosf(t * 0.09f)), h * (0.68f + 0.16f * sinf(t * 0.12f)))
        orb(canvas, 2, w * (0.55f + 0.20f * sinf(t * 0.07f)), h * (0.25f + 0.14f * cosf(t * 0.10f)))
        val x = w * (0.5f + 0.10f * sinf(t * 0.035f))
        val y = h * (0.48f + 0.07f * cosf(t * 0.027f))
        val cover = art
        if (cover != null) {
            val sz = h * 0.13f
            val left = x - sz / 2f
            val top = y - h * 0.19f - sz
            artRect.set(left, top, left + sz, top + sz)
            artPath.reset()
            artPath.addRoundRect(artRect, sz * 0.14f, sz * 0.14f, Path.Direction.CW)
            artDst.set(left.toInt(), top.toInt(), (left + sz).toInt(), (top + sz).toInt())
            artPaint.alpha = 150
            canvas.save()
            canvas.clipPath(artPath)
            canvas.drawBitmap(cover, null, artDst, artPaint)
            canvas.restore()
        }
        tp.textSize = h * 0.22f
        tp.color = 0x55FFFFFF
        canvas.drawText(timeText, x, y, tp)
        tp.textSize = h * 0.045f
        tp.color = 0x66FFFFFF
        canvas.drawText(dateText, x, y + h * 0.07f, tp)
        if (noteText.isNotEmpty()) {
            tp.textSize = h * 0.04f
            tp.color = 0x77FFFFFF
            canvas.drawText(noteText, x, y + h * 0.13f, tp)
        }
        postInvalidateDelayed(40L)
    }
}

// Central-lock carousel: the frame stays fixed in the middle while the icons glide behind it.
class AppCarousel(c: Context) : View(c) {
    var apps: List<App> = emptyList()
    var onLaunch: ((App) -> Unit)? = null
    var onStep: (() -> Unit)? = null
    var onLock: (() -> Unit)? = null
    var speed = 13.0
    var iconScale = 1f
    var clearNeighbours = false
    var showName = true
    private val dens = c.resources.displayMetrics.density
    private var pos = 0f
    private var target = 0
    private var pulse = 0f
    private var settled = true
    private var running = false
    private var lastNs = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val tile = RectF()
    private val path = Path()
    private val dst = Rect()
    private val loc = IntArray(2)
    private var glow: RadialGradient? = null
    private var sheen: LinearGradient? = null

    init {
        isFocusable = true
        tp.color = 0xFFFFFFFF.toInt()
        tp.textAlign = Paint.Align.CENTER
        tp.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        tp.setShadowLayer(8f, 0f, 2f, 0x77000000)
        setOnKeyListener { _, keyCode, event -> handleKey(keyCode, event) }
    }

    fun selectedPkg(): String? = if (apps.isNotEmpty() && target in apps.indices) apps[target].pkg else null

    fun updateApps(list: List<App>, startPkg: String? = null) {
        val keep = selectedPkg() ?: startPkg
        apps = list
        var t = 0
        if (keep != null) {
            val k = list.indexOfFirst { it.pkg == keep }
            if (k >= 0) t = k
        }
        target = t
        pos = t.toFloat()
        settled = true
        invalidate()
    }

    private fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        val mine = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!mine) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> move(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(1)
            else -> {
                if (event.repeatCount == 0 && apps.isNotEmpty()) onLaunch?.invoke(apps[target])
            }
        }
        return true
    }

    private fun move(dir: Int) {
        if (apps.isEmpty()) return
        val t = (target + dir).coerceIn(0, apps.size - 1)
        if (t == target) {
            pulse = 0.5f
            kick()
            return
        }
        target = t
        onStep?.invoke()
        kick()
    }

    private val step = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = ((now - lastNs) / 1e9f).coerceIn(0f, 0.05f)
            lastNs = now
            var again = false
            val diff = target - pos
            if (Math.abs(diff) > 0.003f) {
                pos += diff * (1f - Math.exp(-speed * dt).toFloat())
                again = true
                settled = false
            } else if (!settled) {
                pos = target.toFloat()
                settled = true
                pulse = 1f
                onLock?.invoke()
            }
            if (pulse > 0f) {
                pulse -= dt / 0.45f
                if (pulse < 0f) pulse = 0f else again = true
            }
            invalidate()
            if (again) postOnAnimation(this) else running = false
        }
    }

    private fun kick() {
        if (!running) {
            running = true
            lastNs = System.nanoTime()
            postOnAnimation(step)
        }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val f = h * 0.62f
        glow = RadialGradient(0f, 0f, f, 0x703DDC97, 0x003DDC97, Shader.TileMode.CLAMP)
        sheen = LinearGradient(0f, -f / 2f, 0f, f / 2f, 0x44FFFFFF, 0x06FFFFFF, Shader.TileMode.CLAMP)
        tp.textSize = h * 0.115f
    }

    override fun onDraw(canvas: Canvas) {
        if (apps.isEmpty() || width == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h * 0.42f
        val f = h * 0.62f
        val rad = f * 0.26f

        // fixed frosted frame
        rect.set(cx - f / 2f, cy - f / 2f, cx + f / 2f, cy + f / 2f)
        path.reset()
        path.addRoundRect(rect, rad, rad, Path.Direction.CW)
        drawBackdrop(canvas, this, path, bp, dst, loc)

        // glow
        val g = glow
        if (g != null) {
            canvas.save()
            canvas.translate(cx, cy)
            p.style = Paint.Style.FILL
            p.shader = g
            p.alpha = (255 * (0.65f + 0.35f * pulse)).toInt()
            canvas.drawCircle(0f, 0f, f, p)
            p.shader = null
            p.alpha = 255
            canvas.restore()
        }

        // icons glide past the frame; the nearest one is largest and brightest
        val gap1 = f * 1.08f
        val gap2 = f * 0.60f
        val base = f * 0.80f * iconScale
        val first = Math.max(0, (pos - 5f).toInt())
        val last = Math.min(apps.size - 1, (pos + 5f).toInt() + 1)
        val order = (first..last).sortedByDescending { Math.abs(it - pos) }
        p.style = Paint.Style.FILL
        for (i in order) {
            val d = i - pos
            val ad = Math.abs(d)
            val s = 0.36f + 0.64f / (1f + 1.6f * ad * ad)
            val a = if (clearNeighbours) 0.32f + 0.68f / (1f + 0.5f * ad * ad) else 0.15f + 0.85f / (1f + 0.9f * ad * ad)
            val off = if (ad <= 1f) ad * gap1 else gap1 + (ad - 1f) * gap2
            val x = cx + (if (d < 0f) -off else off)
            val size = base * s
            val tl = size * 1.22f
            tile.set(x - tl / 2f, cy - tl / 2f, x + tl / 2f, cy + tl / 2f)
            p.color = ((0x2A * a).toInt() shl 24) or 0xFFFFFF
            canvas.drawRoundRect(tile, tl * 0.26f, tl * 0.26f, p)
            val dr = apps[i].icon
            val half = size / 2f
            dr.setBounds((x - half).toInt(), (cy - half).toInt(), (x + half).toInt(), (cy + half).toInt())
            dr.setAlpha((255 * a).toInt())
            dr.draw(canvas)
        }

        // glass ring on top, with a pulse when an app locks in
        val ps = 1f + 0.07f * pulse
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(ps, ps)
        rect.set(-f / 2f, -f / 2f, f / 2f, f / 2f)
        val sh = sheen
        if (sh != null) {
            p.shader = sh
            p.alpha = 255
            canvas.drawRoundRect(rect, rad, rad, p)
            p.shader = null
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = (if (isFocused) 3f else 2f) * dens
        p.color = if (isFocused) 0xFFB8FFE3.toInt() else 0x99B8FFE3.toInt()
        canvas.drawRoundRect(rect, rad, rad, p)
        canvas.restore()
        if (pulse > 0f) {
            p.strokeWidth = 2f * dens
            p.color = ((pulse * 0.55f * 255f).toInt() shl 24) or 0x3DDC97
            val ex = f / 2f + f * 0.22f * (1f - pulse)
            rect.set(cx - ex, cy - ex, cx + ex, cy + ex)
            canvas.drawRoundRect(rect, rad + (ex - f / 2f), rad + (ex - f / 2f), p)
        }
        p.style = Paint.Style.FILL

        // selected app name
        val ni = Math.round(pos).coerceIn(0, apps.size - 1)
        val fr = Math.abs(pos - ni)
        val na = (1f - fr * 2f).coerceIn(0f, 1f)
        tp.alpha = (255 * na).toInt()
        if (showName) canvas.drawText(apps[ni].label, cx, h * 0.93f, tp)
    }
}

class MainActivity : Activity() {
    private val vlc = "org.videolan.vlc"
    private lateinit var root: FrameLayout
    private lateinit var colView: LinearLayout
    private lateinit var clock: GlassClock
    private lateinit var date: TextView
    private lateinit var dn: DayNightView
    private lateinit var greet: TextView
    private lateinit var sub: TextView
    private lateinit var carousel: AppCarousel
    private lateinit var cardHolder: FrameLayout
    private lateinit var wpPill: GlassBox
    private lateinit var optPill: GlassBox
    private lateinit var ambientView: AmbientView
    private lateinit var forest: ForestView
    private var resumed = false
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private val prefs: SharedPreferences by lazy { getSharedPreferences("glass", Context.MODE_PRIVATE) }

    private val wps = intArrayOf(R.drawable.wp1, R.drawable.wp2, R.drawable.wp3, R.drawable.wp4)
    private val wpNames = arrayOf("Autumn", "Bamboo", "Dunes", "Meadow")
    private var wpIdx = 1
    private var wpAnim: ValueAnimator? = null

    private val favs = listOf(
        "org.videolan.vlc",
        "com.google.android.youtube.tv", "com.google.android.youtube",
        "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus", "com.android.chrome"
    )

    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            syncForestSound()
            handler.postDelayed(this, 15000)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun focusScale(v: View) {
        v.setOnFocusChangeListener { view, f ->
            val s = if (f) 1.07f else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(140).start()
        }
    }

    private fun roundClip(v: View, r: Int) {
        val rad = dp(r).toFloat()
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, rad)
            }
        }
    }

    private fun pill(text: String, onClick: () -> Unit): GlassBox {
        val b = GlassBox(this)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(16), dp(9), dp(16), dp(9))
        b.isFocusable = true
        b.isClickable = true
        b.setOnClickListener { onClick() }
        focusScale(b)
        val t = TextView(this)
        t.text = text
        t.setTextColor(0xFFF5F8FC.toInt())
        t.textSize = 15f
        b.addView(t)
        return b
    }

    private fun plp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.setMargins(dp(6), dp(6), dp(6), dp(6))
        return lp
    }

    // ================= create =================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prev = prefs.getString("crash", null)
        if (prev != null) {
            prefs.edit().remove("crash").commit()
            val et = TextView(this)
            et.setTextColor(0xFFFFFFFF.toInt())
            et.setBackgroundColor(0xFF200000.toInt())
            et.textSize = 15f
            et.setPadding(dp(30), dp(30), dp(30), dp(30))
            et.text = "Glass Home crashed last time. Take a screenshot of this and send it to Claude, then reopen the app.\n\n" + prev
            setContentView(et)
            return
        }
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, ex ->
            try {
                prefs.edit().putString("crash", Log.getStackTraceString(ex).take(1200)).commit()
            } catch (x: Throwable) {}
            old?.uncaughtException(th, ex)
        }

        root = FrameLayout(this)
        root.addView(WallView(this), FrameLayout.LayoutParams(-1, -1))
        forest = ForestView(this)
        root.addView(forest, FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this)
        colView = col
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(44), dp(18), dp(44), dp(10))
        col.clipChildren = false

        // top-right buttons
        val head = LinearLayout(this)
        head.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        head.clipChildren = false
        wpPill = pill("Wallpaper") { openWallpaperPanel() }
        optPill = pill("Options") { openOptionsPanel() }
        head.addView(wpPill, plp())
        head.addView(optPill, plp())
        head.addView(pill("Settings") {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e: Exception) {}
        }, plp())
        col.addView(head, LinearLayout.LayoutParams(-1, -2))

        val topRow = LinearLayout(this)
        topRow.orientation = LinearLayout.HORIZONTAL
        topRow.clipChildren = false

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        left.clipChildren = false

        // date + AM/PM + animated day/night icon
        val dateRow = LinearLayout(this)
        dateRow.orientation = LinearLayout.HORIZONTAL
        dateRow.gravity = Gravity.CENTER_VERTICAL
        date = TextView(this)
        date.setTextColor(0xE6FFFFFF.toInt())
        date.textSize = 20f
        date.gravity = Gravity.START
        date.setPadding(dp(3), 0, 0, 0)
        date.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        date.setShadowLayer(8f, 0f, 2f, 0x66000000)
        dateRow.addView(date, LinearLayout.LayoutParams(-2, -2))
        dn = DayNightView(this)
        val dlp = LinearLayout.LayoutParams(dp(32), dp(32))
        dlp.leftMargin = dp(8)
        dateRow.addView(dn, dlp)
        left.addView(dateRow, LinearLayout.LayoutParams(-2, -2))

        clock = GlassClock(this)
        val clockH = (resources.displayMetrics.heightPixels * 0.22f).toInt()
        left.addView(clock, LinearLayout.LayoutParams(-1, clockH))

        // greeting tab with rotating messages
        val screenW = resources.displayMetrics.widthPixels
        val greetBox = GlassBox(this)
        greetBox.orientation = LinearLayout.VERTICAL
        greetBox.gravity = Gravity.START
        greetBox.setPadding(dp(22), dp(8), dp(22), dp(10))
        greet = TextView(this)
        greet.textSize = 22f
        greet.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        greet.setShadowLayer(8f, 0f, 2f, 0x66000000)
        greet.maxWidth = (screenW * 0.44f).toInt()
        greetBox.addView(greet)
        sub = TextView(this)
        sub.textSize = 15f
        sub.setTextColor(0xE0FFFFFF.toInt())
        sub.maxLines = 2
        sub.ellipsize = TextUtils.TruncateAt.END
        sub.maxWidth = (screenW * 0.44f).toInt()
        sub.setShadowLayer(6f, 0f, 1f, 0x66000000)
        greetBox.addView(sub)
        left.addView(greetBox, LinearLayout.LayoutParams(-2, -2))

        val lp = LinearLayout.LayoutParams(0, -1, 1.7f)
        lp.setMargins(0, dp(4), dp(12), dp(4))
        topRow.addView(left, lp)

        cardHolder = FrameLayout(this)
        cardHolder.clipChildren = false
        cardHolder.addView(musicCard(), FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        val hlp = LinearLayout.LayoutParams(0, -1, 1f)
        hlp.setMargins(dp(12), dp(4), 0, dp(4))
        topRow.addView(cardHolder, hlp)
        col.addView(topRow, LinearLayout.LayoutParams(-1, 0, 1f))

        // central-lock carousel
        carousel = AppCarousel(this)
        carousel.onLaunch = { a -> launch(a.pkg) }
        applyCarousel()
        carousel.onStep = { playGlide() }
        carousel.onLock = {
            playLock()
            if (prefs.getBoolean("remember_app", true)) carousel.selectedPkg()?.let { prefs.edit().putString("last_app", it).apply() }
        }
        val carH = (resources.displayMetrics.heightPixels * 0.33f).toInt()
        col.addView(carousel, LinearLayout.LayoutParams(-1, carH))

        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        ambientView = AmbientView(this)
        ambientView.visibility = View.GONE
        root.addView(ambientView, FrameLayout.LayoutParams(-1, -1))

        setContentView(root)
        wpIdx = prefs.getInt("wp", 1).coerceIn(1, 4)
        applyWallpaper(wpIdx, false)
        initSounds()
        ready = true
        if (prefs.getBoolean("startup_anim", true)) {
            colView.alpha = 0f
            colView.translationY = dp(14).toFloat()
            colView.animate().alpha(1f).translationY(0f).setStartDelay(200).setDuration(1100).start()
            forest.alpha = 0f
            forest.animate().alpha(1f).setStartDelay(500).setDuration(1400).start()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        build()
        if (tracks.isEmpty() && hasAudioPerm()) loadTracks()
        pollExternal()
        refreshMusic()
        handler.post(tick)
        handler.post(progTick)
        handler.postDelayed(msgTick, 600)
        resetIdle()
        resumed = true
        applyForest()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        if (ready) applyForest()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(progTick)
        handler.removeCallbacks(msgTick)
        handler.removeCallbacks(idleRun)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { mp?.release() } catch (e: Throwable) {}
        mp = null
        try { sp?.release() } catch (e: Throwable) {}
        sp = null
        stopForestSound()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!ready) return
        if (ambient) exitAmbient()
        resetIdle()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (ready) {
            loadTracks()
            refreshMusic()
        }
    }

    @Deprecated("Launcher back handling")
    override fun onBackPressed() {
        if (ambient) {
            exitAmbient()
            return
        }
        if (overlay != null) hideOverlay()
    }

    // ================= inactivity / ambient =================
    private var ambient = false
    private var prevFocus: View? = null
    private var pausedByAmbient = 0
    private val idleRun = Runnable { enterAmbient() }

    private fun idleDelay(): Long = prefs.getInt("idle_min", 10) * 60_000L

    private fun applyForest() {
        if (!ready) return
        forest.enabled = prefs.getBoolean("forest_on", true)
        forest.birdsOn = prefs.getBoolean("forest_birds", true)
        forest.fxOn = prefs.getBoolean("forest_fx", true)
        forest.activity = prefs.getInt("forest_act", 1).coerceIn(0, 2)
        forest.intensity = prefs.getInt("forest_int", 1).coerceIn(0, 2)
        var calm = 1f
        var run = resumed
        if (ambient) {
            val mode = prefs.getInt("amb_mode", 0)
            val af = prefs.getInt("amb_forest", 1)
            if (mode == 0 || mode == 3 || af == 0) run = false
            if (af == 1) calm = 0.5f
        }
        forest.calm = calm
        forest.setRunning(run)
        syncForestSound()
    }

    // ---- forest sounds: a quiet looping ambience, separate from music, only while the launcher is visible ----
    private var fsp: MediaPlayer? = null
    private var fspSel = 0

    private fun stopForestSound() {
        try { fsp?.release() } catch (x: Throwable) {}
        fsp = null
        fspSel = 0
    }

    private fun syncForestSound() {
        if (!ready) return
        val sel = prefs.getInt("forest_snd", 0)
        var want = sel in 1..3 && prefs.getBoolean("forest_on", true) && resumed
        if (want && ambient) {
            val m = prefs.getInt("amb_mode", 0)
            if (m == 0 || m == 3 || prefs.getInt("amb_forest", 1) == 0) want = false
        }
        if (want) {
            if (playing) want = false
            val e = ext
            if (e != null && e.playbackState?.state == PlaybackState.STATE_PLAYING) want = false
        }
        if (!want) {
            stopForestSound()
            return
        }
        val v = floatArrayOf(0.12f, 0.25f, 0.45f)[prefs.getInt("forest_vol", 1).coerceIn(0, 2)]
        val cur = fsp
        if (cur == null || fspSel != sel) {
            stopForestSound()
            val res = intArrayOf(0, R.raw.forest_birds, R.raw.forest_woodland, R.raw.forest_dark)[sel]
            val m = try { MediaPlayer.create(this, res) } catch (x: Throwable) { null }
            if (m == null) return
            m.isLooping = true
            m.setVolume(v, v)
            try { m.start() } catch (x: Throwable) {}
            fsp = m
            fspSel = sel
        } else {
            try {
                cur.setVolume(v, v)
                if (!cur.isPlaying) cur.start()
            } catch (x: Throwable) {}
        }
    }

    private fun resetIdle() {
        handler.removeCallbacks(idleRun)
        val d = idleDelay()
        if (ready && d > 0L) handler.postDelayed(idleRun, d)
    }

    private fun enterAmbient() {
        if (ambient || !ready) return
        hideOverlay()
        ambient = true
        ambientView.mode = prefs.getInt("amb_mode", 0)
        ambientView.invalidate()
        applyForest()
        prevFocus = currentFocus
        updateTime()
        ambientView.visibility = View.VISIBLE
        ambientView.alpha = 0f
        ambientView.animate().alpha(1f).setDuration(1600).start()
        colView.animate().alpha(0f).setDuration(1200).start()
        ambientView.requestFocus()
        if (prefs.getBoolean("amb_pause", false)) {
            val e = ext
            if (playing && mp != null) {
                try { mp?.pause() } catch (x: Throwable) {}
                playing = false
                pausedByAmbient = 1
            } else if (e != null && e.playbackState?.state == PlaybackState.STATE_PLAYING) {
                try { e.transportControls.pause() } catch (x: Throwable) {}
                pausedByAmbient = 2
            }
            refreshMusic()
        }
    }

    private fun exitAmbient() {
        if (!ambient) return
        ambient = false
        applyForest()
        ambientView.animate().alpha(0f).setDuration(600).withEndAction {
            if (!ambient) ambientView.visibility = View.GONE
        }.start()
        colView.animate().alpha(1f).setDuration(600).start()
        handler.postDelayed({
            if (!ambient) {
                val pf = prevFocus
                if (pf != null) pf.requestFocus() else carousel.requestFocus()
            }
        }, 400)
        if (pausedByAmbient == 1) {
            try { mp?.start(); playing = true } catch (x: Throwable) {}
        } else if (pausedByAmbient == 2) {
            try { ext?.transportControls?.play() } catch (x: Throwable) {}
        }
        pausedByAmbient = 0
        refreshMusic()
    }

    // ================= sounds =================
    private val sr = 22050
    private var sp: SoundPool? = null
    private var sidGlide = 0
    private var sidLock = 0
    private var lastStream = 0
    private var lastGlideMs = 0L

    private fun synth(kind: Int): ShortArray {
        val dur = if (kind == 0) 0.20 else 0.07
        val n = (sr * dur).toInt()
        val buf = DoubleArray(n)
        val rnd = java.util.Random(11)
        if (kind == 0) {
            // "krrrr": a fast ratchet of tiny metallic ticks that slows down as it locks
            var t = 0.0
            var gap = 0.0042
            while (t < 0.15) {
                val s0 = (t * sr).toInt()
                val len = (sr * 0.006).toInt()
                for (j in 0 until len) {
                    val k = s0 + j
                    if (k >= n) break
                    val env = Math.exp(-j / (sr * 0.0011))
                    val tone = Math.sin(2.0 * Math.PI * 3100.0 * j / sr) * 0.55
                    val noise = (rnd.nextDouble() * 2.0 - 1.0) * 0.45
                    buf[k] += (tone + noise) * env * 0.55
                }
                t += gap
                gap *= 1.07
            }
            val s1 = (0.15 * sr).toInt()
            for (j in 0 until (sr * 0.05).toInt()) {
                val k = s1 + j
                if (k >= n) break
                buf[k] += Math.sin(2.0 * Math.PI * 150.0 * j / sr) * Math.exp(-j / (sr * 0.012)) * 0.7
            }
        } else {
            for (j in 0 until n) {
                val env = Math.exp(-j / (sr * 0.006))
                buf[j] = (Math.sin(2.0 * Math.PI * 1800.0 * j / sr) * 0.6 + Math.sin(2.0 * Math.PI * 900.0 * j / sr) * 0.4) * env * 0.8
            }
        }
        var peak = 0.0001
        for (v in buf) if (Math.abs(v) > peak) peak = Math.abs(v)
        val out = ShortArray(n)
        for (i in 0 until n) {
            var v = buf[i] / peak * 0.85
            val fade = n - i
            if (fade < 120) v *= fade / 120.0
            out[i] = (v * 32767.0).toInt().toShort()
        }
        return out
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        val bb = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + pcm.size * 2)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1.toShort())
        bb.putShort(1.toShort())
        bb.putInt(sr)
        bb.putInt(sr * 2)
        bb.putShort(2.toShort())
        bb.putShort(16.toShort())
        bb.put("data".toByteArray())
        bb.putInt(pcm.size * 2)
        for (v in pcm) bb.putShort(v)
        FileOutputStream(f).use { it.write(bb.array()) }
    }

    private fun initSounds() {
        try {
            val f1 = File(cacheDir, "glass_glide_v1.wav")
            val f2 = File(cacheDir, "glass_lock_v1.wav")
            if (!f1.exists()) writeWav(f1, synth(0))
            if (!f2.exists()) writeWav(f2, synth(1))
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val s = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
            sidGlide = s.load(f1.absolutePath, 1)
            sidLock = s.load(f2.absolutePath, 1)
            sp = s
        } catch (e: Throwable) {}
    }

    private fun sfxVol(): Float = prefs.getInt("sfx_vol", 70) / 100f

    private fun playGlide() {
        if (!prefs.getBoolean("sfx_on", true)) return
        val s = sp ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastGlideMs < 85) return
        lastGlideMs = now
        if (lastStream != 0) s.stop(lastStream)
        val v = sfxVol()
        lastStream = s.play(sidGlide, v, v, 1, 0, 1f)
    }

    private fun playLock() {
        if (!prefs.getBoolean("sfx_on", true)) return
        val s = sp ?: return
        val v = sfxVol() * 0.8f
        s.play(sidLock, v, v, 1, 0, 1f)
    }

    // ================= wallpaper =================
    private fun applyWallpaper(i: Int, animate: Boolean) {
        var full: Bitmap? = decodeWp(wps[i - 1])
        if (full == null) full = gradientBitmap()
        val blur = blurOf(full)
        wpAnim?.cancel()
        if (animate && Wall.full != null) {
            Wall.prevFull = Wall.full
            Wall.prevBlur = Wall.blur
            Wall.full = full
            Wall.blur = blur
            Wall.mix = 0f
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = 650
            a.addUpdateListener {
                Wall.mix = it.animatedValue as Float
                if (Wall.mix >= 1f) {
                    Wall.prevFull = null
                    Wall.prevBlur = null
                }
                invalidateAll(root)
            }
            a.start()
            wpAnim = a
        } else {
            Wall.full = full
            Wall.blur = blur
            Wall.mix = 1f
            Wall.prevFull = null
            Wall.prevBlur = null
            invalidateAll(root)
        }
        Wall.scrim = 0x55000000
        wpIdx = i
        prefs.edit().putInt("wp", i).apply()
    }

    private fun invalidateAll(v: View) {
        v.invalidate()
        if (v is ViewGroup) {
            for (k in 0 until v.childCount) invalidateAll(v.getChildAt(k))
        }
    }

    private fun decodeWp(resId: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inScaled = false
        return try {
            BitmapFactory.decodeResource(resources, resId, o)
        } catch (e: Throwable) {
            null
        }
    }

    private fun thumbOf(k: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inScaled = false
        o.inSampleSize = 8
        return try {
            BitmapFactory.decodeResource(resources, wps[k], o)
        } catch (e: Throwable) {
            null
        }
    }

    private fun blurOf(src: Bitmap): Bitmap {
        var b = src
        while (b.width / 2 >= 100) {
            b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
        }
        return b
    }

    private fun gradientBitmap(): Bitmap {
        val w = 480
        val h = 270
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF0A1128.toInt(), 0xFF14245A.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return b
    }

    // ================= overlays (wallpaper + options) =================
    private var overlay: FrameLayout? = null
    private var overlayOpener: View? = null

    private fun overlayShell(title: String, content: View, widthFrac: Float): FrameLayout {
        val ov = FrameLayout(this)
        ov.setBackgroundColor(0x88000000.toInt())
        ov.isClickable = true
        val box = GlassBox(this)
        box.orientation = LinearLayout.VERTICAL
        box.clipChildren = false
        box.setPadding(dp(26), dp(20), dp(26), dp(22))
        val t = TextView(this)
        t.text = title
        t.setTextColor(0xFFFFFFFF.toInt())
        t.textSize = 22f
        t.setTypeface(null, Typeface.BOLD)
        t.setPadding(dp(4), 0, 0, dp(12))
        box.addView(t)
        box.addView(content)
        val w = (resources.displayMetrics.widthPixels * widthFrac).toInt()
        ov.addView(box, FrameLayout.LayoutParams(w, -2, Gravity.CENTER))
        return ov
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v.visibility == View.VISIBLE && v !is FrameLayout) return v
        if (v is ViewGroup) {
            for (k in 0 until v.childCount) {
                val r = firstFocusable(v.getChildAt(k))
                if (r != null) return r
            }
        }
        return null
    }

    private fun showOverlay(ov: FrameLayout, opener: View?) {
        hideOverlay()
        overlay = ov
        overlayOpener = opener
        root.addView(ov, FrameLayout.LayoutParams(-1, -1))
        colView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        ov.alpha = 0f
        ov.animate().alpha(1f).setDuration(180).start()
        ov.post { firstFocusable(ov)?.requestFocus() }
    }

    private fun hideOverlay() {
        val ov = overlay ?: return
        overlay = null
        root.removeView(ov)
        colView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val o = overlayOpener
        if (o != null) o.requestFocus() else carousel.requestFocus()
    }

    private fun openWallpaperPanel() {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.clipChildren = false
        val labels = ArrayList<TextView>()
        val rings = ArrayList<ImageView>()
        val refresh = {
            for (k in 0 until 4) {
                val on = (k + 1 == wpIdx)
                val g = GradientDrawable()
                g.cornerRadius = dp(12).toFloat()
                g.setColor(0)
                if (on) g.setStroke(dp(3), 0xFF3DDC97.toInt())
                rings[k].foreground = if (on) g else null
                labels[k].setTextColor(if (on) 0xFF3DDC97.toInt() else 0xFFFFFFFF.toInt())
                labels[k].text = if (on) wpNames[k] + "  \u2022  Active" else wpNames[k]
            }
        }
        for (k in 0 until 4) {
            val box = GlassBox(this)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER_HORIZONTAL
            box.setPadding(dp(10), dp(10), dp(10), dp(8))
            box.isFocusable = true
            box.isClickable = true
            focusScale(box)
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setImageBitmap(thumbOf(k))
            roundClip(iv, 12)
            box.addView(iv, LinearLayout.LayoutParams(dp(140), dp(80)))
            val tv = TextView(this)
            tv.textSize = 14f
            tv.setPadding(0, dp(6), 0, 0)
            box.addView(tv)
            box.setOnClickListener {
                applyWallpaper(k + 1, true)
                refresh()
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.setMargins(dp(6), dp(4), dp(6), dp(4))
            row.addView(box, lp)
            labels.add(tv)
            rings.add(iv)
        }
        refresh()
        showOverlay(overlayShell("Wallpaper", row, 0.72f), wpPill)
    }

    private fun optRow(label: String, value: () -> String, action: (TextView) -> Unit): GlassBox {
        val b = GlassBox(this)
        b.orientation = LinearLayout.HORIZONTAL
        b.gravity = Gravity.CENTER_VERTICAL
        b.setPadding(dp(18), dp(8), dp(18), dp(8))
        b.isFocusable = true
        b.isClickable = true
        focusScale(b)
        val l = TextView(this)
        l.text = label
        l.textSize = 16f
        l.setTextColor(0xFFFFFFFF.toInt())
        b.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        val v = TextView(this)
        v.text = value()
        v.textSize = 16f
        v.setTextColor(0xFF3DDC97.toInt())
        b.addView(v)
        b.setOnClickListener {
            action(v)
            v.text = value()
        }
        return b
    }

    private fun openOptionsPanel() {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.clipChildren = false
        val rows = ArrayList<GlassBox>()
        rows.add(optRow("Carousel and sounds", { "Open" }) { openCarouselPanel() })
        rows.add(optRow("Greeting messages", { "Open" }) { openGreetingPanel() })
        rows.add(optRow("Living Forest settings", { "Open" }) { openForestPanel() })
        rows.add(optRow("Ambient mode and inactivity", { "Open" }) { openAmbientPanel() })
        rows.add(optRow("Reminders and appointments", { loadAppts().size.toString() }) {
            showReminders()
        })
        rows.add(optRow("Show other apps' music (notification access)", { if (listenerEnabled()) "On" else "Turn on" }) {
            try {
                startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
            } catch (e: Exception) {
                Toast.makeText(this, "This TV has no notification access screen.", Toast.LENGTH_LONG).show()
            }
        })
        rows.add(optRow("Startup animation", { onOff("startup_anim", true) }) { toggle("startup_anim", true) })
        rows.add(optRow("Close", { "" }) { hideOverlay() })
        for (r in rows) {
            val lp = LinearLayout.LayoutParams(-1, dp(44))
            lp.setMargins(0, dp(3), 0, dp(3))
            col.addView(r, lp)
        }
        showOverlay(overlayShell("Glass Home options", col, 0.62f), optPill)
    }

    private fun applyCarousel() {
        carousel.speed = doubleArrayOf(8.0, 13.0, 20.0)[prefs.getInt("car_speed", 1).coerceIn(0, 2)]
        carousel.iconScale = if (prefs.getInt("car_size", 0) == 1) 1.05f else 1f
        carousel.clearNeighbours = prefs.getInt("car_nb", 0) == 1
        carousel.showName = prefs.getBoolean("car_name", true)
        carousel.invalidate()
    }

    private fun openCarouselPanel() {
        val vols = intArrayOf(20, 40, 60, 80, 100)
        val speeds = arrayOf("Smooth", "Normal", "Quick")
        val rows = ArrayList<GlassBox>()
        rows.add(optRow("Sound effects", { onOff("sfx_on", true) }) { toggle("sfx_on", true) })
        rows.add(optRow("Sound effects volume", { prefs.getInt("sfx_vol", 70).toString() + "%" }) {
            val cur = prefs.getInt("sfx_vol", 70)
            var nx = vols[0]
            for (i in vols.indices) if (vols[i] > cur) { nx = vols[i]; break }
            prefs.edit().putInt("sfx_vol", nx).apply()
            playGlide()
        })
        rows.add(optRow("Glide speed", { speeds[prefs.getInt("car_speed", 1).coerceIn(0, 2)] }) { cyc("car_speed", 1, 3); applyCarousel() })
        rows.add(optRow("Selected icon size", { if (prefs.getInt("car_size", 0) == 1) "Large" else "Standard" }) { cyc("car_size", 0, 2); applyCarousel() })
        rows.add(optRow("Neighbouring apps", { if (prefs.getInt("car_nb", 0) == 1) "Clearer" else "Subtle" }) { cyc("car_nb", 0, 2); applyCarousel() })
        rows.add(optRow("Show app name", { onOff("car_name", true) }) { toggle("car_name", true); applyCarousel() })
        rows.add(optRow("Remember last app", { onOff("remember_app", true) }) { toggle("remember_app", true) })
        rows.add(optRow("Close", { "" }) { hideOverlay() })
        panelList("Carousel and sounds", rows)
    }

    private fun openGreetingPanel() {
        val secs = intArrayOf(10, 20, 30, 60, 120, 300)
        val rows = ArrayList<GlassBox>()
        rows.add(optRow("Message changes every", { prefs.getInt("msg_sec", 20).let { if (it >= 60) (it / 60).toString() + " min" else "$it sec" } }) {
            val cur = prefs.getInt("msg_sec", 20)
            val i = secs.indexOf(cur)
            prefs.edit().putInt("msg_sec", secs[(i + 1) % secs.size]).apply()
            handler.removeCallbacks(msgTick)
            handler.postDelayed(msgTick, 1000)
        })
        rows.add(optRow("Family messages (Nia, Vee)", { onOff("cat_fam", true) }) { toggle("cat_fam", true) })
        rows.add(optRow("Daily life messages", { onOff("cat_day", true) }) { toggle("cat_day", true) })
        rows.add(optRow("Entertainment messages", { onOff("cat_ent", true) }) { toggle("cat_ent", true) })
        rows.add(optRow("Reminders and Nia's age", { onOff("cat_rem", true) }) { toggle("cat_rem", true) })
        rows.add(optRow("Close", { "" }) { hideOverlay() })
        panelList("Greeting messages", rows)
    }

    private fun onOff(k: String, def: Boolean): String = if (prefs.getBoolean(k, def)) "On" else "Off"

    private fun toggle(k: String, def: Boolean) {
        prefs.edit().putBoolean(k, !prefs.getBoolean(k, def)).apply()
    }

    private fun cyc(k: String, def: Int, n: Int) {
        prefs.edit().putInt(k, (prefs.getInt(k, def) + 1) % n).apply()
    }

    private fun panelList(title: String, rows: List<GlassBox>) {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.clipChildren = false
        for (r in rows) {
            val lp = LinearLayout.LayoutParams(-1, dp(44))
            lp.setMargins(0, dp(3), 0, dp(3))
            col.addView(r, lp)
        }
        showOverlay(overlayShell(title, col, 0.62f), optPill)
    }

    private fun openForestPanel() {
        val acts = arrayOf("Low", "Natural", "Lively")
        val ints = arrayOf("Low", "Medium", "High")
        val rows = ArrayList<GlassBox>()
        rows.add(optRow("Living Forest", { onOff("forest_on", true) }) { toggle("forest_on", true); applyForest() })
        rows.add(optRow("Bird animations", { onOff("forest_birds", true) }) { toggle("forest_birds", true); applyForest() })
        rows.add(optRow("Forest ambient effects", { onOff("forest_fx", true) }) { toggle("forest_fx", true); applyForest() })
        rows.add(optRow("Bird activity", { acts[prefs.getInt("forest_act", 1).coerceIn(0, 2)] }) { cyc("forest_act", 1, 3); applyForest() })
        rows.add(optRow("Ambient animation intensity", { ints[prefs.getInt("forest_int", 1).coerceIn(0, 2)] }) { cyc("forest_int", 1, 3); applyForest() })
        val snds = arrayOf("Off", "Morning birdsong", "Woodland", "Dark forest")
        val vols = arrayOf("Low", "Medium", "High")
        rows.add(optRow("Forest sounds", { snds[prefs.getInt("forest_snd", 0).coerceIn(0, 3)] }) { cyc("forest_snd", 0, 4); syncForestSound() })
        rows.add(optRow("Forest sound volume", { vols[prefs.getInt("forest_vol", 1).coerceIn(0, 2)] }) { cyc("forest_vol", 1, 3); syncForestSound() })
        rows.add(optRow("Close", { "" }) { hideOverlay() })
        panelList("Living Forest", rows)
    }

    private fun openAmbientPanel() {
        val modes = arrayOf("Classic", "Pure ambient", "Ambient clock", "Static wallpaper")
        val afs = arrayOf("Off", "Reduced", "Full")
        val mins = intArrayOf(0, 2, 5, 10, 15, 30)
        val rows = ArrayList<GlassBox>()
        rows.add(optRow("Ambient mode", { modes[prefs.getInt("amb_mode", 0).coerceIn(0, 3)] }) { cyc("amb_mode", 0, 4) })
        rows.add(optRow("Inactivity timeout", { val m = prefs.getInt("idle_min", 10); if (m == 0) "Off" else "$m min" }) {
            val cur = prefs.getInt("idle_min", 10)
            val i = mins.indexOf(cur)
            prefs.edit().putInt("idle_min", mins[(i + 1) % mins.size]).apply()
            resetIdle()
        })
        rows.add(optRow("Forest during ambient", { afs[prefs.getInt("amb_forest", 1).coerceIn(0, 2)] }) { cyc("amb_forest", 1, 3) })
        rows.add(optRow("Pause music when ambient starts", { if (prefs.getBoolean("amb_pause", false)) "Pause" else "Keep playing" }) { toggle("amb_pause", false) })
        rows.add(optRow("Show song and artwork (Classic)", { onOff("amb_song", true) }) { toggle("amb_song", true) })
        rows.add(optRow("Start ambient mode now", { "Start" }) {
            hideOverlay()
            handler.postDelayed({ enterAmbient() }, 250)
        })
        rows.add(optRow("Close", { "" }) { hideOverlay() })
        panelList("Ambient mode", rows)
    }

    // ================= reminders =================
    private fun loadAppts(): MutableList<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val raw = prefs.getString("appts", "") ?: ""
        for (line in raw.split("\n")) {
            val i = line.indexOf('|')
            if (i > 0) out.add(Pair(line.substring(0, i), line.substring(i + 1)))
        }
        out.sortBy { it.first }
        return out
    }

    private fun saveAppts(l: List<Pair<String, String>>) {
        prefs.edit().putString("appts", l.joinToString("\n") { it.first + "|" + it.second }).apply()
    }

    private fun showReminders() {
        val list = loadAppts()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outFmt = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())
        val items = ArrayList<String>()
        items.add("+  Add a reminder or appointment")
        for (a in list) {
            val d = try { fmt.parse(a.first) } catch (e: Exception) { null }
            items.add((if (d != null) outFmt.format(d) else a.first) + "  -  " + a.second)
        }
        AlertDialog.Builder(this)
            .setTitle("Reminders and appointments (select one to remove)")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) addReminder() else confirmRemove(which - 1)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun addReminder() {
        val input = EditText(this)
        input.hint = "e.g. Nia clinic visit"
        AlertDialog.Builder(this)
            .setTitle("What is it for?")
            .setView(input)
            .setPositiveButton("Next") { _, _ ->
                val t = input.text.toString().replace('|', ' ').replace('\n', ' ').trim()
                if (t.isNotEmpty()) pickDate(t)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickDate(title: String) {
        val c = Calendar.getInstance()
        DatePickerDialog(this, { _, y, m, d ->
            val cal = Calendar.getInstance()
            cal.clear()
            cal.set(y, m, d)
            val key = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
            val l = loadAppts()
            l.add(Pair(key, title))
            saveAppts(l)
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
            recent.clear()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun confirmRemove(i: Int) {
        val l = loadAppts()
        if (i !in l.indices) return
        AlertDialog.Builder(this)
            .setTitle("Remove this reminder?")
            .setMessage(l[i].second)
            .setPositiveButton("Remove") { _, _ ->
                l.removeAt(i)
                saveAppts(l)
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    // ================= greeting + rotating messages =================
    private class Msg(val text: String, val slots: Int = 15, val day: Int = 0, val weight: Int = 1)

    private val recent = ArrayList<String>()
    private val library = listOf(
        Msg("How is lil Nia doing today?", 15, 0, 2),
        Msg("Has she had her moisturising routine today?", 7, 0, 2),
        Msg("How did Nia sleep last night?", 1, 0, 2),
        Msg("Is Nia's next doctor's visit coming up?"),
        Msg("Have we checked Nia's vaccination schedule?"),
        Msg("Has Nia had a good feed recently?", 7),
        Msg("Tummy time with Nia today?", 3),
        Msg("Time for Nia's bedtime cuddles?", 12),
        Msg("Is Nia snug and warm tonight?", 8),
        Msg("A little song for Nia today?"),
        Msg("Some fresh air with Nia today?", 3),
        Msg("Time for a few new photos of Nia?"),
        Msg("How is Vee doing today?", 15, 0, 2),
        Msg("Has Vee had a good day so far?", 3),
        Msg("Anything nice planned for Vee this week?"),
        Msg("What are we watching tonight?", 12, 0, 2),
        Msg("Time for some music?", 15, 0, 2),
        Msg("Shall we put on something relaxing?", 12),
        Msg("Lights down low for movie time?", 12),
        Msg("Tea or coffee?", 3),
        Msg("Have you had some water today?"),
        Msg("Take a deep breath. You're doing great."),
        Msg("What's the plan for today?", 1),
        Msg("Have we had lunch yet?", 2),
        Msg("Time to stretch your legs.", 2),
        Msg("Dinner plans tonight?", 4),
        Msg("How was the day?", 4),
        Msg("Is everything locked up for the night?", 8),
        Msg("Sleep well tonight.", 8),
        Msg("Happy weekend! Anything fun planned?", 15, 1),
        Msg("Nearly the weekend. Any plans?", 15, 3),
        Msg("A fresh week begins. What comes first?", 1, 2)
    )

    private fun slotNow(): Int {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            h in 5..11 -> 1
            h in 12..16 -> 2
            h in 17..20 -> 4
            else -> 8
        }
    }

    private fun dobCal(): Calendar {
        val c = Calendar.getInstance()
        c.clear()
        c.set(2026, Calendar.AUGUST, 25)
        return c
    }

    private fun todayCal(): Calendar {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c
    }

    private fun daysBetween(a: Calendar, b: Calendar): Int =
        Math.round((b.timeInMillis - a.timeInMillis) / 86400000.0).toInt()

    private fun plural(n: Int, w: String): String = n.toString() + " " + w + (if (n == 1) "" else "s")

    private fun monthsOld(): Int {
        val dob = dobCal()
        val t = todayCal()
        var months = (t.get(Calendar.YEAR) - dob.get(Calendar.YEAR)) * 12 + t.get(Calendar.MONTH) - dob.get(Calendar.MONTH)
        if (t.get(Calendar.DAY_OF_MONTH) < dob.get(Calendar.DAY_OF_MONTH)) months--
        return months
    }

    private fun niaAge(): String {
        val total = daysBetween(dobCal(), todayCal())
        if (total < 14) return plural(total, "day")
        if (total <= 84) {
            val w = total / 7
            val d = total % 7
            return plural(w, "week") + (if (d > 0) " and " + plural(d, "day") else "")
        }
        val months = monthsOld()
        val anchor = dobCal()
        anchor.add(Calendar.MONTH, months)
        val w = daysBetween(anchor, todayCal()) / 7
        return plural(months, "month") + (if (w > 0) " and " + plural(w, "week") else "")
    }

    private fun milestone(): String? {
        val dob = dobCal()
        val t = todayCal()
        val total = daysBetween(dob, t)
        if (total <= 0) return null
        if (t.get(Calendar.DAY_OF_MONTH) == dob.get(Calendar.DAY_OF_MONTH)) {
            val m = monthsOld()
            if (m == 12) return "Happy 1st birthday, Nia!"
            if (m > 0) return "Happy " + m + "-month birthday, Nia!"
        } else if (total % 7 == 0 && total <= 84) {
            return "Happy " + (total / 7) + "-week birthday, Nia!"
        }
        return null
    }

    private fun reminderLines(): List<String> {
        val out = ArrayList<String>()
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val outFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        val today = todayCal()
        for (a in loadAppts()) {
            try {
                val d = fmt.parse(a.first) ?: continue
                val c = Calendar.getInstance()
                c.time = d
                val diff = daysBetween(today, c)
                if (diff == 0) out.add("Today: " + a.second)
                else if (diff == 1) out.add("Tomorrow: " + a.second)
                else if (diff in 2..14) out.add(a.second + " is in " + diff + " days (" + outFmt.format(d) + ")")
            } catch (e: Exception) {}
        }
        return out
    }

    private fun msgCat(t: String): Int = when {
        t.contains("Nia") || t.contains("Vee") -> 0
        t.contains("watching") || t.contains("music") || t.contains("relaxing") || t.contains("movie") -> 2
        else -> 1
    }

    private fun nextMessage(): String {
        val slot = slotNow()
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val weekend = dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
        val pool = ArrayList<String>()
        val remAll = reminderLines()
        val remOn = prefs.getBoolean("cat_rem", true)
        val rem = if (remOn) remAll else ArrayList<String>()
        for (r in rem) {
            pool.add(r)
            pool.add(r)
            pool.add(r)
        }
        val total = daysBetween(dobCal(), todayCal())
        if (remOn && total >= 0) {
            val age = "Nia is " + niaAge() + " old today."
            pool.add(age)
            pool.add(age)
        }
        val ms = if (remOn) milestone() else null
        if (ms != null) {
            for (i in 0 until 4) pool.add(ms)
        }
        var hasVacc = false
        for (r in remAll) {
            val l = r.lowercase()
            if (l.contains("vacc") || l.contains("immun") || l.contains("jab")) hasVacc = true
        }
        for (m in library) {
            if ((m.slots and slot) == 0) continue
            if (m.day == 1 && !weekend) continue
            if (m.day == 2 && dow != Calendar.MONDAY) continue
            if (m.day == 3 && dow != Calendar.FRIDAY) continue
            if (hasVacc && m.text.contains("vaccination schedule")) continue
            val c = msgCat(m.text)
            if (c == 0 && !prefs.getBoolean("cat_fam", true)) continue
            if (c == 1 && !prefs.getBoolean("cat_day", true)) continue
            if (c == 2 && !prefs.getBoolean("cat_ent", true)) continue
            for (i in 0 until m.weight) pool.add(m.text)
        }
        if (pool.isEmpty()) return "Welcome home."
        var fresh = pool.filter { !recent.contains(it) }
        if (fresh.isEmpty()) {
            recent.clear()
            fresh = pool
        }
        val pick = fresh.random()
        recent.add(pick)
        while (recent.size > 14) recent.removeAt(0)
        return pick
    }

    private val msgTick = object : Runnable {
        override fun run() {
            showMessage()
            handler.postDelayed(this, prefs.getInt("msg_sec", 20).coerceIn(10, 600) * 1000L)
        }
    }

    private fun showMessage() {
        val msg = nextMessage()
        if (sub.text.isEmpty()) {
            sub.text = msg
            sub.alpha = 0f
            sub.animate().alpha(1f).setDuration(500).start()
            return
        }
        sub.animate().alpha(0f).setDuration(350).withEndAction {
            sub.text = msg
            sub.animate().alpha(1f).setDuration(500).start()
        }.start()
    }

    private fun updateTime() {
        val now = Date()
        val t = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
        val ampm = SimpleDateFormat("a", Locale.getDefault()).format(now)
        val dstr = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
        clock.text = t
        date.text = dstr + "  \u2022  " + ampm
        ambientView.timeText = t
        ambientView.dateText = dstr
        val showSong = musicPlaying && mTitle.text.isNotEmpty() && prefs.getBoolean("amb_song", true)
        ambientView.noteText = if (showSong) {
            "\u266A  " + mTitle.text + (if (mArtist.text.isNotEmpty()) "  \u2022  " + mArtist.text else "")
        } else ""
        ambientView.art = if (showSong) lastArt else null

        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        dn.setNight(hr < 6 || hr >= 18)
        val part = when {
            hr in 5..11 -> "Good morning"
            hr in 12..16 -> "Good afternoon"
            hr in 17..20 -> "Good evening"
            else -> "Good night"
        }
        val msg = part + ", Mr and Mrs Point"
        greet.text = msg
        val w = greet.paint.measureText(msg)
        greet.paint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xCCCFEFFF.toInt(), 0xFFFFFFFF.toInt()),
            null, Shader.TileMode.CLAMP
        )
    }

    // ================= apps =================
    private fun build() {
        carousel.updateApps(loadApps(), if (prefs.getBoolean("remember_app", true)) prefs.getString("last_app", null) else null)
        updateTime()
        if (currentFocus == null) carousel.requestFocus()
    }

    private fun launch(pkg: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) {
            try { startActivity(i) } catch (e: Exception) {}
        }
    }

    private fun loadApps(): List<App> {
        val pm = packageManager
        val seen = HashSet<String>()
        val out = ArrayList<App>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (r in pm.queryIntentActivities(q, 0)) {
                val pkg = r.activityInfo.packageName
                if (pkg != packageName && seen.add(pkg)) {
                    out.add(App(r.loadLabel(pm).toString(), pkg, r.loadIcon(pm)))
                }
            }
        }
        val idx = { p: String -> val i = favs.indexOf(p); if (i < 0) 999 else i }
        return out.sortedWith(compareBy<App>({ idx(it.pkg) }, { it.label.lowercase() }))
    }

    private fun hasPkg(p: String): Boolean =
        try { packageManager.getPackageInfo(p, 0); true } catch (e: Exception) { false }

    private fun openMusic() {
        if (hasPkg(vlc)) launch(vlc)
    }

    // ================= music player =================
    private class Track(val uri: Uri, val title: String, val artist: String)

    private var tracks = ArrayList<Track>()
    private var mp: MediaPlayer? = null
    private var idx = 0
    private var playing = false
    private var errors = 0
    private var ownArt: Bitmap? = null
    private var lastArt: Bitmap? = null
    private var musicPlaying = false
    private var shuffle = false
    private val history = ArrayList<Int>()
    private lateinit var shufBtn: GlassBox
    private var ext: MediaController? = null
    private var extLastPlayingMs = 0L
    private lateinit var mTitle: TextView
    private lateinit var mArtist: TextView
    private lateinit var mTime: TextView
    private lateinit var mNote: TextView
    private lateinit var mArt: ImageView
    private lateinit var mBar: ProgressBarView
    private lateinit var mEq: EqView
    private lateinit var mPlayIcon: ImageView

    private val progTick = object : Runnable {
        override fun run() {
            pollExternal()
            refreshMusic()
            updateProgress()
            handler.postDelayed(this, 1000)
        }
    }

    private fun bl(weight: Float): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(0, dp(46), weight)
        p.setMargins(dp(3), 0, dp(3), 0)
        return p
    }

    private fun iconBtn(res: Int, onClick: () -> Unit): GlassBox {
        val b = GlassBox(this)
        b.gravity = Gravity.CENTER
        b.isFocusable = true
        b.isClickable = true
        b.setOnClickListener { onClick() }
        focusScale(b)
        val iv = ImageView(this)
        iv.setImageResource(res)
        iv.setColorFilter(0xFFFFFFFF.toInt())
        b.addView(iv, LinearLayout.LayoutParams(dp(24), dp(24)))
        return b
    }

    private fun pickNext(): Int {
        if (!shuffle || tracks.size < 2) return idx + 1
        val recent = history.takeLast(Math.min(tracks.size / 2, 20))
        var n: Int
        var tries = 0
        do {
            n = (Math.random() * tracks.size).toInt()
            tries++
        } while ((n == idx || recent.contains(n)) && tries < 30)
        return n
    }

    private fun updateShuffleUi() {
        val t = shufBtn.getChildAt(0) as TextView
        t.setTextColor(if (shuffle) 0xFF3DDC97.toInt() else 0xFFF5F8FC.toInt())
        t.text = if (shuffle) "Shuffle \u2713" else "Shuffle"
    }

    private fun smallPill(text: String, onClick: () -> Unit): GlassBox {
        val b = pill(text, onClick)
        b.setPadding(dp(4), dp(9), dp(4), dp(9))
        val t = b.getChildAt(0) as TextView
        t.textSize = 13f
        t.maxLines = 1
        return b
    }

    private fun openSongPicker() {
        if (!ensureTracks()) {
            if (hasAudioPerm()) Toast.makeText(this, "No music found on this TV", Toast.LENGTH_SHORT).show()
            return
        }
        val lv = ListView(this)
        val sel = GradientDrawable()
        sel.cornerRadius = dp(12).toFloat()
        sel.setColor(0x333DDC97)
        sel.setStroke(dp(1), 0x883DDC97.toInt())
        lv.selector = sel
        lv.divider = null
        lv.dividerHeight = 0
        lv.overScrollMode = View.OVER_SCROLL_NEVER
        lv.isVerticalScrollBarEnabled = false
        lv.adapter = object : BaseAdapter() {
            override fun getCount(): Int = tracks.size
            override fun getItem(position: Int): Any = tracks[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row: LinearLayout
                val a: TextView
                val b: TextView
                if (convertView == null) {
                    row = LinearLayout(this@MainActivity)
                    row.orientation = LinearLayout.VERTICAL
                    row.setPadding(dp(16), dp(8), dp(16), dp(8))
                    row.minimumHeight = dp(58)
                    row.gravity = Gravity.CENTER_VERTICAL
                    a = TextView(this@MainActivity)
                    a.textSize = 17f
                    a.maxLines = 1
                    a.ellipsize = TextUtils.TruncateAt.END
                    b = TextView(this@MainActivity)
                    b.textSize = 13f
                    b.maxLines = 1
                    b.ellipsize = TextUtils.TruncateAt.END
                    b.setTextColor(0xB3FFFFFF.toInt())
                    row.addView(a)
                    row.addView(b)
                    row.tag = arrayOf(a, b)
                } else {
                    row = convertView as LinearLayout
                    val tv = row.tag as Array<*>
                    a = tv[0] as TextView
                    b = tv[1] as TextView
                }
                val t = tracks[position]
                val cur = position == idx
                a.text = if (cur) "\u266A  " + t.title else t.title
                a.setTextColor(if (cur) 0xFF3DDC97.toInt() else 0xFFFFFFFF.toInt())
                a.setTypeface(null, if (cur) Typeface.BOLD else Typeface.NORMAL)
                b.text = t.artist
                return row
            }
        }
        lv.setSelection(idx)
        lv.setOnItemClickListener { _, _, pos, _ ->
            hideOverlay()
            playIndex(pos)
        }
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        val h = (resources.displayMetrics.heightPixels * 0.55f).toInt()
        wrap.addView(lv, LinearLayout.LayoutParams(-1, h))
        showOverlay(overlayShell("Songs  (" + tracks.size + ")", wrap, 0.5f), null)
    }

    private fun musicCard(): GlassBox {
        val card = GlassBox(this)
        card.orientation = LinearLayout.VERTICAL
        card.clipChildren = false
        card.setPadding(dp(16), dp(14), dp(16), dp(14))

        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL

        val artFrame = FrameLayout(this)
        val abg = GradientDrawable()
        abg.cornerRadius = dp(14).toFloat()
        abg.setColor(0x33FFFFFF)
        artFrame.background = abg
        roundClip(artFrame, 14)
        mArt = ImageView(this)
        mArt.scaleType = ImageView.ScaleType.CENTER_CROP
        artFrame.addView(mArt, FrameLayout.LayoutParams(-1, -1))
        mNote = TextView(this)
        mNote.text = "\u266A"
        mNote.textSize = 30f
        mNote.setTextColor(0xCCFFFFFF.toInt())
        mNote.gravity = Gravity.CENTER
        artFrame.addView(mNote, FrameLayout.LayoutParams(-1, -1))
        top.addView(artFrame, LinearLayout.LayoutParams(dp(76), dp(76)))

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        val trow = LinearLayout(this)
        trow.orientation = LinearLayout.HORIZONTAL
        trow.gravity = Gravity.CENTER_VERTICAL
        mTitle = TextView(this)
        mTitle.setTextColor(0xFFFFFFFF.toInt())
        mTitle.textSize = 18f
        mTitle.setTypeface(null, Typeface.BOLD)
        mTitle.maxLines = 1
        mTitle.ellipsize = TextUtils.TruncateAt.END
        trow.addView(mTitle, LinearLayout.LayoutParams(0, -2, 1f))
        mEq = EqView(this)
        val elp = LinearLayout.LayoutParams(dp(20), dp(16))
        elp.leftMargin = dp(8)
        trow.addView(mEq, elp)
        info.addView(trow, LinearLayout.LayoutParams(-1, -2))

        mArtist = TextView(this)
        mArtist.setTextColor(0xB3FFFFFF.toInt())
        mArtist.textSize = 13f
        mArtist.maxLines = 1
        mArtist.ellipsize = TextUtils.TruncateAt.END
        info.addView(mArtist, LinearLayout.LayoutParams(-1, -2))

        val pr = LinearLayout(this)
        pr.orientation = LinearLayout.HORIZONTAL
        pr.gravity = Gravity.CENTER_VERTICAL
        pr.setPadding(0, dp(10), 0, 0)
        mBar = ProgressBarView(this)
        pr.addView(mBar, LinearLayout.LayoutParams(0, dp(5), 1f))
        mTime = TextView(this)
        mTime.setTextColor(0xB3FFFFFF.toInt())
        mTime.textSize = 12f
        mTime.setPadding(dp(10), 0, 0, 0)
        pr.addView(mTime)
        info.addView(pr, LinearLayout.LayoutParams(-1, -2))

        val ilp = LinearLayout.LayoutParams(0, -2, 1f)
        ilp.leftMargin = dp(14)
        top.addView(info, ilp)
        card.addView(top, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.clipChildren = false
        row.setPadding(0, dp(10), 0, 0)
        shuffle = prefs.getBoolean("shuffle", false)
        row.addView(iconBtn(android.R.drawable.ic_media_previous) { prevTrack() }, bl(1f))
        val pb = iconBtn(android.R.drawable.ic_media_play) { togglePlay() }
        mPlayIcon = pb.getChildAt(0) as ImageView
        row.addView(pb, bl(1.2f))
        row.addView(iconBtn(android.R.drawable.ic_media_next) { nextTrack() }, bl(1f))
        shufBtn = smallPill("Shuffle") {
            shuffle = !shuffle
            prefs.edit().putBoolean("shuffle", shuffle).apply()
            updateShuffleUi()
        }
        updateShuffleUi()
        row.addView(shufBtn, bl(1.7f))
        row.addView(smallPill("Songs") { openSongPicker() }, bl(1.3f))
        row.addView(smallPill("VLC") { openMusic() }, bl(1.1f))
        card.addView(row, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun permName(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPerm(): Boolean =
        checkSelfPermission(permName()) == PackageManager.PERMISSION_GRANTED

    private fun askPerm() {
        try {
            requestPermissions(arrayOf(permName()), 1)
        } catch (e: Throwable) {
            Toast.makeText(
                this,
                "Can't show the permission prompt. Enable it in Settings > Apps > Glass Home > Permissions.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun listenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(packageName)
    }

    // Reads what other music apps (like VLC) are playing, once notification access is on.
    private fun pollExternal() {
        var best: MediaController? = null
        if (listenerEnabled()) {
            try {
                val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val list = msm.getActiveSessions(ComponentName(this, NowPlayingService::class.java))
                for (c in list) {
                    if (c.packageName == packageName) continue
                    val st = c.playbackState?.state
                    if (st == PlaybackState.STATE_PLAYING) {
                        best = c
                        extLastPlayingMs = SystemClock.elapsedRealtime()
                        break
                    }
                    if (best == null && (st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_BUFFERING)) best = c
                }
                if (best != null && best.playbackState?.state != PlaybackState.STATE_PLAYING &&
                    SystemClock.elapsedRealtime() - extLastPlayingMs > 90000L
                ) best = null
            } catch (e: Throwable) {
                best = null
            }
        }
        ext = best
    }

    private fun loadTracks() {
        val list = ArrayList<Track>()
        if (hasAudioPerm()) {
            try {
                val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                val proj = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST
                )
                val c = contentResolver.query(
                    base, proj,
                    MediaStore.Audio.Media.DURATION + " >= 30000", null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
                )
                if (c != null) {
                    while (c.moveToNext() && list.size < 3000) {
                        val id = c.getLong(0)
                        val title = c.getString(1) ?: "Unknown"
                        var artist = c.getString(2) ?: ""
                        if (artist.isEmpty() || artist == "<unknown>") artist = "Unknown artist"
                        list.add(Track(ContentUris.withAppendedId(base, id), title, artist))
                    }
                    c.close()
                }
            } catch (e: Exception) {}
        }
        tracks = list
        idx = prefs.getInt("mIdx", 0)
        if (idx < 0 || idx >= tracks.size) idx = 0
        loadArt()
    }

    private fun loadArt() {
        ownArt = null
        if (tracks.isEmpty() || Build.VERSION.SDK_INT < 29) return
        val i = idx
        val uri = tracks[i].uri
        Thread {
            var b: Bitmap? = null
            try {
                b = contentResolver.loadThumbnail(uri, Size(300, 300), null)
            } catch (e: Throwable) {}
            runOnUiThread {
                if (i == idx) {
                    ownArt = b
                    refreshMusic()
                }
            }
        }.start()
    }

    private fun ensureTracks(): Boolean {
        errors = 0
        if (!hasAudioPerm()) {
            askPerm()
            return false
        }
        if (tracks.isEmpty()) loadTracks()
        refreshMusic()
        return tracks.isNotEmpty()
    }

    private fun extActive(): Boolean = !playing && ext != null

    private fun togglePlay() {
        val e = ext
        if (!playing && e != null) {
            try {
                if (e.playbackState?.state == PlaybackState.STATE_PLAYING) e.transportControls.pause()
                else e.transportControls.play()
            } catch (x: Throwable) {}
            handler.postDelayed({ pollExternal(); refreshMusic() }, 300)
            return
        }
        if (!ensureTracks()) return
        val m = mp
        if (m == null) {
            playIndex(idx)
            return
        }
        try {
            if (m.isPlaying) {
                m.pause()
                playing = false
            } else {
                m.start()
                playing = true
            }
        } catch (x: Throwable) {
            playIndex(idx)
            return
        }
        refreshMusic()
    }

    private fun nextTrack() {
        val e = ext
        if (!playing && e != null) {
            try { e.transportControls.skipToNext() } catch (x: Throwable) {}
            handler.postDelayed({ pollExternal(); refreshMusic() }, 400)
            return
        }
        if (ensureTracks()) playIndex(pickNext())
    }

    private fun prevTrack() {
        val e = ext
        if (!playing && e != null) {
            try { e.transportControls.skipToPrevious() } catch (x: Throwable) {}
            handler.postDelayed({ pollExternal(); refreshMusic() }, 400)
            return
        }
        if (!ensureTracks()) return
        if (shuffle && history.isNotEmpty()) {
            playIndex(history.removeAt(history.size - 1), true)
        } else {
            playIndex(idx - 1, true)
        }
    }

    private fun playIndex(i: Int, back: Boolean = false) {
        if (tracks.isEmpty()) return
        if (!back && mp != null) {
            history.add(idx)
            if (history.size > 100) history.removeAt(0)
        }
        try { ext?.transportControls?.pause() } catch (x: Throwable) {}
        idx = ((i % tracks.size) + tracks.size) % tracks.size
        prefs.edit().putInt("mIdx", idx).apply()
        try { mp?.release() } catch (e: Throwable) {}
        mp = null
        playing = false
        mBar.fraction = 0f
        loadArt()
        try {
            val m = MediaPlayer()
            m.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            m.setDataSource(this, tracks[idx].uri)
            m.setOnPreparedListener { pm ->
                errors = 0
                pm.start()
                playing = true
                refreshMusic()
            }
            m.setOnCompletionListener { playIndex(pickNext()) }
            m.setOnErrorListener { _, _, _ ->
                errors++
                if (errors < 5) {
                    playIndex(pickNext())
                } else {
                    playing = false
                    refreshMusic()
                }
                true
            }
            mp = m
            m.prepareAsync()
        } catch (e: Throwable) {
            Toast.makeText(this, "Can't play this file", Toast.LENGTH_SHORT).show()
        }
        refreshMusic()
    }

    private fun refreshMusic() {
        if (ready) syncForestSound()
        if (!ready) return
        val title: String
        val artist: String
        var art: Bitmap? = null
        var isPlaying = false
        val e = ext
        if (!playing && e != null) {
            val md = e.metadata
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Now playing"
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            isPlaying = e.playbackState?.state == PlaybackState.STATE_PLAYING
        } else if (!hasAudioPerm()) {
            title = "Allow music access"
            artist = "Select play to load your songs"
        } else if (tracks.isEmpty()) {
            title = "No music found"
            artist = "Add songs to the TV or plug in a USB drive"
        } else {
            val t = tracks[idx]
            title = t.title
            artist = t.artist
            art = ownArt
            isPlaying = playing
        }
        mTitle.text = title
        mArtist.text = artist
        if (art !== lastArt) {
            lastArt = art
            if (art != null) {
                mArt.setImageBitmap(art)
                mNote.visibility = View.GONE
            } else {
                mArt.setImageDrawable(null)
                mNote.visibility = View.VISIBLE
            }
        }
        mPlayIcon.setImageResource(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        mEq.active = isPlaying
        musicPlaying = isPlaying
        if (mp == null && e == null) {
            mBar.fraction = 0f
            mTime.text = "0:00"
        }
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return (s / 60).toString() + ":" + (s % 60).toString().padStart(2, '0')
    }

    private fun updateProgress() {
        val e = ext
        if (!playing && e != null) {
            val md = e.metadata
            val st = e.playbackState
            val d = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            if (st != null) {
                var p = st.position
                if (st.state == PlaybackState.STATE_PLAYING) {
                    p += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
                }
                if (d > 0L) {
                    mBar.fraction = p.toFloat() / d.toFloat()
                    mTime.text = fmt(p.toInt()) + " / " + fmt(d.toInt())
                } else {
                    mBar.fraction = 0f
                    mTime.text = fmt(p.toInt())
                }
            }
            return
        }
        val m = mp
        if (m != null && playing) {
            try {
                val d = m.duration
                val pos = m.currentPosition
                mBar.fraction = if (d > 0) pos.toFloat() / d else 0f
                mTime.text = fmt(pos) + " / " + fmt(d)
            } catch (x: Throwable) {}
        }
    }
}
