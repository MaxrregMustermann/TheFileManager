/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.search

import java.util.Collections

/**
 * The roots whose whole subtree is known to be in the index.
 *
 * A search below one of these can be answered from the index alone, which is the entire point of
 * having an index. The set is deliberately in memory only: it is a claim about the current process,
 * and after a restart nothing is claimed until the scan runs again, so a search is never wrong, only
 * slower.
 */
class IndexCoverage {
    private val roots = Collections.synchronizedSet(mutableSetOf<String>())

    fun add(uri: String) {
        roots.add(uri.trimEnd('/'))
    }

    /** Whether everything at or below [uri] has been indexed. */
    fun contains(uri: String): Boolean {
        val normalized = uri.trimEnd('/')
        synchronized(roots) {
            return roots.any { normalized == it || normalized.startsWith("$it/") }
        }
    }

    /** Forgets [uri] and everything below it, which is what losing the index entries implies. */
    fun remove(uri: String) {
        val normalized = uri.trimEnd('/')
        synchronized(roots) {
            roots.removeIf { it == normalized || it.startsWith("$normalized/") }
        }
    }

    fun clear() {
        roots.clear()
    }
}