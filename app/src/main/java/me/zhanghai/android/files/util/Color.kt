/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import androidx.annotation.ColorInt
import androidx.annotation.FloatRange
import kotlin.math.roundToInt

@JvmInline
value class Color(@ColorInt val value: Int)

fun Int.asColor(): Color = Color(this)

fun Color.withModulatedAlpha(@FloatRange(from = 0.0, to = 1.0) alphaModulation: Float): Color {
    val alpha = ((value ushr 24) and 0xFF) * alphaModulation
    return (((alpha.roundToInt()) shl 24) or (value and 0x00FFFFFF)).asColor()
}
