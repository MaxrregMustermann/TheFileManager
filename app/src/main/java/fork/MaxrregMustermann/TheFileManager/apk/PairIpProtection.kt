/*
 * Copyright (c) 2026 MaxrregMustermann <MaxrregMustermann@airmail.cc>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.apk

import com.reandroid.apk.ApkModule
import java.io.File
import java.io.IOException

/**
 * Whether a set of splits is Play Store protected, and so cannot be signed with an app's own key.
 *
 * A protected split ships `libpairipcore.so` in its ABI directory. Merging one produces an APK that
 * installs nowhere, because the Play Store's integrity check is looking for a signature this app
 * cannot produce, so the user has to be told rather than handed a file that silently fails.
 *
 * Adapted from AntiSplit-NG, which is licensed under the Apache License 2.0.
 */
object PairIpProtection {
    private const val PAIR_IP_LIBRARY = "libpairipcore.so"

    /**
     * The ABI a split's file name is for, or null when it carries none.
     *
     * The wider ABI is checked first because it is a substring of the narrower one: `x86` appears
     * inside `x86_64`, and `arm` inside `arm64`. Getting the order wrong means inspecting the wrong
     * `lib` directory and missing the protection entirely.
     */
    @JvmStatic
    fun architectureOf(splitName: String): String? {
        if (splitName.contains("x86_64") || splitName.contains("x86-64") || splitName.contains("x64")
        ) {
            return "x86_64"
        }
        if (splitName.contains("x86")) {
            return "x86"
        }
        if (splitName.contains("arm64")) {
            return "arm64-v8a"
        }
        if (splitName.contains("v7a") || splitName.contains("arm7")) {
            return "armeabi-v7a"
        }
        return null
    }

    /** Whether any split in [splits] is Play Store protected. [log] is told what was inspected. */
    @JvmStatic
    fun isProtected(splits: List<File>, log: (String) -> Unit = {}): Boolean {
        for (split in splits) {
            val architecture = architectureOf(split.name) ?: continue
            try {
                ApkModule.loadApkFile(split, split.name).use { module ->
                    if (module.containsFile("lib/$architecture/$PAIR_IP_LIBRARY")) {
                        log("${split.name} is Play Store protected")
                        return true
                    }
                }
            } catch (e: IOException) {
                log("Could not inspect ${split.name}: ${e.message}")
            } catch (e: InterruptedException) {
                log("Could not inspect ${split.name}: ${e.message}")
                Thread.currentThread().interrupt()
            }
        }
        return false
    }
}