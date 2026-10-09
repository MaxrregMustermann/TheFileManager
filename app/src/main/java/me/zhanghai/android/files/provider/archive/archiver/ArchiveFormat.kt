/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

/**
 * The formats the app can create an archive in.
 *
 * The IDs are part of the contract with `format.rs`, which is what the native side matches on.
 */
enum class ArchiveFormat(val id: Int) {
    SEVEN_Z(0),
    ZIP(1),
    TAR(2),
    TAR_XZ(3),
    XZ(4);

    companion object {
        fun fromId(id: Int): ArchiveFormat? = values().firstOrNull { it.id == id }
    }
}