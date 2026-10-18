/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.fileproperties

import android.os.AsyncTask
import java8.nio.file.Path
import fork.MaxrregMustermann.TheFileManager.file.FileItem
import fork.MaxrregMustermann.TheFileManager.file.loadFileItem
import fork.MaxrregMustermann.TheFileManager.util.Failure
import fork.MaxrregMustermann.TheFileManager.util.Loading
import fork.MaxrregMustermann.TheFileManager.util.Stateful
import fork.MaxrregMustermann.TheFileManager.util.Success
import fork.MaxrregMustermann.TheFileManager.util.valueCompat

class FileLiveData private constructor(
    path: Path,
    file: FileItem?
) : PathObserverLiveData<Stateful<FileItem>>(path) {
    constructor(path: Path) : this(path, null)

    constructor(file: FileItem) : this(file.path, file)

    init {
        if (file != null) {
            value = Success(file)
        } else {
            loadValue()
        }
        observe()
    }

    override fun loadValue() {
        value = Loading(value?.value)
        AsyncTask.THREAD_POOL_EXECUTOR.execute {
            val value = try {
                val file = path.loadFileItem()
                Success(file)
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }
}
