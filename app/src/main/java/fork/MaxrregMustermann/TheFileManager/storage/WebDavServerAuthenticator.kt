/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.storage

import fork.MaxrregMustermann.TheFileManager.provider.webdav.client.Authentication
import fork.MaxrregMustermann.TheFileManager.provider.webdav.client.Authenticator
import fork.MaxrregMustermann.TheFileManager.provider.webdav.client.Authority
import fork.MaxrregMustermann.TheFileManager.settings.Settings
import fork.MaxrregMustermann.TheFileManager.util.valueCompat

object WebDavServerAuthenticator : Authenticator {
    private val transientServers = mutableSetOf<WebDavServer>()

    override fun getAuthentication(authority: Authority): Authentication? {
        val server = synchronized(transientServers) {
            transientServers.find { it.authority == authority }
        } ?: Settings.STORAGES.valueCompat.find {
            it is WebDavServer && it.authority == authority
        } as WebDavServer?
        return server?.authentication
    }

    fun addTransientServer(server: WebDavServer) {
        synchronized(transientServers) { transientServers += server }
    }

    fun removeTransientServer(server: WebDavServer) {
        synchronized(transientServers) { transientServers -= server }
    }
}
