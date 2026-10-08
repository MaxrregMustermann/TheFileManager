/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.os.Bundle
import android.os.Parcelable
import me.zhanghai.android.files.app.appClassLoader

fun <T : Parcelable> Bundle.getParcelableSafe(key: String?): T? {
    classLoader = appClassLoader
    return getParcelable(key)
}
