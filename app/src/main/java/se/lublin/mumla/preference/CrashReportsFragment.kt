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

package se.lublin.mumla.preference

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.DateFormat
import java.util.Date
import se.lublin.humla.audio.NativeCrashTest
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import se.lublin.mumla.R
import se.lublin.mumla.util.CrashReporter
import se.lublin.mumla.util.CrashReportStore

/**
 * Crash reports screen: list (timestamp + reason), per-report View / Share /
 * Delete, Delete-all, plus hidden crash-test buttons (one per capture path).
 *
 * Single inheritance rules out extending HumlaServiceFragment, so service
 * state is reached by composition via the host SettingsActivity.
 */
class CrashReportsFragment : MumlaPreferenceFragment() {
    private var observer: HumlaObserver? = null
    private val anrHandler by lazy { Handler(Looper.getMainLooper()) }
    private var detailDialog: AlertDialog? = null
    private var confirmDialog: AlertDialog? = null

    override fun onCreateMumlaPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_crash_reports, rootKey)
        findPreference<Preference>("crash_delete_all")?.setOnPreferenceClickListener {
            try {
                val store = CrashReporter.store(requireContext())
                val ok = store.deleteAll()
                clearStagedShares()
                refreshList()
                Toast.makeText(
                    requireContext(),
                    if (ok) R.string.crash_all_deleted else R.string.crash_load_failed,
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to delete crash reports", e)
                try {
                    Toast.makeText(requireContext(), R.string.crash_load_failed, Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                }
            }
            true
        }
        wireTestButtons()
        // Warm the native loader off the main thread: the first
        // isAvailable() call dlopens humlaaudio, which is disk I/O.
        // Later refreshServiceState() calls on the UI thread are then cheap.
        try {
            Thread({
                try {
                    NativeCrashTest.isAvailable()
                } catch (_: Throwable) {
                }
            }, "crash-native-warm").start()
        } catch (_: Throwable) {
        }
        refreshList()
        refreshServiceState()
    }

    override fun onStart() {
        super.onStart()
        registerObserver()
    }

    override fun onStop() {
        unregisterObserver()
        super.onStop()
    }

    override fun onPause() {
        try {
            detailDialog?.dismiss()
        } catch (_: Throwable) {
        }
        detailDialog = null
        try {
            confirmDialog?.dismiss()
        } catch (_: Throwable) {
        }
        confirmDialog = null
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        // Fail-closed: host flag gates, fragment argument is a hint only
        // (arguments survive process-death recreation, so the arg alone must
        // never gate; process death therefore relocks).
        refreshServiceState()
        refreshList()
        if (observer == null) {
            registerObserver()
        }
    }

    fun onServiceStateChanged() {
        if (!isAdded) {
            return
        }
        // Late bind: onStart registration can run before the host bind
        // completes, so retry here when the connection arrives.
        if (observer == null) {
            registerObserver()
        }
        refreshServiceState()
    }

    private fun host(): SettingsActivity? {
        return activity as? SettingsActivity
    }

    private fun registerObserver() {
        try {
            val svc = host()?.getCrashService() ?: return
            if (observer != null) {
                return
            }
            val obs = object : HumlaObserver() {
                override fun onConnected() {
                    activity?.runOnUiThread { refreshServiceState() }
                }

                override fun onDisconnected(e: HumlaException?) {
                    activity?.runOnUiThread { refreshServiceState() }
                }
            }
            observer = obs
            svc.registerObserver(obs)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to register crash service observer", e)
        }
    }

    private fun unregisterObserver() {
        try {
            val obs = observer ?: return
            observer = null
            host()?.getCrashService()?.unregisterObserver(obs)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to unregister crash service observer", e)
        }
    }

    private fun refreshList() {
        try {
            val category = findPreference<PreferenceCategory>("crash_list_category") ?: return
            category.removeAll()
            val store = CrashReporter.store(requireContext())
            val entries = store.list()
            if (entries.isEmpty()) {
                val empty = Preference(requireContext())
                empty.title = getString(R.string.crash_reports_empty)
                empty.isSelectable = false
                category.addPreference(empty)
            } else {
                val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                for (entry in entries) {
                    val pref = Preference(requireContext())
                    val ts = entry.exitTimestamp ?: entry.file.lastModified()
                    val whenStr = try {
                        fmt.format(Date(ts))
                    } catch (_: Throwable) {
                        entry.id
                    }
                    pref.title = getString(R.string.crash_row, whenStr, humanizeReason(entry.reason))
                    pref.summary = entry.id
                    pref.setOnPreferenceClickListener {
                        showDetail(entry)
                        true
                    }
                    category.addPreference(pref)
                }
            }
            // Crash-test buttons are always visible; safety comes from the
            // disconnected-only enablement plus the per-button confirmation.
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to refresh crash list", e)
        }
    }

    private fun refreshServiceState() {
        try {
            val connected = host()?.isCrashConnected() == true
            val bound = host()?.isCrashBound() == true
            val available = try {
                NativeCrashTest.isAvailable()
            } catch (_: Throwable) {
                false
            }
            val enabled = bound && !connected
            for (key in arrayOf("crash_test_java", "crash_test_native", "crash_test_anr")) {
                val pref = findPreference<Preference>(key) ?: continue
                pref.isEnabled = enabled && (key != "crash_test_native" || available)
                pref.summary = when {
                    !bound -> getString(R.string.crash_test_unbound)
                    connected -> getString(R.string.crash_test_disabled_call)
                    key == "crash_test_native" && !available ->
                        getString(R.string.crash_test_disabled_native)
                    else -> getString(R.string.crash_test_summary)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to refresh crash test state", e)
        }
    }

    private fun showDetail(entry: CrashReportStore.Entry) {
        try {
            val store = CrashReporter.store(requireContext())
            val raw = store.read(entry) ?: run {
                Toast.makeText(requireContext(), R.string.crash_load_failed, Toast.LENGTH_SHORT).show()
                return
            }
            val payload = CrashReporter.renderSharePayload(raw) ?: run {
                Toast.makeText(requireContext(), R.string.crash_load_failed, Toast.LENGTH_SHORT).show()
                return
            }
            val (summary, txt) = payload
            val (preview, omitted) = CrashReportStore.previewForDisplay(txt)
            // Message-only content: AlertDialog's built-in ScrollView keeps
            // the button bar on-screen. A custom setView() does not work —
            // AlertController re-adds it with WRAP_CONTENT, so huge traces
            // measure full-height and push Share/Delete/Close off-screen.
            val body = StringBuilder()
            body.append(summary)
            body.append("\n\n").append(getString(R.string.crash_public_warning))
            body.append("\n\n").append(preview)
            if (omitted > 0) {
                body.append("\n\n").append(getString(R.string.crash_preview_omitted, omitted))
            }
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(humanizeReason(entry.reason))
                .setMessage(body.toString())
                .setPositiveButton(R.string.share) { dialog, _ ->
                    dialog.dismiss()
                    shareReport(entry, summary, txt)
                }
                .setNegativeButton(R.string.delete) { dialog, _ ->
                    dialog.dismiss()
                    val deleted = try {
                        store.delete(entry)
                    } catch (_: Throwable) {
                        false
                    }
                    deleteStagedSharesFor(entry.id)
                    refreshList()
                    Toast.makeText(
                        requireContext(),
                        if (deleted) R.string.crash_deleted else R.string.crash_load_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .setNeutralButton(R.string.close, null)
                .show()
            detailDialog = dialog
            // The message view is not selectable by default; allow copy.
            try {
                dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
            } catch (_: Throwable) {
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to show crash detail", e)
            try {
                Toast.makeText(requireContext(), R.string.crash_load_failed, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }

    private fun shareReport(entry: CrashReportStore.Entry, summary: String, txt: String) {
        // Stage under cacheDir (the store dir stays JSON-only) with a unique
        // name so repeat shares never collide. Staged copies are pruned on
        // share and cleared by Delete-all (see clearStagedShares).
        val ctx = try {
            requireContext()
        } catch (e: Throwable) {
            Log.w(TAG, "Share without context", e)
            return
        }
        val dir = try {
            File(ctx.cacheDir, "crash-shares").also { if (!it.isDirectory) it.mkdirs() }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not stage share file", e)
            try {
                Toast.makeText(ctx, R.string.crash_share_failed, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
            return
        }
        val safeId = CrashReportStore.sanitizeId(entry.id, 64)
        val txtFile = File(dir, "$safeId-${System.currentTimeMillis()}.txt")
        try {
            txtFile.writeText(txt, Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not write share file", e)
            try {
                Toast.makeText(ctx, R.string.crash_share_failed, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
            return
        }
        pruneStagedShares()
        val authority = ctx.packageName + ".crashreports"
        val uri = try {
            FileProvider.getUriForFile(ctx, authority, txtFile)
        } catch (e: Throwable) {
            Log.w(TAG, "FileProvider share failed; check crash_report_paths.xml", e)
            try {
                Toast.makeText(ctx, R.string.crash_share_failed, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
            return
        }
        try {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.putExtra(Intent.EXTRA_TEXT, summary)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, getString(R.string.share)))
        } catch (e: Throwable) {
            Log.w(TAG, "Share intent failed", e)
            try {
                Toast.makeText(ctx, R.string.crash_share_failed, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }

    private fun wireTestButtons() {
        findPreference<Preference>("crash_test_java")?.setOnPreferenceClickListener {
            confirmAndRun("java")
            true
        }
        findPreference<Preference>("crash_test_native")?.setOnPreferenceClickListener {
            confirmAndRun("native")
            true
        }
        findPreference<Preference>("crash_test_anr")?.setOnPreferenceClickListener {
            confirmAndRun("anr")
            true
        }
    }

    private fun confirmAndRun(kind: String) {
        // Re-check at click time (TOCTOU): neither a call connected after the
        // screen opened nor one connected while the dialog is open may die —
        // the positive button re-checks immediately before acting.
        if (host()?.isCrashConnected() == true) {
            refreshServiceState()
            Toast.makeText(requireContext(), R.string.crash_test_disabled_call, Toast.LENGTH_SHORT).show()
            return
        }
        if (kind == "native") {
            val available = try {
                NativeCrashTest.isAvailable()
            } catch (_: Throwable) {
                false
            }
            if (!available) {
                refreshServiceState()
                return
            }
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.crash_confirm)
            .setPositiveButton(R.string.confirm) { d, _ ->
                d.dismiss()
                // Service can transition while the dialog is open
                // (auto-reconnect/backoff); abort instead of killing a call.
                if (host()?.isCrashConnected() == true) {
                    refreshServiceState()
                    Toast.makeText(requireContext(), R.string.crash_test_disabled_call, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                runTest(kind)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        confirmDialog = dialog
        dialog.show()
    }

    private fun runTest(kind: String) {
        // Dismiss-before-act is handled by the positive-button dismiss above;
        // the ANR dialog would otherwise overlay a frozen confirmation and
        // onPause dismissal could never run.
        when (kind) {
            "java" -> throw RuntimeException("Crash-test Java throw")
            "native" -> try {
                NativeCrashTest.nativeCrash()
            } catch (e: Throwable) {
                // Missing symbol on a stale .so must not masquerade as the
                // wrong capture path: report and re-gate the buttons.
                Log.w(TAG, "Native crash hook failed", e)
                refreshServiceState()
                try {
                    Toast.makeText(requireContext(), R.string.crash_test_disabled_native, Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) {
                }
            }
            // Well past the ~5s ANR threshold to guarantee the system dialog.
            "anr" -> anrHandler.post {
                try {
                    Thread.sleep(ANR_FREEZE_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            else -> Log.w(TAG, "Unknown crash-test kind: $kind")
        }
    }

    private fun humanizeReason(reason: String): String {
        return when (reason) {
            "REASON_CRASH" -> getString(R.string.crash_reason_crash)
            "REASON_CRASH_NATIVE" -> getString(R.string.crash_reason_native)
            "REASON_ANR" -> getString(R.string.crash_reason_anr)
            else -> getString(R.string.crash_reason_unknown)
        }
    }

    private fun deleteStagedSharesFor(entryId: String) {
        try {
            val dir = File(requireContext().cacheDir, "crash-shares")
            if (!dir.isDirectory) return
            val prefix = CrashReportStore.sanitizeId(entryId, 64)
            dir.listFiles { f -> f.isFile && f.name.startsWith("$prefix-") }?.forEach {
                try {
                    it.delete()
                } catch (_: Throwable) {
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not purge staged shares", e)
        }
    }

    private fun clearStagedShares() {
        try {
            val dir = File(requireContext().cacheDir, "crash-shares")
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach {
                try {
                    if (it.isFile) it.delete()
                } catch (_: Throwable) {
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not clear staged shares", e)
        }
    }

    private fun pruneStagedShares() {
        try {
            val dir = File(requireContext().cacheDir, "crash-shares")
            if (!dir.isDirectory) return
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() } ?: return
            for (i in CrashReportStore.MAX_FILES until files.size) {
                try {
                    files[i].delete()
                } catch (_: Throwable) {
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Could not prune staged shares", e)
        }
    }

    companion object {
        private val TAG: String = CrashReportsFragment::class.java.simpleName

        private const val ANR_FREEZE_MS = 15_000L
    }
}
