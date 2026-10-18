package fork.MaxrregMustermann.TheFileManager.util

import android.os.storage.StorageVolume
import fork.MaxrregMustermann.TheFileManager.compat.directoryCompat

val StorageVolume.isMounted: Boolean
    get() = directoryCompat != null
