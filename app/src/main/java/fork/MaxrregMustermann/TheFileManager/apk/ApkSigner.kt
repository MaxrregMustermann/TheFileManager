/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.apk

import com.android.apksig.ApkSigner
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Signs a merged APK so that it can be installed.
 *
 * A merged APK carries the signatures of every split it was built from, none of which agree, so it
 * has to be re-signed. There is no key that could be the right one, so an ephemeral key is generated
 * for the merge. The result installs, and it cannot update an existing installation of the same app,
 * which is inherent to merging and not something a file manager can fix.
 */
object ApkSigner {
    private const val KEY_SIZE = 2048

    private const val SIGNATURE_ALGORITHM = "SHA256withRSA"

    private const val SUBJECT = "CN=Material Files AntiSplit, O=Material Files"

    /** How long the generated certificate is good for. */
    private const val VALIDITY_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000

    @Throws(IOException::class)
    fun sign(input: File, output: File, minSdkVersion: Int) {
        // minSdkVersion only decides which signature schemes are used, and the merged APK targets
        // whatever the splits targeted.
        val keyPair = generateKeyPair()
        val signerConfig = ApkSigner.SignerConfig.Builder(
            SUBJECT, keyPair.private, listOf(selfSignedCertificate(keyPair))
        ).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(minSdkVersion)
            .setCreatedBy("Material Files")
            .build()
            .sign()
    }

    private fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE, SecureRandom()) }
            .generateKeyPair()

    private fun selfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            X500Name(SUBJECT), BigInteger.valueOf(now), Date(now),
            Date(now + VALIDITY_MILLIS), X500Name(SUBJECT), keyPair.public
        )
        val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
            .build(privateKeyOf(keyPair))
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun privateKeyOf(keyPair: KeyPair): PrivateKey = keyPair.private
}
