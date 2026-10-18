/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.filelist

/** Which list the file list is showing. */
enum class FileListMode {
    /** The contents of the current directory. */
    DIRECTORY,

    /** The most recently modified files the search index knows about, from everywhere. */
    RECENT
}