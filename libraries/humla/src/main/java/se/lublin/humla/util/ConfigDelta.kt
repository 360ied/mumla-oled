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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.humla.util

import java.util.Optional
import se.lublin.humla.model.Server

/**
 * Typed replacement for the pref→bundle→effect double dispatch (migration
 * slice 2): `MumlaService.onSharedPreferenceChanged` and
 * `HumlaService.configureExtras` collapsed into `apply(ConfigDelta)`. The
 * stringly `EXTRAS_*` bundle is dead.
 *
 * One-shot command — constructed per change, never stored. Nullable field =
 * unchanged; `apply()` skips nulls in fixed order (identity/reconnect keys →
 * audio pipeline params → live sends → SCO route). Returns whether a
 * reconnect is needed.
 *
 * Side-effect-only prefs (theme, overlay, TTS engine) travel as presence
 * signals for the app layer's appliers; `HumlaService.apply` ignores them.
 * `ttsEngine` is tri-state (unchanged vs system-default vs engine package),
 * mirroring `Settings.setTtsEngine`.
 */
data class CertRef(
    val data: ByteArray,
    val password: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CertRef) return false
        return data.contentEquals(other.data) && password == other.password
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + (password?.hashCode() ?: 0)
        return result
    }
}

data class TrustStoreRef(
    val path: String?,
    val password: String?,
    val format: String?,
)

data class ConfigDelta(
    val server: Server? = null,
    val certificate: CertRef? = null,
    val forceTcp: Boolean? = null,
    val trustStore: TrustStoreRef? = null,
    val transmitMode: Int? = null,
    val detectionThreshold: Float? = null,
    val amplitudeBoost: Float? = null,
    val inputQuality: Int? = null,
    val framesPerPacket: Int? = null,
    val halfDuplex: Boolean? = null,
    val bluetoothSco: Boolean? = null,
    val adaptiveLeveler: Boolean? = null,
    val accessTokens: List<String>? = null,
    val localMuteHistory: List<Int>? = null,
    val localIgnoreHistory: List<Int>? = null,
    val clientName: String? = null,
    val audioStream: Int? = null,
    val theme: String? = null,
    val overlayShown: Boolean? = null,
    val overlayPlacement: String? = null,
    val ttsEngine: Optional<String>? = null,
) {
    /** Whether any core (service-applied) field is set. */
    fun hasCore(): Boolean =
        server != null || certificate != null || forceTcp != null || trustStore != null ||
            transmitMode != null || detectionThreshold != null || amplitudeBoost != null ||
            inputQuality != null || framesPerPacket != null || halfDuplex != null ||
            bluetoothSco != null || adaptiveLeveler != null || accessTokens != null ||
            localMuteHistory != null || localIgnoreHistory != null || clientName != null ||
            audioStream != null

    /**
     * Whether any audio-affecting field besides the SCO request is set.
     * Detection threshold is excluded: it applies directly to the input mode
     * plus a live handler call, so it needs no pipeline reload.
     */
    fun hasOtherAudioKeys(): Boolean =
        amplitudeBoost != null || transmitMode != null || inputQuality != null ||
            audioStream != null || framesPerPacket != null || halfDuplex != null ||
            adaptiveLeveler != null

    /**
     * Java-friendly incremental builder (data-class `copy()` has no Java
     * defaults). Sets one field per preference branch, then [build].
     * Keep [hasCore] in sync when adding fields: every core field below
     * must also appear in [hasCore] (and in [hasOtherAudioKeys] when it
     * affects the audio pipeline).
     */
    class Builder {
        var server: Server? = null
        var certificate: CertRef? = null
        var forceTcp: Boolean? = null
        var trustStore: TrustStoreRef? = null
        var transmitMode: Int? = null
        var detectionThreshold: Float? = null
        var amplitudeBoost: Float? = null
        var inputQuality: Int? = null
        var framesPerPacket: Int? = null
        var halfDuplex: Boolean? = null
        var bluetoothSco: Boolean? = null
        var adaptiveLeveler: Boolean? = null
        var accessTokens: List<String>? = null
        var localMuteHistory: List<Int>? = null
        var localIgnoreHistory: List<Int>? = null
        var clientName: String? = null
        var audioStream: Int? = null
        var theme: String? = null
        var overlayShown: Boolean? = null
        var overlayPlacement: String? = null
        var ttsEngine: Optional<String>? = null

        fun build(): ConfigDelta = ConfigDelta(
            server = server,
            certificate = certificate,
            forceTcp = forceTcp,
            trustStore = trustStore,
            transmitMode = transmitMode,
            detectionThreshold = detectionThreshold,
            amplitudeBoost = amplitudeBoost,
            inputQuality = inputQuality,
            framesPerPacket = framesPerPacket,
            halfDuplex = halfDuplex,
            bluetoothSco = bluetoothSco,
            adaptiveLeveler = adaptiveLeveler,
            accessTokens = accessTokens,
            localMuteHistory = localMuteHistory,
            localIgnoreHistory = localIgnoreHistory,
            clientName = clientName,
            audioStream = audioStream,
            theme = theme,
            overlayShown = overlayShown,
            overlayPlacement = overlayPlacement,
            ttsEngine = ttsEngine,
        )
    }
}
