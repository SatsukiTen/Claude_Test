package com.example.blockbreaker

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var soundManager: SoundManager
    private lateinit var gameView: GameView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        soundManager = SoundManager(this)
        soundManager.init()

        gameView = GameView(this, soundManager = soundManager)
        setContentView(gameView)
    }

    override fun onPause() {
        super.onPause()
        soundManager.pauseBgm()
        gameView.pause()
    }

    override fun onResume() {
        super.onResume()
        // BGM はタップ時のみ再開するのでここでは resumeBgm() しない
        gameView.reloadSettings()
        gameView.resume()
    }

    override fun onDestroy() {
        super.onDestroy()
        soundManager.release()
    }
}
