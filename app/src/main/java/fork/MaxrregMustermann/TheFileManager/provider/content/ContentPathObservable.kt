/*
 * Copyright (c) 2022 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.provider.content

import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import fork.MaxrregMustermann.TheFileManager.provider.common.AbstractPathObservable
import fork.MaxrregMustermann.TheFileManager.provider.content.resolver.Resolver
import fork.MaxrregMustermann.TheFileManager.provider.content.resolver.ResolverException

internal class ContentPathObservable(
    uri: Uri,
    intervalMillis: Long
) : AbstractPathObservable(intervalMillis) {
    private val cursor: Cursor

    private val contentObserver = object : ContentObserver(handler) {
        override fun deliverSelfNotifications(): Boolean = true

        override fun onChange(selfChange: Boolean) {
            notifyObservers()
        }
    }

    init {
        cursor = try {
            Resolver.query(uri, emptyArray(), null, null, null)
        } catch (e: ResolverException) {
            throw e.toFileSystemException(uri.toString())
        }
        cursor.registerContentObserver(contentObserver)
    }

    override fun onCloseLocked() {
        cursor.unregisterContentObserver(contentObserver)
        cursor.close()
    }
}
