package net.kaneonexus.dartclicker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        setContentView(buildLayout()) // refresh permission status
    }

    private fun buildLayout(): ScrollView {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(60), dp(28), dp(40))
            setBackgroundColor(0xFF080A0E.toInt())
        }

        // Title
        root.addView(tv("AUTO DARTS", 32f, 0xFFC8A040.toInt(), bold = true, center = true))
        root.addView(tv("NEXUS Integrated Intelligence", 10f, 0xFF5A4018.toInt(), center = true).also {
            (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(6)
        })
        root.addView(tv("kaneonexus.net", 9f, 0xFF3A2A0E.toInt(), center = true).also {
            (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(40)
        })

        // Permission status
        root.addView(tv("PERMISSIONS REQUIRED", 9f, 0xFF7A5A22.toInt()))
        root.addView(divider())

        val hasOverlay = Settings.canDrawOverlays(this)
        val hasA11y = isAccessibilityEnabled()

        root.addView(statusRow("Screen Overlay", hasOverlay))
        root.addView(statusRow("Accessibility Service", hasA11y))

        root.addView(space(dp(24)))

        if (!hasOverlay) {
            root.addView(actionButton("Grant Overlay Permission", 0xFF1E4A2A.toInt()) {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            }.also {
                (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(10)
            })
        }

        if (!hasA11y) {
            root.addView(actionButton("Enable Accessibility Service", 0xFF1A2A4A.toInt()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.also {
                (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(10)
            })
        }

        if (hasOverlay && hasA11y) {
            root.addView(tv("✓  Both permissions granted.\nThe Auto Darts overlay is active.",
                12f, 0xFF70C060.toInt(), center = true).also {
                (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(20)
            })
        }

        root.addView(space(dp(16)))
        root.addView(divider())
        root.addView(tv("HOW TO USE", 9f, 0xFF7A5A22.toInt()).also {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        })

        val instructions = listOf(
            "1. Grant both permissions above",
            "2. Open your dartboard app",
            "3. Tap CALIBRATE on the floating overlay",
            "4. Tap your dartboard's CENTER",
            "5. Tap any point on the outer EDGE",
            "6. Tap below the board as your THROW ORIGIN",
            "7. Select game mode (Cricket / 301 / 501…)",
            "8. Adjust Speed and Accuracy sliders",
            "9. Tap THROW to start auto-throwing",
            "",
            "Each throw is a swipe gesture from the\nthrow origin up to the target segment.\nONE = single throw for testing."
        )
        instructions.forEach { line ->
            root.addView(tv(line, 11f, 0xFF5A4018.toInt()))
        }

        root.addView(space(dp(30)))
        root.addView(divider())
        root.addView(tv("Intelligence Without the Artificial", 8f, 0xFF2A1E0A.toInt(), center = true).also {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(12)
        })

        scroll.addView(root)
        return scroll
    }

    // ─────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────

    private fun isAccessibilityEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.contains(packageName, ignoreCase = true)
        } catch (_: Exception) { false }
    }

    private fun statusRow(label: String, ok: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(tv(label, 13f, 0xFFB0A080.toInt()).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(tv(
            if (ok) "✓  Enabled" else "✗  Required",
            12f,
            if (ok) 0xFF70C060.toInt() else 0xFFC06040.toInt(),
            bold = true
        ))
        return row
    }

    private fun actionButton(text: String, bgColor: Int, onClick: () -> Unit) =
        Button(this).apply {
            this.text = text
            setBackgroundColor(bgColor)
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { onClick() }
        }

    private fun tv(text: String, sp: Float, color: Int, bold: Boolean = false, center: Boolean = false) =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = sp
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            if (center) gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun divider() = View(this).apply {
        setBackgroundColor(0xFF2A1E0A.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).also {
            it.topMargin = dp(4); it.bottomMargin = dp(8)
        }
    }

    private fun space(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
