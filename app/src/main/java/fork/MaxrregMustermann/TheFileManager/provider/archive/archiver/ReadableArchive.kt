/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.provider.archive.archiver

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

    /**
     * How many entries [readEntry] has returned, which is the position the next one comes from.
     *
     * This is what makes an entry addressable. An archive is walked once to build its entry table,
     * and remembering where each entry came from means a later read can ask for entry [n] instead
     * of searching for it by name.
     */
    val position: Int

    /**
     * A stream over [entry], which a listing took from position [index] of this archive, or null
     * when this archive cannot serve it.
     *
     * Null rather than an exception, because "not from here" is not a failure and there are two of
     * them: an archive only moves forward, so an entry before [position] is not reachable, and an
     * archive that has run out of entries does not have it. Either way the caller's answer is the
     * same - open the archive again and try once more.
     *
     * The default walks forward from [position] to [index], which is what the app did before any
     * archive was held open. For a caller that reads its entries in order, which is what an
     * extraction job does, that walk is one step per read and the archive never has to be opened
     * again - which is the whole point, because an archive opened once and walked in order decodes
     * a solid 7Z block once rather than once per entry. A backend that can address an entry
     * directly overrides this and skips the walk.
     */
    @Throws(Exception::class)
    fun openEntry(index: Int, entry: ReadArchive.Entry, charset: Charset): InputStream? {
        if (index < position) {
            // Already walked past it. Only a reopened archive can go back.
            return null
        }
        var current: ReadArchive.Entry? = null
        var reachedEnd = false
        while (position <= index) {
            val next = readEntry(charset)
            if (next == null) {
                reachedEnd = true
                break
            }
            current = next
        }
        // The name is checked because a stale index reads a *different* entry's bytes under this
        // entry's name, and that is a wrong answer rather than an error. The archive can change on
        // disk under a cached file system; comparing costs one string comparison per read.
        if (reachedEnd || current == null || current.name != entry.name) {
            return null
        }
        return newDataInputStream()
    }
}