/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.filelist

import android.os.Bundle
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import fork.MaxrregMustermann.TheFileManager.app.AppActivity
import fork.MaxrregMustermann.TheFileManager.file.MimeType
import fork.MaxrregMustermann.TheFileManager.file.fileProviderUri
import fork.MaxrregMustermann.TheFileManager.util.ParcelableArgs
import fork.MaxrregMustermann.TheFileManager.util.ParcelableParceler
import fork.MaxrregMustermann.TheFileManager.util.args
import fork.MaxrregMustermann.TheFileManager.util.createEditIntent
import fork.MaxrregMustermann.TheFileManager.util.startActivitySafe

// Use a trampoline activity so that we can have a proper icon and title.
class EditFileActivity : AppActivity() {
    private val args by args<Args>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startActivitySafe(args.path.fileProviderUri.createEditIntent(args.mimeType))
        finish()
    }

    @Parcelize
    class Args(
        val path: @WriteWith<ParcelableParceler> Path,
        val mimeType: MimeType
    ) : ParcelableArgs
}
