/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.fileproperties.permission

import androidx.annotation.DrawableRes
import fork.MaxrregMustermann.TheFileManager.R
import fork.MaxrregMustermann.TheFileManager.util.SelectionLiveData

class GroupListAdapter(
    selectionLiveData: SelectionLiveData<Int>
) : PrincipalListAdapter(selectionLiveData) {
    @DrawableRes
    override val principalIconRes: Int = R.drawable.people_icon_control_normal_24dp
}
