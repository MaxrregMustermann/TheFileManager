/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.provider.sftp

import fork.MaxrregMustermann.TheFileManager.provider.common.PosixFileModeBit
import fork.MaxrregMustermann.TheFileManager.provider.common.toInt
import net.schmizz.sshj.sftp.FileAttributes

fun Set<PosixFileModeBit>.toSftpAttributes(): FileAttributes =
    FileAttributes.Builder().withPermissions(toInt()).build()
