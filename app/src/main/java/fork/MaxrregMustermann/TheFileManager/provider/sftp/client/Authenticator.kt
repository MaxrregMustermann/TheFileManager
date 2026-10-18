/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.provider.sftp.client

interface Authenticator {
    fun getAuthentication(authority: Authority): Authentication?
}
