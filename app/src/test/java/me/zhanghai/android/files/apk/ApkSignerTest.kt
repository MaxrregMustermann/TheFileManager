/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apk

import com.android.apksig.ApkVerifier
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApkSignerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun signsAMergedApkSoThatItCanBeInstalled() {
        val merged = mergeFixture()
        assertFalse("A merged APK inherits incompatible signatures", verify(merged).isVerified)

        val signed = File(temporaryFolder.root, "merged-signed.apk")
        ApkSigner.sign(merged, signed, MIN_SDK_VERSION)

        val result = verify(signed)
        assertTrue(result.allErrors.toString(), result.isVerified)
        assertEquals(1, result.signerCertificates.size)
    }

    /**
     * Signing an already signed APK replaces the signature rather than adding to it, so the result
     * still verifies but is signed by a different key.
     */
    @Test
    fun resigningReplacesTheSignature() {
        val signed = File(temporaryFolder.root, "merged-signed.apk")
        ApkSigner.sign(mergeFixture(), signed, MIN_SDK_VERSION)
        val again = File(temporaryFolder.root, "merged-signed-again.apk")
        ApkSigner.sign(signed, again, MIN_SDK_VERSION)
        val result = verify(again)
        assertTrue(result.allErrors.toString(), result.isVerified)
        assertFalse(result.signerCertificates[0] == verify(signed).signerCertificates[0])
    }

    private fun mergeFixture(): File {
        val splitsDirectory = temporaryFolder.newFolder("splits-${counter++}")
        val base = File(splitsDirectory, "base.apk").also {
            ApkFixtures.createApk(it, entries = mapOf("classes.dex" to "base".toByteArray()))
        }
        val split = File(splitsDirectory, "split_config.en.apk").also {
            ApkFixtures.createApk(it, split = "config.en")
        }
        val merged = File(temporaryFolder.root, "merged-$counter.apk")
        ApkMerger.merge(listOf(base, split), merged) { }
        return merged
    }

    private fun verify(file: File) =
        // The fixture declares no <uses-sdk>, so without this the verifier would also check API
        // levels that never supported SHA-256 signatures.
        ApkVerifier.Builder(file).setMinCheckedPlatformVersion(MIN_SDK_VERSION).build().verify()

    companion object {
        private const val MIN_SDK_VERSION = 23

        private var counter = 0
    }
}