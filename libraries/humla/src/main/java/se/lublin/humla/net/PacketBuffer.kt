/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.net

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * A {@link java.nio.ByteBuffer} based class for constructing Mumble protocol messages.
 */
class PacketBuffer {
    private var mBuffer: ByteBuffer

    constructor(buffer: ByteBuffer) {
        mBuffer = buffer
    }

    constructor(data: ByteArray, len: Int) {
        mBuffer = ByteBuffer.wrap(data)
        mBuffer.limit(len)
    }

    companion object {
        @JvmStatic
        fun allocate(len: Int): PacketBuffer {
            return PacketBuffer(ByteBuffer.allocate(len))
        }

        @JvmStatic
        fun allocateDirect(len: Int): PacketBuffer {
            return PacketBuffer(ByteBuffer.allocateDirect(len))
        }
    }

    /**
     * Returns the current size of the packet.
     * @return The number of bytes written to the packet buffer.
     */
    fun size(): Int {
        return mBuffer.position()
    }

    /**
     * Returns the maximum size this packet can contain.
     * @return The capacity of this packet buffer.
     */
    fun capacity(): Int {
        return mBuffer.limit()
    }

    fun left(): Int {
        return mBuffer.limit() - mBuffer.position()
    }

    fun append(v: Long) {
        mBuffer.put(v.toByte())
    }

    fun append(d: ByteArray, len: Int) {
        mBuffer.put(d, 0, len)
    }

    fun skip(len: Int) {
        mBuffer.position(mBuffer.position() + len)
    }

    fun next(): Int {
        return mBuffer.get().toInt() and 0xFF
    }

    /**
     * Slices the underlying byte buffer at the current position and limits it to size.
     * @param size The limit of the buffer to obtain.
     * @return The sliced byte buffer.
     */
    fun bufferBlock(size: Int): ByteBuffer {
        if (size > mBuffer.remaining()) {
            throw BufferUnderflowException()
        }
        val buffer = mBuffer.slice()
        buffer.limit(size)
        skip(size)
        return buffer
    }

    /**
     * Retrieves a block of data at the current position from the underlying byte buffer.
     * @param size The size of the data block to allocate.
     * @return The allocated data block.
     */
    fun dataBlock(size: Int): ByteArray {
        val block = ByteArray(size)
        mBuffer.get(block, 0, size)
        return block
    }

    fun readBool(): Boolean {
        return readLong() > 0
    }

    fun readDouble(): Double {
        if (left() < 8) {
            throw BufferUnderflowException()
        }

        val i = next().toLong() or (next().toLong() shl 8) or (next().toLong() shl 16) or
            (next().toLong() shl 24) or (next().toLong() shl 32) or (next().toLong() shl 40) or
            (next().toLong() shl 48) or (next().toLong() shl 56)
        return i.toDouble()
    }

    fun readFloat(): Float {
        if (left() < 4) {
            throw BufferUnderflowException()
        }

        val i = next() or (next() shl 8) or (next() shl 16) or (next() shl 24)
        return Float.fromBits(i)
    }

    fun readLong(): Long {
        var i = 0L
        val v = next().toLong()

        if (v and 0x80L == 0x00L) {
            i = v and 0x7FL
        } else if (v and 0xC0L == 0x80L) {
            i = (v and 0x3FL) shl 8 or next().toLong()
        } else if (v and 0xF0L == 0xF0L) {
            val tmp = (v and 0xFCL).toInt()
            when (tmp) {
                0xF0 -> i = (next().toLong() shl 24) or (next().toLong() shl 16) or
                    (next().toLong() shl 8) or next().toLong()
                0xF4 -> i = (next().toLong() shl 56) or (next().toLong() shl 48) or
                    (next().toLong() shl 40) or (next().toLong() shl 32) or
                    (next().toLong() shl 24) or (next().toLong() shl 16) or
                    (next().toLong() shl 8) or next().toLong()
                0xF8 -> {
                    i = readLong()
                    i = i.inv()
                }
                0xFC -> {
                    i = v and 0x03L
                    i = i.inv()
                }
                else -> throw BufferUnderflowException()
            }
        } else if (v and 0xF0L == 0xE0L) {
            i = (v and 0x0FL) shl 24 or (next().toLong() shl 16) or
                (next().toLong() shl 8) or next().toLong()
        } else if (v and 0xE0L == 0xC0L) {
            i = (v and 0x1FL) shl 16 or (next().toLong() shl 8) or next().toLong()
        }
        return i
    }

    fun rewind() {
        mBuffer.rewind()
    }

    fun writeBool(b: Boolean) {
        val v = if (b) 1 else 0
        writeLong(v.toLong())
    }

    fun writeDouble(v: Double) {
        val i = v.toBits()
        append(i and 0xFFL)
        append((i shr 8) and 0xFFL)
        append((i shr 16) and 0xFFL)
        append((i shr 24) and 0xFFL)
        append((i shr 32) and 0xFFL)
        append((i shr 40) and 0xFFL)
        append((i shr 48) and 0xFFL)
        append((i shr 56) and 0xFFL)
    }

    fun writeFloat(v: Float) {
        val i = v.toBits().toLong() and 0xFFFFFFFFL

        append(i and 0xFFL)
        append((i shr 8) and 0xFFL)
        append((i shr 16) and 0xFFL)
        append((i shr 24) and 0xFFL)
    }

    fun writeLong(value: Long) {
        var i = value

        if (i < 0 && i.inv() < 0x100000000L) {
            // Signed number.
            i = i.inv()
            if (i <= 0x3) {
                // Shortcase for -1 to -4
                append(0xFCL or i)
                return
            } else {
                append(0xF8L)
            }
        }

        if (i in 0..0x7F) {
            // Need top bit clear
            append(i)
        } else if (i in 0..0x3FFF) {
            // Need top two bits clear
            append((i shr 8) or 0x80L)
            append(i and 0xFFL)
        } else if (i in 0..0x1FFFFF) {
            // Need top three bits clear
            append((i shr 16) or 0xC0L)
            append((i shr 8) and 0xFFL)
            append(i and 0xFFL)
        } else if (i in 0..0xFFFFFFF) {
            // Need top four bits clear
            append((i shr 24) or 0xE0L)
            append((i shr 16) and 0xFFL)
            append((i shr 8) and 0xFFL)
            append(i and 0xFFL)
        } else if (i in 0..0xFFFFFFFFL) {
            // It's a full 32-bit integer.
            append(0xF0L)
            append((i shr 24) and 0xFFL)
            append((i shr 16) and 0xFFL)
            append((i shr 8) and 0xFFL)
            append(i and 0xFFL)
        } else {
            // It's a 64-bit value.
            append(0xF4L)
            append((i shr 56) and 0xFFL)
            append((i shr 48) and 0xFFL)
            append((i shr 40) and 0xFFL)
            append((i shr 32) and 0xFFL)
            append((i shr 24) and 0xFFL)
            append((i shr 16) and 0xFFL)
            append((i shr 8) and 0xFFL)
            append(i and 0xFFL)
        }
    }
}
