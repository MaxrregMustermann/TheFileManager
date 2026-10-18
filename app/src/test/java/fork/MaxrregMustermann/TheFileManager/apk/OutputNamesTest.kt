/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.apk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Naming the output file.
 *
 * A container may carry any of a handful of extensions and the result is always an `.apk`, so
 * getting this wrong silently produces files the platform refuses to install.
 */
class OutputNamesTest {
    private val suffix = "_antisplit"

    @Test
    fun replacesEveryContainerExtension() {
        // ".apm" is not an extension the app supports, so it is not matched.
        for (extension in listOf("zip", "xapk", "aspk", "apkm", "apks")) {
            assertEquals(
                "app$extension", "app$suffix.apk", OutputNames.merged("app.$extension", suffix)
            )
        }
    }

    @Test
    fun aBareApkIsLeftAlone() {
        // A loose split is already .apk, so the suffix must not be applied twice.
        assertEquals("base.apk", OutputNames.merged("base.apk", suffix))
    }

    @Test
    fun keepsTheNameAroundTheExtension() {
        assertEquals(
            "com.example.game$suffix.apk", OutputNames.merged("com.example.game.xapk", suffix)
        )
        assertEquals("a.b.c$suffix.apk", OutputNames.merged("a.b.c.apks", suffix))
        assertEquals("app$suffix.apk", OutputNames.merged("app.zip", suffix))
    }

    @Test
    fun leavesAnUnknownExtensionAlone() {
        assertEquals("app.tar", OutputNames.merged("app.tar", suffix))
        assertEquals("app", OutputNames.merged("app", suffix))
        assertEquals("app.apm", OutputNames.merged("app.apm", suffix))
        // The trailing extension is the one that counts, whatever precedes it.
        assertEquals("app.xapk$suffix.apk", OutputNames.merged("app.xapk.zip", suffix))
    }

    @Test
    fun worksWithAnEmptySuffix() {
        assertEquals("app.apk", OutputNames.merged("app.xapk", ""))
    }

    @Test
    fun onlyReplacesATrailingExtension() {
        assertEquals("app$suffix.apk", OutputNames.merged("app.xapk", suffix))
    }

    @Test
    fun replacesTheExtensionInsideAFullPath() {
        assertEquals(
            "/storage/emulated/0/Download/app$suffix.apk",
            OutputNames.mergedInPath("/storage/emulated/0/Download/app.xapk", suffix)
        )
        assertEquals(
            "/storage/emulated/0/Download/app$suffix.apk",
            OutputNames.mergedInPath("/storage/emulated/0/Download/app.apks", suffix)
        )
    }

    @Test
    fun renamesAnAlreadyExtractedApk() {
        assertEquals("base$suffix.apk", OutputNames.mergedFromApk("base.apk", suffix))
        assertEquals(
            "com.example.game$suffix.apk", OutputNames.mergedFromApk("com.example.game.apk", suffix)
        )
    }

    @Test
    fun stripsOnlyATrailingApk() {
        assertEquals("com.example.game", OutputNames.withoutExtension("com.example.game.apk"))
        assertEquals("app.zip", OutputNames.withoutExtension("app.zip"))
        assertEquals("app", OutputNames.withoutExtension("app"))
    }

    @Test
    fun namesTheFallbackOutput() {
        assertEquals("unknown$suffix.apk", OutputNames.unknown(suffix))
        assertEquals("unknown.apk", OutputNames.unknown(""))
    }

    @Test
    fun treatsTheSuffixAsLiteralText() {
        // The suffix can come from the user; $1 and \d must not be read as a pattern.
        assertEquals("app\$1.apk", OutputNames.merged("app.zip", "\$1"))
        assertEquals("app\\d.apk", OutputNames.merged("app.zip", "\\d"))
        assertEquals("app[x].apk", OutputNames.merged("app.zip", "[x]"))
        assertEquals("app\$1.apk", OutputNames.mergedFromApk("app.apk", "\$1"))
        assertEquals("/tmp/app\$1.apk", OutputNames.mergedInPath("/tmp/app.zip", "\$1"))
    }

    @Test
    fun aSuffixThatLooksLikeAnExtensionIsNotReinterpreted() {
        assertEquals("appx.apk.apk", OutputNames.merged("app.zip", "x.apk"))
    }

    @Test
    fun theDefaultSuffixMarksTheFileAsMerged() {
        assertEquals("app_merged.apk", OutputNames.merged("app.xapk"))
    }
}

/**
 * Mapping the ABI token in a split's file name onto the ABI's real directory name.
 *
 * This decides which `lib/` folder is inspected for Play Store protection, so a wrong answer here
 * means a protected app gets merged and signed anyway.
 */
class PairIpArchitectureTest {
    @Test
    fun mapsEveryAbiToken() {
        assertEquals("arm64-v8a", PairIpProtection.architectureOf("config.arm64_v8a.apk"))
        assertEquals("arm64-v8a", PairIpProtection.architectureOf("split_config.arm64_v8a.apk"))
        assertEquals("armeabi-v7a", PairIpProtection.architectureOf("config.armeabi-v7a.apk"))
        assertEquals("armeabi-v7a", PairIpProtection.architectureOf("config.armv7a.apk"))
        assertEquals("armeabi-v7a", PairIpProtection.architectureOf("config.arm7.apk"))
        assertEquals("x86", PairIpProtection.architectureOf("config.x86.apk"))
        assertEquals("x86_64", PairIpProtection.architectureOf("config.x86_64.apk"))
        assertEquals("x86_64", PairIpProtection.architectureOf("config.x86-64.apk"))
        assertEquals("x86_64", PairIpProtection.architectureOf("config.x64.apk"))
    }

    @Test
    fun ignoresNamesThatCarryNoAbi() {
        assertNull(PairIpProtection.architectureOf("base.apk"))
        assertNull(PairIpProtection.architectureOf("config.en.apk"))
        assertNull(PairIpProtection.architectureOf("config.xxhdpi.apk"))
        assertNull(PairIpProtection.architectureOf("config.mdpi.apk"))
    }

    @Test
    fun checksTheWiderAbiBeforeTheNarrowerOne() {
        // "x86" is a substring of "x86_64", and "arm" appears inside "arm64", so the order matters.
        assertEquals("x86_64", PairIpProtection.architectureOf("config.x86_64.apk"))
        assertEquals("x86", PairIpProtection.architectureOf("config.x86.apk"))
        assertEquals("arm64-v8a", PairIpProtection.architectureOf("config.arm64_v8a.apk"))
        assertEquals("armeabi-v7a", PairIpProtection.architectureOf("config.armeabi-v7a.apk"))
    }
}

/** Which extensions the merge action offers itself for. */
class SplitApkContainerTest {
    @Test
    fun offersEverySplitExtension() {
        for (extension in listOf("apk", "apks", "apkm", "xapk", "aspk", "zip")) {
            assertTrue("$extension must be mergeable", SplitApkContainer.isMergeable(extension))
        }
    }

    @Test
    fun ignoresCase() {
        assertTrue(SplitApkContainer.isMergeable("APKS"))
        assertTrue(SplitApkContainer.isContainer("Xapk"))
    }

    @Test
    fun doesNotOfferItselfForAnythingElse() {
        for (extension in listOf("txt", "tar", "apkm.bak", "", "ap")) {
            assertFalse("$extension must not be mergeable", SplitApkContainer.isMergeable(extension))
        }
    }

    @Test
    fun tellsAContainerFromALooseSplit() {
        assertTrue(SplitApkContainer.isContainer("apks"))
        assertFalse(SplitApkContainer.isContainer("apk"))
    }
}