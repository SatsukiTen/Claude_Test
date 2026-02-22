package com.example.blockbreaker

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    // 各パラメータの表示名、最小値、最大値
    private val paramLabels = listOf(
        "Ball Speed",
        "Max Speed Multiplier",
        "Paddle Width (Stage 1 %)",
        "Paddle Shrink / Stage (%)"
    )
    private val paramMins = floatArrayOf(
        GameSettings.MIN_BALL_SPEED,
        GameSettings.MIN_MAX_SPEED,
        GameSettings.MIN_PADDLE_BASE,
        GameSettings.MIN_PADDLE_SHRINK
    )
    private val paramMaxs = floatArrayOf(
        GameSettings.MAX_BALL_SPEED,
        GameSettings.MAX_MAX_SPEED,
        GameSettings.MAX_PADDLE_BASE,
        GameSettings.MAX_PADDLE_SHRINK
    )

    private lateinit var seekBars: List<SeekBar>
    private lateinit var labelViews: List<TextView>
    private val currentValues = FloatArray(4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // SharedPreferences から設定を読み込む
        GameSettings.load(this).copyInto(currentValues)

        seekBars = listOf(
            findViewById(R.id.seekBallSpeed),
            findViewById(R.id.seekMaxSpeed),
            findViewById(R.id.seekPaddleBase),
            findViewById(R.id.seekPaddleShrink)
        )
        labelViews = listOf(
            findViewById(R.id.labelBallSpeed),
            findViewById(R.id.labelMaxSpeed),
            findViewById(R.id.labelPaddleBase),
            findViewById(R.id.labelPaddleShrink)
        )

        for (i in 0..3) {
            seekBars[i].progress = toProgress(i, currentValues[i])
            updateLabel(i)
            val index = i  // ラムダキャプチャ用
            seekBars[i].setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    currentValues[index] = toValue(index, progress)
                    updateLabel(index)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    // ドラッグ終了時に保存
                    GameSettings.save(this@SettingsActivity, currentValues)
                }
            })
        }

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            currentValues[0] = GameSettings.DEF_BALL_SPEED
            currentValues[1] = GameSettings.DEF_MAX_SPEED
            currentValues[2] = GameSettings.DEF_PADDLE_BASE
            currentValues[3] = GameSettings.DEF_PADDLE_SHRINK
            for (i in 0..3) {
                seekBars[i].progress = toProgress(i, currentValues[i])
                updateLabel(i)
            }
            GameSettings.save(this, currentValues)
        }

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
    }

    override fun onStop() {
        super.onStop()
        // 画面を離れる際に必ず保存
        GameSettings.save(this, currentValues)
    }

    // float → SeekBar progress (0-1000)
    private fun toProgress(i: Int, v: Float): Int =
        ((v - paramMins[i]) / (paramMaxs[i] - paramMins[i]) * 1000f)
            .toInt().coerceIn(0, 1000)

    // SeekBar progress → float
    private fun toValue(i: Int, progress: Int): Float =
        paramMins[i] + progress / 1000f * (paramMaxs[i] - paramMins[i])

    private fun updateLabel(i: Int) {
        val valueStr = when (i) {
            0    -> "%.3f".format(currentValues[i])
            1    -> "%.1f ×".format(currentValues[i])
            2    -> "%.0f %%".format(currentValues[i])
            else -> "%.1f %%".format(currentValues[i])
        }
        labelViews[i].text = "${paramLabels[i]}:  $valueStr"
    }
}
