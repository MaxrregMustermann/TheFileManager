/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.filelist

// TODO: Make immutable?
class PasteState(
    var copy: Boolean = false,
    val files: FileItemSet = fileItemSetOf()
)
