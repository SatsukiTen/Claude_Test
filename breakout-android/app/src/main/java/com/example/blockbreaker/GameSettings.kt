package com.example.blockbreaker

import android.content.Context

object GameSettings {
    private const val PREFS_NAME = "blockbreaker_settings"

    const val KEY_BALL_SPEED    = "ball_speed"
    const val KEY_MAX_SPEED     = "max_speed_mult"
    const val KEY_PADDLE_BASE   = "paddle_base"
    const val KEY_PADDLE_SHRINK = "paddle_shrink"

    const val DEF_BALL_SPEED    = 0.015f
    const val DEF_MAX_SPEED     = 4.0f
    const val DEF_PADDLE_BASE   = 28f
    const val DEF_PADDLE_SHRINK = 1.6f

    const val MIN_BALL_SPEED    = 0.005f; const val MAX_BALL_SPEED    = 0.030f
    const val MIN_MAX_SPEED     = 1.0f;  const val MAX_MAX_SPEED     = 8.0f
    const val MIN_PADDLE_BASE   = 10f;   const val MAX_PADDLE_BASE   = 45f
    const val MIN_PADDLE_SHRINK = 0f;    const val MAX_PADDLE_SHRINK = 4.0f

    fun load(context: Context): FloatArray {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return floatArrayOf(
            p.getFloat(KEY_BALL_SPEED,    DEF_BALL_SPEED),
            p.getFloat(KEY_MAX_SPEED,     DEF_MAX_SPEED),
            p.getFloat(KEY_PADDLE_BASE,   DEF_PADDLE_BASE),
            p.getFloat(KEY_PADDLE_SHRINK, DEF_PADDLE_SHRINK)
        )
    }

    fun save(context: Context, values: FloatArray) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().run {
            putFloat(KEY_BALL_SPEED,    values[0])
            putFloat(KEY_MAX_SPEED,     values[1])
            putFloat(KEY_PADDLE_BASE,   values[2])
            putFloat(KEY_PADDLE_SHRINK, values[3])
            apply()
        }
    }

    fun reset(context: Context) =
        save(context, floatArrayOf(DEF_BALL_SPEED, DEF_MAX_SPEED, DEF_PADDLE_BASE, DEF_PADDLE_SHRINK))
}
