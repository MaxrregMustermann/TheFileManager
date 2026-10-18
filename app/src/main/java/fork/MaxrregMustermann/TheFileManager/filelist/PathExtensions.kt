/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.filelist

import java8.nio.file.Path
import fork.MaxrregMustermann.TheFileManager.file.MimeType
import fork.MaxrregMustermann.TheFileManager.file.isSupportedArchive
import fork.MaxrregMustermann.TheFileManager.provider.archive.archiveFile
import fork.MaxrregMustermann.TheFileManager.provider.archive.isArchivePath
import fork.MaxrregMustermann.TheFileManager.provider.document.isDocumentPath
import fork.MaxrregMustermann.TheFileManager.provider.document.resolver.DocumentResolver
import fork.MaxrregMustermann.TheFileManager.provider.linux.isLinuxPath

val Path.name: String
    get() = fileName?.toString() ?: if (isArchivePath) archiveFile.fileName.toString() else "/"

fun Path.toUserFriendlyString(): String = if (isLinuxPath) toFile().path else toUri().toString()

fun Path.isArchiveFile(mimeType: MimeType): Boolean = !isArchivePath && mimeType.isSupportedArchive

val Path.isLocalPath: Boolean
    get() =
        isLinuxPath || (isDocumentPath && DocumentResolver.isLocal(this as DocumentResolver.Path))

val Path.isRemotePath: Boolean
    get() = !isLocalPath
