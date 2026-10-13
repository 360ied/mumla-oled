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

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import se.lublin.mumla.R
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaRoomDatabase
import se.lublin.mumla.util.HumlaServiceFragment

class AccessTokenFragment : HumlaServiceFragment() {
    interface AccessTokenListener {
        fun onAccessTokenAdded(serverId: Long, token: String)

        fun onAccessTokenRemoved(serverId: Long, token: String)
    }

    private var mTokens: MutableList<String>? = null

    private var mTokenList: ListView? = null
    private var mTokenAdapter: TokenAdapter? = null
    private var mTokenField: EditText? = null

    private var mDatabase: MumlaDatabase? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)

        mTokens = ArrayList(getAccessTokens())
        mTokenAdapter = TokenAdapter(context, mTokens ?: throw NullPointerException("mTokens"))
        mDatabase = MumlaRoomDatabase.getInstance(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_tokens, container, false)

        // NPE-exact: the original dereferenced every lookup unconditionally.
        val tokenList = view.findViewById(R.id.tokenList) as ListView?
            ?: throw NullPointerException("tokenList")
        mTokenList = tokenList
        tokenList.adapter = mTokenAdapter

        val tokenField = view.findViewById(R.id.tokenField) as EditText?
            ?: throw NullPointerException("tokenField")
        mTokenField = tokenField
        tokenField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                addToken()
                true
            } else {
                false
            }
        }

        val addButton = view.findViewById(R.id.tokenAddButton) as ImageButton?
            ?: throw NullPointerException("tokenAddButton")
        addButton.setOnClickListener { addToken() }

        return view
    }

    private fun addToken() {
        // NPE-exact: the original dereferenced every field unconditionally.
        val tokenField = mTokenField ?: throw NullPointerException("mTokenField")
        val tokens = mTokens ?: throw NullPointerException("mTokens")
        val adapter = mTokenAdapter ?: throw NullPointerException("mTokenAdapter")
        val tokenList = mTokenList ?: throw NullPointerException("mTokenList")
        val database = mDatabase ?: throw NullPointerException("mDatabase")

        val tokenText = tokenField.text.toString().trim()

        if (tokenText == "") {
            return
        }

        tokenField.setText("")

        Log.i(TAG, "Adding a token")

        tokens.add(tokenText)
        adapter.notifyDataSetChanged()

        tokenList.smoothScrollToPosition(tokens.size - 1)
        database.addAccessToken(getServerId(), tokenText)
        val service = getService()
        if (service != null && service.isConnected()) {
            service.HumlaSession().sendAccessTokens(tokens)
        }
    }

    private fun getServerId(): Long {
        // NPE-exact: the original dereferenced getArguments() unconditionally.
        val args = arguments ?: throw NullPointerException("arguments")
        return args.getLong("server")
    }

    private fun getAccessTokens(): List<String> {
        // NPE-exact: new ArrayList(null) threw NPE in the original.
        val args = arguments ?: throw NullPointerException("arguments")
        return args.getStringArrayList("access_tokens") ?: throw NullPointerException("access_tokens")
    }

    private inner class TokenAdapter(context: Context, objects: List<String>) :
        ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, objects) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            var view = convertView
            if (convertView == null) {
                // NPE-exact: the original dereferenced getActivity() unconditionally.
                val host = activity ?: throw NullPointerException("activity")
                view = host.layoutInflater.inflate(R.layout.token_row, parent, false)
            }
            // NPE-exact: inflate never returns null here; the original dereferenced view.
            val row = view ?: throw NullPointerException("view")

            // NPE-exact: the original passed getItem straight into setText/removeAccessToken.
            val token = getItem(position) ?: throw NullPointerException("item")

            val title = row.findViewById(R.id.tokenItemTitle) as TextView?
                ?: throw NullPointerException("tokenItemTitle")
            title.text = token

            val deleteButton = row.findViewById(R.id.tokenItemDelete) as ImageButton?
                ?: throw NullPointerException("tokenItemDelete")
            deleteButton.setOnClickListener {
                // NPE-exact: the original dereferenced every field unconditionally.
                val tokens = mTokens ?: throw NullPointerException("mTokens")
                val database = mDatabase ?: throw NullPointerException("mDatabase")
                tokens.removeAt(position)
                notifyDataSetChanged()
                database.removeAccessToken(getServerId(), token)
                val service = getService()
                if (service != null && service.isConnected()) {
                    service.HumlaSession().sendAccessTokens(tokens)
                }
            }

            return row
        }
    }

    companion object {
        private val TAG: String = AccessTokenFragment::class.java.name
    }
}
