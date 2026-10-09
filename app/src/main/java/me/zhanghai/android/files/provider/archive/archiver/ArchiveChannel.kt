/*
 * Copyright (c) 2026 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.archive.archiver

import java8.nio.channels.SeekableByteChannel
import java.nio.ByteBuffer

/**
 * The four channel operations the native side needs, on a class that declares them itself.
 *
 * The native side looks these methods up by name against this object's concrete class, which is what
 * JNI does. Handing it a [SeekableByteChannel] directly does not work: the channel the app opens an
 * archive through is a delegation wrapper, and the methods it forwards are not findable that way,
 * so every call failed at runtime with `NoSuchMethodError` and aborted the process.
 *
 * Declaring them here means what the native side asks for is always there, whatever the app passes
 * underneath.
 */
internal class ArchiveChannel(private val channel: SeekableByteChannel) {
    /** Fills [buffer] with up to its remaining bytes, returning -1 at the end of the file. */
    fun read(buffer: ByteBuffer): Int = channel.read(buffer)

    /** Writes [buffer]'s remaining bytes, returning how many were taken. */
    fun write(buffer: ByteBuffer): Int = channel.write(buffer)

    /** Moves the channel and returns the new position. */
    fun position(newPosition: Long): Long {
        channel.position(newPosition)
        return newPosition
    }

    fun size(): Long = channel.size()
}