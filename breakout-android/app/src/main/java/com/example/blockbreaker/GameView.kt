package com.example.blockbreaker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs

class GameView(context: Context, attrs: AttributeSet? = null) :
    SurfaceView(context, attrs), Runnable, SurfaceHolder.Callback {

    // ── Thread ──────────────────────────────────────────────────────────────

    private var gameThread: Thread? = null
    @Volatile private var isRunning = false

    // ── Screen ──────────────────────────────────────────────────────────────

    private var screenW = 0f
    private var screenH = 0f

    // ── Game objects ────────────────────────────────────────────────────────

    private var ball = Ball(0f, 0f)
    private var paddle = Paddle(0f, 0f)
    private val blocks = mutableListOf<Block>()
    private var ballSpeed = 0f

    // ── State ───────────────────────────────────────────────────────────────

    enum class GameState { WAITING, PLAYING, GAME_OVER, WIN }

    private var gameState = GameState.WAITING
    private var score = 0
    private var lives = 3

    // ── Paint ───────────────────────────────────────────────────────────────

    private val bgPaint = Paint().apply { color = Color.BLACK }
    private val ballPaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }
    private val paddlePaint = Paint().apply { color = Color.rgb(100, 200, 255); isAntiAlias = true }
    private val blockPaint = Paint().apply { isAntiAlias = true }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    // ── Init ────────────────────────────────────────────────────────────────

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    // ── SurfaceHolder.Callback ──────────────────────────────────────────────

    override fun surfaceCreated(holder: SurfaceHolder) {
        resume()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenW = width.toFloat()
        screenH = height.toFloat()
        initGame()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        // スレッドは onPause() で停止済みのはず。念のため停止を保証する。
        pause()
    }

    // ── Lifecycle helpers (Activity から呼ぶ) ──────────────────────────────────

    fun pause() {
        isRunning = false
        try {
            gameThread?.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        gameThread = null
    }

    fun resume() {
        if (isRunning) return          // 多重起動防止
        if (screenW == 0f) return      // surfaceChanged 前は開始しない
        isRunning = true
        gameThread = Thread(this).apply { start() }
    }

    // ── Game setup ──────────────────────────────────────────────────────────

    private fun initGame() {
        ballSpeed = screenW * 0.015f

        val pw = screenW * 0.25f
        val ph = maxOf(screenH * 0.018f, 25f)
        val py = screenH * 0.85f
        paddle = Paddle(screenW / 2f - pw / 2f, py, pw, ph)

        val r = screenW * 0.028f
        ball = Ball(screenW / 2f, paddle.y - r - 4f, r, ballSpeed, -ballSpeed)

        setupBlocks()
        score = 0
        lives = 3
        gameState = GameState.WAITING
    }

    private fun setupBlocks() {
        blocks.clear()
        val cols = 8
        val rows = 5
        val margin = screenW * 0.015f
        val topOffset = screenH * 0.14f
        val bw = (screenW - margin * (cols + 1)) / cols
        val bh = screenH * 0.045f

        val rowColors = intArrayOf(
            Color.rgb(220, 50, 50),    // red
            Color.rgb(220, 140, 50),   // orange
            Color.rgb(200, 200, 50),   // yellow
            Color.rgb(50, 180, 50),    // green
            Color.rgb(50, 150, 220)    // blue
        )

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val x = margin + col * (bw + margin)
                val y = topOffset + row * (bh + margin)
                blocks.add(Block(x, y, bw, bh, rowColors[row]))
            }
        }
    }

    // ── Game loop ───────────────────────────────────────────────────────────

    override fun run() {
        val msPerFrame = 1000L / 60L
        while (isRunning) {
            val t0 = System.currentTimeMillis()
            update()
            renderFrame()
            val sleep = msPerFrame - (System.currentTimeMillis() - t0)
            if (sleep > 0) Thread.sleep(sleep)
        }
    }

    private fun update() {
        if (gameState != GameState.PLAYING) return

        ball.x += ball.speedX
        ball.y += ball.speedY

        // Wall bounces
        if (ball.x - ball.radius < 0f) {
            ball.x = ball.radius
            ball.speedX = abs(ball.speedX)
        } else if (ball.x + ball.radius > screenW) {
            ball.x = screenW - ball.radius
            ball.speedX = -abs(ball.speedX)
        }
        if (ball.y - ball.radius < 0f) {
            ball.y = ball.radius
            ball.speedY = abs(ball.speedY)
        }

        // Ball lost
        if (ball.y - ball.radius > screenH) {
            lives--
            if (lives <= 0) {
                gameState = GameState.GAME_OVER
            } else {
                resetBall()
                gameState = GameState.WAITING
            }
            return
        }

        // Paddle bounce
        if (ball.speedY > 0f &&
            ball.y + ball.radius >= paddle.y &&
            ball.y - ball.radius <= paddle.y + paddle.height &&
            ball.x + ball.radius >= paddle.x &&
            ball.x - ball.radius <= paddle.x + paddle.width
        ) {
            ball.y = paddle.y - ball.radius
            ball.speedY = -abs(ball.speedY)
            // Angle based on hit position: left → left, right → right, center → straight up
            val hitRatio = (ball.x - paddle.x) / paddle.width  // 0.0 .. 1.0
            ball.speedX = ballSpeed * (hitRatio * 2f - 1f)
        }

        // Block collision — break on first hit per frame
        for (block in blocks) {
            if (!block.isAlive) continue
            if (checkBallBlock(block)) {
                block.isAlive = false
                score += 10
                break
            }
        }

        if (blocks.none { it.isAlive }) {
            gameState = GameState.WIN
        }
    }

    /**
     * Circle–AABB collision. Returns true and applies bounce if overlapping.
     */
    private fun checkBallBlock(b: Block): Boolean {
        val nearX = ball.x.coerceIn(b.x, b.x + b.width)
        val nearY = ball.y.coerceIn(b.y, b.y + b.height)
        val dx = ball.x - nearX
        val dy = ball.y - nearY
        if (dx * dx + dy * dy > ball.radius * ball.radius) return false

        // Reflect off the axis with smaller penetration depth
        val overlapX = minOf((ball.x + ball.radius) - b.x, (b.x + b.width) - (ball.x - ball.radius))
        val overlapY = minOf((ball.y + ball.radius) - b.y, (b.y + b.height) - (ball.y - ball.radius))
        if (overlapX < overlapY) {
            ball.speedX = -ball.speedX
        } else {
            ball.speedY = -ball.speedY
        }
        return true
    }

    private fun resetBall() {
        ball.x = screenW / 2f
        ball.y = paddle.y - ball.radius - 4f
        ball.speedX = ballSpeed
        ball.speedY = -ballSpeed
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun renderFrame() {
        val canvas = holder.lockCanvas() ?: return
        try {
            drawScene(canvas)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun drawScene(canvas: Canvas) {
        // Background
        canvas.drawRect(0f, 0f, screenW, screenH, bgPaint)

        // Blocks
        for (block in blocks) {
            if (!block.isAlive) continue
            blockPaint.color = block.color
            canvas.drawRoundRect(
                block.x, block.y,
                block.x + block.width, block.y + block.height,
                10f, 10f, blockPaint
            )
        }

        // Paddle
        canvas.drawRoundRect(
            paddle.x, paddle.y,
            paddle.x + paddle.width, paddle.y + paddle.height,
            paddle.height / 2f, paddle.height / 2f, paddlePaint
        )

        // Ball
        canvas.drawCircle(ball.x, ball.y, ball.radius, ballPaint)

        // HUD
        textPaint.textSize = screenH * 0.04f
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("Score: $score", screenW * 0.04f, screenH * 0.07f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Lives: $lives", screenW * 0.96f, screenH * 0.07f, textPaint)

        // State overlay
        textPaint.textAlign = Paint.Align.CENTER
        when (gameState) {
            GameState.WAITING -> {
                textPaint.textSize = screenH * 0.045f
                val msg = if (score == 0 && lives == 3) "Drag paddle  ·  Tap to start"
                          else "Tap to continue  (Lives: $lives)"
                canvas.drawText(msg, screenW / 2f, screenH * 0.60f, textPaint)
            }
            GameState.GAME_OVER -> {
                textPaint.textSize = screenH * 0.08f
                canvas.drawText("GAME OVER", screenW / 2f, screenH * 0.44f, textPaint)
                textPaint.textSize = screenH * 0.05f
                canvas.drawText("Score: $score", screenW / 2f, screenH * 0.53f, textPaint)
                textPaint.textSize = screenH * 0.04f
                canvas.drawText("Tap to restart", screenW / 2f, screenH * 0.62f, textPaint)
            }
            GameState.WIN -> {
                textPaint.textSize = screenH * 0.08f
                canvas.drawText("YOU WIN!", screenW / 2f, screenH * 0.44f, textPaint)
                textPaint.textSize = screenH * 0.05f
                canvas.drawText("Score: $score", screenW / 2f, screenH * 0.53f, textPaint)
                textPaint.textSize = screenH * 0.04f
                canvas.drawText("Tap to play again", screenW / 2f, screenH * 0.62f, textPaint)
            }
            else -> Unit
        }
    }

    // ── Touch ───────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (gameState == GameState.WAITING || gameState == GameState.PLAYING) {
                    paddle.x = (event.x - paddle.width / 2f).coerceIn(0f, screenW - paddle.width)
                    if (gameState == GameState.WAITING) gameState = GameState.PLAYING
                }
            }
            MotionEvent.ACTION_UP -> {
                if (gameState == GameState.GAME_OVER || gameState == GameState.WIN) {
                    initGame()
                }
            }
        }
        return true
    }
}
