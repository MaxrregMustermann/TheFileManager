/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package fork.MaxrregMustermann.TheFileManager.storage

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.annotation.DrawableRes
import java8.nio.file.Path
import kotlinx.parcelize.Parcelize
import fork.MaxrregMustermann.TheFileManager.R
import fork.MaxrregMustermann.TheFileManager.compat.getDescriptionCompat
import fork.MaxrregMustermann.TheFileManager.compat.isPrimaryCompat
import fork.MaxrregMustermann.TheFileManager.compat.pathCompat
import fork.MaxrregMustermann.TheFileManager.file.DocumentTreeUri
import fork.MaxrregMustermann.TheFileManager.file.displayName
import fork.MaxrregMustermann.TheFileManager.file.storageVolume
import fork.MaxrregMustermann.TheFileManager.provider.document.createDocumentTreeRootPath
import fork.MaxrregMustermann.TheFileManager.util.createIntent
import fork.MaxrregMustermann.TheFileManager.util.putArgs
import fork.MaxrregMustermann.TheFileManager.util.supportsExternalStorageManager
import kotlin.random.Random

@Parcelize
data class DocumentTree(
    override val id: Long,
    override val customName: String?,
    val uri: DocumentTreeUri
) : Storage() {
    constructor(
        id: Long?,
        customName: String?,
        uri: DocumentTreeUri
    ) : this(id ?: Random.nextLong(), customName, uri)

    override val iconRes: Int
        @DrawableRes
        // Error: Call requires API level 24 (current min is 21):
        // android.os.storage.StorageVolume#equals [NewApi]
        @SuppressLint("NewApi")
        get() =
            // We are using MANAGE_EXTERNAL_STORAGE to access all storage volumes when supported.
            if (!Environment::class.supportsExternalStorageManager()
                && uri.storageVolume.let { it != null && !it.isPrimaryCompat }) {
                R.drawable.sd_card_icon_white_24dp
            } else {
                super.iconRes
            }

    override fun getDefaultName(context: Context): String =
        uri.storageVolume?.getDescriptionCompat(context) ?: uri.displayName
            ?: uri.value.lastPathSegment ?: uri.value.toString()

    override val description: String
        get() = uri.value.toString()

    override val path: Path
        get() = uri.value.createDocumentTreeRootPath()

    override val linuxPath: String?
        get() = uri.storageVolume?.pathCompat

    override fun createEditIntent(): Intent =
        EditDocumentTreeDialogActivity::class.createIntent()
            .putArgs(EditDocumentTreeDialogFragment.Args(this))
}
