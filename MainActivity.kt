package com.glass.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class App(val label: String, val pkg: String, val icon: Drawable)

class BgView(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, w, h, 0xFF0A1128.toInt(), 0xFF14245A.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        glow(canvas, w, h, w * 0.10f, 0f, w * 0.50f, 0xFF2A63FF.toInt())
        glow(canvas, w, h, w * 0.95f, h, w * 0.45f, 0xFF14B8C9.toInt())
        glow(canvas, w, h, w * 0.80f, h * 0.10f, w * 0.30f, 0xFF8A4DFF.toInt())
    }
    private fun glow(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, r: Float, color: Int) {
        val rgb = color and 0x00FFFFFF
        p.shader = RadialGradient(cx, cy, r, rgb or 0xA0000000.toInt(), rgb, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
    }
}

class MainActivity : Activity() {
    private lateinit var clock: TextView
    private lateinit var hello: TextView
    private lateinit var grid: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val cols = 6
    private val favs = listOf(
        "com.google.android.youtube.tv", "com.google.android.youtube",
        "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus", "com.android.chrome"
    )
    private val tick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 20000)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun glass(): StateListDrawable {
        fun d(fill: Int, stroke: Int, sw: Int): GradientDrawable {
            val g = GradientDrawable()
            g.cornerRadius = dp(22).toFloat()
            g.setColor(fill)
            g.setStroke(sw, stroke)
            return g
        }
        val s = StateListDrawable()
        s.addState(intArrayOf(android.R.attr.state_focused), d(0x38FFFFFF, 0xCCFFFFFF.toInt(), dp(3)))
        s.addState(intArrayOf(), d(0x1AFFFFFF, 0x33FFFFFF, dp(1)))
        return s
    }

    private fun focusScale(v: View) {
        v.setOnFocusChangeListener { view, f ->
            val s = if (f) 1.08f else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(140).start()
        }
    }

    private fun pill(text: String, onClick: () -> Unit): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(0xFFF5F8FC.toInt())
        t.textSize = 18f
        t.gravity = Gravity.CENTER
        t.setPadding(dp(22), dp(10), dp(22), dp(10))
        t.isFocusable = true
        t.isClickable = true
        t.background = glass()
        t.setOnClickListener { onClick() }
        focusScale(t)
        return t
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.addView(BgView(this), FrameLayout.LayoutParams(-1, -1))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(48), dp(28), dp(48), dp(16))
        col.clipChildren = false

        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        hello = TextView(this)
        hello.setTextColor(0xFFF5F8FC.toInt())
        hello.textSize = 30f
        hello.setTypeface(null, Typeface.BOLD)
        top.addView(hello, LinearLayout.LayoutParams(0, -2, 1f))
        clock = TextView(this)
        clock.setTextColor(0xB3F5F8FC.toInt())
        clock.textSize = 22f
        clock.setPadding(0, 0, dp(24), 0)
        top.addView(clock)
        top.addView(pill("Settings") {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e: Exception) {}
        })
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.clipChildren = false
        scroll.clipToPadding = false
        scroll.setPadding(0, dp(16), 0, dp(16))
        grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.clipChildren = false
        scroll.addView(grid, ViewGroup.LayoutParams(-1, -2))
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        build()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    @Deprecated("Launcher ignores Back")
    override fun onBackPressed() {}

    private fun cellLp(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(0, dp(132), 1f)
        lp.setMargins(dp(8), dp(8), dp(8), dp(8))
        return lp
    }

    private fun build() {
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        hello.text = when {
            hr < 12 -> "Good morning"
            hr < 18 -> "Good afternoon"
            else -> "Good evening"
        }
        grid.removeAllViews()
        val apps = loadApps()
        var row: LinearLayout? = null
        for ((i, a) in apps.withIndex()) {
            if (i % cols == 0) {
                val r = LinearLayout(this)
                r.orientation = LinearLayout.HORIZONTAL
                r.clipChildren = false
                grid.addView(r, LinearLayout.LayoutParams(-1, -2))
                row = r
            }
            row!!.addView(tile(a), cellLp())
        }
        val rem = apps.size % cols
        if (rem != 0 && row != null) {
            for (k in rem until cols) row.addView(View(this), cellLp())
        }
        ((grid.getChildAt(0) as? ViewGroup)?.getChildAt(0))?.requestFocus()
    }

    private fun tile(a: App): View {
        val t = LinearLayout(this)
        t.orientation = LinearLayout.VERTICAL
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8), dp(12), dp(8), dp(12))
        t.isFocusable = true
        t.isClickable = true
        t.background = glass()
        t.setOnClickListener { launch(a.pkg) }
        focusScale(t)
        val iv = ImageView(this)
        iv.setImageDrawable(a.icon)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        t.addView(iv, LinearLayout.LayoutParams(dp(64), dp(64)))
        val tv = TextView(this)
        tv.text = a.label
        tv.setTextColor(0xFFF5F8FC.toInt())
        tv.textSize = 15f
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
