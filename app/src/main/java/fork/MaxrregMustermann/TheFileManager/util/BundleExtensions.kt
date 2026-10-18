/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.util

import android.os.Bundle
import android.os.Parcelable
import fork.MaxrregMustermann.TheFileManager.app.appClassLoader

fun <T : Parcelable> Bundle.getParcelableSafe(key: String?): T? {
    classLoader = appClassLoader
    return getParcelable(key)
}
