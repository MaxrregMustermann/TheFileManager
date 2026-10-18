/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.apk

import com.reandroid.apk.APKLogger
import com.reandroid.archive.ArchiveFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Copies the APKs out of a split container into a working directory.
 *
 * A split set is not one file. Setters ship it as a ZIP holding the splits, each under a slightly
 * different layout: an `.apks` usually keeps them at the top level next to a `toc.pb`, an `.xapk`
 * puts them under `xapk/` next to a `manifest.json`, and an `.apkm` next to an `info.json`. None of
 * that is worth trusting, so every entry whose name ends in `.apk` is taken from wherever it is.
 *
 * Every entry that is *not* taken is logged with the reason. A container that silently produces
 * fewer splits than it holds is indistinguishable from one that merged correctly but lost a
 * language or an architecture, which is a bug worth seeing.
 *
 * Adapted from AntiSplit-NG, which is licensed under the Apache License 2.0.
 */
class SplitExtractor(
    private val workingDirectory: File,
    private val logger: APKLogger? = null
) {
    /**
     * Extracts the container's APKs into the working directory.
     *
     * @param leaveOut entry names to skip, or null or empty to take everything
     * @return the names extracted, in container order
     */
    @Throws(IOException::class)
    fun extract(container: ArchiveFile, leaveOut: List<String>? = null): List<String> {
        val extracted = mutableListOf<String>()
        val checkLeaveOut = !leaveOut.isNullOrEmpty()
        for (entry in container.inputSources) {
            val name = entry.name
            if (!name.endsWith(".apk")) {
                log("Skipping $name: not an APK")
                continue
            }
            if (checkLeaveOut && name in leaveOut!!) {
                log("Skipping $name: not selected")
                continue
            }
            if (extractApk(entry.name, entry.openStream())) {
                extracted.add(name)
            }
        }
        return extracted
    }

    private fun extractApk(entryName: String, input: java.io.InputStream): Boolean {
        if (!staysInside(workingDirectory, entryName)) {
            // A zip entry name may point anywhere on the filesystem, so it is refused outright
            // rather than resolved and then checked.
            log("Skipped invalid path: $entryName")
            return false
        }
        val outputFile = File(workingDirectory, entryName)
        val parent = outputFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
            log("Could not create folder $parent for $entryName")
            return false
        }
        return try {
            input.use { stream ->
                // Truncating rather than appending, so a split left over from an earlier run is
                // replaced instead of being padded with the older one's bytes.
                outputFile.outputStream().use { outputStream: OutputStream ->
                    stream.copyTo(outputStream)
                }
            }
            true
        } catch (e: IOException) {
            log("Could not extract $entryName: ${e.message}")
            false
        }
    }

    private fun log(message: String) {
        logger?.logMessage(message)
    }

    companion object {
        /**
         * Whether a zip entry name resolves to somewhere inside [workingDirectory].
         *
         * Both sides are canonicalised first, so a name that walks back out with `..` or arrives as
         * a symlinked path is caught rather than merely looking harmless.
         */
        @Throws(IOException::class)
        @JvmStatic
        fun staysInside(workingDirectory: File, entryName: String): Boolean {
            val workingPath = workingDirectory.canonicalPath + File.separator
            return File(workingDirectory, entryName).canonicalPath.startsWith(workingPath)
        }

        /**
         * Extracts [containerFile]'s splits into [workingDirectory].
         *
         * The convenience form for callers that have a file rather than an open archive, which is
         * what the file jobs do.
         */
        @Throws(IOException::class)
        @JvmStatic
        fun extract(
            containerFile: File,
            workingDirectory: File,
            logger: APKLogger? = null,
            leaveOut: List<String>? = null
        ): List<String> =
            ArchiveFile(containerFile).use { archive ->
                SplitExtractor(workingDirectory, logger).extract(archive, leaveOut)
            }
    }
}