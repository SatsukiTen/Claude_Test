package com.example.blockbreaker

data class Block(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val color: Int,
    var isAlive: Boolean = true
)
