/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.util

fun AutoCloseable.closeSafe() {
    try {
        close()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
