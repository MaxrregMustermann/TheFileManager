/*
 * Copyright (c) 2026 MaxrregMustermann <MaxrregMustermann@airmail.cc>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.bridge

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
import fork.MaxrregMustermann.TheFileManager.R
import fork.MaxrregMustermann.TheFileManager.provider.archive.ArchiveFileSystem
import fork.MaxrregMustermann.TheFileManager.provider.archive.ArchiveFileSystemProvider
import fork.MaxrregMustermann.TheFileManager.provider.archive.archiver.ArchiveFormat
import fork.MaxrregMustermann.TheFileManager.provider.archive.archiver.NewEntry
import fork.MaxrregMustermann.TheFileManager.provider.archive.archiver.ReadArchive
import fork.MaxrregMustermann.TheFileManager.provider.archive.archiver.RustArchive
import fork.MaxrregMustermann.TheFileManager.provider.linux.LinuxFileSystemProvider
import fork.MaxrregMustermann.TheFileManager.provider.linux.LocalLinuxFileSystemProvider

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
 *     fork.MaxrregMustermann.TheFileManager/fork.MaxrregMustermann.TheFileManager.bridge.BridgeInstrumentation
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
        // Before anything reaches ArchiveFileSystem. See disableRootProbing for why this is a
        // correctness fix rather than a workaround.
        disableRootProbing()
        // Every stage runs, and every stage that throws is recorded rather than allowed to end the
        // run. The reason is that the first version of this file stopped at the first failure, and
        // a single bad format then hid every other result: the compressed-TAR check failed, so the
        // run never reached proveWhichLibrary, and the log said nothing at all about which library
        // served the file. A report that stops early is a report about the first thing that broke.
        //
        // The verdict is still all-or-nothing - any recorded failure makes the whole run FAIL - so
        // continuing is not softening anything. It is the difference between "one thing is wrong"
        // and "here is the one thing that is wrong, and here are the five that are right".
        val failures = ArrayList<String>()
        for (stage in STAGES) {
            try {
                stage.second(this)
            } catch (throwable: Throwable) {
                // Deliberately catching Throwable and not just Exception: an `Error` escaping the
                // native side is exactly the failure this exists to catch, and it has to be reported
                // rather than taking the instrumentation down with it.
                Log.e(REPORT_TAG, "FAILED in ${stage.first}", throwable)
                failures += "${stage.first}: $throwable"
            }
        }
        val outcome = if (failures.isEmpty()) {
            "PASS"
        } else {
            "FAIL: ${failures.size} of ${STAGES.size} stages failed: ${failures.joinToString(" | ")}"
        }
        Log.i(REPORT_TAG, "stages: ${STAGES.joinToString(", ") { it.first }}")
        result.putString(REPORT_TAG, outcome)
        Log.i(REPORT_TAG, outcome)
        finish(if (failures.isEmpty()) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
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
    /**
     * Stops the archive path from probing for root, so `ArchiveFileSystem` is reachable at all.
     *
     * This was a real failure, not a hypothetical one. On an API 24 emulator the proof died at the
     * first `ArchiveFileSystem` call with
     *
     *     RemoteFileSystemException: Root isn't available
     *         at ArchiveFileSystem.getDirectoryChildren(ArchiveFileSystem.kt:320)
     *
     * while everything driven straight through `RustArchive.Reader` in the same run passed, with
     * real bytes. `RootablePath` takes its strategy from `Settings.ROOT_STRATEGY` unless the app
     * is running as root, and `ArchiveReader.openArchive` opens the archive through the
     * `file.newByteChannel()` extension, which resolves to the root-capable `LinuxFileSystemProvider`
     * rather than the `LocalLinuxFileSystemProvider` this class builds on purpose.
     *
     * NEVER is not a workaround: it is what a user without root already gets, since AUTOMATIC falls
     * back to the local object for any path that does not require root. Proving with ALWAYS would
     * prove a configuration no ordinary user is in.
     *
     * `EnumSettingLiveData` stores an enum's ordinal rather than its name, so NEVER is `"0"`. The
     * stored value is read back and checked rather than assumed, so a future change to how the
     * preference is stored reports itself instead of failing later somewhere unrelated.
     */
    private fun disableRootProbing() {
        val key = targetContext.getString(R.string.pref_key_root_strategy)
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(targetContext)
            .edit().putString(key, NEVER_ORDINAL).commit()
        val stored = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(targetContext).getString(key, null)
        Log.i(REPORT_TAG, "root strategy preference stored as '$stored' ($NEVER_ORDINAL is NEVER)")
        check(stored == NEVER_ORDINAL) {
            "the root strategy preference did not take, so the archive path may probe for root " +
                "again and fail with 'Root isn't available'"
        }
    }

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
            RustArchive.Reader(openChannel(archive), emptyList(), archive.substringAfterLast('/'))
                .use { reader ->
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
    }

    /**
     * Opens a TAR, a 7Z, a foreign ZIP and four compressed TARs, lists each one and extracts from
     * it.
     *
     * Every one of these is an archive `tar`, `gzip`, `bzip2`, `xz`, `zip` or `7z` made on the host,
     * and every one of them holds the same `license.txt` that tools/make-bridge-fixtures.sh pushed
     * beside them. The bytes are compared against that file, not against a constant written into
     * this one, so the check is against ground truth and this file cannot be the only source of
     * truth about what the bytes should be.
     *
     * A ZIP written by this library is *not* in the list, because [proveTheBridge] already
     * round-trips one: it was written by this library and read by this library, which proves the
     * bridge agrees with itself. `foreign.zip`, made by the host's own `zip(1)`, is in the list
     * instead, and is the one that means anything.
     *
     * Each format is checked on its own and every failure is kept, because the first version of
     * this loop threw on the first bad format and the formats after it were never mentioned. "tar.gz
     * is wrong, and here is what tar, 7Z and bzip2 did" is worth more than "tar.gz is wrong" and
     * nothing else.
     */
    private fun proveTheFormats() {
        val problems = ArrayList<String>()
        var checked = 0
        var skipped = 0
        for (format in FOREIGN_FORMATS) {
            val path = "$FIXTURES_DIR/${format.fixture}"
            if (!exists(path) && !format.required) {
                skipped++
                Log.i(
                    REPORT_TAG,
                    "SKIPPED ${format.fixture}: not pushed, so the host had no ${format.fixture} " +
                        "maker; the others ran and are unaffected"
                )
                continue
            }
            try {
                check(exists(path)) {
                    "${format.fixture} is not on the device: tools/bridge-proof.sh pushes " +
                        "tools/make-bridge-fixtures.sh's output, and without it this proves nothing"
                }
                val plaintext = java.io.File("$FIXTURES_DIR/$PLAINTEXT_FIXTURE").readBytes()
                RustArchive.Reader(openChannel(path), emptyList(), format.fixture).use { reader ->
                    val entry = reader.readEntry() ?: throw AssertionError(
                        "${format.fixture} listed no entries"
                    )
                    check(entry.name == format.entryName) {
                        "${format.fixture} listed ${entry.name} first, not ${format.entryName}"
                    }
                    check(reader.readEntry() == null) {
                        "${format.fixture} listed more than one entry; these fixtures hold one file"
                    }
                    val read = readEntryFully(reader.newDataInputStream())
                    Log.i(REPORT_TAG, "BYTES ${format.fixture}/${entry.name} = ${Payload.describe(read)}")
                    check(read.size == entry.size.toInt()) {
                        "${entry.name} in ${format.fixture} is listed as ${entry.size} bytes but " +
                            "${read.size} came back"
                    }
                    check(read.contentEquals(plaintext)) {
                        "${entry.name} in ${format.fixture} came back as " +
                            "${Payload.describe(read)}, which is not the plaintext it was pushed with"
                    }
                }
                checked++
                Log.i(
                    REPORT_TAG,
                    "OK ${format.fixture} opened, listed and extracted through the bridge: " +
                            "${format.entryName}, ${plaintext.size} B matching the plaintext"
                )
            } catch (throwable: Throwable) {
                problems += "${format.fixture}: $throwable"
                Log.e(REPORT_TAG, "NOT OK ${format.fixture}", throwable)
            }
        }
        Log.i(
            REPORT_TAG,
            "formats: $checked opened, listed and matched; $skipped skipped; " +
                "${problems.size} failed, of ${FOREIGN_FORMATS.size} fixtures"
        )
        check(problems.isEmpty()) {
            "${problems.size} of ${FOREIGN_FORMATS.size} foreign archives did not open correctly: " +
                problems.joinToString(" | ")
        }
    }

    /**
     * Proves which of the two archive libraries on the device actually opened the archive.
     *
     * This is the claim the whole rewrite rests on, and on this device it is not free: the app
     * ships **both** implementations. `librust_archive.so` is the one this project builds, and
     * `libarchive-jni.so` comes in inside the `me.zhanghai.android.libarchive:library` AAR, which
     * is still a declared dependency. `ArchiveReader.readArchive` chooses between them:
     *
     * ```kotlin
     * if (RustArchive.ensureAvailable()) {
     *     try {
     *         return RustReadArchive(channel, passwords, name)
     *     } catch (e: IOException) {
     *         e.printStackTrace()
     *     }
     * }
     * return ReadArchive(channel, passwords)   // libarchive-jni.so
     * ```
     *
     * That fallback is silent. Nothing in the UI, nothing in logcat and nothing in
     * [RustArchive.openCount] says which of the two served a given file, so "an ARC opened on the
     * device" is on its own an argument rather than a measurement. Two things turn it into one:
     *
     * 1. **Both shared objects are in the APK, and only one is mapped into the process.** After
     *    opening the archive the way the app opens one, `librust_archive.so` must be mapped and
     *    `libarchive-jni.so` must not be. `ReadArchive` is the only code in the app that touches
     *    the C library, so its absence from `/proc/self/maps` is positive evidence that the Rust
     *    side served this file - not merely that the Rust side was available.
     * 2. **The C library refuses the same bytes, in the same process, through the same channel.**
     *    Calling `ReadArchive` directly on the same archive has to throw. Same bytes, one opens
     *    and one does not: that is the separation, observed rather than argued.
     *
     * Order matters and is deliberate. The mapped-objects check runs before the negative control,
     * because a negative control *by design* loads `libarchive-jni.so` and would therefore make
     * check 1 fail on its own evidence.
     */
    private fun proveWhichLibrary() {
        // (0) Both are shipped. Read out of the installed APK rather than trusted from the build
        // log, because "the fallback is still available" is half of what is being claimed: without
        // a fallback in the APK, not loading it proves nothing.
        val apk = java.io.File(targetContext.applicationInfo.sourceDir)
        val packaged = java.util.zip.ZipFile(apk).use { zip ->
            java.util.Collections.list(zip.entries()).map { it.name }
                .filter { it.endsWith(".so") }.toSet()
        }
        val rustObject = packaged.filter { it.endsWith(RUST_OBJECT) }
        Log.i(REPORT_TAG, "APK native objects: $packaged; of those, $rustObject")
        check(packaged.any { it.endsWith(RUST_OBJECT) }) {
            "$RUST_OBJECT is not in $apk, so nothing else here is a test of this library"
        }
        check(packaged.any { it.endsWith(LEGACY_OBJECT) }) {
            "$LEGACY_OBJECT is not in $apk. The whole point of this step is that the C fallback " +
                "is still shipped and was declined, not that it is absent."
        }

        val archive = "$FIXTURES_DIR/${LONG_TAIL_FIXTURE}"
        check(exists(archive)) { "$LONG_TAIL_FIXTURE is not on the device" }

        // (1) Open it the way the app opens an archive: through the file system provider, so the
        // choice between the two libraries in ArchiveReader.readArchive is the one exercised.
        val before = mappedObjects()
        val fileSystem = ArchiveFileSystemProvider.getOrNewFileSystem(localPath(archive))
        val plaintext = java.io.File("$FIXTURES_DIR/$PLAINTEXT_FIXTURE").readBytes()
        var children: List<Path>
        var read: ByteArray
        try {
            children = fileSystem.getDirectoryChildren(fileSystem.defaultDirectory)
            check(children.size == 1) {
                "$LONG_TAIL_FIXTURE listed $children, which is not the single entry it holds"
            }
            val entry = children.single()
            // Read the bytes through the app's own file system, not through the bridge directly,
            // because "opened and listed" is the claim and a listing alone does not open anything.
            read = fileSystem.newInputStream(entry).use { readEntryFully(it) }
        } finally {
            fileSystem.close()
        }
        val after = mappedObjects()
        Log.i(REPORT_TAG, "listed ${children.size} entry from $LONG_TAIL_FIXTURE through " +
                "ArchiveFileSystem: $children")
        Log.i(REPORT_TAG, "BYTES $LONG_TAIL_FIXTURE/${children.single()} = ${Payload.describe(read)}")
        check(read.contentEquals(plaintext)) {
            "$LONG_TAIL_FIXTURE came back as ${Payload.describe(read)} through the app's own " +
                "file system, which is not the plaintext it was pushed with"
        }
        check(after.contains(RUST_OBJECT)) {
            "$RUST_OBJECT is not mapped into this process after opening $LONG_TAIL_FIXTURE. " +
                "Mapped: $after. Before: $before."
        }
        check(LEGACY_OBJECT !in after) {
            "$LEGACY_OBJECT was mapped into this process while $LONG_TAIL_FIXTURE was opened, so " +
                "the C fallback served it and the Rust library did not. Mapped: $after"
        }
        Log.i(REPORT_TAG, "mapped objects: $RUST_OBJECT present, $LEGACY_OBJECT absent - the " +
                "listing above came from this library and not from the C fallback")

        // (2) The negative control. This loads $LEGACY_OBJECT on purpose.
        val refusal = try {
            ReadArchive(openChannel(archive), emptyList())
            null
        } catch (e: Throwable) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
        check(refusal != null) {
            "the C fallback opened $LONG_TAIL_FIXTURE as well, so this run cannot show which " +
                "library opened it"
        }
        Log.i(REPORT_TAG, "the C fallback refused the same bytes in this process: $refusal")
        Log.i(REPORT_TAG, "so $LONG_TAIL_FIXTURE was opened by $RUST_OBJECT and not by " +
                "$LEGACY_OBJECT: two libraries, the same bytes, one answer each")
    }

    /**
     * The shared objects mapped into this process, by file name.
     *
     * `/proc/self/maps` rather than `System.loadLibrary` bookkeeping, because the claim is about
     * what is actually in the address space rather than about what somebody remembers calling.
     */
    private fun mappedObjects(): Set<String> =
        java.io.File("/proc/self/maps").readLines()
            .mapNotNull { line -> line.substringAfterLast(' ').takeIf { it.endsWith(".so") } }
            .map { it.substringAfterLast('/') }
            .toSet()

    /**
     * Proves the name path end to end: an .arc and an .ice, which the bytes alone detect as ZIP.
     *
     * These two fixtures are the only proof that the file name reaches `nativeOpenNamed`. An ARC
     * begins with the DOS end-of-file marker and an ICE with its own uncompressed length, so
     * detection from the bytes alone calls both of them ZIP and they are refused with "no
     * end-of-central-directory record" — the round trip above cannot fail the way these do
     * without the name, because a ZIP is identified by its own signature.
     *
     * The fixtures, and the plaintext they decode to, are pushed to [FIXTURES_DIR] by
     * tools/bridge-proof.sh, so the bytes are checked against ground truth rather than against a
     * constant. A missing fixture is a failure and not a skip: without it this proves nothing
     * about the name path, and a silent skip would look exactly like a pass.
     */
    private fun proveTheNamePath() {
        proveNamedArchive("store.arc", "LICENSE")
        proveNamedArchive("license_lha.ice", "compressed")
    }

    /**
     * Lists the single entry of the named archive at `store.arc`/`license_lha.ice`, extracts it,
     * and checks the bytes against the plaintext pushed beside the fixtures.
     *
     * The listed size is checked against the bytes that came back too, which is the other half of
     * what the name path is for: an entry whose size the archive does not record must not be
     * listed as zero bytes.
     */
    private fun proveNamedArchive(fixture: String, entryName: String) {
        val path = "$FIXTURES_DIR/$fixture"
        check(exists(path)) {
            "$fixture is not on the device: tools/bridge-proof.sh pushes it, and without it this " +
                "proves nothing about the name path"
        }
        val plaintext = java.io.File("$FIXTURES_DIR/$PLAINTEXT_FIXTURE").readBytes()
        RustArchive.Reader(openChannel(path), emptyList(), fixture).use { reader ->
            val entry = reader.readEntry() ?: throw AssertionError("$fixture listed no entries")
            check(entry.name == entryName) {
                "$fixture listed ${entry.name} as its first entry, not $entryName"
            }
            check(reader.readEntry() == null) {
                "$fixture listed more than one entry; this proof is written for the one-entry " +
                    "fixtures in PROVENANCE.md"
            }
            val read = readEntryFully(reader.newDataInputStream())
            Log.i(REPORT_TAG, "BYTES $fixture/${entry.name} = ${Payload.describe(read)}")
            check(read.size == entry.size.toInt()) {
                "${entry.name} is ${entry.size} bytes in the listing but ${read.size} came back"
            }
            check(read.contentEquals(plaintext)) {
                "${entry.name} in $fixture came back as ${Payload.describe(read)}, which is not " +
                    "the plaintext it was pushed with"
            }
        }
        Log.i(REPORT_TAG, "$fixture listed and extracted through the name path: $entryName")
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
        val archive = externalPath("held-open-proof.zip")
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
        RustArchive.Reader(openChannel(path), emptyList(), path.substringAfterLast('/'))
            .use { reader ->
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
        RustArchive.Reader(openChannel(FOREIGN_ZIP), emptyList(), "foreign.zip").use { reader ->
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

    /**
     * A path in the app's own external files directory.
     *
     * [cachePath] is the obvious place for a scratch archive and it is the wrong one on this
     * emulator: `/data/data/<pkg>/cache` is reached through the app's root file system provider,
     * which shells out to `su`, and the API 24 AVD's `su` is `-rwsr-x--- root:shell`, so it only
     * ever works for the adb shell uid and never for an app. Every stage that used [cachePath] and
     * went through a `java8.nio.file` provider therefore failed with `Root isn't available` before
     * it measured anything - a failure that looks like a broken bridge and is a broken AVD.
     *
     * Nothing about the assertion changes: same archive, same entry count, same in-order and
     * reverse passes. Only the directory moves, to one the app can actually write.
     */
    private fun externalPath(name: String): String {
        // `getExternalFilesDir` is nullable on Android and there is nothing to fall back to: a
        // scratch archive in a directory the app cannot write would fail later and further away,
        // with a message about the bridge rather than about storage.
        val directory = checkNotNull(targetContext.getExternalFilesDir(null)) {
            "this device has no external files directory for the app, so the held-open stage " +
                "cannot run here; this is the AVD's storage, not the bridge"
        }
        return "${directory.absolutePath}/$name"
    }

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

    /**
     * What the manifest says about one corpus archive.
     *
     * [kind] is `good` or `broken`, and it is the difference between "must decode to exactly these
     * bytes" and "must not take the process down". [groundTruth] is the manifest's pointer to a
     * sibling `*.groundtruth.json`, which holds a SHA-256 per file entry. Without it the run can
     * only say an archive opened, and an archive that opens and returns the wrong bytes is exactly
     * the ZIP CRC defect this whole exercise exists to catch.
     */
    private data class CorpusArchive(
        val name: String,
        val format: String,
        val kind: String,
        val groundTruth: String?,
        val defect: String?
    )

    /** One line of the corpus report. */
    private class CorpusVerdict(
        val archive: CorpusArchive,
        val opened: Boolean,
        val entriesSeen: Int,
        val entriesVerified: Int,
        val rejected: Boolean,
        val failure: String?,
        val millis: Long
    ) {
        val healthy: Boolean get() = failure == null
    }

    /**
     * Drives every archive in the corpus through both archive paths on the device.
     *
     * Three things make this stage different from the others, and all three exist because of the
     * way earlier runs lied.
     *
     * **Opening is not correctness.** Every archive here is reported as `ok` by a host oracle that
     * only tested whether it listed, including the ones whose entry data is deliberately corrupt.
     * `arc-wrongcrc16.arc` opens, lists `LICENSE`, and then fails to read it with a CRC mismatch.
     * A run that checks only `open` passes all three wrong-CRC archives and proves nothing about
     * J-0025. So every file entry is read to the end and hashed against the ground truth, and a
     * mismatch is a failure rather than a note.
     *
     * **A broken archive is not allowed to end the run.** Every archive is attempted inside a
     * `Throwable` catch, and every attempt additionally runs against a deadline, so a wedge shows
     * up as one timed-out archive instead of a run that stops reporting at archive 3 of 46.
     *
     * **The pid is the crash detector.** If a native `abort` takes the process down, the
     * instrumentation dies and the remaining archives are silently never attempted. Capturing the
     * pid per archive means that a mid-run death is visible as a gap rather than as "nothing went
     * wrong".
     */
    private fun proveCorpus() {
        val corpus = loadCorpus()
        check(corpus.isNotEmpty()) {
            "MANIFEST.json at $FIXTURES_DIR listed no archives; nothing below was exercised"
        }
        val missing = corpus.filterNot { exists("$FIXTURES_DIR/${it.name}") }
        check(missing.isEmpty()) {
            "${missing.size} of ${corpus.size} corpus archives are not on the device " +
                "(${missing.take(4).joinToString { it.name }}...); push them first"
        }
        Log.i(REPORT_TAG, "corpus: ${corpus.size} archives, ${corpus.count { it.kind == "good" }} good, " +
            "${corpus.count { it.kind == "broken" }} broken")

        val pidAtStart = android.os.Process.myPid()
        val verdicts = ArrayList<CorpusVerdict>(corpus.size)
        val failures = ArrayList<String>()

        for (archive in corpus) {
            val verdict = withDeadline(archive.name) { proofOneArchive(archive) }
            verdicts += verdict
            Log.i(
                REPORT_TAG,
                "CORPUS ${verdict.archive.name} format=${verdict.archive.format} " +
                    "kind=${verdict.archive.kind} opened=${verdict.opened} " +
                    "rejected=${verdict.rejected} entries=${verdict.entriesSeen} " +
                    "verified=${verdict.entriesVerified} ${verdict.millis}ms " +
                    "pid=${android.os.Process.myPid()} " +
                    "outcome=${verdict.failure ?: "ok"}"
            )
            if (!verdict.healthy) {
                failures += "${archive.name}: ${verdict.failure}"
            }
        }

        val pidAtEnd = android.os.Process.myPid()
        Log.i(REPORT_TAG, "corpus: pid $pidAtStart -> $pidAtEnd, ${verdicts.count { it.healthy }} ok, " +
            "${verdicts.count { !it.healthy }} not ok, of ${verdicts.size}")
        for (verdict in verdicts.filterNot { it.healthy }) {
            Log.i(REPORT_TAG, "CORPUS-FAIL ${verdict.archive.name}: ${verdict.failure}")
        }
        check(failures.isEmpty()) {
            "${failures.size} of ${corpus.size} corpus archives did not behave as they must: " +
                failures.take(6).joinToString(" | ")
        }
    }

    /**
     * Runs [body] on a worker thread and gives up on it after [CORPUS_DEADLINE_MILLIS].
     *
     * A hang cannot be caught, only timed out, and a hang is one of the two ways this stage fails.
     * The worker is a daemon so a wedged thread cannot keep the process alive after the run reports.
     */
    private fun <T> withDeadline(what: String, body: () -> T): T {
        // The result travels back through a holder the closure captures. It must not travel through a
        // ThreadLocal: a ThreadLocal is keyed by thread, so the worker sets one value and the
        // thread that joins then reads a different thread's - null - which is what the first version
        // did, and it turned every archive into a NullPointerException.
        val outcome = arrayOfNulls<Any?>(2)
        val work = Runnable {
            val attempt = runCatching(body)
            outcome[if (attempt.isSuccess) 0 else 1] =
                attempt.getOrNull() ?: attempt.exceptionOrNull()
        }
        // Daemon before start, never inside the body: `isDaemon` throws
        // IllegalThreadStateException on a thread that has already begun running, which took the
        // whole run down on the first archive before a single one was proved.
        val worker = Thread(null, work, "corpus-$what", 1 shl 20).apply { isDaemon = true }
        worker.start()
        worker.join(CORPUS_DEADLINE_MILLIS)
        if (worker.isAlive) {
            throw AssertionError(
                "$what did not finish within ${CORPUS_DEADLINE_MILLIS}ms; a parser on this input " +
                    "is not terminating"
            )
        }
        val failure = outcome[1]
        if (failure != null) {
            throw AssertionError("$what threw", failure as Throwable)
        }
        @Suppress("UNCHECKED_CAST")
        return outcome[0] as T
    }

    /**
     * Reads one archive through both paths and decides whether that was correct.
     *
     * The expectation comes from the manifest's `kind`, and the two kinds want opposite things:
     *
     *  * `good` must open *and* every file entry must hash to the ground truth. A good archive that
     *    opens and returns wrong bytes has failed.
     *  * `broken` must not hand back bytes that disagree with the ground truth. Refusing outright is
     *    the ideal answer and is reported as `rejected`; listing entries and then erroring on read
     *    is also acceptable, because truncation legitimately leaves an index readable. What is *not*
     *    acceptable is silently returning corrupt bytes, because that is the J-0025 defect.
     */
    /**
     * Decides whether one archive behaved as its `kind` requires.
     *
     * The two kinds want opposite things, and conflating them is how this stage first reported 34
     * failures out of 46 when the library had in fact refused every single broken archive correctly:
     *
     *  * `good` must open, and every file entry must hash to the ground truth. Anything less is a
     *    failure, including a refusal — a good archive has no reason to be refused.
     *  * `broken` must never return bytes that disagree with the ground truth. Everything else is
     *    the correct answer: refusing outright, erroring on the first read, and listing nothing are
     *    all acceptable for an archive that has been deliberately damaged.
     *
     * So the one outcome that fails a broken archive is [WrongBytes], and it is the one outcome that
     * matters most on this whole project: it is exactly the J-0025 defect, a corrupt archive that
     * extracted as though it were fine.
     */
    private fun proofOneArchive(archive: CorpusArchive): CorpusVerdict {
        val started = android.os.SystemClock.elapsedRealtime()
        val path = "$FIXTURES_DIR/${archive.name}"
        val truth = loadGroundTruth(archive)
        val reader = readThroughReader(path, archive.name, truth)
        val fileSystem = readThroughFileSystem(path, truth)
        val elapsed = android.os.SystemClock.elapsedRealtime() - started

        val opened = reader.opened || fileSystem.opened
        val entriesSeen = maxOf(reader.entries, fileSystem.entries)
        val verified = reader.verified + fileSystem.verified
        val rejected = !reader.opened && !fileSystem.opened
        val wrongBytes = reader.wrongBytes || fileSystem.wrongBytes

        val failure = when {
            wrongBytes -> "corrupt bytes were returned as if they were good: " +
                listOfNotNull(reader.refusal, fileSystem.refusal)
                    .firstOrNull { it.contains("hashed to") } ?: "a hash mismatch"
            archive.kind == "good" && !opened ->
                "a good archive did not open: " +
                    listOfNotNull(reader.refusal, fileSystem.refusal).joinToString("; ")
            archive.kind == "good" && reader.refusal != null ->
                "a good archive failed to read: ${reader.refusal}"
            archive.kind == "good" && fileSystem.refusal != null ->
                "a good archive failed to read: ${fileSystem.refusal}"
            else -> null
        }
        return CorpusVerdict(archive, opened, entriesSeen, verified, rejected, failure, elapsed)
    }

    /** One worker at a time, so the result needs no synchronisation beyond the thread join. */
    /**
     * Bytes came back that the ground truth says are wrong.
     *
     * Its own type because it is the *only* outcome that is a failure for a broken archive. Every
     * other refusal — a truncated stream, a missing end-of-central-directory record, a CRC the
     * reader catches itself — is the correct behaviour for an archive that has been deliberately
     * damaged, and folding them together meant the report called correct behaviour a defect.
     */
    private class WrongBytes(message: String) : AssertionError(message)

    private fun wrongBytes(name: String, actual: String, expected: String) = WrongBytes(
        "$name hashed to $actual but ground truth says $expected; corrupt bytes were returned " +
            "as if they were good"
    )

    private class PathOutcome(
        val opened: Boolean,
        val entries: Int,
        val verified: Int,
        /** Set only when bytes that disagree with the ground truth came back. */
        val wrongBytes: Boolean,
        val refusal: String?
    )

    /**
     * Reads every file entry through [RustArchive.Reader], which is the path that goes straight at
     * the JNI bridge.
     */
    private fun readThroughReader(path: String, name: String, truth: Map<String, String?>?): PathOutcome {
        var entries = 0
        var verified = 0
        return try {
            RustArchive.Reader(openChannel(path), emptyList(), name).use { reader ->
                while (true) {
                    val entry = reader.readEntry() ?: break
                    entries++
                    if (entry.isDirectory || entry.isSymlink) {
                        continue
                    }
                    val expected = truth?.get(entry.name)
                    if (expected == null) {
                        // A directory, or an entry the manifest does not describe. Either way there
                        // is nothing to check it against, and opening a directory's stream is what
                        // made seven good archives look broken.
                        continue
                    }
                    val bytes = readEntryFully(reader.newDataInputStream())
                    val actual = sha256(bytes)
                    if (actual != expected) {
                        throw wrongBytes("$name/${entry.name}", actual, expected)
                    }
                    verified++
                }
                PathOutcome(true, entries, verified, false, null)
            }
        } catch (e: Throwable) {
            PathOutcome(false, entries, verified, e is WrongBytes, describeFailure(name, e))
        }
    }

    /**
     * Reads every entry through [ArchiveFileSystem], which is the path the app's own UI takes and
     * which therefore exercises the root-strategy fix in [disableRootProbing].
     */
    private fun readThroughFileSystem(path: String, truth: Map<String, String?>?): PathOutcome {
        var entries = 0
        var verified = 0
        var wrong = false
        val refusals = ArrayList<String>()
        var opened = false
        return try {
            val fileSystem = ArchiveFileSystemProvider.getOrNewFileSystem(localPath(path))
            try {
                opened = true
                for (child in fileSystem.getDirectoryChildren(fileSystem.defaultDirectory)) {
                    val name = child.toString().substringAfterLast('/')
                    entries++
                    // Checked before the stream is opened. `ArchiveFileSystem` hands back directory
                    // entries alongside files and there is no isDirectory on the listing, so the
                    // manifest is what distinguishes them: a directory appears with no SHA-256.
                    if (truth?.get(name) == null) {
                        continue
                    }
                    try {
                        val bytes = readEntryFully(fileSystem.newInputStream(child))
                        val actual = sha256(bytes)
                        val expected = truth[name]!!
                        if (actual != expected) {
                            throw wrongBytes(name, actual, expected)
                        }
                        verified++
                    } catch (e: Throwable) {
                        if (e is WrongBytes) {
                            wrong = true
                        }
                        refusals += "$name: ${e.message ?: e::class.java.simpleName}"
                    }
                }
                PathOutcome(true, entries, verified, wrong, refusals.firstOrNull())
            } finally {
                fileSystem.close()
            }
        } catch (e: Throwable) {
            PathOutcome(opened, entries, verified, e is WrongBytes, describeFailure(path, e))
        }
    }

    /**
     * Names a refusal as what it is.
     *
     * The stage's contract is that a broken archive is *allowed* to fail, so every one of these is
     * returned as text rather than thrown. What is not allowed is a mismatch reaching
     * [readThroughReader]'s `check`, and that one is a `Throwable` that [proofOneArchive] treats as a
     * failure of the archive rather than as a refusal.
     */
    private fun describeFailure(what: String, error: Throwable): String =
        "${error::class.java.simpleName}: ${error.message ?: "(no message)"}"

    /** Reads `MANIFEST.json` off the device. */
    private fun loadCorpus(): List<CorpusArchive> {
        val file = java.io.File("$FIXTURES_DIR/MANIFEST.json")
        check(file.isFile) {
            "MANIFEST.json is not at $FIXTURES_DIR, so there is no corpus to prove against"
        }
        val entries = org.json.JSONObject(file.readText()).getJSONArray("entries")
        val corpus = ArrayList<CorpusArchive>(entries.length())
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            corpus += CorpusArchive(
                name = entry.getString("name"),
                format = entry.getString("format"),
                kind = entry.getString("kind"),
                groundTruth = entry.optStringOrNull("ground_truth"),
                defect = entry.optStringOrNull("defect")
            )
        }
        return corpus
    }

    /**
     * The per-entry SHA-256s for one archive, keyed by entry name.
     *
     * A directory maps to null. The manifest records directories with no SHA-256, and the listing
     * API hands them back mixed in with the files, so without this the only way to tell them apart
     * was to try opening a directory's data stream and watch it fail.
     */
    private fun loadGroundTruth(archive: CorpusArchive): Map<String, String?>? {
        val fileName = archive.groundTruth ?: return null
        val file = java.io.File("$FIXTURES_DIR/$fileName")
        if (!file.isFile) {
            Log.i(REPORT_TAG, "ground truth $fileName is missing; ${archive.name} is checked only " +
                "for opening, which is much weaker")
            return null
        }
        val entries = org.json.JSONObject(file.readText()).getJSONArray("entries")
        val truth = HashMap<String, String?>(entries.length())
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            truth[entry.getString("name")] =
                if (entry.has("sha256")) entry.getString("sha256") else null
        }
        return truth
    }

    /** `getString` that yields null for an absent key or an explicit JSON null. */
    private fun org.json.JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

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

        /**
         * `Settings.ROOT_STRATEGY`'s NEVER, as the preference stores it.
         *
         * `EnumSettingLiveData` writes `enumValues[application.getString(res).toInt()]`, and
         * `pref_default_value_root_strategy` is `"1"`, which is AUTOMATIC. See
         * [disableRootProbing].
         */
        const val NEVER_ORDINAL = "0"

        /**
         * How long one corpus archive may take before it is called a hang.
         *
         * An i386 emulator running decompressors is slow, so this is generous: the whole corpus of
         * 46 archives, several of them truncated, should not need a tenth of it for any single one.
         * The number is not the point - the point is that a non-terminating parser is reported as
         * one bad archive instead of silently ending the run, which is the second of the two ways
         * the app fails on hostile input.
         */
        const val CORPUS_DEADLINE_MILLIS = 120_000L

        /** A ZIP made by the host's `zip`, pushed here by tools/bridge-proof.sh. */
        const val FOREIGN_ZIP =
            "/storage/emulated/0/Android/data/fork.MaxrregMustermann.TheFileManager/files/foreign.zip"

        /** Where the host pushes the long-tail fixtures, beside the app's own external files. */
        const val FIXTURES_DIR =
            "/storage/emulated/0/Android/data/fork.MaxrregMustermann.TheFileManager/files"

        /**
         * The plaintext both long-tail fixtures decode to, pushed beside them.
         *
         * `store.arc` (one entry, LICENSE) and `license_lha.ice` (one entry, "compressed") are
         * both the license text, so one ground-truth file checks both formats. Every archive
         * tools/make-bridge-fixtures.sh makes holds this same file, so one ground-truth file
         * checks every format rather than a constant per format.
         */
        const val PLAINTEXT_FIXTURE = "license.txt"

        /**
         * The archives [proveTheFormats] opens, and the one entry each holds.
         *
         * Made by GNU tar, gzip, bzip2, xz, zip and p7zip on the host, never by this library, so a
         * pass here is two implementations agreeing rather than one implementation agreeing with
         * itself. The ZIP round-tripped in [proveTheBridge] is deliberately absent: that one was
         * written by this library, and `foreign.zip` below is the one made by something else.
         *
         * The four compressed TARs are here because `tar` says they hold `license.txt` and the
         * library may well disagree. That disagreement is the finding, not a reason to drop them.
         */
        val FOREIGN_FORMATS = listOf(
            FormatFixture("plain.tar", "license.txt"),
            FormatFixture("plain.tar.gz", "license.txt"),
            FormatFixture("plain.tar.xz", "license.txt"),
            FormatFixture("plain.tar.bz2", "license.txt"),
            FormatFixture("plain.7z", "license.txt"),
            // tools/bridge-proof.sh makes foreign.zip only when the host has `zip(1)`, so this one
            // is optional and says so. The five above are not: a missing fixture is a failure,
            // because a skipped check reads exactly like a passed one.
            FormatFixture("foreign.zip", "license.txt", required = false)
        )

        /**
         * Every stage, in the order they run, so a log line names which one produced it and the
         * summary can say how many ran.
         *
         * The order is a constraint, not a preference. `which-library` works by opening an ARC and
         * checking that `libarchive-jni.so` was *not* mapped into the process — the C library has no
         * ARC reader, so its absence is what proves the Rust one served the file. Anything that
         * reaches `ArchiveFileSystem` before it will map the C library in, and the check then fails
         * for a reason that has nothing to do with archives. `the-corpus` goes through
         * `ArchiveFileSystem` for half of every archive, so it has to run after `which-library`;
         * running it third turned that stage's negative control into a false alarm.
         *
         * A receiver lambda rather than a `::method` reference because one stage takes an argument
         * and the rest are private instance methods; a receiver covers all of them without a
         * wrapper per stage.
         */
        val STAGES: List<Pair<String, BridgeInstrumentation.() -> Unit>> = listOf(
            "the-bridge" to { proveTheBridge() },
            "the-formats" to { proveTheFormats() },
            "which-library" to { proveWhichLibrary() },
            "the-name-path" to { proveTheNamePath() },
            "the-corpus" to { proveCorpus() },
            "the-archive-stays-open" to { proveTheArchiveStaysOpen(ENTRY_COUNT) }
        )

        /** One foreign archive and the single entry it is known to hold. */
        class FormatFixture(
            @JvmField val fixture: String,
            @JvmField val entryName: String,
            @JvmField val required: Boolean = true
        )

        /**
         * The long-tail archive used to tell the two archive libraries apart.
         *
         * ARC, because the C `libarchive` has no ARC reader at all, which is what makes the
         * negative control in [proveWhichLibrary] a control rather than another guess.
         */
        const val LONG_TAIL_FIXTURE = "store.arc"

        /** This project's shared object. Built from `libarchive-rs/jni` by `buildRustArchive`. */
        const val RUST_OBJECT = "librust_archive.so"

        /**
         * The C fallback still in the APK, from `me.zhanghai.android.libarchive:library`.
         *
         * Retained on purpose for this run: it is what [proveWhichLibrary] declines.
         */
        const val LEGACY_OBJECT = "libarchive-jni.so"

        private val LOCAL_PROVIDER = LocalLinuxFileSystemProvider(LinuxFileSystemProvider)
    }
}