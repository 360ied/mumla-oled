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

/**
 * Created by andrew on 21/08/13.
 */
object Permissions {
    const val None = 0x0
    const val Write = 0x1
    const val Traverse = 0x2
    const val Enter = 0x4
    const val Speak = 0x8
    const val MuteDeafen = 0x10
    const val Move = 0x20
    const val MakeChannel = 0x40
    const val LinkChannel = 0x80
    const val Whisper = 0x100
    const val TextMessage = 0x200
    const val MakeTempChannel = 0x400
    const val Listen = 0x800

    // Root channel only
    const val Kick = 0x10000
    const val Ban = 0x20000
    const val Register = 0x40000
    const val SelfRegister = 0x80000
    const val ResetUserContent = 0x100000

    const val Cached = 0x8000000

    // Derived: const initializers cannot call functions such as `or`, so the
    // combined mask stays a @JvmField val (plain literals above stay const).
    @JvmField
    val All: Int = Write or Traverse or Enter or Speak or MuteDeafen or Move or
        MakeChannel or LinkChannel or Whisper or TextMessage or MakeTempChannel or
        Listen or Kick or Ban or Register or SelfRegister or ResetUserContent
}
