/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.bridge

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java8.nio.channels.SeekableByteChannel
import java8.nio.file.OpenOption
import java8.nio.file.Path
import java8.nio.file.StandardOpenOption
import me.zhanghai.android.files.provider.archive.ArchiveFileSystem
import me.zhanghai.android.files.provider.archive.ArchiveFileSystemProvider
import me.zhanghai.android.files.provider.archive.archiver.ArchiveFormat
import me.zhanghai.android.files.provider.archive.archiver.NewEntry
import me.zhanghai.android.files.provider.archive.archiver.RustArchive
import me.zhanghai.android.files.provider.linux.LinuxFileSystemProvider
import me.zhanghai.android.files.provider.linux.LocalLinuxFileSystemProvider

/**
 * Proves the JNI bridge on a real device, which is the only place it can be proved.
 *
 * A host-side `cargo test` cannot do this job. Everything that goes wrong at a JNI boundary is
 * invisible from the host: a symbol that does not match the Kotlin declaration, a signature that
 * JNI resolves by string, a local-reference table that overflows, and above all a panic, which on
 * Android aborts the process instead of raising a Java exception. All of those need a JVM, a real
 * `SeekableByteChannel`, and the real `.so` loaded by `System.loadLibrary`.
 *
 * Why this is a bare [Instrumentation] rather than a JUnit test: `androidx.test:runner` is not in
 * this machine's offline Gradle cache, so `AndroidJUnitRunner` cannot be resolved without network
 * access. [Instrumentation] is in the platform, so this needs no dependency at all and runs on the
 * API 24 emulator with:
 *
 * ```
 * adb shell am instrument -w \
 *     me.zhanghai.android.files/me.zhanghai.android.files.bridge.BridgeInstrumentation
 * ```
 *
 * The component name is the app's own package because this lives in the debug source set rather than
 * androidTest. Everything it prints goes to logcat under [REPORT_TAG].
 */
class BridgeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        // Started from `onCreate` rather than left to the framework, so the whole run is one call.
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val outcome = try {
            proveTheBridge()
            "PASS"
        } catch (throwable: Throwable) {
            // Deliberately catching Throwable and not just Exception: an `Error` escaping the native
            // side is exactly the failure this exists to catch, and it has to be reported rather than
            // taking the instrumentation down with it.
            Log.e(REPORT_TAG, "FAILED", throwable)
            "FAIL: $throwable"
        }
        result.putString(REPORT_TAG, outcome)
        Log.i(REPORT_TAG, outcome)
        finish(if (outcome == "PASS") Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    /**
     * Writes a ZIP through the native writer, reads it back through the native reader, and checks
     * that the bytes which came back are the bytes which went in.
     *
     * Going out and back in one run rather than reading a fixture from the host is deliberate: it
     * exercises eight of the nine exports rather than four, so a signature mistake in the write half
     * shows up here too. The foreign archive afterwards covers the case where the writer and the
     * reader are wrong in the same way and cancel out.
     */
    private fun proveTheBridge() {
        check(RustArchive.ensureAvailable()) {
            "librust_archive.so is not in the APK, so nothing below is a test of the bridge"
        }
        Log.i(REPORT_TAG, "librust_archive.so loaded")

        val archive = cachePath("bridge-proof.zip")
        deleteIfExists(archive)
        try {
            val contents = Payload.ENTRY_CONTENTS.toByteArray(Charsets.UTF_8)
            writeZip(archive, contents)
            Log.i(
                REPORT_TAG,
                "wrote a ${size(archive)}-byte ZIP through nativeCreate/nativeWriteEntry/nativeFinish"
            )

            val entries = ArrayList<String>()
            RustArchive.Reader(openChannel(archive), emptyList()).use { reader ->
                while (true) {
                    val entry = reader.readEntry() ?: break
                    entries.add(entry.name)
                    if (entry.isDirectory || entry.isSymlink) {
                        // A directory or a symlink has no contents, so there is nothing to stream.
                        Log.i(REPORT_TAG, "entry ${entry.name} is not a regular file, nothing to read")
                        continue
                    }
                    val read = readEntryFully(reader.newDataInputStream())
                    // The bytes themselves, in the log. This is the acceptance criterion: the log
                    // has to show the bytes arriving, not merely that a length was reported.
                    Log.i(REPORT_TAG, "BYTES ${entry.name} = ${Payload.describe(read)}")
                    check(read.size == entry.size.toInt()) {
                        "${entry.name} is ${entry.size} bytes in the listing but ${read.size} came back"
                    }
                    check(String(read, Charsets.UTF_8) == Payload.ENTRY_CONTENTS) {
                        "${entry.name} did not survive the round trip through the JNI boundary"
                    }
                }
            }
            Log.i(REPORT_TAG, "entries listed: $entries")
            check(entries.contains(Payload.ENTRY_NAME)) {
                "the archive was listed as $entries, which does not contain ${Payload.ENTRY_NAME}"
            }

            // Opening the same archive a second time and reading it again. Each Reader opens the
            // archive once and addresses entries by index inside it, which is the property the whole
            // project is for: a solid 7Z block costs one decode rather than one per entry.
            val again = readZip(archive)
            check(again == contents.toList()) { "a second read of the same archive returned other bytes" }
            Log.i(REPORT_TAG, "second read of the same archive returned the same bytes")

            readForeignZipIfPresent()
        } finally {
            deleteIfExists(archive)
        }

        proveTheArchiveStaysOpen(ENTRY_COUNT)
    }

    /**
     * The property the whole project is for, measured through the app's own file system rather than
     * through the bridge directly: reading every entry of an archive opens the archive once.
     *
     * Before, `ArchiveFileSystem.newInputStream` opened a fresh archive per file and walked it to the
     * entry by name, so this loop opened the archive once per entry. That is invisible to a user
     * until the archive is solid: for 400 entries of a solid 7Z, walking from the start re-decodes
     * the whole block every time, 15.5 s against 204 ms for the same entries stored non-solid. The
     * bytes that come back are identical either way, so the only way to see this is to count the
     * opens, which is what [RustArchive.openCount] is for.
     *
     * The out-of-order pass is the control, and it is what makes the in-order number mean something:
     * an archive only moves forward, so reading the same entries backwards cannot reuse it and has
     * to reopen. If the two numbers came out the same, the in-order one would be an accident rather
     * than a property.
     */
    private fun proveTheArchiveStaysOpen(entryCount: Int) {
        val archive = cachePath("held-open-proof.zip")
        deleteIfExists(archive)
        try {
            writeZipWithEntries(archive, entryCount)
            val fileSystem = ArchiveFileSystemProvider.getOrNewFileSystem(localPath(archive))
            try {
                // Listing the archive opens it once, to walk it. That is not what is being measured,
                // so the count starts after it.
                val entries = fileSystem.getDirectoryChildren(fileSystem.defaultDirectory)
                check(entries.size == entryCount) {
                    "the archive listed ${entries.size} entries, not $entryCount"
                }
                val afterListing = RustArchive.openCount

                val inOrder = readEveryEntry(fileSystem, entries)
                val inOrderOpens = RustArchive.openCount - afterListing
                val inOrderMillis = inOrder.millis
                Log.i(
                    REPORT_TAG,
                    "read $entryCount entries in order through ArchiveFileSystem: " +
                            "${inOrder.bytes} bytes in $inOrderMillis ms, " +
                            "$inOrderOpens archive opens"
                )
                check(inOrderOpens == 1) {
                    "reading $entryCount entries in order opened the archive $inOrderOpens times, " +
                            "not once: the archive is not being held open across reads"
                }

                val beforeOutOfOrder = RustArchive.openCount
                val outOfOrder = readEveryEntry(fileSystem, entries.reversed())
                val outOfOrderOpens = RustArchive.openCount - beforeOutOfOrder
                Log.i(
                    REPORT_TAG,
                    "read the same $entryCount entries in reverse: ${outOfOrder.bytes} bytes in " +
                            "${outOfOrder.millis} ms, $outOfOrderOpens archive opens"
                )
                check(outOfOrder.bytes == inOrder.bytes) {
                    "reading the same entries backwards returned ${outOfOrder.bytes} bytes, " +
                            "not ${inOrder.bytes}"
                }
                check(outOfOrderOpens > inOrderOpens) {
                    "reading out of order cost $outOfOrderOpens opens and reading in order cost " +
                            "${inOrderOpens}, so the cursor is not what is holding the archive open"
                }
            } finally {
                fileSystem.close()
            }
        } finally {
            deleteIfExists(archive)
        }
    }

    private class ReadResult(val bytes: Long, val millis: Long)

    /**
     * Reads every entry of [entries] in the order given, through the file system the app uses, and
     * checks each entry against the contents it was written with.
     *
     * Checking the contents matters: a held archive that served entry *n*'s bytes for entry *n-1*
     * would return the right number of bytes and the wrong ones, and every length in this file would
     * still agree.
     */
    private fun readEveryEntry(fileSystem: ArchiveFileSystem, entries: List<Path>): ReadResult {
        var bytes = 0L
        val start = SystemClock.elapsedRealtime()
        for (path in entries) {
            val read = fileSystem.newInputStream(path).use { stream ->
                readEntryFully(stream)
            }
            // The expectation comes from the entry's own name rather than from its position in this
            // list, so that reading the same entries in another order checks them just as strictly.
            val expected = Payload.entryContents(heldOpenEntryNumber(path))
            check(String(read, Charsets.UTF_8) == expected) {
                "$path came back as ${Payload.describe(read)}, which is not what was written to it"
            }
            bytes += read.size
        }
        return ReadResult(bytes, SystemClock.elapsedRealtime() - start)
    }

    /** Writes [count] entries, in order, named by [heldOpenEntryName]. */
    private fun writeZipWithEntries(path: String, count: Int) {
        openChannel(
            path, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        ).use { channel ->
            RustArchive.Writer(channel, ArchiveFormat.ZIP).use { writer ->
                for (position in 0 until count) {
                    val contents = Payload.entryContents(position).toByteArray(Charsets.UTF_8)
                    writer.writeEntry(
                        NewEntry(
                            heldOpenEntryName(position), contents.size.toLong(), null, false
                        ),
                        ByteArrayInputStream(contents)
                    )
                }
            }
        }
    }

    /**
     * Writes [contents] as the single entry of a ZIP, through the native writer.
     *
     * This is the only exercise of `nativeWriteEntry` in the project, and it is here rather than in a
     * host test because the argument is an `InputStream` the native side pulls from through JNI,
     * which is a boundary a host test cannot cross.
     */
    private fun writeZip(path: String, contents: ByteArray) {
        // CREATE and TRUNCATE because the channel is opened on a path that does not exist yet, and
        // READ as well as WRITE because the native side seeks back over what it has written, which
        // the 7Z signature header needs.
        openChannel(
            path, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        ).use { channel ->
            RustArchive.Writer(channel, ArchiveFormat.ZIP).use { writer ->
                writer.writeEntry(
                    NewEntry(Payload.ENTRY_NAME, contents.size.toLong(), null, false),
                    ByteArrayInputStream(contents)
                )
            }
        }
    }

    /** Reads the first file entry of the archive at [path] and returns its bytes. */
    private fun readZip(path: String): List<Byte> {
        RustArchive.Reader(openChannel(path), emptyList()).use { reader ->
            while (true) {
                val entry = reader.readEntry() ?: break
                if (entry.isDirectory || entry.isSymlink) {
                    continue
                }
                return readEntryFully(reader.newDataInputStream()).toList()
            }
        }
        throw AssertionError("$path has no file entry in it")
    }

    /**
     * Reads a stream the way `InputStream.read(byte[], int, int)` does, in chunks.
     *
     * The chunking is the point: `nativeReadData` moves at most `CHUNK_SIZE` bytes per call and
     * returns null at the end of the entry, so a loop that ignores either shows up as a short read
     * rather than as a wrong number in a log.
     */
    private fun readEntryFully(stream: InputStream): ByteArray {
        stream.use {
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = stream.read(chunk, 0, chunk.size)
                if (read == -1) {
                    break
                }
                output.write(chunk, 0, read)
            }
            return output.toByteArray()
        }
    }

    /**
     * Reads a ZIP that was made by something other than this library, when one has been put there.
     *
     * A round trip proves the bridge agrees with itself. It does not prove the bridge agrees with a
     * real ZIP, because a writer and a reader that are wrong in the same way cancel out. `zip` on
     * the host makes the archive; tools/bridge-proof.sh pushes it.
     */
    private fun readForeignZipIfPresent() {
        if (!exists(FOREIGN_ZIP)) {
            Log.i(REPORT_TAG, "no $FOREIGN_ZIP to read, skipping the foreign-archive check")
            return
        }
        val entries = ArrayList<String>()
        var fileEntries = 0
        RustArchive.Reader(openChannel(FOREIGN_ZIP), emptyList()).use { reader ->
            while (true) {
                val entry = reader.readEntry() ?: break
                entries.add(entry.name)
                if (entry.isDirectory || entry.isSymlink) {
                    continue
                }
                val read = readEntryFully(reader.newDataInputStream())
                Log.i(REPORT_TAG, "BYTES ${entry.name} (foreign) = ${Payload.describe(read)}")
                check(read.isNotEmpty()) { "${entry.name} in the foreign archive came back empty" }
                fileEntries++
            }
        }
        Log.i(REPORT_TAG, "foreign archive listed: $entries")
        check(fileEntries > 0) { "$FOREIGN_ZIP listed no file entries" }
    }

    // ---------------------------------------------------------------------------------------------
    // Local files, deliberately bypassing the app's provider stack.
    //
    // `FileSystemProviders.install()` makes `LinuxFileSystemProvider` the default, and that one is
    // root-capable: with the default `AUTOMATIC` root strategy it probes for `su` before opening
    // anything, and an emulator without root fails every open with "Root isn't available" long
    // before an archive is touched. Going straight to `LocalLinuxFileSystemProvider` opens the same
    // local filesystem with none of that, which keeps this test about the JNI bridge.
    // ---------------------------------------------------------------------------------------------

    private fun openChannel(
        path: String, vararg options: StandardOpenOption
    ): SeekableByteChannel {
        val all = (listOf(StandardOpenOption.READ) + options).toSet<OpenOption>()
        return LOCAL_PROVIDER.newByteChannel(localPath(path), all)
    }

    // Existence and size go through java.io rather than the provider. The provider's
    // readAttributes answers posix attributes through RootablePosixFileAttributeView, which consults
    // root first, so it fails on an emulator without root even though the local read would succeed.
    private fun exists(path: String): Boolean = java.io.File(path).exists()

    private fun size(path: String): Long = java.io.File(path).length()

    private fun deleteIfExists(path: String) {
        runCatching { LOCAL_PROVIDER.delete(localPath(path)) }
    }

    private fun localPath(path: String): Path = LOCAL_PROVIDER.getPath(URI("file://$path"))

    private fun cachePath(name: String): String =
        "${targetContext.cacheDir.absolutePath}/$name"

    /** The fixed strings the round trip checks, so that a failure says what it expected. */
    private object Payload {
        const val ENTRY_NAME = "hello-from-jni.txt"
        const val ENTRY_CONTENTS =
            "The bytes of this entry crossed the JNI boundary and came back intact.\n"

        /** The contents of entry [number], which is also what its name says. */
        fun entryContents(number: Int): String = "contents of entry $number\n"

        /** A readable rendering for the log, plus the first bytes as hex. */
        fun describe(bytes: ByteArray): String {
            val hex = bytes.take(16).joinToString(" ") { String.format("%02x", it) }
            val text = String(bytes, Charsets.UTF_8).replace("\n", "\\n").take(120)
            return "${bytes.size} bytes, hex[0..16)=$hex, text=$text"
        }
    }

    /**
     * The name of entry [number] in the archive the held-open proof writes.
     *
     * Zero-padded so that the entries sort in the order they were written, which is what makes
     * reading them "in order" mean something rather than being an accident of the filesystem.
     */
    private fun heldOpenEntryName(number: Int): String =
        String.format("entry-%05d.txt", number)

    /** The [number] in [heldOpenEntryName]'s name, or a failure that says which path confused it. */
    private fun heldOpenEntryNumber(path: Path): Int {
        val name = path.toString().substringAfterLast('/')
        return name.removePrefix("entry-").removeSuffix(".txt").toIntOrNull()
            ?: throw AssertionError("$path is not an entry name this test wrote")
    }

    companion object {
        const val REPORT_TAG = "BridgeProof"

        /**
         * How many entries the held-open proof writes.
         *
         * Enough that the difference is unmistakable in the log and few enough that the run stays
         * inside an instrumentation timeout on an API 24 emulator. The property being measured does
         * not depend on the number - one open for any number of reads - so this is a readability
         * choice rather than a benchmark size.
         */
        const val ENTRY_COUNT = 64

        /** Must match CHUNK_SIZE in RustArchive.kt and in the Rust crate. */
        const val CHUNK_SIZE = 64 * 1024

        /** A ZIP made by the host's `zip`, pushed here by tools/bridge-proof.sh. */
        const val FOREIGN_ZIP =
            "/storage/emulated/0/Android/data/me.zhanghai.android.files/files/foreign.zip"

        private val LOCAL_PROVIDER = LocalLinuxFileSystemProvider(LinuxFileSystemProvider)
    }
}