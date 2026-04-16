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

    // ── Throw state ───────────────────────────
    private var isRunning = false
    private var throwDelayMs = 2000L
    private var accuracyPx = 12f
    private var flickDp = 150      // swipe distance — end point lands dart

    // ── Turn state ────────────────────────────
    private var dartsThisTurn = 0
    private var waitingForNextRound = false
    private var waitForOpponent = false
    private var awaitingOpponent = false

    // ── Manual target override ─────────────────
    // Set from the target picker after each round — no tap overlay needed
    private var manualTargetSeg = 0
    private var manualTargetMult = 0

    // ── Calibration ───────────────────────────
    // Circle + crosshair overlay the user drags and resizes to fit the board
    private var calibActive = false
    private var calibStage = 0
    private var calibTrebleR = 0f
    private var calibDoubleR = 0f
    private var calibCircleView: View? = null
    private var calibCircleParams: WindowManager.LayoutParams? = null
    private var calibPromptView: LinearLayout? = null
    private var calibPromptParams: WindowManager.LayoutParams? = null
    private var calibCx = 0f
    private var calibCy = 0f
    private var calibR  = 0f

    // ── Overlay ───────────────────────────────
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var expandedContent: LinearLayout? = null
    private var isExpanded = true
    private var turnEndPanel: LinearLayout? = null

    // ── UI refs ───────────────────────────────
    private var tvScore: TextView? = null
    private var tvNextTarget: TextView? = null
    private var tvStatus: TextView? = null
    private var tvTurnCounter: TextView? = null
    private var btnStart: Button? = null
    private var btnOpponent: Button? = null
    private var btnWaitToggle: Button? = null
    private var btnExpandToggle: TextView? = null

    private val BG_ALPHA = 200
    private val RESIZE_STEPS = intArrayOf(160, 210, 260, 310)
    private var resizeIndex = 1

    // ─────────────────────────────────────────
    //  Lifecycle
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
        listOf(overlayView, calibCircleView, calibPromptView).forEach {
            it?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        }
    }

    // ─────────────────────────────────────────
    //  Overlay UI
    // ─────────────────────────────────────────

    private fun buildOverlay() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(BG_ALPHA, 6, 8, 12))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        // ── Top bar ──
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
        tvTurnCounter = label(dartCounterText(), 14f, Color.rgb(80, 60, 20)).also {
            it.letterSpacing = 0.15f
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { lp -> lp.rightMargin = dp(5) }
        }
        topBar.addView(tvTurnCounter)
        topBar.addView(TextView(this).apply {
            text = "⇔"; setTextColor(Color.rgb(100, 140, 180)); textSize = 13f
            setPadding(dp(3), 0, dp(3), 0); setOnClickListener { cycleResize() }
        })
        btnExpandToggle = TextView(this).apply {
            text = "▲"; setTextColor(Color.rgb(140, 100, 30)); textSize = 12f
            setPadding(dp(2), 0, dp(3), 0); setOnClickListener { toggleExpanded() }
        }
        topBar.addView(btnExpandToggle)
        topBar.addView(TextView(this).apply {
            text = "✕"; setTextColor(Color.rgb(200, 60, 60)); textSize = 14f
            setPadding(dp(3), 0, 0, 0); setOnClickListener { closeOverlay() }
        })
        root.addView(topBar)

        // ── THROW row (always visible) ──
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

        // ── After-3-darts panel (hidden until turn ends) ──
        buildTurnEndPanel(root)

        // ── Expanded settings ──
        expandedContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
        }

        expandedContent!!.addView(divider(dp(6)))

        tvScore = label("Ready", 9f, Color.rgb(200, 170, 80)).also { it.setPadding(0, 0, 0, dp(2)) }
        expandedContent!!.addView(tvScore)

        tvNextTarget = label("Next: —", 9f, Color.rgb(80, 160, 80))
        expandedContent!!.addView(tvNextTarget)

        expandedContent!!.addView(divider(dp(5)))

        // Mode
        expandedContent!!.addView(label("MODE", 7f, Color.rgb(90, 65, 25)))
        expandedContent!!.addView(buildModeSpinner())

        // Calibrate button
        expandedContent!!.addView(button("⊙  CALIBRATE BOARD", Color.rgb(30, 30, 80)) {
            startCircleCalibration()
        }.also { it.layoutParams = fullWidthLp(topMargin = dp(6)) })

        expandedContent!!.addView(divider(dp(5)))

        // Speed
        expandedContent!!.addView(label("SPEED  (between darts)", 7f, Color.rgb(90, 65, 25)))
        expandedContent!!.addView(SeekBar(this).apply {
            max = 20; progress = 5   // default ~2000ms
            setOnSeekBarChangeListener(seekListener { p ->
                throwDelayMs = (3000L - p * 130L).coerceAtLeast(400L)
            })
        })
        expandedContent!!.addView(sliderRow("Fast", "Slow"))

        // Accuracy
        expandedContent!!.addView(label("ACCURACY", 7f, Color.rgb(90, 65, 25)).also { it.setPadding(0, dp(3), 0, 0) })
        expandedContent!!.addView(SeekBar(this).apply {
            max = 60; progress = 12
            setOnSeekBarChangeListener(seekListener { p -> accuracyPx = (p + 2).toFloat() })
        })
        expandedContent!!.addView(sliderRow("Precise", "Wild"))

        // Flick distance
        expandedContent!!.addView(label("FLICK DISTANCE", 7f, Color.rgb(90, 65, 25)).also { it.setPadding(0, dp(3), 0, 0) })
        expandedContent!!.addView(SeekBar(this).apply {
            max = 40; progress = 15
            setOnSeekBarChangeListener(seekListener { p -> flickDp = 80 + p * 5 })
        })
        expandedContent!!.addView(sliderRow("Short", "Long"))

        // RESET + ONE
        expandedContent!!.addView(hRow(dp(6)).also { row ->
            row.addView(button("RESET", Color.rgb(50, 30, 10)) { resetGame() }.also {
                it.layoutParams = splitLp(rightMargin = dp(3))
            })
            row.addView(button("ONE DART", Color.rgb(50, 25, 50)) {
                throwOneDart()
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

        // Drag
        var ix = 0; var iy = 0; var itx = 0; var ity = 0
        dragHandle.setOnTouchListener { _, ev ->
            val p = overlayParams!!
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { ix = p.x; iy = p.y; itx = ev.rawX.toInt(); ity = ev.rawY.toInt() }
                MotionEvent.ACTION_MOVE -> {
                    p.x = ix + (ev.rawX - itx).toInt(); p.y = iy + (ev.rawY - ity).toInt()
                    wm.updateViewLayout(overlayView, p)
                }
            }
            true
        }

        wm.addView(overlayView, overlayParams)
    }

    private fun buildTurnEndPanel(root: LinearLayout) {
        turnEndPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.argb(100, 10, 30, 10))
            setPadding(dp(6), dp(8), dp(6), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(4) }
        }

        turnEndPanel!!.addView(label("── 3 DARTS THROWN ──", 8f,
            Color.rgb(140, 200, 100), center = true))
        turnEndPanel!!.addView(label("Next target:", 7f, Color.rgb(100, 140, 80)).also {
            it.setPadding(0, dp(4), 0, 0)
        })

        // Row 1: Trebles
        val r1 = hRow(dp(3))
        listOf("T20" to Pair(20,3), "T19" to Pair(19,3), "T18" to Pair(18,3), "T17" to Pair(17,3))
            .forEach { (l, s) -> r1.addView(segBtn(l, Color.rgb(180, 60, 30)) { pickTarget(s.first, s.second, l) }) }
        turnEndPanel!!.addView(r1)

        // Row 2: More trebles + bull
        val r2 = hRow(dp(2))
        listOf("T16" to Pair(16,3), "T15" to Pair(15,3), "Bull" to Pair(25,1), "D-Bull" to Pair(25,2))
            .forEach { (l, s) -> r2.addView(segBtn(l, Color.rgb(30, 100, 60)) { pickTarget(s.first, s.second, l) }) }
        turnEndPanel!!.addView(r2)

        // Row 3: Doubles
        val r3 = hRow(dp(2))
        listOf("D20" to Pair(20,2), "D16" to Pair(16,2), "D10" to Pair(10,2), "D8" to Pair(8,2))
            .forEach { (l, s) -> r3.addView(segBtn(l, Color.rgb(30, 60, 120)) { pickTarget(s.first, s.second, l) }) }
        turnEndPanel!!.addView(r3)

        // Opponent wait toggle
        btnWaitToggle = button(waitLabel(), waitColor()) { toggleWaitMode() }.also {
            it.layoutParams = fullWidthLp(topMargin = dp(5))
        }
        turnEndPanel!!.addView(btnWaitToggle)

        // YOUR TURN — only shown in opponent mode
        btnOpponent = button("▶  YOUR TURN", Color.rgb(15, 50, 100)) { resumeAfterOpponent() }.also {
            it.setTextColor(Color.rgb(100, 170, 255))
            it.visibility = View.GONE
            it.layoutParams = fullWidthLp(topMargin = dp(3))
        }
        turnEndPanel!!.addView(btnOpponent)

        // Throw next round
        turnEndPanel!!.addView(button("▶  THROW NEXT ROUND", Color.rgb(20, 80, 30)) {
            startNextRound()
        }.also { it.layoutParams = fullWidthLp(topMargin = dp(5)) })

        root.addView(turnEndPanel)
    }

    // ─────────────────────────────────────────
    //  Circle calibration
    //  Drag circle to center on bullseye, resize to match board edge
    // ─────────────────────────────────────────

    private fun startCircleCalibration() {
        if (calibActive) return
        calibActive = true
        val dm = resources.displayMetrics
        calibCx = dm.widthPixels / 2f
        calibCy = dm.heightPixels / 2f
        calibR  = dm.widthPixels * 0.38f   // start at ~38% screen width
        showCalibCircle()
    }

    private fun showCalibCircle() {
        dismissCalibViews()
        val dm = resources.displayMetrics

        fun activeR() = when (calibStage) { 1 -> calibTrebleR; 2 -> calibDoubleR; else -> calibR }
        fun setActiveR(r: Float) { when (calibStage) { 1 -> calibTrebleR = r; 2 -> calibDoubleR = r; else -> calibR = r } }

        calibCircleView = object : View(this) {
            override fun onDraw(canvas: Canvas) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val cx = calibCx; val cy = calibCy
                paint.style = Paint.Style.STROKE

                // Outer board ring
                paint.strokeWidth = dp(3).toFloat()
                paint.color = if (calibStage == 0) Color.argb(220, 240, 200, 60) else Color.argb(70, 240, 200, 60)
                canvas.drawCircle(cx, cy, calibR, paint)

                // Treble ring
                if (calibTrebleR > 0f) {
                    paint.strokeWidth = dp(3).toFloat()
                    paint.color = if (calibStage == 1) Color.argb(220, 60, 200, 80) else Color.argb(70, 60, 200, 80)
                    canvas.drawCircle(cx, cy, calibTrebleR, paint)
                }

                // Double ring
                if (calibDoubleR > 0f) {
                    paint.strokeWidth = dp(3).toFloat()
                    paint.color = if (calibStage == 2) Color.argb(220, 80, 160, 240) else Color.argb(70, 80, 160, 240)
                    canvas.drawCircle(cx, cy, calibDoubleR, paint)
                }

                // Active ring fill
                paint.style = Paint.Style.FILL
                paint.color = when (calibStage) { 1 -> Color.argb(18,60,200,80); 2 -> Color.argb(18,80,160,240); else -> Color.argb(18,240,200,60) }
                canvas.drawCircle(cx, cy, activeR(), paint)

                // Active crosshair
                paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2).toFloat()
                paint.color = when (calibStage) { 1 -> Color.argb(140,60,200,80); 2 -> Color.argb(140,80,160,240); else -> Color.argb(140,240,200,60) }
                val ar = activeR()
                canvas.drawLine(cx - ar, cy, cx + ar, cy, paint)
                canvas.drawLine(cx, cy - ar, cx, cy + ar, paint)

                // Bullseye dot
                paint.style = Paint.Style.FILL; paint.color = Color.rgb(240, 80, 80)
                canvas.drawCircle(cx, cy, dp(9).toFloat(), paint)
                paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.strokeWidth = dp(2).toFloat()
                canvas.drawCircle(cx, cy, dp(9).toFloat(), paint)

                // Resize handle
                val hx = cx + activeR(); val hy = cy
                paint.style = Paint.Style.FILL
                paint.color = when (calibStage) { 1 -> Color.rgb(60,200,80); 2 -> Color.rgb(80,160,240); else -> Color.rgb(240,200,60) }
                canvas.drawCircle(hx, hy, dp(16).toFloat(), paint)
                paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.strokeWidth = dp(2).toFloat()
                canvas.drawCircle(hx, hy, dp(16).toFloat(), paint)
                val ha = dp(7).toFloat()
                canvas.drawLine(hx - ha, hy, hx + ha, hy, paint)
                canvas.drawLine(hx + ha*0.4f, hy - ha*0.6f, hx + ha, hy, paint)
                canvas.drawLine(hx + ha*0.4f, hy + ha*0.6f, hx + ha, hy, paint)
            }
        }

        calibCircleParams = WindowManager.LayoutParams(
            dm.widthPixels, dm.heightPixels,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

        var touchMode = 0; var lastX = 0f; var lastY = 0f
        calibCircleView!!.setOnTouchListener { _, ev ->
            val tx = ev.rawX; val ty = ev.rawY
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    val hx = calibCx + activeR(); val hy = calibCy
                    val dh = Math.sqrt(((tx-hx)*(tx-hx)+(ty-hy)*(ty-hy)).toDouble())
                    touchMode = if (dh < dp(36)) 2 else 1
                    lastX = tx; lastY = ty
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = tx - lastX; val dy = ty - lastY
                    if (touchMode == 1 && calibStage == 0) { calibCx += dx; calibCy += dy }
                    else if (touchMode == 2) {
                        val nr = Math.sqrt(((tx-calibCx)*(tx-calibCx)+(ty-calibCy)*(ty-calibCy)).toDouble()).toFloat()
                        setActiveR(nr.coerceAtLeast(dp(30).toFloat()))
                    }
                    lastX = tx; lastY = ty
                    calibCircleView?.invalidate()
                }
            }
            true
        }

        wm.addView(calibCircleView, calibCircleParams)

        val stageColor = when (calibStage) { 1 -> Color.rgb(60,200,80); 2 -> Color.rgb(80,160,240); else -> Color.rgb(240,200,60) }
        val stageTitle = when (calibStage) { 0 -> "STEP 1/3  OUTER BOARD"; 1 -> "STEP 2/3  TREBLE RING"; else -> "STEP 3/3  DOUBLE RING" }
        val stageHint  = when (calibStage) {
            0 -> "Drag X onto BULLSEYE | Resize ring to OUTER BOARD EDGE"
            1 -> "Resize GREEN ring to the TREBLE band (thin ring 2/3 out)"
            else -> "Resize BLUE ring to the DOUBLE band (outer thin ring)"
        }

        val prompt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(235, 6, 8, 14))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        prompt.addView(TextView(this).apply {
            text = stageTitle; setTextColor(stageColor)
            textSize = 11f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
        })
        prompt.addView(TextView(this).apply {
            text = stageHint; setTextColor(Color.rgb(160, 160, 160))
            textSize = 9f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(8))
        })
        prompt.addView(Button(this).apply {
            text = if (calibStage < 2) "NEXT  ▶" else "✓  CONFIRM"
            setBackgroundColor(if (calibStage < 2) Color.rgb(40,80,40) else Color.rgb(25,90,25))
            setTextColor(Color.WHITE); textSize = 11f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { advanceCalibStage() }
        })
        prompt.addView(Button(this).apply {
            text = "✕  Cancel"; setBackgroundColor(Color.rgb(60, 20, 20)); setTextColor(Color.rgb(200, 100, 100)); textSize = 9f
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(4) }
            setOnClickListener { cancelCalibration() }
        })

        calibPromptView = prompt
        calibPromptParams = WindowManager.LayoutParams(
            dp(270), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(30) }
        wm.addView(calibPromptView, calibPromptParams)
        tvStatus?.apply { setTextColor(stageColor); text = stageTitle }
    }

    private fun advanceCalibStage() {
        when (calibStage) {
            0 -> {
                targetConfig.setBoardCenter(calibCx, calibCy)
                calibTrebleR = calibR * 0.62f
                calibStage = 1; dismissCalibViews(); showCalibCircle()
            }
            1 -> {
                calibDoubleR = calibR * 0.92f
                calibStage = 2; dismissCalibViews(); showCalibCircle()
            }
            2 -> {
                targetConfig.setBoardCenter(calibCx, calibCy)
                targetConfig.autoGenerateFromRings(calibR, calibTrebleR, calibDoubleR)
                targetConfig.setThrowOrigin(calibCx, calibCy + calibR + dp(40))
                calibActive = false; dismissCalibViews()
                tvStatus?.apply { setTextColor(Color.rgb(80, 220, 80)); text = "Calibrated — 3 rings!" }
                updateNextTargetLabel()
            }
        }
    }

    private fun cancelCalibration() {
        calibActive = false; dismissCalibViews()
        tvStatus?.apply { setTextColor(Color.rgb(160, 120, 80)); text = "Calibration cancelled" }
    }

    private fun dismissCalibViews() {
        calibCircleView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; calibCircleView = null
        calibPromptView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; calibPromptView = null
    }


    // ─────────────────────────────────────────
    //  After-turn target picker
    // ─────────────────────────────────────────

    private fun pickTarget(segment: Int, multiplier: Int, label: String) {
        manualTargetSeg = segment
        manualTargetMult = multiplier
        tvStatus?.apply { setTextColor(Color.rgb(180, 220, 100)); text = "Target: $label" }
        updateNextTargetLabel()
    }

    private fun showTurnEndPanel() {
        waitingForNextRound = true
        turnEndPanel?.visibility = View.VISIBLE
        btnStart?.apply { text = "THROW"; setBackgroundColor(Color.rgb(25, 100, 50)); isEnabled = false }
        if (waitForOpponent) {
            awaitingOpponent = true
            btnOpponent?.visibility = View.VISIBLE
            btnWaitToggle?.visibility = View.GONE
            tvStatus?.apply { setTextColor(Color.rgb(80, 130, 210)); text = "Opponent throwing…" }
        } else {
            btnOpponent?.visibility = View.GONE
            btnWaitToggle?.visibility = View.VISIBLE
            tvStatus?.apply { setTextColor(Color.rgb(200, 200, 80)); text = "Pick target → Throw" }
        }
    }

    private fun hideTurnEndPanel() {
        turnEndPanel?.visibility = View.GONE
        btnStart?.isEnabled = true
    }

    private fun startNextRound() {
        waitingForNextRound = false; awaitingOpponent = false
        dartsThisTurn = 0; updateTurnCounter(); hideTurnEndPanel()
        btnOpponent?.visibility = View.GONE
        startThrowing()
    }

    private fun segBtn(label: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; setBackgroundColor(bg); setTextColor(Color.WHITE)
        textSize = 8f; setPadding(dp(2), dp(3), dp(2), dp(3))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .also { it.rightMargin = dp(2) }
        setOnClickListener { onClick() }
    }

    // ─────────────────────────────────────────
    //  Overlay controls
    // ─────────────────────────────────────────

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
        listOf(overlayView, calibCircleView, calibPromptView).forEach {
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

    // ─────────────────────────────────────────
    //  Opponent wait
    // ─────────────────────────────────────────

    private fun toggleWaitMode() {
        waitForOpponent = !waitForOpponent
        btnWaitToggle?.text = waitLabel(); btnWaitToggle?.setBackgroundColor(waitColor())
    }

    private fun resumeAfterOpponent() {
        awaitingOpponent = false; btnOpponent?.visibility = View.GONE
        btnWaitToggle?.visibility = View.VISIBLE
        tvStatus?.apply { setTextColor(Color.rgb(200, 200, 80)); text = "Pick target → Throw" }
    }

    private fun waitLabel() = if (waitForOpponent) "⏸  Opponent Wait: ON" else "⏸  Opponent Wait: OFF"
    private fun waitColor() = if (waitForOpponent) Color.rgb(20, 50, 85) else Color.rgb(25, 20, 10)

    // ─────────────────────────────────────────
    //  Throw scheduling
    // ─────────────────────────────────────────

    private fun toggleRunning() {
        if (waitingForNextRound) return
        if (isRunning) stopThrowing() else startThrowing()
    }

    private fun startThrowing() {
        if (!targetConfig.isCalibrated()) {
            tvStatus?.apply { setTextColor(Color.rgb(220, 80, 60)); text = "Calibrate first!" }
            return
        }
        isRunning = true
        btnStart?.apply { text = "STOP"; setBackgroundColor(Color.rgb(140, 25, 20)) }
        tvStatus?.apply { setTextColor(Color.rgb(80, 180, 80)); text = "Throwing…" }
        scheduleThrow()
    }

    private fun stopThrowing() {
        isRunning = false; awaitingOpponent = false; waitingForNextRound = false
        handler.removeCallbacksAndMessages(null)
        btnStart?.apply { text = "THROW"; isEnabled = true; setBackgroundColor(Color.rgb(25, 100, 50)) }
        btnOpponent?.visibility = View.GONE; hideTurnEndPanel()
        tvStatus?.apply { setTextColor(Color.rgb(100, 100, 100)); text = "Stopped" }
    }

    private fun scheduleThrow() {
        if (!isRunning) return
        handler.postDelayed({ if (isRunning) throwDart() }, throwDelayMs)
    }

    private fun throwOneDart() {
        if (!targetConfig.isCalibrated()) {
            tvStatus?.apply { setTextColor(Color.rgb(220, 80, 60)); text = "Calibrate first!" }
            return
        }
        throwDart()
    }

    // ─────────────────────────────────────────
    //  Core throw — end point of swipe = where dart lands
    // ─────────────────────────────────────────

    private fun throwDart() {
        val engine = gameEngine ?: return

        // Resolve target screen coordinates
        val screen: ScreenTarget? = when {
            // If user picked a target from the panel, use it
            manualTargetSeg > 0 -> targetConfig.getTarget(manualTargetSeg, manualTargetMult)
                ?: targetConfig.getBoardCenter()
            // Otherwise use game engine strategy
            else -> {
                val t = engine.getNextTarget()
                targetConfig.getTarget(t.segment, t.multiplier)
                    ?: targetConfig.getBoardCenter()
            }
        }

        if (screen == null) {
            handler.post { tvStatus?.text = "No target — calibrate!" }
            return
        }

        // Add jitter for natural accuracy
        val jit = { (Random.nextFloat() * 2f - 1f) * accuracyPx }
        val endX = screen.x + jit()
        val endY = screen.y + jit()

        // Swipe: start BELOW the board, end AT the target
        // The END of the gesture registers as the throw landing point
        val flickPx = dp(flickDp).toFloat()
        val startX = endX + (Random.nextFloat() * 2f - 1f) * dp(10)
        val startY = endY + flickPx

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,    // start delay
            200L   // 200ms swipe duration — feels like a real flick
        )

        dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gd: GestureDescription?) {
                    handler.post {
                        // Record hit in game engine
                        if (manualTargetSeg > 0) {
                            engine.recordHit(manualTargetSeg, manualTargetMult)
                        } else {
                            val t = engine.getNextTarget()
                            engine.recordHit(t.segment, t.multiplier)
                        }
                        updateNextTargetLabel()
                        onDartLanded()
                    }
                }
                override fun onCancelled(gd: GestureDescription?) {
                    handler.post {
                        tvStatus?.text = "Gesture blocked"
                        onDartLanded()
                    }
                }
            }, null
        )
    }

    private fun onDartLanded() {
        dartsThisTurn++; updateTurnCounter()
        if (dartsThisTurn >= 3) {
            isRunning = false
            handler.removeCallbacksAndMessages(null)
            btnStart?.apply { text = "THROW"; setBackgroundColor(Color.rgb(25, 100, 50)) }
            showTurnEndPanel()
        } else {
            if (isRunning) scheduleThrow()
        }
    }

    // ─────────────────────────────────────────
    //  Turn counter
    // ─────────────────────────────────────────

    private fun dartCounterText() = (1..3).joinToString(" ") { i -> if (i <= dartsThisTurn) "◉" else "○" }

    private fun updateTurnCounter() {
        tvTurnCounter?.apply {
            text = dartCounterText()
            setTextColor(when (dartsThisTurn) {
                0 -> Color.rgb(60, 45, 15)
                3 -> Color.rgb(240, 200, 60)
                else -> Color.rgb(180, 140, 50)
            })
        }
    }

    // ─────────────────────────────────────────
    //  Game callbacks
    // ─────────────────────────────────────────

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
        stopThrowing(); dartsThisTurn = 0; waitingForNextRound = false
        manualTargetSeg = 0; manualTargetMult = 0
        updateTurnCounter()
        gameEngine = GameEngine(currentMode); setupGameCallbacks()
        tvScore?.text = "Ready"
        tvStatus?.apply { setTextColor(Color.rgb(100, 100, 100)); text = "${currentMode.label} ready" }
        btnStart?.isEnabled = true; updateNextTargetLabel()
    }

    private fun updateNextTargetLabel() {
        tvNextTarget?.text = when {
            manualTargetSeg > 0 -> {
                val mult = when(manualTargetMult) { 3->"T"; 2->"D"; else->"" }
                val seg = if (manualTargetSeg == 25) "Bull" else "$manualTargetSeg"
                "Next: $mult$seg"
            }
            else -> "Next: ${gameEngine?.getNextTargetLabel() ?: "—"}"
        }
    }

    // ─────────────────────────────────────────
    //  View helpers
    // ─────────────────────────────────────────

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false, center: Boolean = false) =
        TextView(this).apply {
            this.text = text; setTextColor(color); textSize = sp
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (center) gravity = Gravity.CENTER
        }

    private fun button(text: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text; setBackgroundColor(bg); setTextColor(Color.WHITE)
        textSize = 9f; setPadding(dp(3), dp(3), dp(3), dp(3)); setOnClickListener { onClick() }
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
