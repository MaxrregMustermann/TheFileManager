/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Builds the file name a merged APK should be saved under.
 *
 * A split container arrives as `.zip`, `.xapk`, `.aspk`, `.apkm`, `.apks` or a bare `.apk`, and the
 * result is always an `.apk`. Getting this wrong silently produces files the platform refuses to
 * install, so every form the container can take is spelled out rather than assumed.
 *
 * Adapted from AntiSplit-NG, which is licensed under the Apache License 2.0.
 */
object OutputNames {
    /**
     * The extensions a container can carry, anchored so that only a trailing one is replaced.
     *
     * `.apm` is deliberately absent: the app never supported it, so a file by that name is left
     * alone rather than being silently renamed into something that looks merged.
     */
    private val CONTAINER_EXTENSION = Pattern.compile("\\.(zip|xapk|aspk|apk[sm])$")

    /** The same, unanchored, for a path that may carry more after the extension. */
    private val CONTAINER_EXTENSION_IN_PATH = Pattern.compile("\\.(zip|xapk|aspk|apk[sm])")

    private val APK_EXTENSION = Pattern.compile("\\.apk$")

    /**
     * Names the merged APK after the container it came from.
     *
     * @param original the name of the file that was opened
     * @param suffix appended before the extension
     */
    @JvmStatic
    fun merged(original: String, suffix: String = DEFAULT_SUFFIX): String =
        CONTAINER_EXTENSION.matcher(original).replaceFirst(replacement(suffix) + ".apk")

    /** The same, for a full path, where the extension is followed by more of the path. */
    @JvmStatic
    fun mergedInPath(path: String, suffix: String = DEFAULT_SUFFIX): String =
        CONTAINER_EXTENSION_IN_PATH.matcher(path).replaceFirst(replacement(suffix) + ".apk")

    /**
     * Names the merged APK after an already extracted `.apk`, which is what a set of loose splits
     * looks like once it has been copied out of its container.
     */
    @JvmStatic
    fun mergedFromApk(name: String, suffix: String = DEFAULT_SUFFIX): String =
        APK_EXTENSION.matcher(name).replaceFirst(replacement(suffix) + ".apk")

    /** Strips a trailing `.apk`, leaving the bare app name. */
    @JvmStatic
    fun withoutExtension(name: String): String = APK_EXTENSION.matcher(name).replaceFirst("")

    /** The name used when nothing in the selected files identifies the app. */
    @JvmStatic
    fun unknown(suffix: String = DEFAULT_SUFFIX): String = "unknown$suffix.apk"

    /**
     * The suffix is editable by the user and may contain `$` or `\`, which [Matcher.replaceFirst]
     * would otherwise read as a group reference or an escape. Quoting it makes it literal text.
     */
    private fun replacement(suffix: String): String = Matcher.quoteReplacement(suffix)

    /** Marks what came out of a merge, so it is not confused with the splits it came from. */
    const val DEFAULT_SUFFIX = "_merged"
}