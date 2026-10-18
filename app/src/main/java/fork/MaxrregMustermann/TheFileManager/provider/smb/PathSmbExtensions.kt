/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.provider.smb

import java8.nio.file.Path
import fork.MaxrregMustermann.TheFileManager.provider.smb.client.Authority

fun Authority.createSmbRootPath(): Path =
    SmbFileSystemProvider.getOrNewFileSystem(this).rootDirectory
