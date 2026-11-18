/*
 * Copyright (C) 2026 Brian Zhu
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package se.lublin.humla.voice

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

/**
 * RNNoise model loader (step 5a.4): the Kotlin side of the control-plane
 * model push. Moved from the deleted NativeAudioInputEngine bridge; the
 * native input engine (VoiceSend) takes the bytes through
 * [EngineNative.setRnnoiseModel] while capture is stopped.
 */
object RnnoiseModel {
    private val cachedModel = AtomicReference<ByteArray?>(null)

    @JvmStatic
    fun load(context: Context?) {
        if (cachedModel.get() != null || context == null) {
            return
        }
        try {
            context.assets.open("rnnoise_model.bin").use { ins ->
                val buffer = ByteArrayOutputStream()
                val data = ByteArray(16384)
                var nRead: Int
                while (ins.read(data, 0, data.size).also { nRead = it } != -1) {
                    buffer.write(data, 0, nRead)
                }
                val model = buffer.toByteArray()
                cachedModel.compareAndSet(null, model)
            }
        } catch (e: IOException) {
            Log.w("RnnoiseModel", "Failed to load rnnoise_model.bin from assets", e)
        }
    }

    @JvmStatic
    fun cached(): ByteArray? {
        return cachedModel.get()
    }
}
