package net.kaneonexus.dartclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*
import kotlin.random.Random

data class ThrowScript(
    val name: String,
    val startX: Float, val startY: Float,
    val endX: Float,   val endY: Float
)

class ScriptStore(context: Context) {
    private val prefs = context.getSharedPreferences("nexus_dart_scripts", Context.MODE_PRIVATE)
    fun save(scripts: List<ThrowScript>) {
        val arr = JSONArray()
        scripts.forEach { s ->
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("sx", s.startX); put("sy", s.startY)
                put("ex", s.endX);   put("ey", s.endY)
            })
        }
        prefs.edit().putString("scripts", arr.toString()).apply()
    }
    fun load(): MutableList<ThrowScript> {
        val raw = prefs.getString("scripts", "[]") ?: "[]"
        val result = mutableListOf<ThrowScript>()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            result.add(ThrowScript(
                o.getString("name"),
                o.getDouble("sx").toFloat(), o.getDouble("sy").toFloat(),
                o.getDouble("ex").toFloat(), o.getDouble("ey").toFloat()
            ))
        }
        return result
    }
}

class DartAccessibilityService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var scriptStore: ScriptStore
    private val handler = Handler(Looper.getMainLooper())

    private var scripts = mutableListOf<ThrowScript>()
    private var activeScriptIndex = 0

    // Crosshairs
    private var startX = 0f; private var startY = 0f
    private var endX   = 0f; private var endY   = 0f
    private var crosshairStartView: View? = null
    private var crosshairStartParams: WindowManager.LayoutParams? = null
    private var crosshairEndView: View? = null
    private var crosshairEndParams: WindowManager.LayoutParams? = null
    private var crosshairsVisible = false

    // Throw state
    private var isRunning = false
    private var throwIntervalMs = 1500L
    private var dartsThisTurn = 0
    private var waitingForTurn = false
    private var waitForOpponent = false
    private var awaitingOpponent = false

    // Board map panel
    private var boardMapView: View? = null
    private var boardMapParams: WindowManager.LayoutParams? = null
    private var boardMapVisible = false
    private var selectedSeg = 0    // 0=none, 1-20=segment, 25=bull
    private var selectedMult = 1   // 1=single, 2=double, 3=treble

    // Overlay
    private var overlayView: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var expandedContent: LinearLayout? = null
    private var isExpanded = true
    private var turnEndPanel: LinearLayout? = null
    private var scriptListLayout: LinearLayout? = null

    private var tvStatus: TextView? = null
    private var tvTurnCounter: TextView? = null
    private var tvActiveScript: TextView? = null
    private var btnStart: Button? = null
    private var btnExpandToggle: TextView? = null
    private var btnWaitToggle: Button? = null
    private var btnOpponent: Button? = null
    private var btnMapToggle: Button? = null

    private val BG_ALPHA = 180
    private val RESIZE_STEPS = intArrayOf(180, 230, 280, 330)
    private var resizeIndex = 1

    // Dartboard segment order (clockwise from top)
    private val SEG_ORDER = intArrayOf(20,1,18,4,13,6,10,15,2,17,3,19,7,16,8,11,14,9,12,5)

    // ─────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        scriptStore = ScriptStore(this)
        scripts = scriptStore.load()
        val dm = resources.displayMetrics
        startX = dm.widthPixels * 0.5f; startY = dm.heightPixels * 0.7f
        endX   = dm.widthPixels * 0.5f; endY   = dm.heightPixels * 0.4f
        buildOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopThrowing() }

    override fun onDestroy() {
        super.onDestroy()
        stopThrowing()
        listOf(overlayView, crosshairStartView, crosshairEndView, boardMapView).forEach {
            it?.let { v -> try { wm.removeView(v) } catch (_: Exception) {} }
        }
    }

    // ─────────────────────────────────────────
    //  Main overlay
    // ─────────────────────────────────────────

    private fun buildOverlay() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(BG_ALPHA, 6, 8, 12))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        overlayView = root

        // Top bar
        val topBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dragHandle = View(this).apply {
            setBackgroundColor(Color.argb(160, 180, 130, 40))
            layoutParams = LinearLayout.LayoutParams(dp(4), dp(26)).also { it.rightMargin = dp(8) }
        }
        topBar.addView(dragHandle)
        topBar.addView(label("AUTO DARTS", 10f, Color.rgb(180, 130, 40), bold = true).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        tvTurnCounter = label(dartDots(), 13f, Color.rgb(80, 60, 20)).also {
            it.letterSpacing = 0.15f
            it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { lp -> lp.rightMargin = dp(4) }
        }
        topBar.addView(tvTurnCounter)
        topBar.addView(tv("⇔", 13f, Color.rgb(100, 140, 180)) { cycleResize() }.also { it.setPadding(dp(3), 0, dp(3), 0) })
        btnExpandToggle = tv("▲", 12f, Color.rgb(140, 100, 30)) { toggleExpanded() }.also { it.setPadding(dp(2), 0, dp(3), 0) }
        topBar.addView(btnExpandToggle)
        topBar.addView(tv("✕", 14f, Color.rgb(200, 60, 60)) { closeOverlay() }.also { it.setPadding(dp(3), 0, 0, 0) })
        root.addView(topBar)

        // Throw row
        val throwRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = fullWidthLp(dp(6)) }
        btnStart = button("THROW", Color.rgb(25, 100, 50)) { toggleRunning() }.also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { lp -> lp.rightMargin = dp(4) }
        }
        throwRow.addView(btnStart)
        tvStatus = label("Ready", 8f, Color.rgb(100, 150, 100)).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { lp -> lp.leftMargin = dp(4) }
            it.gravity = Gravity.CENTER_VERTICAL
        }
        throwRow.addView(tvStatus)
        root.addView(throwRow)

        tvActiveScript = label("No script set", 8f, Color.rgb(160, 130, 50)).also { it.setPadding(0, dp(3), 0, 0) }
        root.addView(tvActiveScript)

        buildTurnEndPanel(root)

        // Expanded content
        expandedContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.VISIBLE }

        expandedContent!!.addView(divider(dp(6)))

        // Board map button
        btnMapToggle = button("◎  BOARD MAP", Color.rgb(20, 40, 70)) { toggleBoardMap() }.also {
            it.setTextColor(Color.rgb(120, 180, 240))
            it.layoutParams = fullWidthLp()
        }
        expandedContent!!.addView(btnMapToggle)

        expandedContent!!.addView(divider(dp(5)))

        // Crosshair controls
        expandedContent!!.addView(label("CROSSHAIRS", 7f, Color.rgb(90, 65, 25)))
        val chRow = hRow(dp(3))
        chRow.addView(button("⊕ START", Color.rgb(120, 30, 30)) { showCrosshairs() }.also {
            it.layoutParams = splitLp(rightMargin = dp(3)); it.setTextColor(Color.rgb(255, 180, 180))
        })
        chRow.addView(button("⊕ END", Color.rgb(100, 80, 20)) { showCrosshairs() }.also {
            it.layoutParams = splitLp(leftMargin = dp(3)); it.setTextColor(Color.rgb(255, 230, 120))
        })
        expandedContent!!.addView(chRow)
        expandedContent!!.addView(label("Red S = swipe start  |  Gold E = lift (dart lands)", 7f, Color.rgb(80, 70, 40)).also { it.setPadding(0, dp(2), 0, 0) })

        expandedContent!!.addView(divider(dp(5)))

        // Save script
        expandedContent!!.addView(label("SAVE SCRIPT", 7f, Color.rgb(90, 65, 25)))
        val nameRow = hRow(dp(3))
        val nameEdit = EditText(this).apply {
            hint = "Name (T20, Bull, D16...)"; setTextColor(Color.rgb(220, 200, 160))
            setHintTextColor(Color.rgb(80, 70, 50)); setBackgroundColor(Color.argb(80, 40, 40, 30))
            textSize = 9f; setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { lp -> lp.rightMargin = dp(4) }
            isFocusable = true; isFocusableInTouchMode = true
        }
        nameRow.addView(nameEdit)
        nameRow.addView(button("SAVE", Color.rgb(30, 70, 30)) {
            val n = nameEdit.text.toString().trim()
            if (n.isNotEmpty()) saveCurrentAsScript(n) else tvStatus?.text = "Enter a name"
        })
        expandedContent!!.addView(nameRow)

        expandedContent!!.addView(divider(dp(5)))

        // Script list
        expandedContent!!.addView(label("SAVED SCRIPTS", 7f, Color.rgb(90, 65, 25)))
        scriptListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        expandedContent!!.addView(scriptListLayout)
        refreshScriptList()

        expandedContent!!.addView(divider(dp(5)))

        // Timer
        expandedContent!!.addView(label("INTERVAL BETWEEN THROWS", 7f, Color.rgb(90, 65, 25)))
        expandedContent!!.addView(SeekBar(this).apply {
            max = 29; progress = 7
            setOnSeekBarChangeListener(seekListener { p -> throwIntervalMs = 100L + p * 100L })
        })
        expandedContent!!.addView(sliderRow("100ms", "3s"))

        expandedContent!!.addView(divider(dp(5)))

        // Opponent + controls
        btnWaitToggle = button(waitLabel(), waitColor()) { toggleWaitMode() }.also { it.layoutParams = fullWidthLp() }
        expandedContent!!.addView(btnWaitToggle)
        btnOpponent = button("▶  YOUR TURN", Color.rgb(15, 50, 100)) { resumeAfterOpponent() }.also {
            it.setTextColor(Color.rgb(100, 170, 255)); it.visibility = View.GONE; it.layoutParams = fullWidthLp(dp(3))
        }
        expandedContent!!.addView(btnOpponent)

        expandedContent!!.addView(divider(dp(5)))
        expandedContent!!.addView(hRow(0).also { row ->
            row.addView(button("ONE DART", Color.rgb(50, 25, 60)) { fireOneDart() }.also {
                it.setTextColor(Color.rgb(200, 150, 220)); it.layoutParams = splitLp(rightMargin = dp(3))
            })
            row.addView(button("RESET TURN", Color.rgb(40, 25, 10)) { resetTurn() }.also { it.layoutParams = splitLp(leftMargin = dp(3)) })
        })

        root.addView(expandedContent)

        overlayParams = WindowManager.LayoutParams(
            dp(RESIZE_STEPS[resizeIndex]), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(6); y = dp(60) }

        var ix = 0; var iy = 0; var itx = 0; var ity = 0
        dragHandle.setOnTouchListener { _, ev ->
            val p = overlayParams!!
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { ix = p.x; iy = p.y; itx = ev.rawX.toInt(); ity = ev.rawY.toInt() }
                MotionEvent.ACTION_MOVE -> { p.x = ix + (ev.rawX-itx).toInt(); p.y = iy + (ev.rawY-ity).toInt(); wm.updateViewLayout(overlayView, p) }
            }
            true
        }
        wm.addView(overlayView, overlayParams)
        updateActiveScriptLabel()
    }

    // ─────────────────────────────────────────
    //  BOARD MAP
    // ─────────────────────────────────────────

    private fun toggleBoardMap() {
        if (boardMapVisible) hideBoardMap() else showBoardMap()
    }

    private fun showBoardMap() {
        hideBoardMap()
        boardMapVisible = true
        btnMapToggle?.setBackgroundColor(Color.rgb(20, 60, 100))

        val mapSize = dp(320)
        val boardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(245, 6, 8, 14))
            setPadding(dp(10), dp(10), dp(10), dp(12))
        }

        // Header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("BOARD MAP", 11f, Color.rgb(180, 130, 40), bold = true).also {
            it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(tv("✕", 14f, Color.rgb(200, 60, 60)) { hideBoardMap() }.also { it.setPadding(dp(6), 0, 0, 0) })
        boardPanel.addView(header)

        // Selection info
        val tvSelInfo = label("Tap a segment to select it", 9f, Color.rgb(160, 140, 90)).also {
            it.setPadding(0, dp(4), 0, dp(4)); it.gravity = Gravity.CENTER
        }
        boardPanel.addView(tvSelInfo)

        // The dartboard canvas
        val boardView = object : View(this) {
            override fun onDraw(canvas: Canvas) {
                drawMiniBoard(canvas, width, height)
            }
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (ev.action == MotionEvent.ACTION_DOWN) {
                    val cx = width / 2f; val cy = height / 2f
                    val maxR = minOf(width, height) / 2f - dp(4)
                    handleBoardTap(ev.x, ev.y, cx, cy, maxR, tvSelInfo)
                    invalidate()
                    return true
                }
                return false
            }
        }
        boardView.layoutParams = LinearLayout.LayoutParams(mapSize, mapSize).also { it.gravity = Gravity.CENTER_HORIZONTAL }
        boardPanel.addView(boardView)

        // Action buttons row
        val actRow = hRow(dp(8))
        actRow.addView(button("⊕ SET CROSSHAIRS HERE", Color.rgb(30, 70, 30)) {
            loadScriptToCrosshairs()
            tvSelInfo.text = "Crosshairs loaded — drag to fine-tune"
        }.also {
            it.setTextColor(Color.rgb(160, 220, 120))
            it.layoutParams = splitLp(rightMargin = dp(3))
        })
        actRow.addView(button("▶ USE AS TARGET", Color.rgb(20, 50, 80)) {
            activateSelectedScript(tvSelInfo)
        }.also {
            it.setTextColor(Color.rgb(120, 180, 255))
            it.layoutParams = splitLp(leftMargin = dp(3))
        })
        boardPanel.addView(actRow)

        // Script name for selected segment
        val saveRow = hRow(dp(6))
        val segNameEdit = EditText(this).apply {
            hint = "Save as script name..."
            setTextColor(Color.rgb(220, 200, 160)); setHintTextColor(Color.rgb(80, 70, 50))
            setBackgroundColor(Color.argb(80, 40, 40, 30))
            textSize = 9f; setPadding(dp(6), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { lp -> lp.rightMargin = dp(4) }
            isFocusable = true; isFocusableInTouchMode = true
        }
        saveRow.addView(segNameEdit)
        saveRow.addView(button("SAVE", Color.rgb(50, 40, 10)) {
            val n = segNameEdit.text.toString().trim().ifEmpty { segmentLabel(selectedSeg, selectedMult) }
            saveCurrentAsScript(n)
            tvSelInfo.text = "Saved: $n"
            boardView.invalidate()
        }.also { it.setTextColor(Color.rgb(220, 180, 80)) })
        boardPanel.addView(saveRow)

        boardMapView = boardPanel
        boardMapParams = WindowManager.LayoutParams(
            dp(340), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(80) }
        wm.addView(boardMapView, boardMapParams)
    }

    private fun hideBoardMap() {
        boardMapView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; boardMapView = null
        boardMapVisible = false
        btnMapToggle?.setBackgroundColor(Color.rgb(20, 40, 70))
    }

    // ── Draw the mini dartboard ──────────────

    private fun drawMiniBoard(canvas: Canvas, w: Int, h: Int) {
        val cx = w / 2f; val cy = h / 2f
        val maxR = minOf(w, h) / 2f - dp(6)

        // Ring radii proportional to real board
        val rBull    = maxR * 0.07f
        val rBullOuter = maxR * 0.16f
        val rInner   = maxR * 0.55f  // inner single/treble boundary
        val rTreble  = maxR * 0.63f
        val rOuter   = maxR * 0.90f  // outer single/double boundary
        val rDouble  = maxR * 0.99f

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Draw segments
        for (i in SEG_ORDER.indices) {
            val seg = SEG_ORDER[i]
            val startAngle = (i * 18f - 9f - 90f)
            val sweep = 18f
            val isLight = i % 2 == 0

            // Outer single
            drawSegmentArc(canvas, paint, cx, cy, rBullOuter, rInner, startAngle, sweep,
                if (isLight) Color.rgb(235, 205, 160) else Color.rgb(30, 20, 12), seg, 1)

            // Treble ring
            drawSegmentArc(canvas, paint, cx, cy, rInner, rTreble, startAngle, sweep,
                if (isLight) Color.rgb(180, 30, 30) else Color.rgb(20, 110, 40), seg, 3)

            // Outer single
            drawSegmentArc(canvas, paint, cx, cy, rTreble, rOuter, startAngle, sweep,
                if (isLight) Color.rgb(235, 205, 160) else Color.rgb(30, 20, 12), seg, 1)

            // Double ring
            drawSegmentArc(canvas, paint, cx, cy, rOuter, rDouble, startAngle, sweep,
                if (isLight) Color.rgb(180, 30, 30) else Color.rgb(20, 110, 40), seg, 2)
        }

        // Bull outer (25)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(20, 110, 40)
        canvas.drawCircle(cx, cy, rBullOuter, paint)

        // Bullseye (50)
        paint.color = Color.rgb(180, 30, 30)
        canvas.drawCircle(cx, cy, rBull, paint)

        // Wire lines
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1).toFloat(); paint.color = Color.argb(140, 180, 180, 180)
        for (i in SEG_ORDER.indices) {
            val a = Math.toRadians((i * 18.0 - 9.0 - 90.0))
            canvas.drawLine(
                (cx + rBullOuter * cos(a)).toFloat(), (cy + rBullOuter * sin(a)).toFloat(),
                (cx + rDouble * cos(a)).toFloat(), (cy + rDouble * sin(a)).toFloat(), paint
            )
        }
        listOf(rBullOuter, rInner, rTreble, rOuter, rDouble).forEach { r ->
            canvas.drawCircle(cx, cy, r, paint)
        }

        // Selection highlight
        if (selectedSeg > 0) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3).toFloat(); paint.color = Color.rgb(80, 220, 255)
            if (selectedSeg == 25) {
                val r = if (selectedMult == 2) rBullOuter else rBull
                canvas.drawCircle(cx, cy, r, paint)
            } else {
                val segIdx = SEG_ORDER.indexOf(selectedSeg)
                val sa = (segIdx * 18f - 9f - 90f)
                val (r1, r2) = when (selectedMult) {
                    3 -> Pair(rInner, rTreble); 2 -> Pair(rOuter, rDouble); else -> Pair(rBullOuter, rInner)
                }
                val path = arcPath(cx, cy, r1, r2, sa, 18f)
                canvas.drawPath(path, paint)
            }
        }

        // Numbers
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(230, 210, 170)
        paint.textAlign = Paint.Align.CENTER; paint.textSize = dp(10).toFloat()
        paint.typeface = Typeface.DEFAULT_BOLD
        for (i in SEG_ORDER.indices) {
            val seg = SEG_ORDER[i]
            val angle = Math.toRadians((i * 18.0 - 90.0))
            val nr = rDouble + dp(10)
            canvas.drawText("$seg", (cx + nr * cos(angle)).toFloat(), (cy + nr * sin(angle)).toFloat() + dp(4), paint)
        }

        // Saved scripts dots
        paint.textSize = dp(7).toFloat(); paint.typeface = Typeface.DEFAULT
        scripts.forEach { s ->
            val info = parseScriptName(s.name) ?: return@forEach
            val (seg, mult) = info
            if (seg == 25) {
                paint.color = Color.rgb(80, 255, 120)
                canvas.drawCircle(cx, cy, if (mult == 2) rBullOuter * 0.6f else rBull * 0.5f, paint)
            } else {
                val idx = SEG_ORDER.indexOf(seg)
                if (idx < 0) return@forEach
                val angle = Math.toRadians((idx * 18.0 - 90.0))
                val r = when (mult) { 3 -> (rInner + rTreble) / 2f; 2 -> (rOuter + rDouble) / 2f; else -> (rBullOuter + rInner) / 2f }
                paint.color = Color.rgb(80, 255, 120)
                canvas.drawCircle((cx + r * cos(angle)).toFloat(), (cy + r * sin(angle)).toFloat(), dp(5).toFloat(), paint)
            }
        }
    }

    private fun drawSegmentArc(canvas: Canvas, paint: Paint, cx: Float, cy: Float,
                                r1: Float, r2: Float, startAngle: Float, sweep: Float,
                                color: Int, seg: Int, mult: Int) {
        val isSelected = (selectedSeg == seg && selectedMult == mult)
        paint.style = Paint.Style.FILL
        paint.color = if (isSelected) Color.argb(200, 60, 200, 255) else color
        val path = arcPath(cx, cy, r1, r2, startAngle, sweep)
        canvas.drawPath(path, paint)
    }

    private fun arcPath(cx: Float, cy: Float, r1: Float, r2: Float, startAngle: Float, sweep: Float): Path {
        val path = Path()
        val rect1 = RectF(cx - r1, cy - r1, cx + r1, cy + r1)
        val rect2 = RectF(cx - r2, cy - r2, cx + r2, cy + r2)
        path.arcTo(rect2, startAngle, sweep)
        path.arcTo(rect1, startAngle + sweep, -sweep)
        path.close()
        return path
    }

    private fun handleBoardTap(tx: Float, ty: Float, cx: Float, cy: Float, maxR: Float, tvInfo: TextView) {
        val dx = tx - cx; val dy = ty - cy
        val r = sqrt(dx * dx + dy * dy)
        val rBull       = maxR * 0.07f
        val rBullOuter  = maxR * 0.16f
        val rInner      = maxR * 0.55f
        val rTreble     = maxR * 0.63f
        val rOuter      = maxR * 0.90f
        val rDouble     = maxR * 0.99f

        if (r > rDouble) { tvInfo.text = "Tap inside the board"; return }

        if (r <= rBull) { selectedSeg = 25; selectedMult = 2
        } else if (r <= rBullOuter) { selectedSeg = 25; selectedMult = 1
        } else {
            val angleDeg = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90 + 360 + 9) % 360)
            val segIdx = (angleDeg / 18).toInt() % 20
            selectedSeg = SEG_ORDER[segIdx]
            selectedMult = when {
                r in rInner..rTreble -> 3
                r in rOuter..rDouble -> 2
                else -> 1
            }
        }

        val lbl = segmentLabel(selectedSeg, selectedMult)
        val existing = scripts.find { it.name.equals(lbl, ignoreCase = true) || parseScriptName(it.name)?.let { (s,m) -> s==selectedSeg && m==selectedMult } == true }
        tvInfo.text = if (existing != null) "Selected: $lbl  (script: ${existing.name})" else "Selected: $lbl  (no script yet)"
        tvInfo.setTextColor(if (existing != null) Color.rgb(100, 220, 100) else Color.rgb(200, 180, 80))
    }

    private fun loadScriptToCrosshairs() {
        if (selectedSeg == 0) return
        val lbl = segmentLabel(selectedSeg, selectedMult)
        val existing = scripts.find { parseScriptName(it.name)?.let { (s,m) -> s==selectedSeg && m==selectedMult } == true
                                   || it.name.equals(lbl, ignoreCase = true) }
        if (existing != null) {
            startX = existing.startX; startY = existing.startY
            endX = existing.endX; endY = existing.endY
            activeScriptIndex = scripts.indexOf(existing)
            updateActiveScriptLabel()
        }
        showCrosshairs()
    }

    private fun activateSelectedScript(tvInfo: TextView) {
        if (selectedSeg == 0) { tvInfo.text = "Tap a segment first"; return }
        val lbl = segmentLabel(selectedSeg, selectedMult)
        val idx = scripts.indexOfFirst { parseScriptName(it.name)?.let { (s,m) -> s==selectedSeg && m==selectedMult } == true
                                      || it.name.equals(lbl, ignoreCase = true) }
        if (idx >= 0) {
            activeScriptIndex = idx
            updateActiveScriptLabel()
            tvInfo.text = "Active: ${scripts[idx].name}"
            tvStatus?.text = "Target: ${scripts[idx].name}"
        } else { tvInfo.text = "No script for $lbl — set crosshairs & save first" }
    }

    // Parse "T20", "D16", "Bull", "20" etc into (segment, multiplier)
    private fun parseScriptName(name: String): Pair<Int, Int>? {
        val n = name.trim().uppercase()
        return when {
            n == "BULL" || n == "BULLSEYE" -> Pair(25, 1)
            n == "DBULL" || n == "D-BULL" || n == "DOUBLEBULL" -> Pair(25, 2)
            n.startsWith("T") -> n.drop(1).toIntOrNull()?.let { Pair(it, 3) }
            n.startsWith("D") -> n.drop(1).toIntOrNull()?.let { Pair(it, 2) }
            else -> n.toIntOrNull()?.let { Pair(it, 1) }
        }
    }

    private fun segmentLabel(seg: Int, mult: Int): String = when {
        seg == 25 && mult == 2 -> "DBull"
        seg == 25 -> "Bull"
        mult == 3 -> "T$seg"
        mult == 2 -> "D$seg"
        else -> "$seg"
    }

    // ─────────────────────────────────────────
    //  Turn end panel
    // ─────────────────────────────────────────

    private fun buildTurnEndPanel(root: LinearLayout) {
        turnEndPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setBackgroundColor(Color.argb(100, 10, 30, 10))
            setPadding(dp(6), dp(8), dp(6), dp(8)); layoutParams = fullWidthLp(dp(4))
        }
        turnEndPanel!!.addView(label("── 3 DARTS ──", 8f, Color.rgb(140, 200, 100), center = true))
        turnEndPanel!!.addView(label("Pick script for next round:", 7f, Color.rgb(100, 140, 80)).also { it.setPadding(0, dp(3), 0, dp(3)) })
        val quickPick = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "quickpick" }
        turnEndPanel!!.addView(quickPick)
        turnEndPanel!!.addView(button("◎  OPEN BOARD MAP", Color.rgb(15, 40, 70)) { showBoardMap() }.also {
            it.setTextColor(Color.rgb(100, 170, 240)); it.layoutParams = fullWidthLp(dp(3))
        })
        val waitBtn = button(waitLabel(), waitColor()) { toggleWaitMode() }.also { it.layoutParams = fullWidthLp(dp(4)) }
        btnWaitToggle = waitBtn; turnEndPanel!!.addView(waitBtn)
        btnOpponent = button("▶  YOUR TURN", Color.rgb(15, 50, 100)) { resumeAfterOpponent() }.also {
            it.setTextColor(Color.rgb(100, 170, 255)); it.visibility = View.GONE; it.layoutParams = fullWidthLp(dp(3))
        }
        turnEndPanel!!.addView(btnOpponent)
        turnEndPanel!!.addView(button("▶  THROW NEXT ROUND", Color.rgb(20, 80, 30)) { startNextRound() }.also { it.layoutParams = fullWidthLp(dp(5)) })
        root.addView(turnEndPanel)
    }

    private fun refreshQuickPick() {
        val qp = turnEndPanel?.findViewWithTag<LinearLayout>("quickpick") ?: return
        qp.removeAllViews()
        scripts.chunked(4).forEach { chunk ->
            val row = hRow(dp(2))
            chunk.forEach { script ->
                row.addView(Button(this).apply {
                    text = script.name; setBackgroundColor(Color.rgb(30, 55, 30)); setTextColor(Color.rgb(160, 220, 120))
                    textSize = 8f; setPadding(dp(2), dp(3), dp(2), dp(3))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.rightMargin = dp(2) }
                    setOnClickListener { activeScriptIndex = scripts.indexOf(script); updateActiveScriptLabel(); tvStatus?.text = "Aim: ${script.name}" }
                })
            }
            qp.addView(row)
        }
    }

    private fun showTurnEndPanel() {
        waitingForTurn = true; refreshQuickPick(); turnEndPanel?.visibility = View.VISIBLE
        btnStart?.apply { text = "THROW"; setBackgroundColor(Color.rgb(25, 100, 50)); isEnabled = false }
        if (waitForOpponent) { awaitingOpponent = true; btnOpponent?.visibility = View.VISIBLE
            tvStatus?.apply { setTextColor(Color.rgb(80, 130, 210)); text = "Opponent throwing..." }
        } else { tvStatus?.apply { setTextColor(Color.rgb(200, 200, 80)); text = "Pick script or throw" } }
    }

    private fun hideTurnEndPanel() { turnEndPanel?.visibility = View.GONE; btnStart?.isEnabled = true }
    private fun startNextRound() {
        waitingForTurn = false; awaitingOpponent = false; dartsThisTurn = 0
        updateTurnCounter(); hideTurnEndPanel(); btnOpponent?.visibility = View.GONE; startThrowing()
    }

    // ─────────────────────────────────────────
    //  Crosshairs
    // ─────────────────────────────────────────

    private fun showCrosshairs() {
        crosshairsVisible = true; showCrosshair(true); showCrosshair(false)
        tvStatus?.apply { setTextColor(Color.rgb(240, 180, 40)); text = "Drag crosshairs to position" }
    }

    private fun showCrosshair(isStart: Boolean) {
        val size = dp(56); val color = if (isStart) Color.rgb(220, 60, 60) else Color.rgb(240, 200, 60)
        val lbl = if (isStart) "S" else "E"
        val view = object : View(this) {
            override fun onDraw(canvas: Canvas) {
                val cx = width/2f; val cy = height/2f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth = dp(2).toFloat(); style = Paint.Style.STROKE }
                val r = width/2f - dp(4)
                canvas.drawCircle(cx, cy, r, paint); canvas.drawCircle(cx, cy, r*0.25f, paint)
                val arm = r+dp(6); val gap = dp(5).toFloat()
                canvas.drawLine(cx-arm, cy, cx-gap, cy, paint); canvas.drawLine(cx+gap, cy, cx+arm, cy, paint)
                canvas.drawLine(cx, cy-arm, cx, cy-gap, paint); canvas.drawLine(cx, cy+gap, cx, cy+arm, paint)
                paint.style = Paint.Style.FILL; paint.color = Color.argb(220, color.red, color.green, color.blue)
                canvas.drawCircle(cx, cy, dp(5).toFloat(), paint)
                paint.color = Color.WHITE; paint.textSize = dp(10).toFloat(); paint.textAlign = Paint.Align.CENTER
                canvas.drawText(lbl, cx, cy + dp(4), paint)
            }
        }
        val px = if (isStart) startX else endX; val py = if (isStart) startY else endY
        val params = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (px-size/2).toInt(); y = (py-size/2).toInt() }
        var lastX2 = 0f; var lastY2 = 0f
        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { lastX2 = ev.rawX; lastY2 = ev.rawY }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX-lastX2; val dy = ev.rawY-lastY2
                    if (isStart) { startX += dx; startY += dy } else { endX += dx; endY += dy }
                    lastX2 = ev.rawX; lastY2 = ev.rawY
                    params.x = ((if (isStart) startX else endX)-size/2).toInt()
                    params.y = ((if (isStart) startY else endY)-size/2).toInt()
                    try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> {
                    if (isStart) { startX = ev.rawX; startY = ev.rawY } else { endX = ev.rawX; endY = ev.rawY }
                    tvStatus?.text = if (isStart) "Start: ${startX.toInt()},${startY.toInt()}" else "End: ${endX.toInt()},${endY.toInt()}"
                }
            }
            true
        }
        if (isStart) { crosshairStartView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; crosshairStartView = view; crosshairStartParams = params }
        else { crosshairEndView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; crosshairEndView = view; crosshairEndParams = params }
        wm.addView(view, params)
    }

    private fun hideCrosshairs() {
        crosshairStartView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; crosshairStartView = null
        crosshairEndView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; crosshairEndView = null; crosshairsVisible = false
    }

    // ─────────────────────────────────────────
    //  Script management
    // ─────────────────────────────────────────

    private fun saveCurrentAsScript(name: String) {
        val existing = scripts.indexOfFirst { it.name == name }
        val s = ThrowScript(name, startX, startY, endX, endY)
        if (existing >= 0) scripts[existing] = s else scripts.add(s)
        scriptStore.save(scripts)
        if (scripts.size == 1) activeScriptIndex = 0
        refreshScriptList(); updateActiveScriptLabel()
        tvStatus?.apply { setTextColor(Color.rgb(80, 200, 80)); text = "Saved: $name" }
        boardMapView?.let { (it as? LinearLayout)?.let { panel ->
            for (i in 0 until panel.childCount) { val c = panel.getChildAt(i); if (c is View && c.tag == "boardview") { c.invalidate() } }
        } }
    }

    private fun refreshScriptList() {
        val layout = scriptListLayout ?: return; layout.removeAllViews()
        if (scripts.isEmpty()) { layout.addView(label("No scripts yet", 8f, Color.rgb(60, 50, 30))); return }
        scripts.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(if (i == activeScriptIndex) Color.argb(60, 30, 100, 30) else Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(2) }
            }
            row.addView(label(s.name, 10f, if (i == activeScriptIndex) Color.rgb(160, 220, 100) else Color.rgb(140, 120, 70)).also {
                it.setPadding(dp(4), dp(3), 0, dp(3))
                it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(tv("▶", 10f, Color.rgb(60,160,60)) { activeScriptIndex = i; refreshScriptList(); updateActiveScriptLabel(); startX = s.startX; startY = s.startY; endX = s.endX; endY = s.endY; if (crosshairsVisible) { hideCrosshairs(); showCrosshairs() }; tvStatus?.text = "Active: ${s.name}" }.also { it.setPadding(dp(8), dp(3), dp(4), dp(3)) })
            row.addView(tv("✎", 10f, Color.rgb(140,120,50)) { startX = s.startX; startY = s.startY; endX = s.endX; endY = s.endY; showCrosshairs(); tvStatus?.text = "Editing: ${s.name}" }.also { it.setPadding(dp(4), dp(3), dp(4), dp(3)) })
            row.addView(tv("✕", 10f, Color.rgb(160,60,60)) { scripts.removeAt(i); if (activeScriptIndex >= scripts.size) activeScriptIndex = 0; scriptStore.save(scripts); refreshScriptList(); updateActiveScriptLabel() }.also { it.setPadding(dp(4), dp(3), dp(6), dp(3)) })
            layout.addView(row)
        }
    }

    private fun updateActiveScriptLabel() {
        tvActiveScript?.text = if (scripts.isEmpty()) "No scripts — position crosshairs & save"
                               else "Active: ${scripts.getOrNull(activeScriptIndex)?.name ?: "—"}"
    }

    // ─────────────────────────────────────────
    //  Throw
    // ─────────────────────────────────────────

    private fun resolveThrow(): Pair<Pair<Float,Float>, Pair<Float,Float>> {
        val s = scripts.getOrNull(activeScriptIndex)
        return if (s != null) Pair(Pair(s.startX, s.startY), Pair(s.endX, s.endY))
               else Pair(Pair(startX, startY), Pair(endX, endY))
    }

    private fun fireOneDart() {
        val (start, end) = resolveThrow()
        val jit = { (Random.nextFloat() * 2f - 1f) * 3f }
        val path = Path().apply { moveTo(start.first+jit(), start.second+jit()); lineTo(end.first+jit(), end.second+jit()) }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, 150L)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gd: GestureDescription?) { handler.post { onDartLanded() } }
                override fun onCancelled(gd: GestureDescription?) { handler.post { tvStatus?.text = "Gesture blocked"; onDartLanded() } }
            }, null
        )
    }

    private fun onDartLanded() {
        dartsThisTurn++; updateTurnCounter()
        if (dartsThisTurn >= 3) {
            isRunning = false; handler.removeCallbacksAndMessages(null)
            btnStart?.apply { text = "THROW"; setBackgroundColor(Color.rgb(25, 100, 50)) }
            showTurnEndPanel()
        } else { if (isRunning) handler.postDelayed({ if (isRunning) fireOneDart() }, throwIntervalMs) }
    }

    private fun toggleRunning() { if (waitingForTurn) return; if (isRunning) stopThrowing() else startThrowing() }
    private fun startThrowing() {
        isRunning = true
        btnStart?.apply { text = "STOP"; setBackgroundColor(Color.rgb(140, 25, 20)) }
        tvStatus?.apply { setTextColor(Color.rgb(80, 180, 80)); text = "Throwing..." }
        handler.postDelayed({ if (isRunning) fireOneDart() }, throwIntervalMs)
    }
    private fun stopThrowing() {
        isRunning = false; awaitingOpponent = false; waitingForTurn = false; handler.removeCallbacksAndMessages(null)
        btnStart?.apply { text = "THROW"; isEnabled = true; setBackgroundColor(Color.rgb(25, 100, 50)) }
        btnOpponent?.visibility = View.GONE; hideTurnEndPanel()
        tvStatus?.apply { setTextColor(Color.rgb(100, 100, 100)); text = "Stopped" }
    }
    private fun resetTurn() { dartsThisTurn = 0; updateTurnCounter(); hideTurnEndPanel(); if (isRunning) stopThrowing(); tvStatus?.text = "Turn reset" }

    private fun toggleWaitMode() { waitForOpponent = !waitForOpponent; btnWaitToggle?.text = waitLabel(); btnWaitToggle?.setBackgroundColor(waitColor()) }
    private fun resumeAfterOpponent() { awaitingOpponent = false; btnOpponent?.visibility = View.GONE; startNextRound() }
    private fun waitLabel() = if (waitForOpponent) "⏸  Opponent Wait: ON" else "⏸  Opponent Wait: OFF"
    private fun waitColor() = if (waitForOpponent) Color.rgb(20, 50, 85) else Color.rgb(25, 20, 10)

    private fun dartDots() = (1..3).joinToString(" ") { i -> if (i <= dartsThisTurn) "◉" else "○" }
    private fun updateTurnCounter() {
        tvTurnCounter?.apply { text = dartDots(); setTextColor(when(dartsThisTurn){ 0->Color.rgb(60,45,15); 3->Color.rgb(240,200,60); else->Color.rgb(180,140,50) }) }
    }

    private fun toggleExpanded() { isExpanded = !isExpanded; expandedContent?.visibility = if (isExpanded) View.VISIBLE else View.GONE; btnExpandToggle?.text = if (isExpanded) "▲" else "▼" }
    private fun cycleResize() { resizeIndex = (resizeIndex+1) % RESIZE_STEPS.size; overlayParams?.width = dp(RESIZE_STEPS[resizeIndex]); try { wm.updateViewLayout(overlayView, overlayParams) } catch (_: Exception) {} }
    private fun closeOverlay() { stopThrowing(); hideCrosshairs(); hideBoardMap(); overlayView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; overlayView = null }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false, center: Boolean = false) = TextView(this).apply {
        this.text = text; setTextColor(color); textSize = sp; if (bold) typeface = Typeface.DEFAULT_BOLD; if (center) gravity = Gravity.CENTER
    }
    private fun tv(text: String, sp: Float, color: Int, onClick: () -> Unit) = TextView(this).apply { this.text = text; setTextColor(color); textSize = sp; setOnClickListener { onClick() } }
    private fun button(text: String, bg: Int, onClick: () -> Unit) = Button(this).apply { this.text = text; setBackgroundColor(bg); setTextColor(Color.WHITE); textSize = 9f; setPadding(dp(3), dp(3), dp(3), dp(3)); setOnClickListener { onClick() } }
    private fun divider(vertMargin: Int = dp(4)) = View(this).apply { setBackgroundColor(Color.argb(60, 180, 130, 40)); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = vertMargin; it.bottomMargin = vertMargin } }
    private fun seekListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) = onChange(p); override fun onStartTrackingTouch(sb: SeekBar?) {}; override fun onStopTrackingTouch(sb: SeekBar?) {} }
    private fun hRow(topMargin: Int = 0) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = topMargin } }
    private fun fullWidthLp(topMargin: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = topMargin }
    private fun splitLp(leftMargin: Int = 0, rightMargin: Int = 0) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.leftMargin = leftMargin; it.rightMargin = rightMargin }
    private fun sliderRow(left: String, right: String) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; addView(label(left, 7f, Color.rgb(70,50,20)).also { it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }); addView(label(right, 7f, Color.rgb(70,50,20))) }
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private val Int.red get() = (this shr 16) and 0xFF
    private val Int.green get() = (this shr 8) and 0xFF
    private val Int.blue get() = this and 0xFF
}
