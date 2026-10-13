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

package se.lublin.humla.util

import android.os.Parcel
import android.os.Parcelable
import se.lublin.humla.protobuf.Mumble

/**
 * Created by andrew on 14/07/13.
 */
class HumlaException : Exception, Parcelable {

    val reason: HumlaDisconnectReason

    /** Indicates that this exception was caused by a reject from the server. */
    val reject: Mumble.Reject?

    /** Indicates that this exception was caused by being kicked/banned from the server. */
    val userRemove: Mumble.UserRemove?

    constructor(message: String?, e: Throwable?, reason: HumlaDisconnectReason) : super(message, e) {
        this.reason = reason
        reject = null
        userRemove = null
    }

    constructor(message: String?, reason: HumlaDisconnectReason) : super(message) {
        this.reason = reason
        reject = null
        userRemove = null
    }

    constructor(e: Throwable?, reason: HumlaDisconnectReason) : super(e) {
        this.reason = reason
        reject = null
        userRemove = null
    }

    constructor(reject: Mumble.Reject) : super("Rejected: " + reject.reason) {
        this.reject = reject
        userRemove = null
        reason = HumlaDisconnectReason.REJECT
    }

    constructor(userRemove: Mumble.UserRemove) : super(
        (if (userRemove.ban) "Banned: " else "Kicked: ") + userRemove.reason
    ) {
        this.userRemove = userRemove
        reject = null
        reason = HumlaDisconnectReason.USER_REMOVE
    }

    private constructor(parcel: Parcel) : super(
        parcel.readString(),
        (parcel.readSerializable() as? Throwable?)
    ) {
        reason = try {
            HumlaDisconnectReason.entries[parcel.readInt()]
        } catch (e: RuntimeException) {
            HumlaDisconnectReason.OTHER_ERROR
        }
        var reject: Mumble.Reject? = null
        if (parcel.readInt() == 1) {
            try {
                val bytes = parcel.createByteArray()
                if (bytes != null) {
                    reject = Mumble.Reject.parseFrom(bytes)
                }
            } catch (e: RuntimeException) {
                // Ignore corrupted parcel
            }
        }
        this.reject = reject

        var userRemove: Mumble.UserRemove? = null
        if (parcel.readInt() == 1) {
            try {
                val bytes = parcel.createByteArray()
                if (bytes != null) {
                    userRemove = Mumble.UserRemove.parseFrom(bytes)
                }
            } catch (e: RuntimeException) {
                // Ignore corrupted parcel
            }
        }
        this.userRemove = userRemove
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(message)
        dest.writeSerializable(cause)
        dest.writeInt(reason.ordinal)
        if (reject != null) {
            dest.writeInt(1)
            dest.writeByteArray(reject.toByteArray())
        } else {
            dest.writeInt(0)
        }
        if (userRemove != null) {
            dest.writeInt(1)
            dest.writeByteArray(userRemove.toByteArray())
        } else {
            dest.writeInt(0)
        }
    }

    enum class HumlaDisconnectReason {
        REJECT,
        USER_REMOVE,
        CONNECTION_ERROR,
        OTHER_ERROR
    }

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<HumlaException> = object : Parcelable.Creator<HumlaException> {
            override fun createFromParcel(source: Parcel): HumlaException {
                return HumlaException(source)
            }

            override fun newArray(size: Int): Array<HumlaException?> {
                return arrayOfNulls(size)
            }
        }
    }
}
