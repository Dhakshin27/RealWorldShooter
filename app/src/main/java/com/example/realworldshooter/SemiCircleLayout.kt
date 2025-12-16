package com.example.realworldshooter

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.cos
import kotlin.math.sin

class SemiCircleLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    private val padding = 20 // extra space to avoid clipping

    init {
        // Allow children to be drawn outside the bounds
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val radius = width / 2f
        val height = (radius + padding + radius / 2).toInt() // extra space for semicircle

        setMeasuredDimension(width, height)

        // Measure children
        val childSize = width / 5
        val childSpec = MeasureSpec.makeMeasureSpec(childSize, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) {
            getChildAt(i).measure(childSpec, childSpec)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val radius = width / 2f
        val centerX = width / 2f
        val centerY = radius + padding

        val angleStep = 180f / (childCount - 1)

        for (i in 0 until childCount) {
            val angle = Math.toRadians((180f - i * angleStep).toDouble())
            val x = (centerX + radius * cos(angle)).toFloat()
            val y = (centerY - radius * sin(angle)).toFloat()

            val child = getChildAt(i)
            val half = child.measuredWidth / 2

            child.layout(
                (x - half).toInt(),
                (y - half).toInt(),
                (x + half).toInt(),
                (y + half).toInt()
            )
        }
    }
}
