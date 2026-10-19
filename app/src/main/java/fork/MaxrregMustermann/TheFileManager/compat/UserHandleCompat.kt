/*
 * Copyright (c) 2026 MaxrregMustermann <MaxrregMustermann@airmail.cc>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.compat

object UserHandleCompat {
    // @see UserHandle.PER_USER_RANGE
    private const val PER_USER_RANGE = 100000

    fun getAppId(uid: Int): Int = uid % PER_USER_RANGE
}
