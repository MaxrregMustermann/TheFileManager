/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.provider.linux

import fork.MaxrregMustermann.TheFileManager.provider.common.AbstractWatchKey

internal class LocalLinuxWatchKey(
    watchService: LocalLinuxWatchService,
    path: LinuxPath,
    val watchDescriptor: Int
) : AbstractWatchKey<LocalLinuxWatchKey, LinuxPath>(watchService, path)
