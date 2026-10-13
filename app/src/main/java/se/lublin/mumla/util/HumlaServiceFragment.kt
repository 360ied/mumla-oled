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

package se.lublin.mumla.util

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import se.lublin.humla.IHumlaService
import se.lublin.humla.util.IHumlaObserver
import se.lublin.mumla.service.IMumlaService

/**
 * Fragment class intended to make binding the Humla service to fragments easier.
 * Created by andrew on 04/08/13.
 */
abstract class HumlaServiceFragment : Fragment() {

    private var mServiceProvider: HumlaServiceProvider? = null

    /** State boolean to make sure we don't double initialize a fragment once a service has been bound. */
    private var mBound: Boolean = false

    override fun onAttach(context: Context) {
        super.onAttach(context)

        try {
            mServiceProvider = context as HumlaServiceProvider
        } catch (e: ClassCastException) {
            // A wrapped context (theme wrapper, test harness) is not the host activity itself.
            if (activity is HumlaServiceProvider) {
                mServiceProvider = activity as HumlaServiceProvider
            } else {
                val failure = ClassCastException(
                    context.javaClass.name + " must implement HumlaServiceProvider"
                )
                failure.initCause(e)
                throw failure
            }
        }
    }

    override fun onDetach() {
        mServiceProvider = null
        super.onDetach()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        // NPE-exact: the original dereferenced mServiceProvider unconditionally.
        val provider = mServiceProvider ?: throw NullPointerException("mServiceProvider")
        provider.addServiceFragment(this)
        val service = provider.getService()
        if (service != null && !mBound) {
            onServiceAttached(service)
        }
    }

    override fun onDestroy() {
        val provider = mServiceProvider
        if (provider != null) {
            provider.removeServiceFragment(this)
            val service = provider.getService()
            if (service != null && mBound) {
                onServiceDetached(service)
            }
        }
        super.onDestroy()
    }

    /** The definitive place where data from the service will be used to initialize the fragment. Only called once per bind, whether the fragment loads first or the service. */
    open fun onServiceBound(service: IHumlaService) { }

    open fun onServiceUnbound() { }

    /** If implemented, will register the returned observer to the service upon binding. */
    open fun getServiceObserver(): IHumlaObserver? {
        return null
    }

    private fun onServiceAttached(service: IHumlaService) {
        mBound = true
        val observer = getServiceObserver()
        if (observer != null) {
            service.registerObserver(observer)
        }

        onServiceBound(service)
    }

    private fun onServiceDetached(service: IHumlaService) {
        mBound = false
        val observer = getServiceObserver()
        if (observer != null) {
            service.unregisterObserver(observer)
        }

        onServiceUnbound()
    }

    fun setServiceBound(bound: Boolean) {
        // NPE-exact: the original dereferenced mServiceProvider unconditionally.
        val provider = mServiceProvider ?: throw NullPointerException("mServiceProvider")
        if (bound && !mBound) {
            val service = provider.getService() ?: throw NullPointerException("service")
            onServiceAttached(service)
        } else if (mBound && !bound) {
            val service = provider.getService() ?: throw NullPointerException("service")
            onServiceDetached(service)
        }
    }

    fun getService(): IMumlaService? {
        return mServiceProvider?.getService()
    }
}
