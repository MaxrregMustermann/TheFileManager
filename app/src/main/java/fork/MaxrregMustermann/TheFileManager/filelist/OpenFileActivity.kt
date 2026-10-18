/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.filelist

import android.content.Intent
import android.os.Bundle
import java8.nio.file.Path
import fork.MaxrregMustermann.TheFileManager.app.AppActivity
import fork.MaxrregMustermann.TheFileManager.app.application
import fork.MaxrregMustermann.TheFileManager.file.MimeType
import fork.MaxrregMustermann.TheFileManager.file.asMimeTypeOrNull
import fork.MaxrregMustermann.TheFileManager.file.fileProviderUri
import fork.MaxrregMustermann.TheFileManager.filejob.FileJobService
import fork.MaxrregMustermann.TheFileManager.provider.archive.isArchivePath
import fork.MaxrregMustermann.TheFileManager.util.createViewIntent
import fork.MaxrregMustermann.TheFileManager.util.extraPath
import fork.MaxrregMustermann.TheFileManager.util.startActivitySafe

class OpenFileActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val path = intent.extraPath
        val mimeType = intent.type?.asMimeTypeOrNull()
        if (path != null && mimeType != null) {
            openFile(path, mimeType)
        }
        finish()
    }

    private fun openFile(path: Path, mimeType: MimeType) {
        if (path.isArchivePath) {
            FileJobService.open(path, mimeType, false, this)
        } else {
            val intent = path.fileProviderUri.createViewIntent(mimeType)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .apply { extraPath = path }
            startActivitySafe(intent)
        }
    }

    companion object {
        private const val ACTION_OPEN_FILE = "fork.MaxrregMustermann.TheFileManager.intent.action.OPEN_FILE"

        fun createIntent(path: Path, mimeType: MimeType): Intent =
            Intent(ACTION_OPEN_FILE)
                .setPackage(application.packageName)
                .setType(mimeType.value)
                .apply { extraPath = path }
    }
}
