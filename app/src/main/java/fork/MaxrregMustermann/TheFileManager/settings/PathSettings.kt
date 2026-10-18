/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.settings

import androidx.core.content.res.ResourcesCompat
import java8.nio.file.Path
import fork.MaxrregMustermann.TheFileManager.R
import fork.MaxrregMustermann.TheFileManager.filelist.FileSortOptions
import fork.MaxrregMustermann.TheFileManager.filelist.FileViewType

object PathSettings {
    private const val NAME_SUFFIX = "path"

    @Suppress("UNCHECKED_CAST")
    fun getFileListViewType(path: Path): SettingLiveData<FileViewType?> =
        EnumSettingLiveData(
            NAME_SUFFIX, R.string.pref_key_file_list_view_type, path.toString(),
            ResourcesCompat.ID_NULL, FileViewType::class.java
        ) as SettingLiveData<FileViewType?>

    fun getFileListSortOptions(path: Path): SettingLiveData<FileSortOptions?> =
        ParcelValueSettingLiveData(
            NAME_SUFFIX, R.string.pref_key_file_list_sort_options, path.toString(), null
        )
}
