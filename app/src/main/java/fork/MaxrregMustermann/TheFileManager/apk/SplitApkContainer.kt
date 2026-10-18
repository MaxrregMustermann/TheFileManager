/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.apk

/**
 * Which extensions are a split set this app can turn into one installable APK.
 *
 * A bare `.apk` is one split of a set, or the whole set when an app shipped as a single APK. The
 * rest are ZIPs holding the splits, which [SplitExtractor] unpacks. A `.zip` counts too, since that
 * is what a bundle is very often renamed to before it is shared.
 */
object SplitApkContainer {
    /** The extensions that are a bundle in a ZIP rather than a split APK itself. */
    val CONTAINER_EXTENSIONS = setOf("apks", "apkm", "xapk", "aspk", "zip")

    /** Every extension this app can turn into one installable APK. */
    val MERGEABLE_EXTENSIONS = CONTAINER_EXTENSIONS + "apk"

    fun isMergeable(extension: String): Boolean =
        extension.lowercase() in MERGEABLE_EXTENSIONS

    fun isContainer(extension: String): Boolean =
        extension.lowercase() in CONTAINER_EXTENSIONS
}