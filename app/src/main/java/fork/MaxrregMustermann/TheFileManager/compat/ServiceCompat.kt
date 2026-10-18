/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.compat

import android.app.Service
import androidx.core.app.ServiceCompat

fun Service.stopForegroundCompat(flags: Int) {
    ServiceCompat.stopForeground(this, flags)
}
