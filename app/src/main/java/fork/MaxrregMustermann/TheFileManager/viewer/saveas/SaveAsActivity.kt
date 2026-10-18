/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.viewer.saveas

import android.os.Bundle
import android.os.Environment
import java8.nio.file.Path
import java8.nio.file.Paths
import fork.MaxrregMustermann.TheFileManager.R
import fork.MaxrregMustermann.TheFileManager.app.AppActivity
import fork.MaxrregMustermann.TheFileManager.file.MimeType
import fork.MaxrregMustermann.TheFileManager.file.asMimeTypeOrNull
import fork.MaxrregMustermann.TheFileManager.filejob.FileJobService
import fork.MaxrregMustermann.TheFileManager.filelist.FileListActivity
import fork.MaxrregMustermann.TheFileManager.util.saveAsPath
import fork.MaxrregMustermann.TheFileManager.util.showToast

class SaveAsActivity : AppActivity() {
    private val createFileLauncher =
        registerForActivityResult(FileListActivity.CreateFileContract(), ::onCreateFileResult)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val intent = intent
        val mimeType = intent.type?.asMimeTypeOrNull() ?: MimeType.ANY
        val path = intent.saveAsPath
        if (path == null) {
            showToast(R.string.save_as_error)
            finish()
            return
        }
        val title = path.fileName.toString()
        val initialPath =
            Paths.get(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path
            )
        createFileLauncher.launch(Triple(mimeType, title, initialPath))
    }

    private fun onCreateFileResult(result: Path?) {
        if (result == null) {
            finish()
            return
        }
        FileJobService.save(intent.saveAsPath!!, result, this)
        finish()
    }
}
