/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexCoverageTest {
    private val coverage = IndexCoverage()

    @Test
    fun nothingIsCoveredByDefault() {
        assertFalse(coverage.contains("file:///storage/emulated/0"))
    }

    @Test
    fun coversASubtreeOnceItsRootHasBeenIndexed() {
        coverage.add("file:///storage/emulated/0")
        assertTrue(coverage.contains("file:///storage/emulated/0"))
        assertTrue(coverage.contains("file:///storage/emulated/0/Documents"))
        assertTrue(coverage.contains("file:///storage/emulated/0/Documents/Reports"))
    }

    @Test
    fun anUnrelatedRootIsNotCovered() {
        coverage.add("file:///storage/emulated/0")
        assertFalse(coverage.contains("file:///storage/emulated/1"))
        assertFalse(coverage.contains("file:///"))
    }

    @Test
    fun aSiblingWithTheSamePrefixIsNotCovered() {
        coverage.add("file:///storage/emulated/0/Documents")
        assertFalse(coverage.contains("file:///storage/emulated/0/DocumentsBackup"))
    }

    @Test
    fun aTrailingSlashDoesNotMatter() {
        coverage.add("file:///storage/emulated/0/")
        assertTrue(coverage.contains("file:///storage/emulated/0"))
        assertTrue(coverage.contains("file:///storage/emulated/0/"))
    }

    @Test
    fun forgettingARootForgetsItsWholeSubtree() {
        coverage.add("file:///storage/emulated/0/Documents")
        coverage.add("file:///storage/emulated/0/Pictures")
        coverage.remove("file:///storage/emulated/0/Documents")
        assertFalse(coverage.contains("file:///storage/emulated/0/Documents"))
        assertFalse(coverage.contains("file:///storage/emulated/0/Documents/Reports"))
        assertTrue(coverage.contains("file:///storage/emulated/0/Pictures"))
    }

    @Test
    fun forgettingSomethingInsideACoveredRootLeavesTheRootAlone() {
        // Listing a directory only ever covers the whole tree it walked, so losing a subdirectory
        // that was never its own coverage root must not un-cover the root.
        coverage.add("file:///storage/emulated/0")
        coverage.remove("file:///storage/emulated/0/Documents")
        assertTrue(coverage.contains("file:///storage/emulated/0"))
    }

    @Test
    fun clearingForgetsEverything() {
        coverage.add("file:///storage/emulated/0")
        coverage.clear()
        assertFalse(coverage.contains("file:///storage/emulated/0"))
    }
}
