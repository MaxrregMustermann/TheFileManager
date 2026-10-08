/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk

import com.reandroid.apk.ApkModule
import com.reandroid.apk.ApkUtil
import com.reandroid.app.AndroidManifest
import com.reandroid.arsc.array.ResValueMapArray
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlElement
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ValueType

/**
 * Strips the split bookkeeping out of a merged manifest.
 *
 * A merged APK that still advertises itself as a split, or that still points at the Play Store's
 * "these are the splits I need" metadata, is rejected by the installer on some devices. Everything
 * removed here only means something while the APK is a split, so it goes once the splits have been
 * merged in.
 *
 * Adapted from AntiSplit-NG, which is licensed under the Apache License 2.0.
 */
object ManifestSanitizer {
    /** The marker a fused module leaves behind, naming itself as the one to load. */
    private const val FUSED_MODULES = "com.android.dynamic.apk.fused.modules"

    /** The Play Store's pointer at the list of split APKs this app needs. */
    private const val VENDING_SPLITS = "com.android.vending.splits"

    private const val VENDING_PREFIX = "com.android.vending."

    private const val STAMP_PREFIX = "com.android.stamp."

    /** Removes the split declarations from [module] in place. */
    fun sanitize(module: ApkModule, log: (String) -> Unit = {}): Boolean {
        if (!module.hasAndroidManifest()) {
            return false
        }
        val manifest = module.androidManifest
        log("Sanitizing manifest")
        // Only meaningful while the APK is a split.
        removeAttributes(manifest, AndroidManifest.ID_requiredSplitTypes, log)
        removeAttributes(manifest, AndroidManifest.ID_splitTypes, log)
        removeAttributesByName(manifest, AndroidManifest.NAME_splitTypes, log)
        removeAttributesByName(manifest, AndroidManifest.NAME_requiredSplitTypes, log)
        // A merged APK loads its native libraries from its own lib directory, and is never itself
        // required by a parent split.
        removeFromManifestAndApplication(
            manifest, AndroidManifest.ID_extractNativeLibs, AndroidManifest.NAME_extractNativeLibs,
            log
        )
        removeFromManifestAndApplication(
            manifest, AndroidManifest.ID_isSplitRequired, AndroidManifest.NAME_isSplitRequired, log
        )
        val application = manifest.applicationElement
        if (application != null) {
            for (metaData in splitMetaData(application)) {
                val name = stringAttribute(metaData, AndroidManifest.ID_name)
                log("Removed element: <${metaData.name}> name=\"$name\"")
                if (referencesVendingSplitList(metaData)) {
                    removeVendingSplitList(module, metaData, log)
                }
                application.remove(metaData)
            }
        }
        manifest.refresh()
        return true
    }

    /**
     * The `<meta-data>` elements a split APK uses to point at its required splits: the Play Store's
     * markers and the vendor stamp markers.
     */
    private fun splitMetaData(application: ResXmlElement): List<ResXmlElement> {
        val iterator = application.iterator(ResXmlElement::class.java)
        val result = mutableListOf<ResXmlElement>()
        while (iterator.hasNext()) {
            val element = iterator.next()
            if (!element.equalsName(AndroidManifest.TAG_meta_data)) {
                continue
            }
            val value = stringAttribute(element, AndroidManifest.ID_name) ?: continue
            if (isFusedModuleMarker(element, value) || value.startsWith(VENDING_PREFIX) ||
                value.startsWith(STAMP_PREFIX)) {
                result.add(element)
            }
        }
        return result
    }

    private fun isFusedModuleMarker(element: ResXmlElement, name: String): Boolean {
        if (name != FUSED_MODULES) {
            return false
        }
        val value = element.searchAttributeByResourceId(AndroidManifest.ID_value) ?: return false
        return value.valueAsString == ApkUtil.DEF_MODULE_NAME
    }

    /** Whether this element is the Play Store's "these are my splits" pointer. */
    private fun referencesVendingSplitList(metaData: ResXmlElement): Boolean {
        val name = stringAttribute(metaData, AndroidManifest.ID_name) ?: return false
        if (name != VENDING_SPLITS) {
            return false
        }
        val value = splitListReference(metaData) ?: return false
        return value.valueType == ValueType.REFERENCE
    }

    private fun splitListReference(metaData: ResXmlElement) =
        metaData.searchAttributeByResourceId(AndroidManifest.ID_value)
            ?: metaData.searchAttributeByResourceId(AndroidManifest.ID_resource)

    /**
     * Drops the file list the Play Store's split metadata points at, both from the archive and from
     * the resource table. The table entry is nulled rather than removed, because its id may still be
     * referenced from dex code.
     */
    private fun removeVendingSplitList(
        module: ApkModule,
        metaData: ResXmlElement,
        log: (String) -> Unit
    ) {
        if (!module.hasTableBlock()) {
            return
        }
        val reference = splitListReference(metaData) ?: return
        val resourceEntry = module.tableBlock.getResource(reference.data) ?: return
        val zipEntryMap = module.zipEntryMap
        for (entry in resourceEntry) {
            if (entry == null) {
                continue
            }
            for (path in splitFilesOf(entry)) {
                log("Removed table entry: $path")
                zipEntryMap.remove(path)
            }
            entry.setNull(true)
            entry.typeBlock.parentSpecTypePair.removeNullEntries(entry.id)
        }
    }

    /**
     * The paths one entry of the split list points at. Bundletool writes the list as a string array,
     * but a hand built APK can hold a single string instead.
     */
    private fun splitFilesOf(entry: Entry): List<String> {
        val paths = mutableListOf<String>()
        val value = entry.resValue
        if (value != null && value.valueAsString != null) {
            paths.add(value.valueAsString)
        }
        val items: ResValueMapArray? = entry.resValueMapArray
        if (items != null) {
            for (index in 0 until items.size()) {
                val item = items.get(index)
                val path = item?.valueAsString
                if (path != null) {
                    paths.add(path)
                }
            }
        }
        return paths
    }

    private fun removeAttributes(
        manifest: AndroidManifestBlock,
        resourceId: Int,
        log: (String) -> Unit
    ) {
        if (resourceId == 0) {
            return
        }
        val manifestElement = manifest.manifestElement ?: run {
            log("WARN: AndroidManifest has no <manifest> element")
            return
        }
        if (manifestElement.removeAttributesWithId(resourceId)) {
            log("Removed attribute id=${hex(resourceId)}")
        }
    }

    private fun removeAttributesByName(
        manifest: AndroidManifestBlock,
        name: String,
        log: (String) -> Unit
    ) {
        val manifestElement = manifest.manifestElement ?: run {
            log("WARN: AndroidManifest has no <manifest> element")
            return
        }
        if (manifestElement.removeAttributesWithName(name)) {
            log("Removed attribute name=$name")
        }
    }

    private fun removeFromManifestAndApplication(
        manifest: AndroidManifestBlock,
        resourceId: Int,
        nameForLogging: String,
        log: (String) -> Unit
    ) {
        if (resourceId == 0) {
            return
        }
        val manifestElement = manifest.manifestElement ?: run {
            log("WARN: AndroidManifest has no <manifest> element")
            return
        }
        if (manifestElement.removeAttributesWithId(resourceId)) {
            log("Removed from <manifest> id=${hex(resourceId)} ($nameForLogging)")
        }
        val application = manifestElement.getElement(AndroidManifest.TAG_application)
        if (application != null && application.removeAttributesWithId(resourceId)) {
            log("Removed from <application> id=${hex(resourceId)} ($nameForLogging)")
        }
    }

    private fun stringAttribute(element: ResXmlElement, resourceId: Int): String? {
        val attribute = element.searchAttributeByResourceId(resourceId) ?: return null
        if (attribute.valueType != ValueType.STRING) {
            return null
        }
        return attribute.valueAsString
    }

    /** Resource ids read better zero padded, the way the platform sources write them. */
    private fun hex(resourceId: Int): String = "0x%08x".format(resourceId)
}
