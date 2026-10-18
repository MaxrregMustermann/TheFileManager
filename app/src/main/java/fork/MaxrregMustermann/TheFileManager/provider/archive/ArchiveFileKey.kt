/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.provider.archive

import android.os.Parcelable
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import fork.MaxrregMustermann.TheFileManager.util.ParcelableParceler

@Parcelize
internal data class ArchiveFileKey(
    private val archiveFile: @WriteWith<ParcelableParceler> Path,
    private val entryName: String
) : Parcelable
