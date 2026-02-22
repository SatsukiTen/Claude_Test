package com.example.blockbreaker

data class Ball(
    var x: Float,
    var y: Float,
    var radius: Float = 20f,
    var speedX: Float = 0f,
    var speedY: Float = 0f
)
