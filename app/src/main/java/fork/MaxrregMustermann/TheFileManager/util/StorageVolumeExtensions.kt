/*
 * Copyright (c) 2026 MaxrregMustermann <MaxrregMustermann@airmail.cc>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.util

import android.os.storage.StorageVolume
import fork.MaxrregMustermann.TheFileManager.compat.directoryCompat

val StorageVolume.isMounted: Boolean
    get() = directoryCompat != null
