package com.willianswill.novatv.core.ui

import android.view.View

fun View.enableCinemaFocusZoom(scale: Float = 1.06f) {
    setOnFocusChangeListener { v, hasFocus ->
        val target = if (hasFocus) scale else 1.0f
        v.animate()
            .scaleX(target)
            .scaleY(target)
            .setDuration(140L)
            .start()
        if (hasFocus) {
            v.elevation = 12f
        } else {
            v.elevation = 0f
        }
    }
}
