/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.storage

import android.os.Bundle
import android.view.View
import androidx.fragment.app.commit
import fork.MaxrregMustermann.TheFileManager.app.AppActivity
import fork.MaxrregMustermann.TheFileManager.util.args
import fork.MaxrregMustermann.TheFileManager.util.putArgs

class EditExternalStorageShortcutDialogActivity : AppActivity() {
    private val args by args<EditExternalStorageShortcutDialogFragment.Args>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            val fragment = EditExternalStorageShortcutDialogFragment().putArgs(args)
            supportFragmentManager.commit {
                add(fragment, EditExternalStorageShortcutDialogFragment::class.java.name)
            }
        }
    }
}
