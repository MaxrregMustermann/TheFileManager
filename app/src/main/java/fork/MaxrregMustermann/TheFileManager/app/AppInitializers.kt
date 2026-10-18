/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.app

import android.os.AsyncTask
import android.os.Build
import android.webkit.WebView
import jcifs.context.SingletonContext
import fork.MaxrregMustermann.TheFileManager.BuildConfig
import fork.MaxrregMustermann.TheFileManager.coil.initializeCoil
import fork.MaxrregMustermann.TheFileManager.filejob.fileJobNotificationTemplate
import fork.MaxrregMustermann.TheFileManager.ftpserver.ftpServerServiceNotificationTemplate
import fork.MaxrregMustermann.TheFileManager.hiddenapi.HiddenApi
import fork.MaxrregMustermann.TheFileManager.provider.FileSystemProviders
import fork.MaxrregMustermann.TheFileManager.search.SearchIndex
import fork.MaxrregMustermann.TheFileManager.settings.Settings
import fork.MaxrregMustermann.TheFileManager.storage.FtpServerAuthenticator
import fork.MaxrregMustermann.TheFileManager.storage.SftpServerAuthenticator
import fork.MaxrregMustermann.TheFileManager.storage.SmbServerAuthenticator
import fork.MaxrregMustermann.TheFileManager.storage.StorageVolumeListLiveData
import fork.MaxrregMustermann.TheFileManager.storage.WebDavServerAuthenticator
import fork.MaxrregMustermann.TheFileManager.theme.custom.CustomThemeHelper
import fork.MaxrregMustermann.TheFileManager.theme.night.NightModeHelper
import java.util.Properties
import fork.MaxrregMustermann.TheFileManager.provider.ftp.client.Client as FtpClient
import fork.MaxrregMustermann.TheFileManager.provider.sftp.client.Client as SftpClient
import fork.MaxrregMustermann.TheFileManager.provider.smb.client.Client as SmbClient
import fork.MaxrregMustermann.TheFileManager.provider.webdav.client.Client as WebDavClient

val appInitializers = listOf(
    ::disableHiddenApiChecks,
    ::initializeWebViewDebugging,
    ::initializeCoil,
    ::initializeFileSystemProviders,
    ::initializeSearchIndex,
    ::upgradeApp,
    ::initializeLiveDataObjects,
    ::initializeCustomTheme,
    ::initializeNightMode,
    ::createNotificationChannels
)

private fun disableHiddenApiChecks() {
    HiddenApi.disableHiddenApiChecks()
}

private fun initializeSearchIndex() {
    SearchIndex.initialize(application)
}

private fun initializeWebViewDebugging() {
    if (BuildConfig.DEBUG) {
        WebView.setWebContentsDebuggingEnabled(true)
    }
}

private fun initializeFileSystemProviders() {
    FileSystemProviders.install()
    FileSystemProviders.overflowWatchEvents = true
    // SingletonContext.init() calls NameServiceClientImpl.initCache() which connects to network.
    AsyncTask.THREAD_POOL_EXECUTOR.execute {
        SingletonContext.init(
            Properties().apply {
                setProperty("jcifs.netbios.cachePolicy", "0")
                setProperty("jcifs.smb.client.maxVersion", "SMB1")
            }
        )
    }
    FtpClient.authenticator = FtpServerAuthenticator
    SftpClient.authenticator = SftpServerAuthenticator
    SmbClient.authenticator = SmbServerAuthenticator
    WebDavClient.authenticator = WebDavServerAuthenticator
}

private fun initializeLiveDataObjects() {
    // Force initialization of LiveData objects so that it won't happen on a background thread.
    StorageVolumeListLiveData.value
    Settings.FILE_LIST_DEFAULT_DIRECTORY.value
}

private fun initializeCustomTheme() {
    CustomThemeHelper.initialize(application)
}

private fun initializeNightMode() {
    NightModeHelper.initialize(application)
}

private fun createNotificationChannels() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        notificationManager.createNotificationChannels(
            listOf(
                backgroundActivityStartNotificationTemplate.channelTemplate,
                fileJobNotificationTemplate.channelTemplate,
                ftpServerServiceNotificationTemplate.channelTemplate
            ).map { it.create(application) }
        )
    }
}
