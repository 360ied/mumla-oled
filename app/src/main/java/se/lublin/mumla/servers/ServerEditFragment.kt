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

package se.lublin.mumla.servers

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import se.lublin.mumla.Settings

class ServerEditFragment : DialogFragment() {
    private var mNameEdit: EditText? = null
    private var mHostEdit: EditText? = null
    private var mPortEdit: EditText? = null
    private var mUsernameEdit: EditText? = null
    private var mPasswordEdit: EditText? = null

    private var mListener: ServerEditListener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        try {
            mListener = context as ServerEditListener
        } catch (e: ClassCastException) {
            throw ClassCastException(context.toString() + " must implement ServerEditListener!")
        }
    }

    override fun onStart() {
        super.onStart()
        // Override positive button to not automatically dismiss on press.
        // We can't accomplish this with AlertDialog.Builder.
        (dialog as AlertDialog).getButton(Dialog.BUTTON_POSITIVE).setOnClickListener {
            if (validate()) {
                val server = createServer()
                // NPE-exact: the original dereferenced mListener unconditionally.
                val listener = mListener ?: throw NullPointerException("mListener")
                listener.onServerEdited(getAction(), server)
                dismiss()
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        // NPE-exact: the original passed getActivity() straight through.
        val hostActivity = activity ?: throw NullPointerException("activity")
        val settings = Settings.getInstance(hostActivity)

        val actionName = when (getAction()) {
            Action.ADD_ACTION -> getString(R.string.add)
            Action.EDIT_ACTION -> getString(android.R.string.ok)
            Action.CONNECT_ACTION -> getString(R.string.connect)
        }

        val inflater = LayoutInflater.from(hostActivity)
        val view = inflater.inflate(R.layout.dialog_server_edit, null, false)

        val titleLabel = view.findViewById(R.id.server_edit_name_title) as TextView
        mNameEdit = view.findViewById(R.id.server_edit_name) as EditText
        mHostEdit = view.findViewById(R.id.server_edit_host) as EditText
        mPortEdit = view.findViewById(R.id.server_edit_port) as EditText
        mUsernameEdit = view.findViewById(R.id.server_edit_username) as EditText
        // NPE-exact: the original dereferenced mUsernameEdit unconditionally.
        (mUsernameEdit ?: throw NullPointerException("mUsernameEdit")).hint = settings.defaultUsername
        mPasswordEdit = view.findViewById(R.id.server_edit_password) as EditText

        val oldServer = getServer()
        if (oldServer != null) {
            (mNameEdit ?: throw NullPointerException("mNameEdit")).setText(oldServer.name)
            (mHostEdit ?: throw NullPointerException("mHostEdit")).setText(oldServer.host)
            if (oldServer.port != 0) {
                (mPortEdit ?: throw NullPointerException("mPortEdit")).setText(oldServer.port.toString())
            }
            (mUsernameEdit ?: throw NullPointerException("mUsernameEdit")).setText(oldServer.username)
            (mPasswordEdit ?: throw NullPointerException("mPasswordEdit")).setText(oldServer.password)
        }

        if (shouldIgnoreTitle()) {
            titleLabel.visibility = View.GONE
            (mNameEdit ?: throw NullPointerException("mNameEdit")).visibility = View.GONE
        }

        // Warn (without blocking) when this dialog was opened from a deep link whose URL
        // embedded a password. Never shown for manually added/edited servers.
        val urlPasswordWarning = view.findViewById(R.id.server_edit_url_password_warning) as TextView
        if (hasUrlPassword() && oldServer != null) {
            // The warning only applies while the field still holds the link's password.
            val urlPassword = oldServer.password
            urlPasswordWarning.visibility = View.VISIBLE
            (mPasswordEdit ?: throw NullPointerException("mPasswordEdit"))
                .addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                    override fun afterTextChanged(s: Editable?) {
                        urlPasswordWarning.visibility =
                            if (s.toString() == urlPassword) View.VISIBLE else View.GONE
                    }
                })
        } else {
            urlPasswordWarning.visibility = View.GONE
        }

        return MaterialAlertDialogBuilder(requireActivity())
            .setPositiveButton(actionName, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setView(view)
            .create()
    }

    fun createServer(): Server {
        // NPE-exact: the original dereferenced every field unconditionally.
        val nameEdit = mNameEdit ?: throw NullPointerException("mNameEdit")
        val hostEdit = mHostEdit ?: throw NullPointerException("mHostEdit")
        val portEdit = mPortEdit ?: throw NullPointerException("mPortEdit")
        val usernameEdit = mUsernameEdit ?: throw NullPointerException("mUsernameEdit")
        val passwordEdit = mPasswordEdit ?: throw NullPointerException("mPasswordEdit")

        val name = nameEdit.text.toString().trim()
        val host = hostEdit.text.toString().trim()

        val port: Int
        try {
            port = Integer.parseInt(portEdit.text.toString())
        } catch (ex: NumberFormatException) {
            // Setting 0, meaning that port isn't configured. Consumers of
            // Server.getPort() will have to deal with that. Like displaying
            // nothing, looking up SRV record, using Constants.DEFAULT_PORT.
            return buildServer(name, host, 0, usernameEdit, passwordEdit)
        }

        return buildServer(name, host, port, usernameEdit, passwordEdit)
    }

    private fun buildServer(
        name: String,
        host: String,
        port: Int,
        usernameEdit: EditText,
        passwordEdit: EditText
    ): Server {
        var username = usernameEdit.text.toString().trim()
        val password = passwordEdit.text.toString()

        if (username == "") {
            username = usernameEdit.hint.toString()
        }

        // Inherit database ID of provided server.
        val existing = getServer()
        val id = if (existing != null) existing.id else -1

        return Server(id, name, host, port, username, password)
    }

    /**
     * Checks all fields in this ServerEditFragment for validity.
     * If an invalid field is found, an error is shown and false is returned.
     * @return true if the inputted values are valid, false otherwise.
     */
    fun validate(): Boolean {
        // NPE-exact: the original dereferenced both fields unconditionally.
        val hostEdit = mHostEdit ?: throw NullPointerException("mHostEdit")
        val portEdit = mPortEdit ?: throw NullPointerException("mPortEdit")
        if (hostEdit.text.length == 0) {
            hostEdit.error = getString(R.string.invalid_host)
            return false
        } else if (portEdit.text.length > 0) {
            try {
                val port = Integer.parseInt(portEdit.text.toString())
                if (port < 1 || port > 65535) {
                    portEdit.error = getString(R.string.invalid_port_range)
                    return false
                }
            } catch (nfe: NumberFormatException) {
                portEdit.error = getString(R.string.invalid_port_range)
                return false
            }
        }
        return true
    }

    private fun getServer(): Server? {
        // NPE-exact: the original dereferenced getArguments() unconditionally.
        val args = arguments ?: throw NullPointerException("arguments")
        return args.getParcelable(ARGUMENT_SERVER, Server::class.java)
    }

    private fun getAction(): Action {
        // NPE-exact: the original dereferenced getArguments() unconditionally.
        val args = arguments ?: throw NullPointerException("arguments")
        return Action.values()[args.getInt(ARGUMENT_ACTION)]
    }

    private fun shouldIgnoreTitle(): Boolean {
        // NPE-exact: the original dereferenced getArguments() unconditionally.
        val args = arguments ?: throw NullPointerException("arguments")
        return args.getBoolean(ARGUMENT_IGNORE_TITLE)
    }

    private fun hasUrlPassword(): Boolean {
        // NPE-exact: the original dereferenced getArguments() unconditionally.
        val args = arguments ?: throw NullPointerException("arguments")
        return args.getBoolean(ARGUMENT_URL_PASSWORD, false)
    }

    interface ServerEditListener {
        fun onServerEdited(action: Action, server: Server)
    }

    enum class Action {
        CONNECT_ACTION,
        EDIT_ACTION,
        ADD_ACTION
    }

    companion object {
        private const val ARGUMENT_SERVER = "server"
        private const val ARGUMENT_ACTION = "action"
        private const val ARGUMENT_IGNORE_TITLE = "ignore_title"
        private const val ARGUMENT_URL_PASSWORD = "url_password"

        /**
         * Creates a new [ServerEditFragment] dialog. Results will be delivered to the parent
         * activity via [ServerEditListener].
         * @param server Optional, if set will populate the fragment with data from the server.
         * @param action The action the fragment is performing (i.e. Add, Edit)
         * @param ignoreTitle If true, don't show fields related to the server title (useful for quick
         *                    connect dialogs)
         */
        @JvmStatic
        fun createServerEditDialog(
            server: Server?,
            action: Action,
            ignoreTitle: Boolean
        ): DialogFragment {
            return createServerEditDialog(server, action, ignoreTitle, false)
        }

        /**
         * Creates a new [ServerEditFragment] dialog. Results will be delivered to the parent
         * activity via [ServerEditListener].
         * @param server Optional, if set will populate the fragment with data from the server.
         * @param action The action the fragment is performing (i.e. Add, Edit)
         * @param ignoreTitle If true, don't show fields related to the server title (useful for quick
         *                    connect dialogs)
         * @param urlPassword If true, the server came from a deep link whose URL embedded a password;
         *                    an inline warning row is shown (the dialog still only warns, it does not
         *                    block or strip the password).
         */
        @JvmStatic
        fun createServerEditDialog(
            server: Server?,
            action: Action,
            ignoreTitle: Boolean,
            urlPassword: Boolean
        ): DialogFragment {
            val args = Bundle()
            args.putParcelable(ARGUMENT_SERVER, server)
            args.putInt(ARGUMENT_ACTION, action.ordinal)
            args.putBoolean(ARGUMENT_IGNORE_TITLE, ignoreTitle)
            args.putBoolean(ARGUMENT_URL_PASSWORD, urlPassword)
            val fragment = ServerEditFragment()
            fragment.arguments = args
            return fragment
        }
    }
}
