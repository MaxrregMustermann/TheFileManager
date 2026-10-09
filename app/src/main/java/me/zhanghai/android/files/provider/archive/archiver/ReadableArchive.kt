/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java.io.Closeable
import java.io.InputStream
import java.nio.charset.Charset

/**
 * An archive that can be read entry by entry.
 *
 * Two implementations exist: [ReadArchive], which is `libarchive`, and [RustReadArchive], which is
 * the Rust crates. [ArchiveReader] chooses between them at open time, so nothing above it has to
 * know which is in use.
 */
interface ReadableArchive : Closeable {
    /**
     * Moves to the next entry and returns it, or null at the end of the archive.
     *
     * [charset] is the fallback for entry names that are not valid UTF-8. The Rust implementation
     * always treats names as UTF-8, since every crate it uses decodes them that way.
     */
    @Throws(Exception::class)
    fun readEntry(charset: Charset): ReadArchive.Entry?

    /** A stream over the contents of the entry that was last returned. */
    @Throws(Exception::class)
    fun newDataInputStream(): InputStream
}