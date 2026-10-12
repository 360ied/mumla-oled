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

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Base class for TCP/UDP protocol implementations.
 * Provides a common threading model (single threaded queue for write)
 * Created by andrew on 25/03/14.
 * @deprecated This shouldn't be needed. Redundant inheritance with limited shared code.
 */
@Deprecated("Redundant inheritance with limited shared code; deleted in slice 4")
abstract class HumlaNetworkThread : Runnable {
    companion object {
        private val TAG = HumlaNetworkThread::class.java.name
    }

    private var mExecutor: ExecutorService? = null
    private var mSendExecutor: ExecutorService? = null
    private var mReceiveExecutor: ExecutorService? = null
    private var mMainHandler: Handler = Handler(Looper.getMainLooper())
    private var mInitialized = false

    protected fun startThreads() {
        if (mInitialized) {
            throw IllegalArgumentException("Threads already initialized.")
        }
        mExecutor = Executors.newSingleThreadExecutor()
        mSendExecutor = Executors.newSingleThreadExecutor()
        mReceiveExecutor = Executors.newSingleThreadExecutor()
        mExecutor!!.execute(this)
        mInitialized = true
    }

    protected fun stopThreads() {
        if (!mInitialized) {
            // TODO? Used throw, like startThreads()
            Log.e(TAG, "Error in stopThreads: Threads already shutdown")
            return
        }
        mSendExecutor!!.shutdown()
        mReceiveExecutor!!.shutdownNow()
        mExecutor!!.shutdownNow()
        mSendExecutor = null
        mReceiveExecutor = null
        mExecutor = null
        mInitialized = false
    }

    protected fun executeOnSendThread(r: Runnable) {
        val executor = mSendExecutor ?: return
        executor.execute(r)
    }

    protected fun executeOnReceiveThread(r: Runnable) {
        // Preserved literally from Java (checks the send executor, dispatches
        // on it): the dead receive executor is never ported (see slices.md).
        val executor = mSendExecutor ?: return
        executor.execute(r)
    }

    protected fun executeOnMainThread(r: Runnable) {
        mMainHandler.post(r)
    }

    protected fun getMainHandler(): Handler {
        return mMainHandler
    }
}
