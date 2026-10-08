/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk

import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ApkMergerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var splitsDirectory: File
    private lateinit var base: File
    private lateinit var split: File
    private lateinit var output: File

    private val logs = mutableListOf<String>()

    private fun setUpSplits() {
        splitsDirectory = temporaryFolder.newFolder("splits")
        base = File(splitsDirectory, "base.apk").also {
            ApkFixtures.createApk(it, entries = mapOf("classes.dex" to "base".toByteArray()))
        }
        split = File(splitsDirectory, "split_config.arm64_v8a.apk").also {
            ApkFixtures.createApk(
                it, split = "config.arm64_v8a",
                entries = mapOf(
                    "lib/arm64-v8a/libnative.so" to "native".toByteArray(),
                    "classes2.dex" to "split".toByteArray()
                )
            )
        }
        output = File(temporaryFolder.root, "merged.apk")
    }

    private fun manifestOf(file: File): AndroidManifestBlock =
        ApkModule.loadApkFile(file, file.name).use { it.androidManifest }

    private fun merge(force: Boolean = false) =
        ApkMerger.merge(listOf(base, split), output, force) { logs.add(it) }

    @Test
    fun fixtureIsAValidBaseModule() {
        setUpSplits()
        ApkModule.loadApkFile(base, base.name).use { module ->
            assertTrue(ApkMerger.isBaseModule(module))
            assertEquals(ApkFixtures.PACKAGE_NAME, module.androidManifest.packageName)
        }
    }

    @Test
    fun fixtureSplitIsNotABaseModule() {
        setUpSplits()
        ApkModule.loadApkFile(split, split.name).use { module ->
            assertFalse(ApkMerger.isBaseModule(module))
            assertTrue(module.androidManifest.isSplit)
            assertEquals("config.arm64_v8a", module.androidManifest.split)
        }
    }

    @Test
    fun mergesSplitsIntoOneInstallableApk() {
        setUpSplits()
        merge()
        assertTrue(output.isFile)
        val names = ApkFixtures.entryNames(output)
        // Everything both modules carried, including the second dex file.
        assertTrue(names.contains("AndroidManifest.xml"))
        assertTrue(names.contains("classes.dex"))
        assertTrue(names.contains("classes2.dex"))
        assertTrue(names.contains("lib/arm64-v8a/libnative.so"))
    }

    @Test
    fun mergedManifestIsNoLongerASplit() {
        setUpSplits()
        merge()
        val manifest = manifestOf(output)
        assertFalse(manifest.isSplit)
        assertNull(manifest.split)
        assertEquals(ApkFixtures.PACKAGE_NAME, manifest.packageName)
        // A merged APK still has a launcher activity.
        assertNotNull(manifest.mainActivity)
    }

    @Test
    fun removesTheIsSplitRequiredFlag() {
        setUpSplits()
        val featureSplit = File(splitsDirectory, "split_feature.apk").also {
            ApkFixtures.createApk(it, split = "feature", isSplitRequired = true)
        }
        ApkMerger.merge(listOf(base, featureSplit), output) { }
        val manifest = manifestOf(output)
        val manifestElement = manifest.manifestElement
        assertNull(manifestElement?.searchAttributeByResourceId(AndroidManifest.ID_isSplitRequired))
    }

    @Test
    fun removesTheIsFeatureSplitFlag() {
        setUpSplits()
        val featureSplit = File(splitsDirectory, "split_feature.apk").also {
            ApkFixtures.createApk(it, split = "feature", isFeatureSplit = true)
        }
        ApkMerger.merge(listOf(base, featureSplit), output) { }
        val manifest = manifestOf(output)
        assertNull(
            manifest.manifestElement?.searchAttributeByResourceId(AndroidManifest.ID_isFeatureSplit)
        )
    }

    @Test
    fun removesPlayStoreSplitMetadata() {
        setUpSplits()
        val manifest = ApkModule.loadApkFile(split, split.name).use { module ->
            val application = module.androidManifest.getOrCreateApplicationElement()
            val metaData = application.getOrCreateElement(AndroidManifest.TAG_meta_data)
            metaData
                .createAndroidAttribute(AndroidManifest.NAME_name, AndroidManifest.ID_name)
                .setValueAsString("com.android.vending.splits")
            metaData
                .createAndroidAttribute(AndroidManifest.NAME_value, AndroidManifest.ID_value)
                .setValueAsString("splits.xml")
            module.androidManifest.refresh()
            module.writeApk(split)
        }
        assertNotNull(manifest)
        ApkMerger.merge(listOf(base, split), output) { }
        val merged = manifestOf(output)
        val names = merged.applicationElement.listElements(AndroidManifest.TAG_meta_data)
            .mapNotNull { it.searchAttributeByResourceId(AndroidManifest.ID_name)?.valueAsString }
        assertFalse(names.contains("com.android.vending.splits"))
    }

    @Test
    fun logsProgress() {
        setUpSplits()
        merge()
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.any { it.contains("Sanitizing manifest") })
    }

    @Test
    fun rejectsAnEmptySelection() {
        setUpSplits()
        assertThrows(IOException::class.java) {
            ApkMerger.merge(emptyList(), output) { }
        }
    }

    @Test
    fun stagingDirectoryIsCleanedUp() {
        setUpSplits()
        val stagingParent = output.parentFile
        merge()
        val leftovers = stagingParent.listFiles()?.filter { it.name.startsWith("apk-merge") }.orEmpty()
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun overwritesAnExistingOutput() {
        setUpSplits()
        output.writeBytes("stale".toByteArray())
        merge()
        assertTrue(ApkFixtures.entryNames(output).contains("AndroidManifest.xml"))
    }
}
