package com.satyam.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : Activity() {

    data class AppItem(val label: String, val pkg: String, val intent: Intent, val icon: Drawable)

    private lateinit var root: FrameLayout
    private lateinit var drawer: LinearLayout
    private lateinit var grid: GridView
    private lateinit var search: EditText
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var dock: LinearLayout
    private var apps: List<AppItem> = emptyList()
    private var shown: List<AppItem> = emptyList()
    private var open = false
    private val adapter = Adp()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { updateClock(); handler.postDelayed(this, 10000) }
    }
    private lateinit var detector: GestureDetector

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun round(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat()
    }

    private fun roundTop(color: Int, r: Int) = GradientDrawable().apply {
        setColor(color)
        val f = dp(r).toFloat()
        cornerRadii = floatArrayOf(f, f, f, f, 0f, 0f, 0f, 0f)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        root = FrameLayout(this)
        setContentView(root)

        // ---- Home screen: clock widget + dock ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(18), dp(32), dp(18))
            background = round(0x55000000, 28)
        }
        clock = TextView(this).apply {
            textSize = 64f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        date = TextView(this).apply { textSize = 16f; setTextColor(Color.WHITE) }
        card.addView(clock); card.addView(date)
        root.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(90) })

        val hint = TextView(this).apply {
            text = "\u2303"; textSize = 22f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER
        }
        root.addView(hint, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply { bottomMargin = dp(112) })

        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = round(0x66FFFFFF, 30)
        }
        root.addView(dock, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(18)) })

        // ---- App drawer ----
        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundTop(0xF2121218.toInt(), 28)
            setPadding(dp(14), dp(14), dp(14), 0)
            visibility = View.GONE
        }
        search = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(0x99FFFFFF.toInt()); setTextColor(Color.WHITE)
            textSize = 16f
            background = round(0x33FFFFFF, 24)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { filter(s?.toString() ?: "") }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            setOnEditorActionListener { _, _, _ ->
                shown.firstOrNull()?.let { launch(it) }; true
            }
        }
        drawer.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
        grid = GridView(this).apply {
            numColumns = 4
            verticalSpacing = dp(6)
            setSelector(android.R.color.transparent)
            setAdapter(adapter)
            setOnItemClickListener { _, _, p, _ -> launch(shown[p]) }
            setOnItemLongClickListener { _, _, p, _ -> showMenu(shown[p]); true }
        }
        drawer.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(drawer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(48) })

        detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vy) < abs(vx)) return false
                if (!open && vy < -1000) { openDrawer(); return true }
                val atTop = grid.childCount == 0 || (grid.firstVisiblePosition == 0 && grid.getChildAt(0).top >= 0)
                if (open && vy > 1200 && atTop) { closeDrawer(); return true }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        detector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        loadApps(); buildDock()
        handler.post(tick)
    }

    override fun onPause() { super.onPause(); handler.removeCallbacks(tick) }

    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); closeDrawer() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (open) closeDrawer() }

    private fun updateClock() {
        val now = Date()
        clock.text = SimpleDateFormat("hh:mm", Locale.getDefault()).format(now)
        date.text = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now)
    }

    private fun openDrawer() {
        if (open) return
        open = true
        drawer.visibility = View.VISIBLE
        drawer.translationY = root.height.toFloat()
        drawer.animate().translationY(0f).setDuration(220).start()
    }

    private fun closeDrawer() {
        if (!open) return
        open = false
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(search.windowToken, 0)
        search.setText("")
        drawer.animate().translationY(root.height.toFloat()).setDuration(200)
            .withEndAction { drawer.visibility = View.GONE }.start()
    }

    private fun loadApps() {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        apps = pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { r ->
                val cn = ComponentName(r.activityInfo.packageName, r.activityInfo.name)
                val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                AppItem(r.loadLabel(pm).toString(), r.activityInfo.packageName, i, r.loadIcon(pm))
            }
            .sortedBy { it.label.lowercase() }
        filter(if (this::search.isInitialized) search.text.toString() else "")
    }

    private fun filter(q: String) {
        shown = if (q.isBlank()) apps else apps.filter { it.label.contains(q.trim(), true) }
        adapter.notifyDataSetChanged()
    }

    private fun buildDock() {
        dock.removeAllViews()
        val pm = packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        )
        for (intent in intents) {
            val ri = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).firstOrNull() ?: continue
            intent.setComponent(ComponentName(ri.activityInfo.packageName, ri.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val v = iconView(ri.loadIcon(pm), dp(56))
            v.setOnClickListener { try { startActivity(intent) } catch (e: Exception) {} }
            dock.addView(v, LinearLayout.LayoutParams(dp(56), dp(56)).apply { setMargins(dp(14), 0, dp(14), 0) })
        }
    }

    private fun launch(a: AppItem) {
        try { startActivity(a.intent) } catch (e: Exception) {}
        closeDrawer()
    }

    private fun showMenu(a: AppItem) {
        AlertDialog.Builder(this).setTitle(a.label)
            .setItems(arrayOf("App info", "Uninstall")) { _, which ->
                try {
                    if (which == 0) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${a.pkg}")))
                    else startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}")))
                } catch (e: Exception) {}
            }.show()
    }

    private fun iconView(d: Drawable, size: Int) = ImageView(this).apply {
        setImageDrawable(d)
        layoutParams = LinearLayout.LayoutParams(size, size)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) {
                o.setRoundRect(0, 0, v.width, v.height, v.width * 0.28f)
            }
        }
        clipToOutline = true
    }

    private inner class Adp : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val a = shown[p]
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(8), 0, dp(8))
            }
            box.addView(iconView(a.icon, dp(56)))
            box.addView(TextView(this@MainActivity).apply {
                text = a.label; textSize = 12f; setTextColor(Color.WHITE)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER; setPadding(dp(2), dp(5), dp(2), 0)
            })
            return box
        }
    }
}
