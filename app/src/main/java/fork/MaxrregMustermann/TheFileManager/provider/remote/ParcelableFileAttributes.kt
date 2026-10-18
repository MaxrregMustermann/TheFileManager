/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.provider.remote

import android.os.Parcel
import android.os.Parcelable
import java8.nio.file.attribute.FileAttribute
import fork.MaxrregMustermann.TheFileManager.compat.readSerializableCompat
import fork.MaxrregMustermann.TheFileManager.provider.common.PosixFileMode
import fork.MaxrregMustermann.TheFileManager.provider.common.PosixFileModeBit
import fork.MaxrregMustermann.TheFileManager.provider.common.toAttribute
import fork.MaxrregMustermann.TheFileManager.util.toEnumSet
import java.io.Serializable

class ParcelableFileAttributes(val value: Array<out FileAttribute<*>>) : Parcelable {
    private constructor(source: Parcel) : this(
        source.readSerializableCompat<List<Set<PosixFileModeBit>>>()!!
            .map { it.toAttribute() }
            .toTypedArray()
    )

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        //noinspection unchecked
        val modes = value.map {
            when (val mode = PosixFileMode.fromAttribute(it)) {
                is Serializable -> mode
                else -> mode.toEnumSet()
            }
        }
        dest.writeSerializable(modes as Serializable)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<ParcelableFileAttributes> {
            override fun createFromParcel(source: Parcel): ParcelableFileAttributes =
                ParcelableFileAttributes(source)

            override fun newArray(size: Int): Array<ParcelableFileAttributes?> = arrayOfNulls(size)
        }
    }
}

fun Array<out FileAttribute<*>>.toParcelable(): ParcelableFileAttributes =
    ParcelableFileAttributes(this)
