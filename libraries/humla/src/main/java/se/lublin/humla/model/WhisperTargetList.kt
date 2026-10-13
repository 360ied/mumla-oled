/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

package se.lublin.humla.model

/**
 * A simple implementation of a fixed-size whisper target list using a bit vector.
 * Created by andrew on 29/04/16.
 */
class WhisperTargetList {
    companion object {
        const val TARGET_MIN: Byte = 1
        const val TARGET_MAX: Byte = 30
    }

    private val activeTargets: Array<WhisperTarget?> = arrayOfNulls(TARGET_MAX - TARGET_MIN + 1)

    // Mumble stores voice targets using a 5-bit identifier.
    // Use a bit vector to represent this 32-element range.
    private var takenIds: Int = 0

    init {
        clear()
    }

    /**
     * Assigns the target to a slot.
     * @param target The whisper target to assign.
     * @return The slot number in range [1, 30].
     */
    fun append(target: WhisperTarget): Byte {
        var freeId: Byte = -1
        for (i in TARGET_MIN.toInt()..TARGET_MAX.toInt()) {
            if ((takenIds and (1 shl i)) == 0) {
                freeId = i.toByte()
                break
            }
        }
        if (freeId.toInt() != -1) {
            activeTargets[freeId - TARGET_MIN] = target
            takenIds = takenIds or (1 shl freeId.toInt())
        }

        return freeId
    }

    fun get(id: Byte): WhisperTarget? {
        if (id < TARGET_MIN || id > TARGET_MAX) {
            throw IndexOutOfBoundsException()
        }

        if ((takenIds and (1 shl id.toInt())) == 0) {
            return null
        }

        return activeTargets[id - TARGET_MIN]
    }

    fun free(slot: Byte) {
        if (slot < TARGET_MIN || slot > TARGET_MAX) {
            throw IndexOutOfBoundsException()
        }

        // Null the slot as well as the bit: takenIds masks reads, but the
        // array would otherwise retain the WhisperTargetChannel (and its
        // IChannel) until overwritten.
        activeTargets[slot - TARGET_MIN] = null
        takenIds = (takenIds and (1 shl slot.toInt()).inv())
    }

    fun spaceRemaining(): Int {
        var counter = 0
        for (i in TARGET_MIN.toInt()..TARGET_MAX.toInt()) {
            if ((takenIds and (1 shl i)) == 0) {
                counter++
            }
        }
        return counter
    }

    fun clear() {
        activeTargets.fill(null)
        takenIds = 0
    }
}
