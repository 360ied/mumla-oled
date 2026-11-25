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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import se.lublin.mumla.R
import se.lublin.mumla.app.MumlaViewModelFactory

class AccessTokenFragment : Fragment() {

    private val viewModel: AccessTokenViewModel by viewModels {
        MumlaViewModelFactory(this)
    }

    private var mTokenField: EditText? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_tokens, container, false)

        val tokenList = view.findViewById<ListView>(R.id.tokenList)
        val adapter = TokenAdapter(requireContext())
        tokenList.adapter = adapter

        val tokenField = view.findViewById<EditText>(R.id.tokenField)
        mTokenField = tokenField
        tokenField.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                addToken()
                true
            } else {
                false
            }
        }

        view.findViewById<ImageButton>(R.id.tokenAddButton).setOnClickListener { addToken() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var shown = -1
                viewModel.tokens.collect { tokens ->
                    adapter.submit(tokens)
                    // Scroll once the added token is in the list; the first
                    // emission and removals keep the position.
                    if (shown >= 0 && tokens.size > shown) {
                        tokenList.smoothScrollToPosition(tokens.size - 1)
                    }
                    shown = tokens.size
                }
            }
        }
        return view
    }

    private fun addToken() {
        val tokenField = mTokenField ?: return
        val tokenText = tokenField.text.toString().trim()
        if (tokenText.isEmpty()) {
            return
        }
        tokenField.setText("")
        viewModel.add(tokenText)
    }

    override fun onDestroyView() {
        mTokenField = null
        super.onDestroyView()
    }

    private inner class TokenAdapter(context: Context) :
        ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, ArrayList()) {

        fun submit(tokens: List<String>) {
            clear()
            addAll(tokens)
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.token_row, parent, false)
            val token = getItem(position) ?: return view

            view.findViewById<TextView>(R.id.tokenItemTitle).text = token
            view.findViewById<ImageButton>(R.id.tokenItemDelete).setOnClickListener {
                viewModel.remove(token)
            }
            return view
        }
    }
}
