/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.file.attribute.FileTime
import java.io.Closeable
import java.io.InputStream

/**
 * What the app says about the entry it is about to write.
 *
 * The Rust side reads an entry by pulling its bytes from the app rather than the app pushing them
 * into the archive, which is what `libarchive` does and what [WriteArchive] adapts to. Both
 * implementations therefore take the entry's contents as a stream, and neither needs the entry's
 * bytes held in memory.
 */
class NewEntry(
    val name: String,
    val size: Long,
    val lastModifiedTime: FileTime?,
    val isDirectory: Boolean,
    val isSymbolicLink: Boolean = false,
    val symbolicLinkTarget: String? = null
)

/**
 * An archive that entries can be added to.
 *
 * Two implementations exist: [WriteArchive], which is `libarchive`, and [RustArchive.Writer]. The
 * app never needs to know which it has.
 */
interface WritableArchive : Closeable {
    /**
     * Adds one entry.
     *
     * [source] is the entry's contents, or null for an entry that has none, such as a directory or
     * a symbolic link. The caller keeps ownership of it and does not close it.
     */
    @Throws(Exception::class)
    fun writeEntry(entry: NewEntry, source: InputStream?)
}