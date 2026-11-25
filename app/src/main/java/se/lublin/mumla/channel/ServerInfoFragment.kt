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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import se.lublin.mumla.R
import se.lublin.mumla.app.MumlaViewModelFactory

/**
 * A fragment that displays known information from the remote server.
 * Created by andrew on 28/08/13.
 */
class ServerInfoFragment : Fragment() {

    private val viewModel: ServerInfoViewModel by viewModels {
        MumlaViewModelFactory(requireActivity().application)
    }

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
        mProtocolView = view.findViewById(R.id.server_info_protocol)
        mOSVersionView = view.findViewById(R.id.server_info_os_version)
        mTCPLatencyView = view.findViewById(R.id.server_info_tcp_latency)
        mUDPLatencyView = view.findViewById(R.id.server_info_udp_latency)
        mHostView = view.findViewById(R.id.server_info_host)
        mMaxBandwidthView = view.findViewById(R.id.server_info_max_bandwidth)
        mCurrentBandwidthView = view.findViewById(R.id.server_info_current_bandwidth)
        mCodecView = view.findViewById(R.id.server_info_codec)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    /**
     * Renders one state emission. Values that are not (yet) known keep the
     * previous text, exactly like the old poll's skip-and-retry: UDP latency
     * stays unknown permanently on TCP-only or UDP-blocked connections.
     */
    private fun render(state: ServerInfoUiState) {
        if (!state.connected) {
            return
        }
        // View-teardown race: an emission can land after the views were nulled.
        val protocolView = mProtocolView ?: return
        val osVersionView = mOSVersionView ?: return
        val tcpLatencyView = mTCPLatencyView ?: return
        val udpLatencyView = mUDPLatencyView ?: return
        val hostView = mHostView ?: return
        val codecView = mCodecView ?: return
        val maxBandwidthView = mMaxBandwidthView ?: return
        val currentBandwidthView = mCurrentBandwidthView ?: return

        state.release?.let { release ->
            protocolView.text = getString(R.string.server_info_protocol, release)
        }
        osVersionView.text = getString(R.string.server_info_version, state.osName, state.osVersion)
        setLatencyText(tcpLatencyView, state.tcpLatencyMicros)
        setLatencyText(udpLatencyView, state.udpLatencyMicros)
        val host = state.host
        val port = state.port
        if (host != null && port != null) {
            hostView.text = getString(R.string.server_info_host, host, port)
        }
        maxBandwidthView.text =
            getString(R.string.server_info_max_bandwidth, (state.maxBandwidth ?: -1).toFloat() / 1000f)
        state.currentBandwidth?.let { current ->
            currentBandwidthView.text =
                getString(R.string.server_info_current_bandwidth, current.toFloat() / 1000f)
        }
        codecView.text = getString(R.string.server_info_codec, state.codecName ?: "<null>")
    }

    private fun setLatencyText(view: TextView, latencyMicros: Long?) {
        view.text = if (latencyMicros == null) {
            getString(R.string.server_info_latency_unknown)
        } else {
            getString(R.string.server_info_latency, latencyMicros.toFloat() * 1e-3f)
        }
    }

    override fun onDestroyView() {
        mProtocolView = null
        mOSVersionView = null
        mTCPLatencyView = null
        mUDPLatencyView = null
        mHostView = null
        mCodecView = null
        mMaxBandwidthView = null
        mCurrentBandwidthView = null
        super.onDestroyView()
    }
}
