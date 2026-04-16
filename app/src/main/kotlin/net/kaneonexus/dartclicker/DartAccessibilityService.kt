package net.kaneonexus.dartclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import kotlin.random.Random

class DartAccessibilityService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var targetConfig: TargetConfig
    private var gameEngine: GameEngine? = null
    private var currentMode = GameMode.GAME_501
    private val handler = Handler(Looper.getMainLooper())

    private var isRunning = false
    private var throwDelayMs = 1500L
    private var accuracyPx = 15f
    private var flickDp = 120

    private var dartsThisTurn = 0
    private var waitForOpponent = false
    private var awaitingOpponent = false

    private var tapAimMode = false
    private var manualTarget: ScreenTarget? = null
    private var tapAimOverlay: View? = null
    private var crosshairView: View? = null
    private var crosshairParams: WindowManager.LayoutParams? = null

    private var calibStep = CalibStep.IDLE
    private var calibCrosshair: View? = null
    private var calibCrosshairParams: WindowManager.LayoutParams? = null
    private var calibCrosshairX = 0f
    private var calibCrosshairY = 0f
    private var calibConfirmBtn: View? = null

    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var expandedContent: LinearLayout? = null
    private var isExpanded = true

    private var tvScore: TextView? = null
    private var tvNextTarget: TextView? = null
    private var tvStatus: TextView? = null
    private var tvTurnCounter: TextView? = null
    private var tvAimCoords: TextView? = null
    private var btnStart: Button? = null
    private var btnOpponent: Button? = null
    private var btnWaitToggle: Button? = null
    private var btnTapAim: Button? = null
    private var btnExpandToggle: TextView? = null

    private val BG_ALPHA = 195
    private val RESIZE_STEPS = intArrayOf(160, 200, 250, 300)
    private var resizeIndex = 1

    enum class CalibStep { IDLE, DRAG_CENTER, DRAG_EDGE }

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
        listOf(overlayView, tapAimOverlay, crosshairView, calibCrosshair, calibConfirmBtn).forEach {
            it?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        }
    }

    private fun buildOverlay() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(BG_ALPHA, 6, 8, 12))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val dragHandle = View(this).apply {
            setBackgroundColor(Color.argb(160, 180, 130, 40))
            layoutParams = LinearLayout.LayoutParams(dp(4), dp(28)).also { it.rightMargin = dp(8) }
        }
        topBar.addView(dragHandle)

        topBar.addView(label("AUTO DARTS", 10f, Color.rgb(180, 130, 40), bold = true).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        tvTurnCounter = label(dartCounterText(), 13f, Color.rgb(80, 60, 20)).also {
            it.letterSpacing = 0.2f
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { lp -> lp.rightMargin = dp(5) }
        }
        topBar.addView(tvTurnCounter)

        topBar.addView(TextView(this).apply {
            text = "⇔"
            setTextColor(Color.rgb(100, 140, 180))
            textSize = 13f
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { cycleResize() }
        })

        btnExpandToggle = TextView(this).apply {
            text = "▲"
            setTextColor(Color.rgb(140, 100, 30))
            textSize = 12f
            setPadding(dp(2), 0, dp(4), 0)
            setOnClickListener { toggleExpanded() }
        }
        topBar.addView(btnExpandToggle)

        topBar.addView(TextView(this).apply {
            text = "✕"
            setTextColor(Color.rgb(200, 60, 60))
            textSize = 14f
            setPadding(dp(4), 0, 0, 0)
            setOnClickListener { closeOverlay() }
        })

        root.addView(topBar)

        val alwaysRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(6) }
        }
        btnStart = button("THROW", Color.rgb(25, 100, 50)) { toggleRunning() }.also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { lp -> lp.rightMargin = dp(4) }
        }
        alwaysRow.addView(btnStart)
        tvStatus = label("Ready", 8f, Color.rgb(100, 150, 100)).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .also { lp -> lp.leftMargin = dp(4) }
            it.gravity = Gravity.CENTER_VERTICAL
        }
        alwaysRow.addView(tvStatus)
        root.addView(alwaysRow)

        expandedContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
        }

        expandedContent!!.addView(divider(dp(6)))

        tvScore = label("Ready", 9f, Color.rgb(200, 170, 80)).also { it.setPadding(0, 0, 0, dp(2)) }
        expandedContent!!.addView(tvScore)

        val targetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tvNextTarget = label("Next: —", 9f, Color.rgb(80, 160, 80)).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        targetRow.addView(tvNextTarget)
        tvAimCoords = label("", 8f, Color.rgb(160, 120, 40))
        targetRow.addView(tvAimCoords)
        expandedContent!!.addView(targetRow)

        expandedContent!!.addView(divider(dp(6)))

        expandedContent!!.addView(label("MODE", 7f, Color.rgb(90, 65, 25)))
        expandedContent!!.addView(buildModeSpinner())

        btnTapAim = button(tapAimLabel(), tapAimColor()) { toggleTapAim() }.also {
            it.layoutParams = fullWidthLp(topMargin = dp(5))
        }
        expandedContent!!.addView(btnTapAim)

        expandedContent!!.addView(button("⊙  CALIBRATE BOARD", Color.rgb(30, 30, 70)) {
            startDragCalibration()
        }.also { it.layoutParams = fullWidthLp(topMargin = dp(4)) })

        expandedContent!!.addView(divider(dp(6)))

        btnWaitToggle = button(waitLabel(), waitColor()) { toggleWaitMode() }.also {
            it.layoutParams = fullWidthLp()
        }
        expandedContent!!.addView(btnWaitToggle)

        btnOpponent = button("▶   YOUR TURN", Color.rgb(15, 50, 100)) { resumeAfterOpponent() }.also {
            it.setTextColor(Color.rgb(100, 170, 255))
            it.visibility = View.GONE
            it.layoutParams = fullWidthLp(topMargin = dp(3))
        }
        expandedContent!!.addView(btnOpponent)

        expandedContent!!.addView(divider(dp(6)))

        expandedContent!!.addView(label("SPEED", 7f, Color.rgb(90, 65, 25)))
        expandedContent!!.addView(SeekBar(this).apply {
            max = 18; progress = 5
            setOnSeekBarChangeListener(seekListener { p ->
                throwDelayMs = (2000L - p * 100L).coerceAtLeast(200L)
            })
        })
        expandedContent!!.addView(sliderRow("Fast", "Slow"))

        expandedContent!!.addView(label("ACCURACY", 7f, Color.rgb(90, 65, 25)).also { it.setPadding(0, dp(3), 0, 0) })
        expandedContent!!.addView(SeekBar(this).apply {
            max = 70; progress = 15
            setOnSeekBarChangeListener(seekListener { p -> accuracyPx = p.toFloat().coerceAtLeast(2f) })
        })
        expandedContent!!.addView(sliderRow("Precise", "Wild"))

        expandedContent!!.addView(label("FLICK DISTANCE", 7f, Color.rgb(90, 65, 25)).also { it.setPadding(0, dp(3), 0, 0) })
        expandedContent!!.addView(SeekBar(this).apply {
            max = 40; progress = 12
            setOnSeekBarChangeListener(seekListener { p -> flickDp = 60 + p * 5 })
        })
        expandedContent!!.addView(sliderRow("Short", "Long"))

        expandedContent!!.addView(hRow(dp(5)).also { row ->
            row.addView(button("RESET", Color.rgb(50, 30, 10)) { resetGame() }.also {
                it.layoutParams = splitLp(rightMargin = dp(3))
            })
            row.addView(button("ONE", Color.rgb(50, 25, 50)) {
                if (targetConfig.isCalibrated() || manualTarget != null) throwDart()
            }.also {
                it.setTextColor(Color.rgb(200, 150, 200))
                it.layoutParams = splitLp(leftMargin = dp(3))
            })
        })

        root.addView(expandedContent)
        overlayView = root

        overlayParams = WindowManager.LayoutParams(
            dp(RESIZE_STEPS[resizeIndex]),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(6); y = dp(60) }

        var ix = 0; var iy = 0; var itx = 0; var ity = 0
        dragHandle.setOnTouchListener { _, ev ->
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

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        expandedContent?.visibility = if (isExpanded) View.VISIBLE else View.GONE
        btnExpandToggle?.text = if (isExpanded) "▲" else "▼"
    }

    private fun cycleResize() {
        resizeIndex = (resizeIndex + 1) % RESIZE_STEPS.size
        overlayParams?.width = dp(RESIZE_STEPS[resizeIndex])
        try { wm.updateViewLayout(overlayView, overlayParams) } catch (_: Exception) {}
    }

    private fun closeOverlay() {
        stopThrowing()
        listOf(overlayView, tapAimOverlay, crosshairView, calibCrosshair, calibConfirmBtn).forEach {
            it?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        }
        overlayView = null
    }

    private fun buildModeSpinner() = Spinner(this).apply {
        val modes = arrayOf("Cricket", "301", "501", "701", "1001")
        adapter = ArrayAdapter(this@DartAccessibilityService,
            android.R.layout.simple_spinner_item, modes).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setSelection(2)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentMode = arrayOf(GameMode.CRICKET, GameMode.GAME_301, GameMode.GAME_501,
                    GameMode.GAME_701, GameMode.GAME_1001)[pos]
                resetGame()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun startDragCalibration() {
        if (calibStep != CalibStep.IDLE) return
        calibStep = CalibStep.DRAG_CENTER
        val dm = resources.displayMetrics
        calibCrosshairX = dm.widthPixels / 2f
        calibCrosshairY = dm.heightPixels / 2f
        showCalibCrosshair("Drag to BULLSEYE\nthen tap CONFIRM", Color.rgb(240, 200, 60))
        tvStatus?.apply { setTextColor(Color.rgb(240, 180, 40)); text = "Drag to bullseye" }
    }

    private fun showCalibCrosshair(instruction: String, color: Int) {
        dismissCalibCrosshair()
        val size = dp(80)
        calibCrosshair = object : View(this) {
            override fun onDraw(canvas: Canvas) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color; strokeWidth = dp(3).toFloat(); style = Paint.Style.STROKE
                }
                val cx = width / 2f; val cy = height / 2f; val r = width / 2f - dp(4)
                canvas.drawCircle(cx, cy, r, paint)
                canvas.drawCircle(cx, cy, dp(4).toFloat(), paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(100, (color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF)
                canvas.drawCircle(cx, cy, dp(4).toFloat(), paint)
                paint.style = Paint.Style.STROKE; paint.color = color
                val arm = r + dp(8); val gap = dp(6).toFloat()
                canvas.drawLine(cx - arm, cy, cx - gap, cy, paint)
                canvas.drawLine(cx + gap, cy, cx + arm, cy, paint)
                canvas.drawLine(cx, cy - arm, cx, cy - gap, paint)
                canvas.drawLine(cx, cy + gap, cx, cy + arm, paint)
            }
        }
        calibCrosshairParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (calibCrosshairX - size / 2).toInt(); y = (calibCrosshairY - size / 2).toInt()
        }
        var dx = 0f; var dy = 0f
        calibCrosshair!!.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { dx = ev.rawX - calibCrosshairX; dy = ev.rawY - calibCrosshairY }
                MotionEvent.ACTION_MOVE -> {
                    calibCrosshairX = ev.rawX - dx; calibCrosshairY = ev.rawY - dy
                    calibCrosshairParams?.apply {
                        x = (calibCrosshairX - size / 2).toInt(); y = (calibCrosshairY - size / 2).toInt()
                    }
                    try { wm.updateViewLayout(calibCrosshair, calibCrosshairParams) } catch (_: Exception) {}
                }
            }
            true
        }
        wm.addView(calibCrosshair, calibCrosshairParams)
        showCalibConfirmButton(instruction, color)
    }

    private fun showCalibConfirmButton(instruction: String, color: Int) {
        calibConfirmBtn?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        val btn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(220, 8, 10, 14))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        btn.addView(TextView(this).apply {
            text = instruction; setTextColor(color); textSize = 10f; gravity = Gravity.CENTER
        })
        btn.addView(Button(this).apply {
            text = "✓  CONFIRM"; setBackgroundColor(Color.rgb(30, 80, 30)); setTextColor(Color.WHITE)
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(6) }
            setOnClickListener { handleCalibConfirm() }
        })
        btn.addView(Button(this).apply {
            text = "✕  Cancel"; setBackgroundColor(Color.rgb(60, 20, 20)); setTextColor(Color.rgb(200, 100, 100))
            textSize = 9f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(3) }
            setOnClickListener { cancelCalibration() }
        })
        calibConfirmBtn = btn
        val p = WindowManager.LayoutParams(
            dp(180), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(40) }
        wm.addView(calibConfirmBtn, p)
    }

    private fun handleCalibConfirm() {
        when (calibStep) {
            CalibStep.DRAG_CENTER -> {
                targetConfig.setBoardCenter(calibCrosshairX, calibCrosshairY)
                calibStep = CalibStep.DRAG_EDGE
                calibCrosshairX += resources.displayMetrics.widthPixels * 0.25f
                calibCrosshairParams?.apply { x = (calibCrosshairX - dp(40)).toInt() }
                try { wm.updateViewLayout(calibCrosshair, calibCrosshairParams) } catch (_: Exception) {}
                showCalibCrosshair("Drag to board EDGE\nthen tap CONFIRM", Color.rgb(100, 200, 240))
            }
            CalibStep.DRAG_EDGE -> {
                val center = targetConfig.getBoardCenter()!!
                val r = Math.sqrt(
                    ((calibCrosshairX - center.x) * (calibCrosshairX - center.x) +
                     (calibCrosshairY - center.y) * (calibCrosshairY - center.y)).toDouble()
                ).toFloat()
                targetConfig.autoGenerateFromCenter(r)
                targetConfig.setThrowOrigin(center.x, center.y + r + dp(30))
                finishCalibration()
            }
            else -> cancelCalibration()
        }
    }

    private fun finishCalibration() {
        calibStep = CalibStep.IDLE; dismissCalibCrosshair()
        tvStatus?.apply { setTextColor(Color.rgb(80, 200, 80)); text = "Calibrated!" }
        updateNextTargetLabel()
    }

    private fun cancelCalibration() {
        calibStep = CalibStep.IDLE; dismissCalibCrosshair()
        tvStatus?.apply { setTextColor(Color.rgb(120, 120, 120)); text = "Cancelled" }
    }

    private fun dismissCalibCrosshair() {
        calibCrosshair?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        calibCrosshair = null; calibCrosshairParams = null
        calibConfirmBtn?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        calibConfirmBtn = null
    }

    private fun toggleTapAim() {
        tapAimMode = !tapAimMode
        btnTapAim?.text = tapAimLabel(); btnTapAim?.setBackgroundColor(tapAimColor())
        if (tapAimMode) {
            showTapAimOverlay()
            tvStatus?.apply { setTextColor(Color.rgb(240, 180, 40)); text = "Tap board to aim" }
        } else {
            dismissTapAimOverlay(); dismissCrosshair(); manualTarget = null; updateAimCoordsLabel()
            tvStatus?.apply { setTextColor(Color.rgb(120, 120, 120)); text = "Auto aim" }
        }
    }

    private fun showTapAimOverlay() {
        if (tapAimOverlay != null) return
        tapAimOverlay = View(this).apply { setBackgroundColor(Color.argb(12, 200, 160, 40)) }
        val p = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        tapAimOverlay!!.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN && tapAimMode) setManualTarget(ev.rawX, ev.rawY)
            false
        }
        wm.addView(tapAimOverlay, p)
    }

    private fun dismissTapAimOverlay() {
        tapAimOverlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }; tapAimOverlay = null
    }

    private fun setManualTarget(x: Float, y: Float) {
        manualTarget = ScreenTarget(x, y, accuracyPx); updateAimCoordsLabel(); moveCrosshair(x, y)
    }

    private fun updateAimCoordsLabel() {
        val mt = manualTarget
        tvAimCoords?.apply {
            text = if (mt != null) "${mt.x.toInt()},${mt.y.toInt()}" else ""
            setTextColor(Color.rgb(180, 140, 50))
        }
        if (tapAimMode && manualTarget != null) {
            tvNextTarget?.apply { text = "Next: tap aim"; setTextColor(Color.rgb(220, 170, 40)) }
        } else {
            tvNextTarget?.setTextColor(Color.rgb(80, 160, 80)); updateNextTargetLabel()
        }
    }

    private fun moveCrosshair(x: Float, y: Float) {
        val size = dp(40)
        if (crosshairView == null) {
            crosshairView = object : View(this) {
                override fun onDraw(canvas: Canvas) {
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.rgb(240, 200, 60); strokeWidth = dp(2).toFloat(); style = Paint.Style.STROKE
                    }
                    val cx = width / 2f; val cy = height / 2f; val r = width / 2f - dp(3)
                    canvas.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.FILL; paint.color = Color.argb(180, 240, 200, 60)
                    canvas.drawCircle(cx, cy, dp(3).toFloat(), paint)
                    paint.style = Paint.Style.STROKE; paint.color = Color.rgb(240, 200, 60)
                    val arm = r + dp(5); val gap = dp(4).toFloat()
                    canvas.drawLine(cx - arm, cy, cx - gap, cy, paint)
                    canvas.drawLine(cx + gap, cy, cx + arm, cy, paint)
                    canvas.drawLine(cx, cy - arm, cx, cy - gap, paint)
                    canvas.drawLine(cx, cy + gap, cx, cy + arm, paint)
                }
            }
            crosshairParams = WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = (x - size / 2).toInt(); this.y = (y - size / 2).toInt()
            }
            wm.addView(crosshairView, crosshairParams)
        } else {
            crosshairParams?.apply { this.x = (x - size / 2).toInt(); this.y = (y - size / 2).toInt() }
            try { wm.updateViewLayout(crosshairView, crosshairParams) } catch (_: Exception) {}
        }
    }

    private fun dismissCrosshair() {
        crosshairView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        crosshairView = null; crosshairParams = null
    }

    private fun tapAimLabel() = if (tapAimMode) "⊕  Tap Aim: ON" else "⊕  Tap Aim: OFF"
    private fun tapAimColor() = if (tapAimMode) Color.rgb(70, 50, 10) else Color.rgb(25, 20, 10)

    private fun dartCounterText() = (1..3).joinToString(" ") { i -> if (i <= dartsThisTurn) "◉" else "○" }

    private fun updateTurnCounter() {
        tvTurnCounter?.apply {
            text = dartCounterText()
            setTextColor(when (dartsThisTurn) {
                0 -> Color.rgb(60, 45, 15); 3 -> Color.rgb(240, 200, 60); else -> Color.rgb(180, 140, 50)
            })
        }
    }

    private fun toggleWaitMode() {
        waitForOpponent = !waitForOpponent
        btnWaitToggle?.text = waitLabel(); btnWaitToggle?.setBackgroundColor(waitColor())
        if (!waitForOpponent && awaitingOpponent) resumeAfterOpponent()
    }

    private fun endOfMyTurn() {
        awaitingOpponent = true; isRunning = false; handler.removeCallbacksAndMessages(null)
        btnStart?.apply { text = "THROW"; setBackgroundColor(Color.rgb(25, 100, 50)); isEnabled = false }
        btnOpponent?.visibility = View.VISIBLE
        tvStatus?.apply { setTextColor(Color.rgb(80, 130, 210)); text = "Opponent…" }
    }

    private fun resumeAfterOpponent() {
        awaitingOpponent = false; dartsThisTurn = 0; updateTurnCounter()
        btnOpponent?.visibility = View.GONE; btnStart?.isEnabled = true; startThrowing()
    }

    private fun waitLabel() = if (waitForOpponent) "⏸  Wait: ON" else "⏸  Wait: OFF"
    private fun waitColor() = if (waitForOpponent) Color.rgb(20, 50, 85) else Color.rgb(25, 20, 10)

    private fun toggleRunning() {
        if (awaitingOpponent) return
        if (isRunning) stopThrowing() else startThrowing()
    }

    private fun startThrowing() {
        val ready = targetConfig.isCalibrated() || (tapAimMode && manualTarget != null)
        if (!ready) { tvStatus?.apply { setTextColor(Color.rgb(220, 80, 60)); text = "Calibrate first!" }; return }
        isRunning = true
        btnStart?.apply { text = "STOP"; setBackgroundColor(Color.rgb(140, 25, 20)) }
        tvStatus?.apply { setTextColor(Color.rgb(80, 180, 80)); text = "Throwing…" }
        scheduleThrow()
    }

    private fun stopThrowing() {
        isRunning = false; awaitingOpponent = false; handler.removeCallbacksAndMessages(null)
        btnStart?.apply { text = "THROW"; isEnabled = true; setBackgroundColor(Color.rgb(25, 100, 50)) }
        btnOpponent?.visibility = View.GONE
        tvStatus?.apply { setTextColor(Color.rgb(100, 100, 100)); text = "Stopped" }
    }

    private fun scheduleThrow() {
        if (!isRunning) return
        handler.postDelayed({ if (isRunning) throwDart() }, throwDelayMs)
    }

    private fun throwDart() {
        val engine = gameEngine ?: return
        val screen: ScreenTarget = when {
            tapAimMode && manualTarget != null -> manualTarget!!
            else -> {
                val t = engine.getNextTarget()
                targetConfig.getTarget(t.segment, t.multiplier) ?: targetConfig.getBoardCenter() ?: return
            }
        }

        // END point = where dart lands. Start below, flick up, release AT target.
        val jit = { Random.nextFloat() * 2f - 1f }
        val endX = screen.x + jit() * accuracyPx
        val endY = screen.y + jit() * accuracyPx
        val flickDistance = dp(flickDp).toFloat()

        val path = Path().apply {
            moveTo(endX + jit() * dp(8), endY + flickDistance)
            lineTo(endX, endY)
        }

        dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 120L))
                .build(),
            object : GestureResultCallback() {
                override fun onCompleted(gd: GestureDescription?) {
                    handler.post {
                        if (!tapAimMode || manualTarget == null) {
                            val t = engine.getNextTarget()
                            engine.recordHit(t.segment, t.multiplier)
                            updateNextTargetLabel()
                        }
                        onDartLanded()
                    }
                }
                override fun onCancelled(gd: GestureDescription?) {
                    handler.post { tvStatus?.text = "Blocked"; onDartLanded() }
                }
            }, null
        )
    }

    private fun onDartLanded() {
        dartsThisTurn++; updateTurnCounter()
        if (dartsThisTurn >= 3) {
            dartsThisTurn = 0
            if (waitForOpponent) endOfMyTurn() else { updateTurnCounter(); if (isRunning) scheduleThrow() }
        } else { if (isRunning) scheduleThrow() }
    }

    private fun setupGameCallbacks() {
        gameEngine?.onScoreUpdate = { handler.post { tvScore?.text = gameEngine?.getScoreDisplay() } }
        gameEngine?.onGameOver = { msg ->
            handler.post {
                stopThrowing()
                tvStatus?.apply { setTextColor(Color.rgb(240, 200, 60)); text = msg }
                tvNextTarget?.text = "—"; tvTurnCounter?.text = "◉ ◉ ◉"
            }
        }
    }

    private fun resetGame() {
        stopThrowing(); dartsThisTurn = 0; awaitingOpponent = false; updateTurnCounter()
        gameEngine = GameEngine(currentMode); setupGameCallbacks()
        tvScore?.text = "Ready"
        tvStatus?.apply { setTextColor(Color.rgb(100, 100, 100)); text = "${currentMode.label} ready" }
        btnOpponent?.visibility = View.GONE; btnStart?.isEnabled = true; updateNextTargetLabel()
    }

    private fun updateNextTargetLabel() {
        if (tapAimMode && manualTarget != null) tvNextTarget?.text = "Next: tap"
        else tvNextTarget?.text = "Next: ${gameEngine?.getNextTargetLabel() ?: "—"}"
    }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false, center: Boolean = false) =
        TextView(this).apply {
            this.text = text; setTextColor(color); textSize = sp
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (center) gravity = Gravity.CENTER
        }

    private fun button(text: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text; setBackgroundColor(bg); setTextColor(Color.WHITE)
        textSize = 9f; setPadding(dp(3), dp(3), dp(3), dp(3))
        setOnClickListener { onClick() }
    }

    private fun divider(vertMargin: Int = dp(4)) = View(this).apply {
        setBackgroundColor(Color.argb(60, 180, 130, 40))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            .also { it.topMargin = vertMargin; it.bottomMargin = vertMargin }
    }

    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) = onChange(p)
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun hRow(topMargin: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = topMargin }
    }

    private fun fullWidthLp(topMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = topMargin }

    private fun splitLp(leftMargin: Int = 0, rightMargin: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
            it.leftMargin = leftMargin; it.rightMargin = rightMargin
        }

    private fun sliderRow(left: String, right: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(label(left, 7f, Color.rgb(70, 50, 20)).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(label(right, 7f, Color.rgb(70, 50, 20)))
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
