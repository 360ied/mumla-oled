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

package se.lublin.mumla.channel

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import se.lublin.humla.Constants
import se.lublin.humla.IHumlaService
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.mumla.R
import se.lublin.mumla.util.HumlaServiceFragment

/**
 * A fragment that displays known information from the remote server.
 * Created by andrew on 28/08/13.
 */
class ServerInfoFragment : HumlaServiceFragment() {

    private val mExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val mHandler = Handler(Looper.getMainLooper())
    private var mPollFuture: ScheduledFuture<*>? = null

    private var mProtocolView: TextView? = null
    private var mOSVersionView: TextView? = null
    private var mTCPLatencyView: TextView? = null
    private var mUDPLatencyView: TextView? = null
    private var mHostView: TextView? = null
    private var mCodecView: TextView? = null
    private var mMaxBandwidthView: TextView? = null
    private var mCurrentBandwidthView: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_server_info, container, false)
        // Nullable casts: the fields are TextView? and use sites NPE-guard;
        // a non-null `as` would throw TypeCastException instead of NPE here.
        mProtocolView = view.findViewById(R.id.server_info_protocol) as TextView?
        mOSVersionView = view.findViewById(R.id.server_info_os_version) as TextView?
        mTCPLatencyView = view.findViewById(R.id.server_info_tcp_latency) as TextView?
        mUDPLatencyView = view.findViewById(R.id.server_info_udp_latency) as TextView?
        mHostView = view.findViewById(R.id.server_info_host) as TextView?
        mMaxBandwidthView = view.findViewById(R.id.server_info_max_bandwidth) as TextView?
        mCurrentBandwidthView = view.findViewById(R.id.server_info_current_bandwidth) as TextView?
        mCodecView = view.findViewById(R.id.server_info_codec) as TextView?
        return view
    }

    /**
     * Updates the info from the service.
     */
    @Throws(RemoteException::class)
    fun updateData() {
        val service = getService()
        if (service == null || !service.isConnected()) {
            return
        }

        // NPE-exact: the original dereferenced every view unconditionally.
        val protocolView = mProtocolView ?: throw NullPointerException("mProtocolView")
        val osVersionView = mOSVersionView ?: throw NullPointerException("mOSVersionView")
        val tcpLatencyView = mTCPLatencyView ?: throw NullPointerException("mTCPLatencyView")
        val udpLatencyView = mUDPLatencyView ?: throw NullPointerException("mUDPLatencyView")
        val hostView = mHostView ?: throw NullPointerException("mHostView")
        val codecView = mCodecView ?: throw NullPointerException("mCodecView")
        val maxBandwidthView = mMaxBandwidthView ?: throw NullPointerException("mMaxBandwidthView")
        val currentBandwidthView = mCurrentBandwidthView ?: throw NullPointerException("mCurrentBandwidthView")

        val session = service.HumlaSession()

        var release = session.getServerRelease()
        if (release == null || release.isEmpty()) {
            val v2 = session.getServerVersionV2()
            release = if (v2 != 0L) {
                Constants.formatVersion(v2)
            } else {
                Constants.formatLegacyVersion(session.getServerVersion())
            }
        }
        protocolView.text = getString(R.string.server_info_protocol, release)
        osVersionView.text = getString(R.string.server_info_version, session.getServerOSName(), session.getServerOSVersion())
        tcpLatencyView.text = getString(R.string.server_info_latency, session.getTCPLatency().toFloat() * Math.pow(10.0, -3.0))
        udpLatencyView.text = getString(R.string.server_info_latency, session.getUDPLatency().toFloat() * Math.pow(10.0, -3.0))
        // TODO SRV note also getHost,Port?
        // NPE-exact: the original dereferenced getTargetServer() unconditionally.
        val targetServer = service.getTargetServer() ?: throw NullPointerException("targetServer")
        hostView.text = getString(
            R.string.server_info_host,
            targetServer.getSrvHost(),
            targetServer.getSrvPort()
        )

        var codecName = "<null>"
        val codecType = session.getCodec()
        if (codecType != null) {
            codecName = when (codecType) {
                HumlaUDPMessageType.UDPVoiceOpus -> "Opus"
                HumlaUDPMessageType.UDPVoiceCELTBeta -> "CELT 0.11.0 (Unsupported)"
                HumlaUDPMessageType.UDPVoiceCELTAlpha -> "CELT 0.7.0 (Unsupported)"
                HumlaUDPMessageType.UDPVoiceSpeex -> "Speex (Unsupported)"
                else -> "???"
            }
        }

        maxBandwidthView.text = getString(R.string.server_info_max_bandwidth, session.getMaxBandwidth().toFloat() / 1000f)
        currentBandwidthView.text = getString(R.string.server_info_current_bandwidth, session.getCurrentBandwidth().toFloat() / 1000f)
        codecView.text = getString(R.string.server_info_codec, codecName)
    }

    override fun onServiceBound(service: IHumlaService) {
        // wow this is ugly
        mPollFuture = mExecutorService.scheduleAtFixedRate({
            mHandler.post {
                try {
                    if (isVisible) updateData()
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            }
        }, 0, POLL_RATE.toLong(), TimeUnit.MILLISECONDS)
    }

    override fun onServiceUnbound() {
        val pollFuture = mPollFuture
        if (pollFuture != null) {
            pollFuture.cancel(true)
            mPollFuture = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mExecutorService.shutdownNow()
    }

    companion object {
        private const val POLL_RATE = 1000
    }
}
