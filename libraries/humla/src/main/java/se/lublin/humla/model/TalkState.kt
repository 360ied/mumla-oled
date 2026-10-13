/*
 * Copyright (C) 2015 Andrew Comminos
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

import android.os.Parcel
import android.os.Parcelable

/**
 * User talk state.
 * Created by andrew on 19/04/15.
 */
enum class TalkState : Parcelable {
    TALKING,
    SHOUTING,
    PASSIVE,
    WHISPERING;

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(ordinal)
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<TalkState> = object : Parcelable.Creator<TalkState> {
            override fun createFromParcel(source: Parcel): TalkState {
                // Clamp corrupted parcels to PASSIVE (mirrors HumlaException's
                // guarded entries[readInt()] fallback instead of throwing).
                return entries.getOrNull(source.readInt()) ?: PASSIVE
            }

            override fun newArray(size: Int): Array<TalkState?> {
                return arrayOfNulls(size)
            }
        }
    }
}
