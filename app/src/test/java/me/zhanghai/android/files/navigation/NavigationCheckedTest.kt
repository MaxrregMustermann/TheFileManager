/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which drawer item counts as selected.
 *
 * Every report about this was the same bug wearing different clothes: two items marked at once, or
 * one marked when nothing was. The rule is small enough to state in one function, and small enough
 * that it should be tested directly rather than through a fragment.
 */
class NavigationCheckedTest {
    @Test
    fun aPathItemIsSelectedForTheCurrentDirectory() {
        assertTrue(isPathChecked(isShowingRecentFiles = false, isCurrentPath = true))
    }

    @Test
    fun aPathItemIsNotSelectedForAnyOtherDirectory() {
        assertFalse(isPathChecked(isShowingRecentFiles = false, isCurrentPath = false))
    }

    @Test
    fun noPathItemIsSelectedWhileRecentFilesAreShowing() {
        // The recent list is not a location, so the item for the directory the user was in must not
        // stay marked. Marking both is what the drawer was doing.
        assertFalse(isPathChecked(isShowingRecentFiles = true, isCurrentPath = true))
        assertFalse(isPathChecked(isShowingRecentFiles = true, isCurrentPath = false))
    }

    @Test
    fun exactlyOneItemIsSelectedInEitherMode() {
        // Walking the drawer as the user would, and counting how many items come out selected at
        // each step. More than one at any point is the bug; none is its opposite. Exactly one of the
        // three items is the current directory at a time, which is what a real drawer looks like.
        val items = listOf("storage", "downloads", "dcim")
        for (showingRecent in listOf(false, true)) {
            for (current in items) {
                val checked = items.count {
                    isPathChecked(showingRecent, isCurrentPath = it == current)
                } + if (showingRecent) 1 else 0
                assertEquals(
                    "expected exactly one selection, recent=$showingRecent current=$current",
                    1, checked
                )
            }
        }
    }

    @Test
    fun theRecentItemHasAnIdNoOtherItemUses() {
        // The rest of the drawer identifies items by their resource id, all of which are positive,
        // so a collision would make two rows share a stable id.
        assertTrue(RecentFilesItem().id < 0)
        assertEquals(RecentFilesItem().id, RecentFilesItem().id)
    }
}