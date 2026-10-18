/*
 * Copyright (c) 2026 MaxrregMustermann <MaxrregMustermann@airmail.cc>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.app

import androidx.annotation.StringRes
import fork.MaxrregMustermann.TheFileManager.R

/**
 * The name shown for the app, under its launcher icon and in the recents screen.
 *
 * The launcher reads this from the activity's label, so changing the name means relaunching the app
 * before a launcher that caches labels picks it up.
 */
enum class AppName(@StringRes val titleRes: Int, val aliasSuffix: String) {
    THE_FILE_MANAGER(R.string.app_name_the_file_manager, "TheFileManager"),
    FILE_MANAGER(R.string.app_name_file_manager, "FileManager"),
    FILES(R.string.app_name_files, "Files")
}