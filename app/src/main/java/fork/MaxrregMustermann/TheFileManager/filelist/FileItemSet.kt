/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.filelist

import android.os.Parcel
import android.os.Parcelable
import java8.nio.file.Path
import fork.MaxrregMustermann.TheFileManager.compat.writeParcelableListCompat
import fork.MaxrregMustermann.TheFileManager.file.FileItem
import fork.MaxrregMustermann.TheFileManager.util.LinkedMapSet
import fork.MaxrregMustermann.TheFileManager.util.readParcelableListCompat

class FileItemSet() : LinkedMapSet<Path, FileItem>(FileItem::path), Parcelable {
    constructor(parcel: Parcel) : this() {
        addAll(parcel.readParcelableListCompat())
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeParcelableListCompat(toList(), flags)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<FileItemSet> {
        override fun createFromParcel(parcel: Parcel): FileItemSet = FileItemSet(parcel)

        override fun newArray(size: Int): Array<FileItemSet?> = arrayOfNulls(size)
    }
}

fun fileItemSetOf(vararg files: FileItem) = FileItemSet().apply { addAll(files) }
