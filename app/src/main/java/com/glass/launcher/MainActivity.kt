package com.glass.launcher

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class App(val label: String, val pkg: String, val icon: Drawable)

object Wall {
    var full: Bitmap? = null
    var blur: Bitmap? = null
    var w = 1920
    var h = 1080
    var ox = 0
    var oy = 0
    var scrim = 0x55000000
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
        if (b != null) canvas.drawBitmap(b, null, dst, p)
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
        val bl = Wall.blur
        if (bl != null) {
            getLocationInWindow(loc)
            canvas.save()
            canvas.clipPath(path)
            canvas.translate(-(loc[0] - Wall.ox).toFloat(), -(loc[1] - Wall.oy).toFloat())
            dst.set(0, 0, Wall.w, Wall.h)
            canvas.drawBitmap(bl, null, dst, bp)
            canvas.restore()
        }
        val f = isFocused
        fill.color = if (f) 0x45FFFFFF else 0x22FFFFFF
        canvas.drawRoundRect(rect, radius, radius, fill)
        val sw = if (f) 3f * dens else 1.2f * dens
        line.strokeWidth = sw
        line.color = if (f) 0xF0FFFFFF.toInt() else 0x44FFFFFF
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

        // 1. soft shadow
        p.color = 0x22000000
        p.setShadowLayer(16f * dens, 0f, 6f * dens, 0x66000000)
        canvas.drawText(text, x, y, p)
        p.clearShadowLayer()

        // 2. rim light. Drawn BEFORE the body, so the body covers the inner half
        //    of the stroke and no overlapping-contour lines show inside the digits.
        p.style = Paint.Style.STROKE
        p.strokeWidth = rim * 2f
        p.color = 0xCCFFFFFF.toInt()
        canvas.drawText(text, x, y, p)
        p.style = Paint.Style.FILL

        // 3. frosted body (blurred wallpaper showing through the digits)
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

        // 4. glossy tint, brighter at the top
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
        p.color = 0xEEFFFFFF.toInt()
        r.set(0f, 0f, w * fraction.coerceIn(0f, 1f), h)
        canvas.drawRoundRect(r, h / 2f, h / 2f, p)
    }
}

class MainActivity : Activity() {
    private val vlc = "org.videolan.vlc"
    private lateinit var root: FrameLayout
    private lateinit var clock: GlassClock
    private lateinit var date: TextView
    private lateinit var greet: TextView
    private lateinit var strip: LinearLayout
    private lateinit var cardHolder: FrameLayout
    private lateinit var wpPill: GlassBox
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private val prefs: SharedPreferences by lazy { getSharedPreferences("glass", Context.MODE_PRIVATE) }

    private val wps = intArrayOf(R.drawable.wp1, R.drawable.wp2, R.drawable.wp3, R.drawable.wp4)
    private val wpNames = arrayOf("Aurora", "Autumn", "Bamboo", "Dunes", "Meadow")

    private val favs = listOf(
        "org.videolan.vlc",
        "com.google.android.youtube.tv", "com.google.android.youtube",
        "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus", "com.android.chrome"
    )

    private val tick = object : Runnable {
        override fun run() {
            updateTime()
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

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(44), dp(22), dp(44), dp(14))
        col.clipChildren = false

        // top-right buttons
        val head = LinearLayout(this)
        head.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        head.clipChildren = false
        wpPill = pill("Wallpaper") { cycleWallpaper() }
        head.addView(wpPill, plp())
        head.addView(pill("Settings") {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e: Exception) {}
        }, plp())
        col.addView(head, LinearLayout.LayoutParams(-1, -2))

        // date + glass clock + greeting tab, tightly grouped, plus the music tab
        val topRow = LinearLayout(this)
        topRow.orientation = LinearLayout.HORIZONTAL
        topRow.clipChildren = false

        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        left.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        left.clipChildren = false

        date = TextView(this)
        date.setTextColor(0xE6FFFFFF.toInt())
        date.textSize = 20f
        date.gravity = Gravity.START
        date.setPadding(dp(3), 0, 0, 0)
        date.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        date.setShadowLayer(8f, 0f, 2f, 0x66000000)
        left.addView(date, LinearLayout.LayoutParams(-2, -2))

        clock = GlassClock(this)
        val clockH = (resources.displayMetrics.heightPixels * 0.26f).toInt()
        left.addView(clock, LinearLayout.LayoutParams(-1, clockH))

        val greetBox = GlassBox(this)
        greetBox.gravity = Gravity.CENTER
        greetBox.setPadding(dp(24), dp(8), dp(24), dp(8))
        greet = TextView(this)
        greet.textSize = 24f
        greet.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        greet.setShadowLayer(8f, 0f, 2f, 0x66000000)
        greetBox.addView(greet)
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

        // horizontal app strip
        val hsv = HorizontalScrollView(this)
        hsv.isHorizontalScrollBarEnabled = false
        hsv.clipChildren = false
        hsv.clipToPadding = false
        hsv.setPadding(dp(4), dp(14), dp(4), dp(14))
        strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.clipChildren = false
        hsv.addView(strip, ViewGroup.LayoutParams(-2, -2))
        col.addView(hsv, LinearLayout.LayoutParams(-1, -2))

        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        applyWallpaper(prefs.getInt("wp", 1))
        ready = true
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        build()
        if (tracks.isEmpty() && hasAudioPerm()) loadTracks()
        refreshMusic()
        handler.post(tick)
        handler.post(progTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(progTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { mp?.release() } catch (e: Throwable) {}
        mp = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (ready) {
            loadTracks()
            refreshMusic()
        }
    }

    @Deprecated("Launcher ignores Back")
    override fun onBackPressed() {}

    // ---------- music player (plays songs from the TV / USB itself) ----------
    private class Track(val uri: Uri, val title: String, val artist: String)

    private var tracks = ArrayList<Track>()
    private var mp: MediaPlayer? = null
    private var idx = 0
    private var playing = false
    private var errors = 0
    private lateinit var mTitle: TextView
    private lateinit var mArtist: TextView
    private lateinit var mTime: TextView
    private lateinit var mBar: ProgressBarView
    private lateinit var mPlayIcon: ImageView

    private val progTick = object : Runnable {
        override fun run() {
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

    private fun musicCard(): GlassBox {
        val card = GlassBox(this)
        card.orientation = LinearLayout.VERTICAL
        card.clipChildren = false
        card.setPadding(dp(16), dp(14), dp(16), dp(14))

        mTitle = TextView(this)
        mTitle.setTextColor(0xFFFFFFFF.toInt())
        mTitle.textSize = 18f
        mTitle.setTypeface(null, Typeface.BOLD)
        mTitle.maxLines = 1
        mTitle.ellipsize = TextUtils.TruncateAt.END
        card.addView(mTitle, LinearLayout.LayoutParams(-1, -2))

        mArtist = TextView(this)
        mArtist.setTextColor(0xB3FFFFFF.toInt())
        mArtist.textSize = 13f
        mArtist.maxLines = 1
        mArtist.ellipsize = TextUtils.TruncateAt.END
        card.addView(mArtist, LinearLayout.LayoutParams(-1, -2))

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
        card.addView(pr, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.clipChildren = false
        row.setPadding(0, dp(10), 0, 0)
        row.addView(iconBtn(android.R.drawable.ic_media_previous) { prevTrack() }, bl(1f))
        val pb = iconBtn(android.R.drawable.ic_media_play) { togglePlay() }
        mPlayIcon = pb.getChildAt(0) as ImageView
        row.addView(pb, bl(1.3f))
        row.addView(iconBtn(android.R.drawable.ic_media_next) { nextTrack() }, bl(1f))
        row.addView(pill("VLC") { openMusic() }, bl(1.3f))
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

    private fun togglePlay() {
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
        } catch (e: Throwable) {
            playIndex(idx)
            return
        }
        refreshMusic()
    }

    private fun nextTrack() {
        if (ensureTracks()) playIndex(idx + 1)
    }

    private fun prevTrack() {
        if (ensureTracks()) playIndex(idx - 1)
    }

    private fun playIndex(i: Int) {
        if (tracks.isEmpty()) return
        idx = ((i % tracks.size) + tracks.size) % tracks.size
        prefs.edit().putInt("mIdx", idx).apply()
        try { mp?.release() } catch (e: Throwable) {}
        mp = null
        playing = false
        mBar.fraction = 0f
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
            m.setOnCompletionListener { playIndex(idx + 1) }
            m.setOnErrorListener { _, _, _ ->
                errors++
                if (errors < 5) {
                    playIndex(idx + 1)
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
        if (!hasAudioPerm()) {
            mTitle.text = "Allow music access"
            mArtist.text = "Select play to load your songs"
        } else if (tracks.isEmpty()) {
            mTitle.text = "No music found"
            mArtist.text = "Add songs to the TV or plug in a USB drive"
        } else {
            val t = tracks[idx]
            mTitle.text = t.title
            mArtist.text = t.artist
        }
        mPlayIcon.setImageResource(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        if (mp == null) {
            mBar.fraction = 0f
            mTime.text = "0:00"
        }
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return (s / 60).toString() + ":" + (s % 60).toString().padStart(2, '0')
    }

    private fun updateProgress() {
        val m = mp
        if (m != null && playing) {
            try {
                val d = m.duration
                val pos = m.currentPosition
                mBar.fraction = if (d > 0) pos.toFloat() / d else 0f
                mTime.text = fmt(pos) + " / " + fmt(d)
            } catch (e: Throwable) {}
        }
    }

    private fun hasPkg(p: String): Boolean =
        try { packageManager.getPackageInfo(p, 0); true } catch (e: Exception) { false }

    private fun openMusic() {
        if (hasPkg(vlc)) launch(vlc)
    }

    // ---------- wallpaper ----------
    private fun cycleWallpaper() {
        val n = (prefs.getInt("wp", 1) + 1) % (wps.size + 1)
        applyWallpaper(n)
    }

    private fun applyWallpaper(i: Int) {
        var full: Bitmap? = null
        if (i > 0) full = decodeWp(wps[i - 1])
        if (full == null) full = gradientBitmap()
        Wall.full = full
        Wall.blur = blurOf(full)
        Wall.scrim = if (i == 0) 0x00000000 else 0x55000000
        prefs.edit().putInt("wp", i).apply()
        (wpPill.getChildAt(0) as TextView).text = "Wallpaper: " + wpNames[i]
        invalidateAll(root)
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
        glow(c, p, w, h, w * 0.10f, 0f, w * 0.50f, 0xFF2A63FF.toInt())
        glow(c, p, w, h, w * 0.95f, h.toFloat(), w * 0.45f, 0xFF14B8C9.toInt())
        glow(c, p, w, h, w * 0.80f, h * 0.10f, w * 0.30f, 0xFF8A4DFF.toInt())
        return b
    }

    private fun glow(c: Canvas, p: Paint, w: Int, h: Int, cx: Float, cy: Float, r: Float, color: Int) {
        val rgb = color and 0x00FFFFFF
        p.shader = RadialGradient(cx, cy, r, rgb or 0xA0000000.toInt(), rgb, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }

    // ---------- clock + greeting ----------
    private fun updateTime() {
        val now = Date()
        clock.text = SimpleDateFormat("h:mm", Locale.getDefault()).format(now)
        date.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now) +
            "  \u2022  " + SimpleDateFormat("a", Locale.getDefault()).format(now)

        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
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

    // ---------- apps ----------
    private fun cellLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(dp(112), dp(120))
        lp.setMargins(dp(7), dp(4), dp(7), dp(4))
        return lp
    }

    private fun build() {
        strip.removeAllViews()
        var first: View? = null
        for (a in loadApps()) {
            val t = tile(a)
            strip.addView(t, cellLp())
            if (first == null) first = t
        }
        updateTime()
        if (currentFocus == null) first?.requestFocus()
    }

    private fun tile(a: App): View {
        val t = GlassBox(this)
        t.orientation = LinearLayout.VERTICAL
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8), dp(10), dp(8), dp(10))
        t.isFocusable = true
        t.isClickable = true
        t.setOnClickListener { launch(a.pkg) }
        focusScale(t)
        val iv = ImageView(this)
        iv.setImageDrawable(a.icon)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        t.addView(iv, LinearLayout.LayoutParams(dp(52), dp(52)))
        val tv = TextView(this)
        tv.text = a.label
        tv.setTextColor(0xFFF5F8FC.toInt())
        tv.textSize = 13f
        tv.maxLines = 1
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(8), 0, 0)
        t.addView(tv, LinearLayout.LayoutParams(-1, -2))
        return t
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
}
