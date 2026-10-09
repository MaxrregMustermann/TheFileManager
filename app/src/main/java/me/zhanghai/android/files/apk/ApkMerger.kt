/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk

import com.reandroid.apk.APKLogger
import com.reandroid.apk.ApkBundle
import com.reandroid.apk.ApkModule
import java.io.File
import java.io.IOException

/**
 * Merges the split APKs an app bundle was built from back into one installable APK.
 *
 * ARSCLib does the hard part, which is rewriting the binary `AndroidManifest.xml` and the resource
 * table so that the result is a well formed APK rather than a pile of zipped files.
 *
 * Adapted from AntiSplit-NG, which is licensed under the Apache License 2.0.
 */
object ApkMerger {
    /**
     * Merges every APK in [directory] into [outputFile].
     *
     * @param force merge even when the splits disagree about their package name or version code,
     * which produces an APK that may not install, rather than failing outright
     * @param log progress sink
     */
    @Throws(IOException::class)
    fun mergeDirectory(
        directory: File,
        outputFile: File,
        force: Boolean = false,
        log: (String) -> Unit = {}
    ) {
        val bundle = ApkBundle()
        bundle.setAPKLogger(LoggingApkLogger(log))
        try {
            bundle.loadApkDirectory(directory)
            val modules = bundle.apkModuleList
            if (modules.isEmpty()) {
                throw IOException("No APK found in $directory")
            }
            log("Merging ${modules.size} modules")
            bundle.mergeModules(force).use { merged ->
                ManifestSanitizer.sanitize(merged, log)
                outputFile.parentFile?.mkdirs()
                merged.writeApk(outputFile)
            }
        } finally {
            bundle.close()
        }
    }

    /**
     * Merges [splits] into [outputFile], wherever they happen to live.
     *
     * ARSCLib reads a whole directory at a time, so the splits are staged into one temporary
     * directory first.
     */
    @Throws(IOException::class)
    fun merge(
        splits: List<File>,
        outputFile: File,
        force: Boolean = false,
        log: (String) -> Unit = {}
    ) {
        if (splits.isEmpty()) {
            throw IOException("No APK to merge")
        }
        val staging = createStagingDirectory(outputFile)
        try {
            for (split in splits) {
                log("Staging ${split.name}")
                split.copyTo(File(staging, split.name), overwrite = true)
            }
            mergeDirectory(staging, outputFile, force, log)
        } finally {
            staging.deleteRecursively()
        }
    }

    /**
     * Copies or unpacks [inputs] into a staging directory beside [outputFile], and returns the split
     * APKs that ended up there.
     *
     * An input is either a loose split or a container that several splits are packed into, which is
     * how they are usually downloaded. The staging directory is deliberately not removed here: the
     * caller needs the extracted splits afterwards, to check them for Play Store protection and to
     * hand to the merger.
     */
    @Throws(IOException::class)
    fun stage(
        inputs: List<File>,
        outputFile: File,
        log: (String) -> Unit = {}
    ): List<File> {
        if (inputs.isEmpty()) {
            throw IOException("No APK to merge")
        }
        return stageInto(inputs, createStagingDirectory(outputFile), log)
    }

    private fun stageInto(inputs: List<File>, staging: File, log: (String) -> Unit): List<File> {
        val splits = mutableListOf<File>()
        for (input in inputs) {
            val extension = input.extension.lowercase()
            if (extension in SplitApkContainer.CONTAINER_EXTENSIONS) {
                // A container is one file holding every split, so it is unpacked rather than copied.
                log("Extracting ${input.name}")
                val extracted = SplitExtractor.extract(input, staging, LoggingApkLogger(log))
                if (extracted.isEmpty()) {
                    throw IOException("${input.name} has no APK in it")
                }
                splits.addAll(extracted.map { File(staging, it) })
            } else {
                log("Staging ${input.name}")
                val staged = File(staging, input.name)
                input.copyTo(staged, overwrite = true)
                splits.add(staged)
            }
        }
        if (splits.isEmpty()) {
            throw IOException("No APK to merge")
        }
        return splits
    }

    /**
     * A temporary directory beside [outputFile], which the merge writes through.
     *
     * Created and deleted through java.io rather than java.nio's helper: that helper resolves a
     * generated name against the directory, which goes through this app's own path implementation
     * and has failed on it.
     */
    fun createWorkingDirectory(outputFile: File): File = createStagingDirectory(outputFile)

    private fun createStagingDirectory(outputFile: File): File {
        val parent = outputFile.parentFile ?: File(".")
        parent.mkdirs()
        val staging = File.createTempFile("apk-merge", "", parent)
        check(staging.delete()) { "Cannot reuse $staging as a directory" }
        check(staging.mkdirs()) { "Cannot create $staging" }
        return staging
    }

    /** Whether [module] is the base of its bundle, which is the one that is not itself a split. */
    fun isBaseModule(module: ApkModule): Boolean = module.isBaseModule

    /** ARSCLib logs through an interface, while the rest of the app passes a function around. */
    private class LoggingApkLogger(private val log: (String) -> Unit) : APKLogger {
        override fun logMessage(message: String) {
            log(message)
        }

        override fun logError(message: String, throwable: Throwable) {
            log("$message: $throwable")
        }

        override fun logVerbose(message: String) {
            log(message)
        }
    }
}
