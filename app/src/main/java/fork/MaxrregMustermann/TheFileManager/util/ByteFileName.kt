/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.util

import fork.MaxrregMustermann.TheFileManager.provider.common.ByteString
import fork.MaxrregMustermann.TheFileManager.provider.common.ByteStringBuilder
import fork.MaxrregMustermann.TheFileManager.provider.common.dropLast
import fork.MaxrregMustermann.TheFileManager.provider.common.substringAfterLast

@JvmInline
value class ByteFileName(val value: ByteString) {
    val singleExtension: ByteString
        get() = value.substringAfterLast(EXTENSION_SEPARATOR, ByteString.EMPTY)

    val extensions: ByteString
        get() {
            val lastExtension = singleExtension
            val lastExtensionString = lastExtension.toString()
            if (DOUBLE_EXTENSIONS.any { lastExtensionString.equals(it, true) }) {
                val secondLastExtension = value.dropLast(lastExtension.length + 1)
                    .substringAfterLast(EXTENSION_SEPARATOR, ByteString.EMPTY)
                if (secondLastExtension.isNotEmpty()) {
                    return ByteStringBuilder()
                        .append(secondLastExtension)
                        .append(EXTENSION_SEPARATOR)
                        .append(lastExtension)
                        .toByteString()
                }
            }
            return lastExtension
        }

    val baseName: ByteString
        get() {
            val extensions = extensions
            return if (extensions.isNotEmpty()) value.dropLast(extensions.length + 1) else value
        }

    companion object {
        const val EXTENSION_SEPARATOR = '.'.code.toByte()

        // https://github.com/GNOME/nautilus/blob/c73ad94a72f8e9a989b01858018de74182d17f0e/eel/eel-vfs-extensions.c#L124
        private val DOUBLE_EXTENSIONS = listOf("bz", "bz2", "gz", "sit", "xz", "Z")

        // Not using File.separatorChar so that behavior is consistent and always ready for URIs.
        // Anyway we are on Android. If one day we were moved to Windows, fail-fast is also good.
        const val SEPARATOR = '/'.code.toByte()
    }
}

fun ByteString.asFileName(): ByteFileName {
    require(isValidFileName)
    return ByteFileName(this)
}

fun ByteString.asFileNameOrNull(): ByteFileName? = if (isValidFileName) ByteFileName(this) else null

private val ByteString.isValidFileName: Boolean
    get() = isNotEmpty() && !contains('\u0000'.code.toByte()) && !contains(ByteFileName.SEPARATOR)
