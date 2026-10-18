/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.compat

import android.util.TypedValue
import androidx.core.util.TypedValueCompat

val TypedValue.complexUnitCompat: Int
    get() = TypedValueCompat.getUnitFromComplexDimension(data)
