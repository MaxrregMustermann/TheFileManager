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
import fork.MaxrregMustermann.TheFileManager.provider.common.PosixPrincipal
import fork.MaxrregMustermann.TheFileManager.provider.common.PosixUser
import fork.MaxrregMustermann.TheFileManager.provider.common.toByteString
import fork.MaxrregMustermann.TheFileManager.util.SelectionLiveData
import fork.MaxrregMustermann.TheFileManager.util.putArgs
import fork.MaxrregMustermann.TheFileManager.util.show
import fork.MaxrregMustermann.TheFileManager.util.viewModels

class SetOwnerDialogFragment : SetPrincipalDialogFragment() {
    override val viewModel: SetPrincipalViewModel by viewModels { { SetOwnerViewModel() } }

    @StringRes
    override val titleRes: Int = R.string.file_properties_permission_set_owner_title

    override fun createAdapter(selectionLiveData: SelectionLiveData<Int>): PrincipalListAdapter =
        UserListAdapter(selectionLiveData)

    override val PosixFileAttributes.principal: PosixPrincipal
        get() = owner()!!

    override fun setPrincipal(path: Path, principal: PrincipalItem, recursive: Boolean) {
        val owner = PosixUser(principal.id, principal.name?.toByteString())
        FileJobService.setOwner(path, owner, recursive, requireContext())
    }

    companion object {
        fun show(file: FileItem, fragment: Fragment) {
            SetOwnerDialogFragment().putArgs(Args(file)).show(fragment)
        }
    }
}
