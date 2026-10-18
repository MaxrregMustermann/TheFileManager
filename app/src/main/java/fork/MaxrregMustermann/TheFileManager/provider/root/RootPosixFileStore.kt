/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.provider.root

import fork.MaxrregMustermann.TheFileManager.provider.common.PosixFileStore
import fork.MaxrregMustermann.TheFileManager.provider.remote.RemoteInterface
import fork.MaxrregMustermann.TheFileManager.provider.remote.RemotePosixFileStore

class RootPosixFileStore(fileStore: PosixFileStore) : RemotePosixFileStore(
    RemoteInterface { RootFileService.getRemotePosixFileStoreInterface(fileStore) }
)
