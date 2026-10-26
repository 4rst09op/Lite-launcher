package com.ryan.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.concurrent.Executors

class AppItem(val label: String, val pkg: String, val intent: Intent, val icon: Drawable)

/** Zero-dependency, near-zero-RAM launcher: clock + app grid. Long-press an app for its settings page. */
class MainActivity : Activity() {

    private val apps = ArrayList<AppItem>()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var grid: GridView

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val app = apps[position]
            val cell = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(10), dp(8), dp(10))
                addView(
                    ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                    LinearLayout.LayoutParams(dp(144), dp(81))
                )
                addView(
                    TextView(context).apply {
                        setTextColor(0xFFE6E9F2.toInt())
                        textSize = 14f
                        maxLines = 1
                        gravity = Gravity.CENTER
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                )
            }
            (cell.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (cell.getChildAt(1) as TextView).text = app.label
            return cell
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF141B34.toInt(), 0xFF05070D.toInt())
            )
        }

        root.addView(TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            textSize = 56f
            setTextColor(Color.WHITE)
        })
        root.addView(TextClock(this).apply {
            format12Hour = "EEEE, MMMM d"
            format24Hour = "EEEE, MMMM d"
            textSize = 18f
            setTextColor(0xFF8A93AB.toInt())
        })

        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(160)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            verticalSpacing = dp(14)
            horizontalSpacing = dp(10)
            clipToPadding = false
            setPadding(0, dp(24), 0, dp(24))
            selector = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0x333D7BFF)
                setStroke(dp(3), 0xFF5B8CFF.toInt())
            }
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                try { startActivity(apps[pos].intent) } catch (_: Exception) {}
            }
            setOnItemLongClickListener { _, _, pos, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + apps[pos].pkg))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {}
                true
            }
        }
        root.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        loadApps()
        grid.requestFocus()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        grid.setSelection(0) // Home button jumps back to the top
    }

    @Deprecated("Launcher must not close on Back")
    override fun onBackPressed() {}

    private fun loadApps() = worker.execute {
        val pm = packageManager
        val seen = HashSet<String>()
        val list = ArrayList<AppItem>()
        // Leanback entries first so TV apps win over their phone-style duplicates
        for (cat in arrayOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(cat)
                    .setClassName(pkg, ri.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val art = ri.activityInfo.loadBanner(pm) ?: ri.loadIcon(pm)
                list.add(AppItem(ri.loadLabel(pm).toString(), pkg, launch, art))
            }
        }
        list.sortBy { it.label.lowercase() }
        runOnUiThread {
            apps.clear()
            apps.addAll(list)
            adapter.notifyDataSetChanged()
        }
    }
}
