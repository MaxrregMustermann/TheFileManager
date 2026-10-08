/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.os.AsyncTask
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.loadFileItem
import me.zhanghai.android.files.search.SearchIndex
import me.zhanghai.android.files.util.CloseableLiveData
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.valueCompat
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 * The most recently modified files the search index knows about.
 *
 * The list grows as the app indexes what it browses, so it starts out short and gets more useful
 * the longer the app is used.
 */
class RecentFileListLiveData : CloseableLiveData<Stateful<List<FileItem>>>() {
    private var future: Future<Unit>? = null

    init {
        loadValue()
    }

    fun loadValue() {
        future?.cancel(true)
        value = Loading(value?.value)
        future = (AsyncTask.THREAD_POOL_EXECUTOR as ExecutorService).submit<Unit> {
            val value = try {
                Success(loadFileItems())
            } catch (e: Exception) {
                Failure(valueCompat.value, e)
            }
            postValue(value)
        }
    }

    private fun loadFileItems(): List<FileItem> {
        val fileItems = mutableListOf<FileItem>()
        for (path in SearchIndex.searchRecent(LIMIT)) {
            val fileItem = try {
                path.loadFileItem()
            } catch (e: IOException) {
                // A file that has been deleted or unmounted since it was indexed.
                e.printStackTrace()
                continue
            }
            fileItems.add(fileItem)
        }
        return fileItems
    }

    override fun close() {
        future?.cancel(true)
    }

    companion object {
        /**
         * Enough to be useful without turning the list into an index dump. The list is ordered by
         * modification time, so anything past this is unlikely to ever be looked at.
         */
        const val LIMIT = 200
    }
}