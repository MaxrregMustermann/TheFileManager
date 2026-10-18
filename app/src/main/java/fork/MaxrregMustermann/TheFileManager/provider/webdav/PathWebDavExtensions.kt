/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.provider.webdav

import java8.nio.file.Path
import fork.MaxrregMustermann.TheFileManager.provider.webdav.client.Authority

fun Authority.createWebDavRootPath(): Path =
    WebDavFileSystemProvider.getOrNewFileSystem(this).rootDirectory
