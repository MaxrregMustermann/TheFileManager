/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.fileproperties.permission

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import fork.MaxrregMustermann.TheFileManager.provider.common.PosixFileModeBit
import fork.MaxrregMustermann.TheFileManager.util.toEnumSet
import fork.MaxrregMustermann.TheFileManager.util.valueCompat

class SetModeViewModel(mode: Set<PosixFileModeBit>) : ViewModel() {
    private val _modeLiveData: MutableLiveData<Set<PosixFileModeBit>> = MutableLiveData(mode)
    val modeLiveData: LiveData<Set<PosixFileModeBit>>
        get() = _modeLiveData
    val mode: Set<PosixFileModeBit>
        get() = _modeLiveData.valueCompat

    fun toggleModeBit(modeBit: PosixFileModeBit) {
        val mode = _modeLiveData.valueCompat.toEnumSet()
        if (modeBit in mode) {
            mode -= modeBit
        } else {
            mode += modeBit
        }
        _modeLiveData.value = mode
    }
}
