/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.filelist

import android.os.AsyncTask
import java8.nio.file.Path
import fork.MaxrregMustermann.TheFileManager.file.FileItem
import fork.MaxrregMustermann.TheFileManager.file.loadFileItem
import fork.MaxrregMustermann.TheFileManager.provider.common.search
import fork.MaxrregMustermann.TheFileManager.search.SearchIndex
import fork.MaxrregMustermann.TheFileManager.search.toIndexUri
import fork.MaxrregMustermann.TheFileManager.util.CloseableLiveData
import fork.MaxrregMustermann.TheFileManager.util.Failure
import fork.MaxrregMustermann.TheFileManager.util.Loading
import fork.MaxrregMustermann.TheFileManager.util.Stateful
import fork.MaxrregMustermann.TheFileManager.util.Success
import fork.MaxrregMustermann.TheFileManager.util.valueCompat
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * Searches below [path] for files whose name or path contains [query].
 *
 * The index answers first, so results appear as soon as it is queried, and the file system is only
 * walked for whatever the index does not know about yet. Once a directory has been indexed all the
 * way down, the walk is skipped entirely, which is what makes search fast.
 */
class SearchFileListLiveData(
    private val path: Path,
    private val query: String
) : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null

    init {
        loadValue()
    }

    fun loadValue() {
        future?.cancel(true)
        value = Loading(emptyList())
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val fileList = mutableListOf<FileItem>()
            val seenUris = mutableSetOf<String>()
            try {
                for (indexedPath in SearchIndex.search(path, query)) {
                    addFileItem(indexedPath, fileList, seenUris)
                }
                postValue(Loading(fileList.toList()))
                if (!SearchIndex.isCovered(path)) {
                    path.search(query, INTERVAL_MILLIS) { paths: List<Path> ->
                        for (path in paths) {
                            addFileItem(path, fileList, seenUris)
                        }
                        postValue(Loading(fileList.toList()))
                    }
                }
                postValue(Success(fileList))
            } catch (e: Exception) {
                // TODO: Retrieval of previous value is racy.
                postValue(Failure(valueCompat.value, e))
            }
        }
    }

    private fun addFileItem(path: Path, fileList: MutableList<FileItem>, seenUris: MutableSet<String>) {
        // A path can be found by both the index and the walk, and must only be shown once.
        if (!seenUris.add(path.toIndexUri())) {
            return
        }
        val fileItem = try {
            path.loadFileItem()
        } catch (e: IOException) {
            e.printStackTrace()
            // TODO: Support file without information.
            return
        }
        fileList.add(fileItem)
    }

    override fun close() {
        future?.cancel(true)
    }

    companion object {
        private const val INTERVAL_MILLIS = 500L
    }
}