/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk

import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the split APKs the tests merge.
 *
 * A split APK is an ordinary APK whose manifest says it is a split, so the fixtures are real zip
 * files with a real binary `AndroidManifest.xml`, written by ARSCLib, which is the same library the
 * merger reads it back with.
 */
object ApkFixtures {
    /** The name the binary manifest has inside an APK. */
    const val MANIFEST_ENTRY_NAME = "AndroidManifest.xml"

    const val PACKAGE_NAME = "com.example.split"
    const val VERSION_CODE = 7

    private const val MAIN_ACTIVITY = ".MainActivity"
    private const val ACTION_MAIN = "android.intent.action.MAIN"
    private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"

    /**
     * Writes an APK to [file].
     *
     * @param split the value of `android:split`, or null for the base module
     * @param isFeatureSplit the value of `android:isFeatureSplit`
     * @param entries the other files to put in the APK
     */
    fun createApk(
        file: File,
        split: String? = null,
        isFeatureSplit: Boolean = false,
        isSplitRequired: Boolean = false,
        entries: Map<String, ByteArray> = emptyMap()
    ) {
        val manifest = AndroidManifestBlock()
        val manifestElement = manifest.getOrCreateElement(AndroidManifest.TAG_manifest)
        manifest.setPackageName(PACKAGE_NAME)
        manifest.setVersionCode(VERSION_CODE)
        manifest.setVersionName("1.0")
        if (split != null) {
            manifest.setSplit(split, true)
        }
        if (isFeatureSplit) {
            manifestElement
                .createAndroidAttribute(AndroidManifest.NAME_isFeatureSplit, AndroidManifest.ID_isFeatureSplit)
                .setValueAsBoolean(true)
        }
        if (isSplitRequired) {
            manifestElement
                .createAndroidAttribute(AndroidManifest.NAME_isSplitRequired, AndroidManifest.ID_isSplitRequired)
                .setValueAsBoolean(true)
        }
        val application = manifest.getOrCreateApplicationElement()
        val activity = application.getOrCreateElement(AndroidManifest.TAG_activity)
        activity
            .createAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(MAIN_ACTIVITY)
        // A base module is recognised by having a launcher activity, so the fixture needs one.
        val intentFilter = activity.getOrCreateElement(AndroidManifest.TAG_intent_filter)
        intentFilter.getOrCreateElement(AndroidManifest.TAG_action)
            .createAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(ACTION_MAIN)
        intentFilter.getOrCreateElement(AndroidManifest.TAG_category)
            .createAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
            .setValueAsString(CATEGORY_LAUNCHER)
        manifest.refresh()

        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
            zip.write(manifest.bytes)
            zip.closeEntry()
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }

    /** The names of the files in [file]. */
    fun entryNames(file: File): Set<String> =
        java.util.zip.ZipFile(file).use { zip ->
            zip.entries().asSequence().map { it.name }.toSet()
        }
}
