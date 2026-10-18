/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.storage

import android.os.Bundle
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import kotlinx.parcelize.Parcelize
import fork.MaxrregMustermann.TheFileManager.R
import fork.MaxrregMustermann.TheFileManager.app.packageManager
import fork.MaxrregMustermann.TheFileManager.file.ExternalStorageUri
import fork.MaxrregMustermann.TheFileManager.util.ParcelableArgs
import fork.MaxrregMustermann.TheFileManager.util.args
import fork.MaxrregMustermann.TheFileManager.util.createDocumentsUiViewDirectoryIntent
import fork.MaxrregMustermann.TheFileManager.util.finish
import fork.MaxrregMustermann.TheFileManager.util.showToast

class AddExternalStorageShortcutFragment : Fragment() {
    private val args by args<Args>()

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val uri = args.uri
        val hasDocumentsUi = uri.value.createDocumentsUiViewDirectoryIntent()
            .resolveActivity(packageManager) != null
        if (hasDocumentsUi) {
            val externalStorageShortcut = ExternalStorageShortcut(
                null, args.customNameRes?.let { getString(it) }, uri
            )
            Storages.addOrReplace(externalStorageShortcut)
        } else {
            showToast(R.string.activity_not_found)
        }
        finish()
    }

    @Parcelize
    class Args(
        @StringRes val customNameRes: Int?,
        val uri: ExternalStorageUri
    ) : ParcelableArgs
}
