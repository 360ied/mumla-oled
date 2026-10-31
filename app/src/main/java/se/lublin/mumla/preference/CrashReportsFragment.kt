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

    override fun onCreateMumlaPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_crash_reports, rootKey)
        findPreference<Preference>("crash_delete_all")?.setOnPreferenceClickListener {
            val store = CrashReporter.store(requireContext())
            store.deleteAll()
            refreshList()
            Toast.makeText(requireContext(), R.string.crash_all_deleted, Toast.LENGTH_SHORT).show()
            true
        }
        wireTestButtons()
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

    override fun onResume() {
        super.onResume()
        // Fail-closed: host flag gates, fragment argument is a hint only
        // (arguments survive process-death recreation, so the arg alone must
        // never gate; process death therefore relocks).
        refreshServiceState()
        refreshList()
    }

    fun onServiceStateChanged() {
        if (!isAdded) {
            return
        }
        refreshServiceState()
    }

    private fun host(): SettingsActivity? {
        return activity as? SettingsActivity
    }

    private fun isUnlocked(): Boolean {
        // Single gate with fail-closed host re-validation: no host flag, no buttons.
        return host()?.isCrashTestUnlocked() == true
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
                    pref.title = getString(R.string.crash_row, whenStr, entry.reason)
                    pref.summary = entry.id
                    pref.setOnPreferenceClickListener {
                        showDetail(entry)
                        true
                    }
                    category.addPreference(pref)
                }
            }
            // Test category visibility follows the session unlock.
            val testCategory = findPreference<PreferenceCategory>("crash_test")
            if (testCategory != null) {
                testCategory.isVisible = isUnlocked()
            }
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
                    !bound -> getString(R.string.crash_test_summary)
                    connected -> getString(R.string.crash_test_disabled_call)
                    key == "crash_test_native" && !available ->
                        getString(R.string.crash_test_disabled_native)
                    else -> getString(R.string.crash_test_summary)
                }
            }
            val testCategory = findPreference<PreferenceCategory>("crash_test")
            if (testCategory != null) {
                testCategory.isVisible = isUnlocked()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to refresh crash test state", e)
        }
    }

    private fun showDetail(entry: CrashReportStore.Entry) {
        try {
            val store = CrashReporter.store(requireContext())
            val raw = store.read(entry) ?: return
            val payload = CrashReporter.renderSharePayload(raw) ?: return
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
                .setTitle(entry.reason)
                .setMessage(body.toString())
                .setPositiveButton(R.string.share) { dialog, _ ->
                    dialog.dismiss()
                    shareReport(entry, summary, txt)
                }
                .setNegativeButton(R.string.delete) { dialog, _ ->
                    dialog.dismiss()
                    store.delete(entry)
                    refreshList()
                    Toast.makeText(requireContext(), R.string.crash_deleted, Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton(R.string.close, null)
                .show()
            // The message view is not selectable by default; allow copy.
            try {
                dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
            } catch (_: Throwable) {
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to show crash detail", e)
            try {
                Toast.makeText(requireContext(), R.string.crash_reports_empty, Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }

    private fun shareReport(entry: CrashReportStore.Entry, summary: String, txt: String) {
        // Stage under cacheDir (JSON-only store dir) with a unique name so
        // repeat shares never collide and Delete-all stays JSON-scoped
        // (deleteAll also removes *.txt as a backstop).
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
            return
        }
        val safeId = entry.id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64).ifEmpty { "report" }
        val txtFile = File(dir, "$safeId-${System.currentTimeMillis()}.txt")
        try {
            txtFile.writeText(txt, Charsets.UTF_8)
        } catch (e: Throwable) {
            Log.w(TAG, "Could not write share file", e)
            return
        }
        val authority = ctx.packageName + ".crashreports"
        val uri = try {
            FileProvider.getUriForFile(ctx, authority, txtFile)
        } catch (e: Throwable) {
            Log.w(TAG, "FileProvider share failed; check crash_report_paths.xml", e)
            try {
                Toast.makeText(ctx, R.string.crash_reports_empty, Toast.LENGTH_SHORT).show()
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
        // screen opened nor one connected between Continue and the crash may die.
        // Fail-closed host re-validation: no host flag, no buttons.
        if (!isUnlocked()) {
            refreshList()
            return
        }
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
                runTest(kind)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun runTest(kind: String) {
        // Dismiss-before-act is handled by the positive-button dismiss above;
        // the ANR dialog would otherwise overlay a frozen confirmation and
        // onPause dismissal could never run.
        when (kind) {
            "java" -> throw RuntimeException("Crash-test Java throw")
            "native" -> NativeCrashTest.nativeCrash()
            // Well past the ~5s ANR threshold to guarantee the system dialog.
            "anr" -> Handler(Looper.getMainLooper()).post {
                try {
                    Thread.sleep(ANR_FREEZE_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    companion object {
        private val TAG: String = CrashReportsFragment::class.java.simpleName

        private const val ANR_FREEZE_MS = 15_000L
    }
}
