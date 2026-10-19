/*
 * Copyright (c) 2026 MaxrregMustermann <MaxrregMustermann@airmail.cc>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.apk

import com.reandroid.archive.ArchiveFile
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Getting the splits out of a container, and nothing else.
 *
 * The container layouts here are the ones that turn up in the wild rather than one tidy one: a
 * `.xapk` with the splits under `xapk/` next to a manifest, an `.apks` with them at the top level
 * next to a `toc.pb`, and an `.apkm` next to an `info.json`.
 */
class SplitExtractorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var workingDirectory: File
    private lateinit var containerDirectory: File
    private lateinit var logger: RecordingApkLogger

    @Before
    fun setUp() {
        workingDirectory = temporaryFolder.newFolder("work")
        containerDirectory = temporaryFolder.newFolder("container")
        logger = RecordingApkLogger()
    }

    private fun extractor() = SplitExtractor(workingDirectory, logger)

    private fun containerOf(
        name: String, splits: List<Pair<String, ByteArray>>, extras: Map<String, String> =
            emptyMap()
    ): File {
        val container = File(containerDirectory, name)
        ZipOutputStream(FileOutputStream(container)).use { zip ->
            for ((entryName, bytes) in splits) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
            for ((entryName, content) in extras) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        return container
    }

    /** Real split APKs, so the merger sees the same thing on device as it does in a test. */
    private fun splits(vararg names: String): List<Pair<String, ByteArray>> =
        names.map { name ->
            val file = File(containerDirectory, name)
            ApkFixtures.createApk(file, split = name.removeSuffix(".apk").takeIf { it != "base" })
            name to file.readBytes()
        }

    private fun workDirEntries(): List<String> =
        workingDirectory.list()?.sorted() ?: emptyList()

    @Test
    fun extractsEveryApk() {
        val container = containerOf("app.xapk", splits("base.apk", "config.en.apk"))

        val extracted = ArchiveFile(container).use { extractor().extract(it) }

        assertEquals(listOf("base.apk", "config.en.apk"), extracted)
        assertEquals(listOf("base.apk", "config.en.apk"), workDirEntries())
    }

    @Test
    fun extractsSplitsFromUnderANestedFolder() {
        // An .xapk keeps them under xapk/, and the layout has to survive extraction.
        val container = containerOf("app.xapk", splits("xapk/base.apk", "xapk/config.en.apk"))

        val extracted = ArchiveFile(container).use { extractor().extract(it) }

        assertEquals(listOf("xapk/base.apk", "xapk/config.en.apk"), extracted)
        assertTrue(File(workingDirectory, "xapk/base.apk").isFile)
    }

    @Test
    fun skipsEntriesThatAreNotApks() {
        val extras = linkedMapOf(
            "icon.png" to "png-bytes",
            "AndroidManifest.xml" to "<manifest/>",
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0",
        )
        val container = containerOf("app.apks", splits("base.apk"), extras)

        ArchiveFile(container).use { assertEquals(listOf("base.apk"), extractor().extract(it)) }

        assertEquals(listOf("base.apk"), workDirEntries())
        // Each skipped entry has to be reported, or a container that quietly lost a language looks
        // the same as one that merged correctly.
        for (name in listOf("icon.png", "AndroidManifest.xml", "META-INF/MANIFEST.MF")) {
            assertTrue(
                "$name must be reported: ${logger.messages}",
                logger.messages.any { it.contains(name) && it.contains("not an APK") }
            )
        }
    }

    @Test
    fun leavesOutTheSplitsItIsToldTo() {
        val container = containerOf(
            "app.apks", splits("base.apk", "config.en.apk", "config.de.apk")
        )

        val extracted =
            ArchiveFile(container).use { extractor().extract(it, listOf("config.de.apk")) }

        assertEquals(listOf("base.apk", "config.en.apk"), extracted)
        assertEquals(listOf("base.apk", "config.en.apk"), workDirEntries())
        assertTrue(
            "the unselected split must be reported: ${logger.messages}",
            logger.messages.any { it.contains("config.de.apk") && it.contains("not selected") }
        )
    }

    @Test
    fun anEmptyLeaveOutListMeansExtractEverything() {
        val container = containerOf("app.apks", splits("base.apk", "config.en.apk"))
        ArchiveFile(container).use {
            assertEquals(2, extractor().extract(it, emptyList()).size)
        }
    }

    @Test
    fun refusesAnyNameThatWouldEscapeTheWorkingDirectory() {
        for (name in listOf(
            "../escaped.apk", "../../escaped.apk", "nested/../../escaped.apk", "..", "../",
            "nested/../.."
        )) {
            assertFalse("$name must be refused", SplitExtractor.staysInside(workingDirectory, name))
        }
        for (name in listOf(
            "base.apk", "nested/config.en.apk", "a/b/c/d.apk", ".hidden.apk", "..apk", "apk..",
            "a..b.apk", "nested/../kept.apk"
        )) {
            assertTrue("$name must be allowed", SplitExtractor.staysInside(workingDirectory, name))
        }
        // A leading slash is not an absolute path here: File resolution keeps it under the parent.
        assertTrue(
            "/etc/escaped.apk must stay inside",
            SplitExtractor.staysInside(workingDirectory, "/etc/escaped.apk")
        )
    }

    @Test
    fun neutralisesAnEntryPointingOutsideTheWorkingDirectory() {
        val container =
            containerOf("evil.apks", splits("base.apk"), mapOf("../escaped.apk" to "payload"))

        ArchiveFile(container).use { extractor().extract(it) }

        // ARSCLib strips ".." before it hands a name over, so the payload lands inside the working
        // directory under its bare name. What matters is that it never reaches the parent, and that
        // the name it landed under is still an .apk so the merge sees it rather than dropping it.
        assertFalse(
            "nothing may be written outside the working directory",
            File(containerDirectory, "escaped.apk").exists()
        )
        assertTrue(File(workingDirectory, "escaped.apk").isFile)
    }

    @Test
    fun createsMissingFoldersForNestedSplitNames() {
        val container = containerOf("app.xapk", emptyList(), mapOf("nested/config.en.apk" to "apk"))

        ArchiveFile(container).use { extractor().extract(it) }

        assertTrue(File(workingDirectory, "nested/config.en.apk").isFile)
    }

    @Test
    fun overwritesAnExistingSplit() {
        val container = containerOf("app.apks", splits("base.apk"))
        val existing = File(workingDirectory, "base.apk")
        FileOutputStream(existing).use { it.write("stale contents".toByteArray()) }

        ArchiveFile(container).use { extractor().extract(it) }

        assertTrue("the file must have been rewritten, not appended to", existing.length() < 100_000)
        assertEquals(File(containerDirectory, "base.apk").length(), existing.length())
    }

    @Test
    fun worksWithoutALogger() {
        val container = containerOf("app.apks", splits("base.apk"), mapOf("icon.png" to "png"))

        ArchiveFile(container).use {
            val silent = SplitExtractor(workingDirectory, null)
            assertEquals(listOf("base.apk"), silent.extract(it))
        }
    }

    @Test
    fun reportsAContainerThatIsNotAZip() {
        val broken = File(containerDirectory, "broken.apks")
        broken.writeText("not a zip")

        assertThrows(Exception::class.java) { ArchiveFile(broken).use { extractor().extract(it) } }
    }

    @Test
    fun reportsAContainerThatIsMissing() {
        assertThrows(Exception::class.java) {
            ArchiveFile(File(containerDirectory, "gone.apks")).use { extractor().extract(it) }
        }
    }

    @Test
    fun extractsNothingFromAContainerWithoutApks() {
        val container = containerOf("empty.apks", emptyList(), mapOf("readme.txt" to "hi"))

        ArchiveFile(container).use { assertEquals(emptyList<String>(), extractor().extract(it)) }

        assertEquals(emptyList<String>(), workDirEntries())
    }

    @Test
    fun everyEntryInTheContainerIsAccountedFor() {
        val extras = linkedMapOf("a.apk" to "first", "b.apk" to "second", "c.txt" to "third")
        val container = containerOf("app.apks", splits("base.apk"), extras)

        val all = ArchiveFile(container).use { archive ->
            archive.inputSources.map { it.name }
        }
        val extracted = ArchiveFile(container).use { extractor().extract(it) }

        assertEquals(
            "nothing in the container may go unaccounted for",
            all.size, extracted.size + logger.messages.size
        )
        assertFalse(File(workingDirectory, "c.txt").exists())
    }

    @Test
    fun theFileOverloadBehavesLikeTheArchiveOne() {
        val container = containerOf("app.apks", splits("base.apk", "config.en.apk"))

        assertEquals(
            listOf("base.apk", "config.en.apk"),
            SplitExtractor.extract(container, workingDirectory, logger)
        )
        assertEquals(listOf("base.apk", "config.en.apk"), workDirEntries())
    }
}

/** Captures what the extractor reported, so a test can assert on the reasons rather than the result. */
class RecordingApkLogger : com.reandroid.apk.APKLogger {
    val messages = mutableListOf<String>()

    override fun logMessage(message: String) {
        messages.add(message)
    }

    override fun logError(message: String, throwable: Throwable) {
        messages.add("$message: $throwable")
    }

    override fun logVerbose(message: String) {
        messages.add(message)
    }
}
/**
 * The staging step the merge job runs before anything is merged.
 *
 * A container has to be unpacked into the staging directory and the loose splits copied beside it,
 * so that from here on there is only one thing to do.
 */
class ApkStagingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun aContainerIsUnpackedAndLooseSplitsAreCopied() {
        val directory = temporaryFolder.newFolder("in")
        val splits = listOf("base.apk", "config.en.apk").map { name ->
            val file = File(directory, name)
            ApkFixtures.createApk(file, split = name.removeSuffix(".apk").takeIf { it != "base" })
            file
        }
        val container = File(directory, "app.apkm")
        ZipOutputStream(FileOutputStream(container)).use { zip ->
            for (split in splits) {
                zip.putNextEntry(ZipEntry(split.name))
                zip.write(split.readBytes())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("info.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }

        val output = File(temporaryFolder.newFolder("out"), "app.apkm_merged.apk")
        val staged = ApkMerger.stage(listOf(container), output)

        assertEquals(
            listOf("base.apk", "config.en.apk"), staged.map { it.name }.sorted()
        )
        // The staged files have to be real APKs, not just names that look right.
        for (file in staged) {
            assertTrue("$file should be an APK", file.length() > 0)
            ApkFixtures.entryNames(file)
        }
    }

    @Test
    fun aLooseSplitIsCopiedNotUnpacked() {
        val directory = temporaryFolder.newFolder("loose")
        val base = File(directory, "base.apk")
        ApkFixtures.createApk(base)

        val output = File(temporaryFolder.newFolder("out"), "base.apk")
        val staged = ApkMerger.stage(listOf(base), output)

        assertEquals(listOf("base.apk"), staged.map { it.name })
        assertEquals(base.length(), staged.single().length())
    }

    @Test
    fun aContainerWithNoApkIsAnError() {
        val directory = temporaryFolder.newFolder("broken")
        val container = File(directory, "app.apks")
        ZipOutputStream(FileOutputStream(container)).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }

        val output = File(temporaryFolder.newFolder("out"), "app.apks.apk")
        val error = assertThrows(IOException::class.java) {
            ApkMerger.stage(listOf(container), output)
        }
        assertTrue("the message should name the file: ${error.message}", "app.apks" in error.message!!)
    }

    @Test
    fun anEmptySelectionIsAnError() {
        val output = File(temporaryFolder.newFolder("out"), "x.apk")
        assertThrows(IOException::class.java) { ApkMerger.stage(emptyList(), output) }
    }
}
