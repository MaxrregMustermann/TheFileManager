/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.fileproperties.permission

import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import java8.nio.file.Path
import fork.MaxrregMustermann.TheFileManager.R
import fork.MaxrregMustermann.TheFileManager.file.FileItem
import fork.MaxrregMustermann.TheFileManager.filejob.FileJobService
import fork.MaxrregMustermann.TheFileManager.provider.common.PosixFileAttributes
import fork.MaxrregMustermann.TheFileManager.provider.common.PosixGroup
import fork.MaxrregMustermann.TheFileManager.provider.common.toByteString
import fork.MaxrregMustermann.TheFileManager.util.SelectionLiveData
import fork.MaxrregMustermann.TheFileManager.util.putArgs
import fork.MaxrregMustermann.TheFileManager.util.show
import fork.MaxrregMustermann.TheFileManager.util.viewModels

class SetGroupDialogFragment : SetPrincipalDialogFragment() {
    override val viewModel: SetPrincipalViewModel by viewModels { { SetGroupViewModel() } }

    @StringRes
    override val titleRes: Int = R.string.file_properties_permission_set_group_title

    override fun createAdapter(selectionLiveData: SelectionLiveData<Int>): PrincipalListAdapter =
        GroupListAdapter(selectionLiveData)

    override val PosixFileAttributes.principal
        get() = group()!!

    override fun setPrincipal(path: Path, principal: PrincipalItem, recursive: Boolean) {
        val group = PosixGroup(principal.id, principal.name?.toByteString())
        FileJobService.setGroup(path, group, recursive, requireContext())
    }

    companion object {
        fun show(file: FileItem, fragment: Fragment) {
            SetGroupDialogFragment().putArgs(Args(file)).show(fragment)
        }
    }
}
