/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.fileproperties.apk

import android.os.AsyncTask
import androidx.lifecycle.MutableLiveData
import fork.MaxrregMustermann.TheFileManager.app.packageManager
import fork.MaxrregMustermann.TheFileManager.util.Failure
import fork.MaxrregMustermann.TheFileManager.util.Loading
import fork.MaxrregMustermann.TheFileManager.util.Stateful
import fork.MaxrregMustermann.TheFileManager.util.Success
import fork.MaxrregMustermann.TheFileManager.util.getPermissionInfoOrNull
import fork.MaxrregMustermann.TheFileManager.util.valueCompat

class PermissionListLiveData(
    private val permissionNames: Array<String>
) : MutableLiveData<Stateful<List<PermissionItem>>>() {
    init {
        loadValue()
    }

    private fun loadValue() {
        value = Loading(value?.value)
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value = try {
                val permissions = permissionNames.map { name ->
                    val packageManager = packageManager
                    val permissionInfo = packageManager.getPermissionInfoOrNull(name, 0)
                    val label = permissionInfo?.loadLabel(packageManager)?.toString()
                        .takeIf { it != name }
                    val description = permissionInfo?.loadDescription(packageManager)?.toString()
                    PermissionItem(name, permissionInfo, label, description)
                }
                Success(permissions)
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }
}
