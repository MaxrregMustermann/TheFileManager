/*
 * Copyright (c) 2023 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.provider.archive

import android.content.Context
import java8.nio.file.Path
import fork.MaxrregMustermann.TheFileManager.fileaction.ArchivePasswordDialogActivity
import fork.MaxrregMustermann.TheFileManager.fileaction.ArchivePasswordDialogFragment
import fork.MaxrregMustermann.TheFileManager.provider.common.UserAction
import fork.MaxrregMustermann.TheFileManager.provider.common.UserActionRequiredException
import fork.MaxrregMustermann.TheFileManager.util.createIntent
import fork.MaxrregMustermann.TheFileManager.util.putArgs
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

class ArchivePasswordRequiredException(
    private val file: Path,
    reason: String?
) :
    UserActionRequiredException(file.toString(), null, reason) {

    override fun getUserAction(continuation: Continuation<Boolean>, context: Context): UserAction {
        return UserAction(
            ArchivePasswordDialogActivity::class.createIntent().putArgs(
                ArchivePasswordDialogFragment.Args(file) { continuation.resume(it) }
            ), ArchivePasswordDialogFragment.getTitle(context),
            ArchivePasswordDialogFragment.getMessage(file, context)
        )
    }
}
