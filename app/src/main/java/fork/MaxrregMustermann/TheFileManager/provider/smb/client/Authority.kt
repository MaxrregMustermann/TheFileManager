/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.provider.smb.client

import android.os.Parcelable
import com.hierynomus.smbj.SMBClient
import kotlinx.parcelize.Parcelize
import fork.MaxrregMustermann.TheFileManager.provider.common.UriAuthority
import fork.MaxrregMustermann.TheFileManager.util.takeIfNotEmpty

@Parcelize
data class Authority(
    val host: String,
    val port: Int,
    val username: String,
    val domain: String?
) : Parcelable {
    fun toUriAuthority(): UriAuthority {
        val userInfo = if (domain != null) "$domain\\$username" else username.takeIfNotEmpty()
        val uriPort = port.takeIf { it != DEFAULT_PORT }
        return UriAuthority(userInfo, host, uriPort)
    }

    override fun toString(): String = toUriAuthority().toString()

    companion object {
        const val DEFAULT_PORT = SMBClient.DEFAULT_PORT
    }
}
