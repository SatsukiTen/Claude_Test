package com.example.blockbreaker

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.example.blockbreaker.SoundManager.SfxId
import kotlin.math.abs

class GameView(
    context: Context,
    attrs: AttributeSet? = null,
    private val soundManager: SoundManager? = null
) : SurfaceView(context, attrs), Runnable, SurfaceHolder.Callback {

    // ── Thread ──────────────────────────────────────────────────────────────

    private var gameThread: Thread? = null
    @Volatile private var isRunning = false

    // ── Screen ──────────────────────────────────────────────────────────────

    private var screenW = 0f
    private var screenH = 0f

    // ── Tunable parameters (loaded from SharedPreferences) ───────────────────

    private var cfgBallSpeed    = GameSettings.DEF_BALL_SPEED
    private var cfgMaxSpeedMult = GameSettings.DEF_MAX_SPEED
    private var cfgPaddleBase   = GameSettings.DEF_PADDLE_BASE
    private var cfgPaddleShrink = GameSettings.DEF_PADDLE_SHRINK

    fun reloadSettings() {
        val v = GameSettings.load(context)
        cfgBallSpeed    = v[0]
        cfgMaxSpeedMult = v[1]
        cfgPaddleBase   = v[2]
        cfgPaddleShrink = v[3]
    }

    // ── Game objects ────────────────────────────────────────────────────────

    private var ball = Ball(0f, 0f)
    private var paddle = Paddle(0f, 0f)
    private val blocks = mutableListOf<Block>()
    private var ballSpeed = 0f
    private var totalBlocks = 0
    private var currentPitchRate = 1f

    // ── State ───────────────────────────────────────────────────────────────

    enum class GameState { STAGE_SELECT, WAITING, PLAYING, PAUSED, STAGE_CLEAR, GAME_OVER, WIN }

    @Volatile private var gameState = GameState.STAGE_SELECT
    private var score = 0
    private var lives = 3
    private var stage = 1
    private val maxStage = 10

    // ── Touch tracking ──────────────────────────────────────────────────────

    // ポーズボタンを押し始めたかどうか（パドル操作と区別するため）
    private var pauseButtonTouched = false

    // ── Stage patterns (8 cols × 5 rows) ────────────────────────────────────

    private val stagePatterns = arrayOf(
        arrayOf("...##...", "...##...", "########", "...##...", "...##..."),  // 1  Plus (16)
        arrayOf("#.#.#.#.", ".#.#.#.#", "#.#.#.#.", ".#.#.#.#", "#.#.#.#."), // 2  Checkerboard (20)
        arrayOf("########", "........", "########", "........", "########"),  // 3  Alt rows (24)
        arrayOf("...##...", "..####..", ".######.", "########", "########"),   // 4  Pyramid↑ (28)
        arrayOf("########", "########", ".######.", "..####..", "...##..."),   // 5  Pyramid↓ (28)
        arrayOf("########", "#.####.#", "#......#", "#.####.#", "########"),  // 6  Double frame (30)
        arrayOf("########", "#.#.#.#.", "########", ".#.#.#.#", "########"),  // 7  Dense alt (32)
        arrayOf("########", "########", "##....##", "########", "########"),  // 8  Near-full (36)
        arrayOf("########", "########", "########", "########", "#.####.#"),  // 9  Almost full (38)
        arrayOf("########", "########", "########", "########", "########")   // 10 Full (40)
    )

    // ── Paint ───────────────────────────────────────────────────────────────

    private val bgPaint      = Paint().apply { color = Color.BLACK }
    private val ballPaint    = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val paddlePaint  = Paint().apply { color = Color.rgb(100, 200, 255); isAntiAlias = true }
    private val blockPaint   = Paint().apply { isAntiAlias = true }
    private val buttonPaint  = Paint().apply { isAntiAlias = true }
    private val overlayPaint = Paint().apply { color = Color.argb(190, 0, 0, 0) }
    private val textPaint    = Paint().apply {
        color = Color.WHITE; isAntiAlias = true; textAlign = Paint.Align.CENTER
    }

    // ── Init ────────────────────────────────────────────────────────────────

    init { holder.addCallback(this); isFocusable = true }

    // ── SurfaceHolder.Callback ──────────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) { resume() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenW = width.toFloat(); screenH = height.toFloat()
        reloadSettings()
        initToSelectScreen()
        resume()
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) { pause() }

    // ── Lifecycle helpers ────────────────────────────────────────────────────

    fun pause() {
        isRunning = false
        gameThread?.let { t ->
            t.interrupt()
            try { t.join(2000) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        gameThread = null
    }

    fun resume() {
        if (isRunning || screenW == 0f) return
        isRunning = true
        gameThread = Thread(this).apply { start() }
    }

    // ── Game setup ──────────────────────────────────────────────────────────

    private fun baseBallSpeed() = screenW * cfgBallSpeed

    private fun paddleWidthForStage() =
        (screenW * ((cfgPaddleBase - (stage - 1) * cfgPaddleShrink) / 100f))
            .coerceAtLeast(screenW * 0.10f)

    private fun initToSelectScreen() {
        score = 0; lives = 3; stage = 1
        soundManager?.pauseBgm()
        gameState = GameState.STAGE_SELECT
    }

    private fun startStage() {
        currentPitchRate = 1f
        ballSpeed = baseBallSpeed()

        val pw = paddleWidthForStage()
        val ph = maxOf(screenH * 0.018f, 25f)
        val py = screenH * 0.85f
        paddle = Paddle(screenW / 2f - pw / 2f, py, pw, ph)

        val r = screenW * 0.028f
        ball = Ball(screenW / 2f, paddle.y - r - 4f, r, ballSpeed, -ballSpeed)

        setupBlocks()
        totalBlocks = blocks.size
        gameState = GameState.WAITING
    }

    private fun setupBlocks() {
        blocks.clear()
        val pattern = stagePatterns[(stage - 1).coerceIn(0, stagePatterns.lastIndex)]
        val cols = pattern[0].length; val rows = pattern.size
        val margin = screenW * 0.015f
        val bw = (screenW - margin * (cols + 1)) / cols
        val bh = screenH * 0.045f
        val topOffset = screenH * 0.15f
        val rowColors = intArrayOf(
            Color.rgb(220, 50, 50), Color.rgb(220, 140, 50), Color.rgb(200, 200, 50),
            Color.rgb(50, 180, 50), Color.rgb(50, 150, 220)
        )
        for (row in 0 until rows) for (col in 0 until cols) {
            if (pattern[row].getOrElse(col) { '.' } != '#') continue
            blocks.add(Block(margin + col * (bw + margin), topOffset + row * (bh + margin), bw, bh, rowColors[row % 5]))
        }
    }

    // ── Layout helpers ───────────────────────────────────────────────────────

    private fun stageButtonRect(s: Int): RectF {
        val col = (s - 1) % 2; val row = (s - 1) / 2
        val btnW = screenW * 0.42f; val btnH = screenH * 0.075f
        val gapX = screenW * 0.06f; val gapY = screenH * 0.018f
        val sx   = (screenW - btnW * 2f - gapX) / 2f; val sy = screenH * 0.28f
        return RectF(sx + col * (btnW + gapX), sy + row * (btnH + gapY),
                     sx + col * (btnW + gapX) + btnW, sy + row * (btnH + gapY) + btnH)
    }

    // ゲームプレイ中のポーズボタン（右上）
    private fun pauseButtonRect() = RectF(
        screenW * 0.83f, screenH * 0.008f, screenW * 0.98f, screenH * 0.065f
    )

    // ポーズ画面のボタン群
    private fun resumeButtonRect()   = RectF(screenW * 0.15f, screenH * 0.42f, screenW * 0.85f, screenH * 0.505f)
    private fun debugButtonRect()    = RectF(screenW * 0.15f, screenH * 0.535f, screenW * 0.85f, screenH * 0.620f)
    private fun toSelectButtonRect() = RectF(screenW * 0.15f, screenH * 0.650f, screenW * 0.85f, screenH * 0.735f)

    // ── Game loop ───────────────────────────────────────────────────────────

    override fun run() {
        val msPerFrame = 1000L / 60L
        while (isRunning) {
            val t0 = System.currentTimeMillis()
            update()
            renderFrame()
            val sleep = msPerFrame - (System.currentTimeMillis() - t0)
            if (sleep > 0) try { Thread.sleep(sleep) } catch (e: InterruptedException) { break }
        }
    }

    private fun update() {
        if (gameState != GameState.PLAYING) return

        ball.x += ball.speedX; ball.y += ball.speedY

        // Wall bounces
        if (ball.x - ball.radius < 0f) {
            ball.x = ball.radius; ball.speedX = abs(ball.speedX)
            soundManager?.playSfx(SfxId.WALL_HIT, currentPitchRate)
        } else if (ball.x + ball.radius > screenW) {
            ball.x = screenW - ball.radius; ball.speedX = -abs(ball.speedX)
            soundManager?.playSfx(SfxId.WALL_HIT, currentPitchRate)
        }
        if (ball.y - ball.radius < 0f) {
            ball.y = ball.radius; ball.speedY = abs(ball.speedY)
            soundManager?.playSfx(SfxId.WALL_HIT, currentPitchRate)
        }

        // Ball lost
        if (ball.y - ball.radius > screenH) {
            lives--
            if (lives <= 0) { gameState = GameState.GAME_OVER; soundManager?.playSfx(SfxId.GAME_OVER) }
            else { soundManager?.playSfx(SfxId.LIFE_LOST); resetBall(); gameState = GameState.WAITING }
            soundManager?.pauseBgm(); return
        }

        // Paddle bounce
        if (ball.speedY > 0f &&
            ball.y + ball.radius >= paddle.y && ball.y - ball.radius <= paddle.y + paddle.height &&
            ball.x + ball.radius >= paddle.x && ball.x - ball.radius <= paddle.x + paddle.width
        ) {
            ball.y = paddle.y - ball.radius
            ball.speedY = -abs(ball.speedY)
            ball.speedX = ballSpeed * ((ball.x - paddle.x) / paddle.width * 2f - 1f)
            soundManager?.playSfx(SfxId.PADDLE_HIT, currentPitchRate)
        }

        // Block collision
        var reflX = false; var reflY = false; var chain = 0
        for (block in blocks) {
            if (!block.isAlive) continue
            val axis = blockHitAxis(block) ?: continue
            block.isAlive = false; chain++
            score += chain * 10
            when (axis) {
                'x' -> if (!reflX) { ball.speedX = -ball.speedX; reflX = true }
                else -> if (!reflY) { ball.speedY = -ball.speedY; reflY = true }
            }
        }
        if (chain > 0) {
            val ratio = 1f - blocks.count { it.isAlive }.toFloat() / totalBlocks
            currentPitchRate = 1f + ratio * 0.5f
            val newSpeed = baseBallSpeed() * (1f + ratio * (cfgMaxSpeedMult - 1f))
            if (ballSpeed > 0f) { val s = newSpeed / ballSpeed; ball.speedX *= s; ball.speedY *= s }
            ballSpeed = newSpeed
            soundManager?.playSfx(SfxId.BLOCK_HIT, currentPitchRate)
        }

        if (blocks.none { it.isAlive }) {
            soundManager?.playSfx(SfxId.WIN); soundManager?.pauseBgm()
            gameState = if (stage < maxStage) GameState.STAGE_CLEAR else GameState.WIN
        }
    }

    private fun blockHitAxis(b: Block): Char? {
        val nearX = ball.x.coerceIn(b.x, b.x + b.width)
        val nearY = ball.y.coerceIn(b.y, b.y + b.height)
        val dx = ball.x - nearX; val dy = ball.y - nearY
        if (dx * dx + dy * dy > ball.radius * ball.radius) return null
        val ox = minOf((ball.x + ball.radius) - b.x, (b.x + b.width) - (ball.x - ball.radius))
        val oy = minOf((ball.y + ball.radius) - b.y, (b.y + b.height) - (ball.y - ball.radius))
        return if (ox < oy) 'x' else 'y'
    }

    private fun resetBall() {
        ball.x = screenW / 2f; ball.y = paddle.y - ball.radius - 4f
        ball.speedX = ballSpeed; ball.speedY = -ballSpeed
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun renderFrame() {
        val canvas = holder.lockCanvas() ?: return
        try { drawScene(canvas) } finally { holder.unlockCanvasAndPost(canvas) }
    }

    private fun drawScene(canvas: Canvas) {
        canvas.drawRect(0f, 0f, screenW, screenH, bgPaint)

        when (gameState) {
            GameState.STAGE_SELECT -> { drawStageSelect(canvas); return }
            else -> Unit
        }

        // ゲームオブジェクト（全ゲーム中状態で描画）
        for (block in blocks) {
            if (!block.isAlive) continue
            blockPaint.color = block.color
            canvas.drawRoundRect(block.x, block.y, block.x + block.width, block.y + block.height, 10f, 10f, blockPaint)
        }
        canvas.drawRoundRect(paddle.x, paddle.y, paddle.x + paddle.width, paddle.y + paddle.height,
            paddle.height / 2f, paddle.height / 2f, paddlePaint)
        canvas.drawCircle(ball.x, ball.y, ball.radius, ballPaint)

        // HUD（Score 左寄り / Lives 中央寄り / Stage 中央）
        textPaint.textSize = screenH * 0.038f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Score: $score", screenW * 0.04f, screenH * 0.055f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Lives: $lives", screenW * 0.80f, screenH * 0.055f, textPaint)
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Stage $stage / $maxStage", screenW / 2f, screenH * 0.100f, textPaint)

        // ポーズボタン（WAITING / PLAYING のときだけ表示）
        if (gameState == GameState.WAITING || gameState == GameState.PLAYING) {
            drawPauseButton(canvas)
        }

        // 状態別オーバーレイ
        textPaint.textAlign = Paint.Align.CENTER
        when (gameState) {
            GameState.WAITING -> {
                textPaint.textSize = screenH * 0.045f
                canvas.drawText(
                    if (score == 0 && lives == 3) "Drag paddle  ·  Tap to start"
                    else "Tap to continue  (Lives: $lives)",
                    screenW / 2f, screenH * 0.62f, textPaint
                )
            }
            GameState.PAUSED      -> drawPauseOverlay(canvas)
            GameState.STAGE_CLEAR -> {
                textPaint.textSize = screenH * 0.07f
                canvas.drawText("STAGE $stage", screenW / 2f, screenH * 0.44f, textPaint)
                textPaint.textSize = screenH * 0.06f
                canvas.drawText("CLEAR!", screenW / 2f, screenH * 0.53f, textPaint)
                textPaint.textSize = screenH * 0.04f
                canvas.drawText("Tap for Stage ${stage + 1}", screenW / 2f, screenH * 0.64f, textPaint)
            }
            GameState.GAME_OVER -> {
                textPaint.textSize = screenH * 0.08f
                canvas.drawText("GAME OVER", screenW / 2f, screenH * 0.44f, textPaint)
                textPaint.textSize = screenH * 0.05f
                canvas.drawText("Score: $score", screenW / 2f, screenH * 0.53f, textPaint)
                textPaint.textSize = screenH * 0.04f
                canvas.drawText("Tap to stage select", screenW / 2f, screenH * 0.64f, textPaint)
            }
            GameState.WIN -> {
                textPaint.textSize = screenH * 0.07f
                canvas.drawText("ALL CLEAR!", screenW / 2f, screenH * 0.44f, textPaint)
                textPaint.textSize = screenH * 0.05f
                canvas.drawText("Score: $score", screenW / 2f, screenH * 0.53f, textPaint)
                textPaint.textSize = screenH * 0.04f
                canvas.drawText("Tap to stage select", screenW / 2f, screenH * 0.64f, textPaint)
            }
            else -> Unit
        }
    }

    // ── Stage select screen ──────────────────────────────────────────────────

    private fun drawStageSelect(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = screenH * 0.06f
        canvas.drawText("SELECT STAGE", screenW / 2f, screenH * 0.18f, textPaint)

        for (s in 1..maxStage) {
            val rect = stageButtonRect(s)
            buttonPaint.color = when {
                s <= 3  -> Color.rgb(30, 90, 50)
                s <= 6  -> Color.rgb(80, 70, 20)
                else    -> Color.rgb(90, 30, 30)
            }
            canvas.drawRoundRect(rect, 18f, 18f, buttonPaint)
            val blockCount = stagePatterns[s - 1].sumOf { r -> r.count { it == '#' } }
            textPaint.textSize = screenH * 0.034f
            canvas.drawText("Stage $s  ($blockCount blocks)",
                rect.centerX(), rect.centerY() + textPaint.textSize * 0.38f, textPaint)
        }
    }

    // ── Pause button ─────────────────────────────────────────────────────────

    private fun drawPauseButton(canvas: Canvas) {
        val r = pauseButtonRect()
        buttonPaint.color = Color.argb(180, 35, 35, 60)
        canvas.drawRoundRect(r, 10f, 10f, buttonPaint)
        textPaint.textSize = screenH * 0.032f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("⏸", r.centerX(), r.centerY() + textPaint.textSize * 0.38f, textPaint)
    }

    // ── Pause overlay ────────────────────────────────────────────────────────

    private fun drawPauseOverlay(canvas: Canvas) {
        // 半透明オーバーレイ
        canvas.drawRect(0f, 0f, screenW, screenH, overlayPaint)

        textPaint.textAlign = Paint.Align.CENTER

        // タイトル
        textPaint.textSize = screenH * 0.075f
        canvas.drawText("PAUSED", screenW / 2f, screenH * 0.30f, textPaint)

        // ▶ RESUME
        val resume = resumeButtonRect()
        buttonPaint.color = Color.rgb(30, 90, 45)
        canvas.drawRoundRect(resume, 18f, 18f, buttonPaint)
        textPaint.textSize = screenH * 0.040f
        canvas.drawText("▶  RESUME", resume.centerX(), resume.centerY() + textPaint.textSize * 0.38f, textPaint)

        // ⚙ DEBUG SETTINGS
        val debug = debugButtonRect()
        buttonPaint.color = Color.rgb(35, 35, 80)
        canvas.drawRoundRect(debug, 18f, 18f, buttonPaint)
        textPaint.textSize = screenH * 0.036f
        canvas.drawText("⚙  DEBUG SETTINGS", debug.centerX(), debug.centerY() + textPaint.textSize * 0.38f, textPaint)

        // ↩ STAGE SELECT
        val toSelect = toSelectButtonRect()
        buttonPaint.color = Color.rgb(70, 28, 28)
        canvas.drawRoundRect(toSelect, 18f, 18f, buttonPaint)
        textPaint.textSize = screenH * 0.036f
        canvas.drawText("↩  STAGE SELECT", toSelect.centerX(), toSelect.centerY() + textPaint.textSize * 0.38f, textPaint)
    }

    // ── Touch ───────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        when (event.action) {

            MotionEvent.ACTION_DOWN -> {
                when (gameState) {
                    GameState.WAITING, GameState.PLAYING -> {
                        if (pauseButtonRect().contains(x, y)) {
                            pauseButtonTouched = true   // ポーズボタン押下開始
                        } else {
                            pauseButtonTouched = false
                            movePaddle(x)
                        }
                    }
                    else -> Unit
                }
            }

            MotionEvent.ACTION_MOVE -> {
                when (gameState) {
                    GameState.WAITING, GameState.PLAYING -> {
                        if (!pauseButtonTouched) {
                            movePaddle(x)
                            if (gameState == GameState.WAITING) {
                                gameState = GameState.PLAYING
                                soundManager?.resumeBgm()
                            }
                        }
                    }
                    else -> Unit
                }
            }

            MotionEvent.ACTION_UP -> {
                when (gameState) {
                    GameState.WAITING, GameState.PLAYING -> {
                        if (pauseButtonTouched && pauseButtonRect().contains(x, y)) {
                            // ポーズ
                            soundManager?.pauseBgm()
                            gameState = GameState.PAUSED
                        }
                        pauseButtonTouched = false
                    }
                    GameState.PAUSED -> when {
                        resumeButtonRect().contains(x, y) -> {
                            // レジューム
                            gameState = GameState.WAITING
                            soundManager?.resumeBgm()
                        }
                        debugButtonRect().contains(x, y) -> {
                            // デバッグ設定画面を開く
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        }
                        toSelectButtonRect().contains(x, y) -> {
                            initToSelectScreen()
                        }
                    }
                    GameState.STAGE_SELECT -> {
                        for (s in 1..maxStage) {
                            if (stageButtonRect(s).contains(x, y)) {
                                stage = s; score = 0; lives = 3; startStage(); break
                            }
                        }
                    }
                    GameState.STAGE_CLEAR             -> { stage++; startStage() }
                    GameState.GAME_OVER, GameState.WIN -> initToSelectScreen()
                }
            }
        }
        return true
    }

    private fun movePaddle(x: Float) {
        paddle.x = (x - paddle.width / 2f).coerceIn(0f, screenW - paddle.width)
    }
}
