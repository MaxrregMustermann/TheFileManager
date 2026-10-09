/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import java8.nio.file.Path
import me.zhanghai.android.files.compat.getDrawableCompat

/**
 * Whether a drawer item that stands for a path is the one marked as selected.
 *
 * The recent list is an alternative to the directory list rather than a place inside it, so while it
 * is showing no path may be marked, and the recent item is. Without that, the item for the path the
 * user was last in stays marked and the drawer shows two selections at once.
 *
 * [isCurrentPath] is the caller's own comparison rather than one made here, because paths only exist
 * once a file system provider is installed, which a unit test cannot do.
 */
fun isPathChecked(isShowingRecentFiles: Boolean, isCurrentPath: Boolean): Boolean =
    !isShowingRecentFiles && isCurrentPath
abstract class NavigationItem {
    abstract val id: Long

    fun getIcon(context: Context): Drawable = context.getDrawableCompat(iconRes!!)

    @get:DrawableRes
    protected abstract val iconRes: Int?

    abstract fun getTitle(context: Context): String

    open fun getSubtitle(context: Context): String? = null

    open fun isChecked(listener: Listener): Boolean = false

    abstract fun onClick(listener: Listener)

    open fun onLongClick(listener: Listener): Boolean = false

    interface Listener {
        val currentPath: Path
        fun navigateTo(path: Path)
        fun navigateToRoot(path: Path)
        fun launchIntent(intent: Intent)
        fun closeNavigationDrawer()

        /** Whether the list of recently modified files is what is being shown. */
        val isShowingRecentFiles: Boolean

        /**
         * Shows the list of recently modified files instead of the current directory.
         *
         * Recent files are from everywhere rather than from one place, so they have no path to
         * navigate to and cannot be a navigation item in the usual sense.
         */
        fun showRecentFiles()
    }
}
