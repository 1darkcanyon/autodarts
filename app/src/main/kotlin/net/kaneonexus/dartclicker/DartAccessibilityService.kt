package net.kaneonexus.dartclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import kotlin.math.abs
import kotlin.random.Random

class DartAccessibilityService : AccessibilityService() {

    // ── Core ──────────────────────────────────
    private lateinit var wm: WindowManager
    private lateinit var targetConfig: TargetConfig
    private var gameEngine: GameEngine? = null
    private var currentMode = GameMode.GAME_501
    private val handler = Handler(Looper.getMainLooper())

    // ── State ─────────────────────────────────
    private var isRunning = false
    private var throwDelayMs = 1500L
    private var accuracyPx = 15f
    private var calibStep = CalibStep.IDLE
    private var calibTapView: View? = null

    // ── Overlay views ─────────────────────────
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var tvScore: TextView? = null
    private var tvNextTarget: TextView? = null
    private var tvStatus: TextView? = null
    private var btnStart: Button? = null

    enum class CalibStep { IDLE, WAITING_CENTER, WAITING_RADIUS, WAITING_ORIGIN }

    // ─────────────────────────────────────────
    //  Service lifecycle
    // ─────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        targetConfig = TargetConfig(this)
        gameEngine = GameEngine(currentMode)
        setupGameCallbacks()
        buildOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopThrowing() }

    override fun onDestroy() {
        super.onDestroy()
        stopThrowing()
        overlayView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        calibTapView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
    }

    // ─────────────────────────────────────────
    //  Overlay UI
    // ─────────────────────────────────────────

    private fun buildOverlay() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(242, 8, 10, 14))  // opaque dark
            setPadding(dp(12), dp(10), dp(12), dp(12))
            elevation = 8f
        }

        // ── Drag handle ──
        val dragBar = View(this).apply {
            setBackgroundColor(Color.argb(180, 180, 130, 40))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).also {
                it.gravity = Gravity.CENTER_HORIZONTAL
                it.bottomMargin = dp(8)
            }
        }
        root.addView(dragBar)

        // ── Title ──
        root.addView(label("AUTO DARTS", 11f, Color.rgb(180, 130, 40), bold = true, centerAlign = true))
        root.addView(label("NEXUS · KANEONEXUS.NET", 8f, Color.rgb(70, 50, 20), centerAlign = true).also {
            it.setPadding(0, 0, 0, dp(8))
        })

        // ── Mode selector ──
        root.addView(label("GAME MODE", 8f, Color.rgb(100, 75, 30)))
        val modeSpinner = Spinner(this).apply {
            val modes = arrayOf("Cricket", "301", "501", "701", "1001")
            adapter = ArrayAdapter(this@DartAccessibilityService,
                android.R.layout.simple_spinner_item, modes).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(2) // default 501
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    currentMode = arrayOf(
                        GameMode.CRICKET, GameMode.GAME_301, GameMode.GAME_501,
                        GameMode.GAME_701, GameMode.GAME_1001
                    )[pos]
                    resetGame()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        root.addView(modeSpinner)

        // ── Score / Cricket board display ──
        tvScore = label("Ready", 10f, Color.rgb(220, 190, 90)).also {
            it.setPadding(0, dp(6), 0, dp(2))
        }
        root.addView(tvScore)

        // ── Next target ──
        tvNextTarget = label("Next: —", 10f, Color.rgb(100, 180, 100))
        root.addView(tvNextTarget)

        // ── Status ──
        tvStatus = label("Stopped", 9f, Color.rgb(120, 120, 120))
        root.addView(tvStatus)

        // ── Speed slider ──
        root.addView(label("SPEED", 8f, Color.rgb(100, 75, 30)).also { it.setPadding(0, dp(8), 0, 0) })
        val speedSeek = SeekBar(this).apply {
            max = 18
            progress = 5   // ~1500ms
            setOnSeekBarChangeListener(seekListener { p ->
                throwDelayMs = (2000L - p * 100L).coerceAtLeast(200L)
            })
        }
        root.addView(speedSeek)

        val speedRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        speedRow.addView(label("Fast", 7f, Color.rgb(70, 50, 20)).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        speedRow.addView(label("Slow", 7f, Color.rgb(70, 50, 20)).also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        root.addView(speedRow)

        // ── Accuracy slider ──
        root.addView(label("ACCURACY", 8f, Color.rgb(100, 75, 30)).also { it.setPadding(0, dp(4), 0, 0) })
        val accSeek = SeekBar(this).apply {
            max = 70
            progress = 15
            setOnSeekBarChangeListener(seekListener { p -> accuracyPx = p.toFloat().coerceAtLeast(2f) })
        }
        root.addView(accSeek)

        val accRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        accRow.addView(label("Precise", 7f, Color.rgb(70, 50, 20)).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        accRow.addView(label("Wild", 7f, Color.rgb(70, 50, 20)).also {
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        root.addView(accRow)

        // ── Buttons ──
        val btnRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(10)
            }
        }

        btnStart = button("THROW", Color.rgb(25, 100, 50)) { toggleRunning() }.also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { lp -> lp.rightMargin = dp(4) }
        }
        btnRow1.addView(btnStart)

        val btnReset = button("RESET", Color.rgb(50, 30, 10)) { resetGame() }.also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { lp -> lp.leftMargin = dp(4) }
        }
        btnRow1.addView(btnReset)
        root.addView(btnRow1)

        // ── Calibration buttons ──
        val btnRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(4)
            }
        }

        val btnCalib = button("CALIBRATE", Color.rgb(40, 40, 80)) { startCalibration() }.also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { lp -> lp.rightMargin = dp(4) }
        }
        btnRow2.addView(btnCalib)

        val btnOneThrow = button("ONE", Color.rgb(60, 30, 60)) { if (targetConfig.isCalibrated()) throwDart() }.also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { lp -> lp.leftMargin = dp(4) }
            it.setTextColor(Color.rgb(200, 150, 200))
        }
        btnRow2.addView(btnOneThrow)
        root.addView(btnRow2)

        overlayView = root

        // ── Window params ──
        overlayParams = WindowManager.LayoutParams(
            dp(200),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8); y = dp(80)
        }

        // ── Drag support via drag handle ──
        var ix = 0; var iy = 0; var itx = 0; var ity = 0
        dragBar.setOnTouchListener { _, ev ->
            val p = overlayParams!!
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { ix = p.x; iy = p.y; itx = ev.rawX.toInt(); ity = ev.rawY.toInt() }
                MotionEvent.ACTION_MOVE -> {
                    p.x = ix + (ev.rawX - itx).toInt()
                    p.y = iy + (ev.rawY - ity).toInt()
                    wm.updateViewLayout(overlayView, p)
                }
            }
            true
        }

        wm.addView(overlayView, overlayParams)
    }

    // ─────────────────────────────────────────
    //  Calibration flow
    // ─────────────────────────────────────────

    private fun startCalibration() {
        if (calibStep != CalibStep.IDLE) return
        calibStep = CalibStep.WAITING_CENTER
        tvStatus?.setTextColor(Color.rgb(240, 180, 40))
        tvStatus?.text = "Tap board CENTER"
        showCalibOverlay()
    }

    private fun showCalibOverlay() {
        if (calibTapView != null) return

        // Semi-transparent gold tint so user sees the calibration is active
        calibTapView = View(this).apply {
            setBackgroundColor(Color.argb(35, 200, 160, 40))
        }

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        calibTapView!!.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) handleCalibTap(ev.rawX, ev.rawY)
            true
        }

        wm.addView(calibTapView, p)
    }

    private fun dismissCalibOverlay() {
        calibTapView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        calibTapView = null
    }

    private fun handleCalibTap(x: Float, y: Float) {
        when (calibStep) {
            CalibStep.WAITING_CENTER -> {
                targetConfig.setBoardCenter(x, y)
                calibStep = CalibStep.WAITING_RADIUS
                tvStatus?.text = "Tap board EDGE (for radius)"
            }
            CalibStep.WAITING_RADIUS -> {
                val center = targetConfig.getBoardCenter()!!
                val radius = Math.sqrt(
                    ((x - center.x) * (x - center.x) + (y - center.y) * (y - center.y)).toDouble()
                ).toFloat()
                targetConfig.autoGenerateFromCenter(radius)
                calibStep = CalibStep.WAITING_ORIGIN
                tvStatus?.text = "Tap THROW ORIGIN (below board)"
            }
            CalibStep.WAITING_ORIGIN -> {
                targetConfig.setThrowOrigin(x, y)
                calibStep = CalibStep.IDLE
                dismissCalibOverlay()
                tvStatus?.setTextColor(Color.rgb(100, 200, 100))
                tvStatus?.text = "Calibrated! Ready."
                updateNextTargetLabel()
            }
            CalibStep.IDLE -> { dismissCalibOverlay() }
        }
    }

    // ─────────────────────────────────────────
    //  Throw logic
    // ─────────────────────────────────────────

    private fun toggleRunning() {
        if (isRunning) stopThrowing() else startThrowing()
    }

    private fun startThrowing() {
        if (!targetConfig.isCalibrated()) {
            tvStatus?.setTextColor(Color.rgb(220, 80, 60))
            tvStatus?.text = "Calibrate first!"
            return
        }
        isRunning = true
        btnStart?.text = "STOP"
        btnStart?.setBackgroundColor(Color.rgb(140, 25, 20))
        tvStatus?.setTextColor(Color.rgb(100, 200, 100))
        tvStatus?.text = "Throwing…"
        scheduleThrow()
    }

    private fun stopThrowing() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        btnStart?.text = "THROW"
        btnStart?.setBackgroundColor(Color.rgb(25, 100, 50))
        tvStatus?.setTextColor(Color.rgb(120, 120, 120))
        tvStatus?.text = "Stopped"
    }

    private fun scheduleThrow() {
        if (!isRunning) return
        handler.postDelayed({
            if (isRunning) {
                throwDart()
                scheduleThrow()
            }
        }, throwDelayMs)
    }

    private fun throwDart() {
        val engine = gameEngine ?: return
        val target = engine.getNextTarget()

        // Resolve screen coordinates
        val screen = targetConfig.getTarget(target.segment, target.multiplier)
            ?: targetConfig.getBoardCenter()
            ?: return

        // Jitter for accuracy simulation
        val jit = { Random.nextFloat() * 2f - 1f }
        val tx = screen.x + jit() * accuracyPx
        val ty = screen.y + jit() * accuracyPx

        // Throw origin (start of swipe gesture)
        val origin = targetConfig.getThrowOrigin()
        val ox = origin?.x ?: tx + jit() * 15f
        val oy = origin?.y ?: (ty + 100f)

        // Build swipe path (quadratic arc, like a real throw)
        val path = Path().apply {
            moveTo(ox, oy)
            // Control point slightly offset for natural arc
            quadTo(
                ox + jit() * 25f,
                oy - (oy - ty) * 0.5f,
                tx, ty
            )
        }

        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,   // start delay
            150L  // duration ms — fast swipe
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gd: GestureDescription?) {
                handler.post {
                    engine.recordHit(target.segment, target.multiplier)
                    updateNextTargetLabel()
                }
            }
            override fun onCancelled(gd: GestureDescription?) {
                handler.post { tvStatus?.text = "Gesture blocked" }
            }
        }, null)
    }

    // ─────────────────────────────────────────
    //  Game helpers
    // ─────────────────────────────────────────

    private fun setupGameCallbacks() {
        gameEngine?.onScoreUpdate = {
            handler.post {
                tvScore?.text = gameEngine?.getScoreDisplay()
            }
        }
        gameEngine?.onGameOver = { msg ->
            handler.post {
                stopThrowing()
                tvStatus?.setTextColor(Color.rgb(240, 200, 60))
                tvStatus?.text = msg
                tvNextTarget?.text = "Next: —"
            }
        }
    }

    private fun resetGame() {
        stopThrowing()
        gameEngine = GameEngine(currentMode)
        setupGameCallbacks()
        tvScore?.text = "Ready"
        tvStatus?.text = "Ready — ${currentMode.label}"
        tvStatus?.setTextColor(Color.rgb(120, 120, 120))
        updateNextTargetLabel()
    }

    private fun updateNextTargetLabel() {
        tvNextTarget?.text = "Next: ${gameEngine?.getNextTargetLabel() ?: "—"}"
    }

    // ─────────────────────────────────────────
    //  View factory helpers
    // ─────────────────────────────────────────

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false, centerAlign: Boolean = false) =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = sp
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (centerAlign) gravity = Gravity.CENTER
        }

    private fun button(text: String, bgColor: Int, onClick: () -> Unit) =
        Button(this).apply {
            this.text = text
            setBackgroundColor(bgColor)
            setTextColor(Color.WHITE)
            textSize = 9f
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { onClick() }
        }

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) = onChange(p)
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
