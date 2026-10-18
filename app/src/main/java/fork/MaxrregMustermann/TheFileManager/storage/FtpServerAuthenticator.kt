/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.storage

import fork.MaxrregMustermann.TheFileManager.provider.ftp.client.Authenticator
import fork.MaxrregMustermann.TheFileManager.provider.ftp.client.Authority
import fork.MaxrregMustermann.TheFileManager.settings.Settings
import fork.MaxrregMustermann.TheFileManager.util.valueCompat

object FtpServerAuthenticator : Authenticator {
    private val transientServers = mutableSetOf<FtpServer>()

    override fun getPassword(authority: Authority): String? {
        val server = synchronized(transientServers) {
            transientServers.find { it.authority == authority }
        } ?: Settings.STORAGES.valueCompat.find {
            it is FtpServer && it.authority == authority
        } as FtpServer?
        return server?.password
    }

    fun addTransientServer(server: FtpServer) {
        synchronized(transientServers) { transientServers += server }
    }

    fun removeTransientServer(server: FtpServer) {
        synchronized(transientServers) { transientServers -= server }
    }
}
