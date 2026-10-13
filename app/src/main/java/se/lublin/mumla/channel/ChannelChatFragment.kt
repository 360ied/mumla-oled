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
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent.KEYCODE_ENTER
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo.IME_NULL
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.core.text.HtmlCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.regex.Pattern
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.IUser
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaObserver
import se.lublin.humla.util.IHumlaObserver
import se.lublin.mumla.R
import se.lublin.mumla.service.IChatMessage
import se.lublin.mumla.util.BitmapUtils
import se.lublin.mumla.util.ChatLinkPolicy
import se.lublin.mumla.util.HumlaServiceFragment
import se.lublin.mumla.util.MumbleImageGetter

class ChannelChatFragment : HumlaServiceFragment(), ChatTargetProvider.OnChatTargetSelectedListener {

    private val mServiceObserver: IHumlaObserver = object : HumlaObserver() {

        override fun onMessageLogged(message: IMessage?) {
            addChatMessage(IChatMessage.TextMessage(message), true)
        }

        override fun onLogInfo(message: String?) {
            addChatMessage(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, message), true)
        }

        override fun onLogWarning(message: String?) {
            addChatMessage(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, message), true)
        }

        override fun onLogError(message: String?) {
            addChatMessage(IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.ERROR, message), true)
        }

        override fun onUserJoinedChannel(user: IUser?, newChannel: IChannel?, oldChannel: IChannel?) {
            val service = getService()
            if (service != null && service.isConnected()) {
                val session = service.HumlaSession()
                if (user != null && session.getSessionUser() != null &&
                    user == session.getSessionUser()
                ) {
                    // NPE-exact: the original dereferenced mTargetProvider unconditionally.
                    val provider = mTargetProvider ?: throw NullPointerException("mTargetProvider")
                    if (provider.getChatTarget() == null) {
                        // Update chat target when user changes channels without a target.
                        updateChatTargetText(null)
                    }
                }
            }
        }
    }

    private var mChatList: ListView? = null
    private var mChatAdapter: ChannelChatAdapter? = null
    private var mChatTextEdit: EditText? = null
    private var mSendButton: ImageButton? = null
    private var mTargetProvider: ChatTargetProvider? = null
    private val imagePicker =
        registerForActivityResult(GetContent(), this::onImagePicked)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        try {
            mTargetProvider = parentFragment as ChatTargetProvider
        } catch (e: ClassCastException) {
            throw ClassCastException(parentFragment.toString() + " must implement ChatTargetProvider")
        }
    }

    override fun onResume() {
        super.onResume()
        // NPE-exact: the original dereferenced mTargetProvider unconditionally.
        val provider = mTargetProvider ?: throw NullPointerException("mTargetProvider")
        provider.registerChatTargetListener(this)
    }

    override fun onPause() {
        super.onPause()
        // NPE-exact: the original dereferenced mTargetProvider unconditionally.
        val provider = mTargetProvider ?: throw NullPointerException("mTargetProvider")
        provider.unregisterChatTargetListener(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_chat, container, false)
        // NPE-exact: the original dereferenced every lookup unconditionally.
        val chatList = view.findViewById(R.id.chat_list) as ListView?
            ?: throw NullPointerException("chat_list")
        mChatList = chatList
        val chatTextEdit = view.findViewById(R.id.chatTextEdit) as EditText?
            ?: throw NullPointerException("chatTextEdit")
        mChatTextEdit = chatTextEdit

        val imageSendButton = view.findViewById(R.id.chatImageSend) as ImageButton?
            ?: throw NullPointerException("chatImageSend")
        imageSendButton.setOnClickListener { imagePicker.launch("image/*") }

        val sendButton = view.findViewById(R.id.chatTextSend) as ImageButton?
            ?: throw NullPointerException("chatTextSend")
        mSendButton = sendButton
        sendButton.setOnClickListener(this::sendMessageFromEditor)

        chatTextEdit.setOnEditorActionListener { v, actionId, event ->
            if (actionId == IME_NULL && event != null && event.keyCode == KEYCODE_ENTER) {
                sendMessageFromEditor(v)
                true
            } else {
                false
            }
        }

        chatTextEdit.addTextChangedListener(object : TextWatcher {

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                sendButton.isEnabled = chatTextEdit.text.length > 0
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            }

            override fun afterTextChanged(s: Editable?) {
            }
        })

        // NPE-exact: the original dereferenced mTargetProvider unconditionally.
        val provider = mTargetProvider ?: throw NullPointerException("mTargetProvider")
        updateChatTargetText(provider.getChatTarget())
        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val adapter = mChatAdapter
        if (adapter != null) {
            adapter.shutdown()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.fragment_chat, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        val clearItem = menu.findItem(R.id.menu_clear_chat)
        // NPE-exact: the original dereferenced getActivity() unconditionally.
        val host = activity ?: throw NullPointerException("activity")
        if (clearItem != null && clearItem.icon != null) {
            val foregroundColor = host.theme.obtainStyledAttributes(
                intArrayOf(android.R.attr.textColorPrimaryInverse)
            ).getColor(0, -1)
            val icon = clearItem.icon ?: throw NullPointerException("icon")
            icon.mutate().setColorFilter(foregroundColor, PorterDuff.Mode.MULTIPLY)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_clear_chat) {
            clear()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * Adds the passed text to the fragment chat body.
     *
     * @param message The message to add.
     * @param scroll  Whether to scroll to the bottom after adding the message.
     */
    fun addChatMessage(message: IChatMessage, scroll: Boolean) {
        val adapter = mChatAdapter ?: return

        adapter.add(message)

        if (scroll) {
            val chatList = mChatList ?: throw NullPointerException("mChatList")
            chatList.post {
                chatList.smoothScrollToPosition(adapter.count - 1)
            }
        }
    }

    private fun onImagePicked(uri: Uri?) {
        if (uri == null) {
            return
        }
        val service = getService()
        if (service == null || !service.isConnected()) {
            return
        }

        // We don't fail on errors when getting orientation
        var flipped = false
        var rotationDeg = 0
        try {
            requireContext().contentResolver.openInputStream(uri).use { imageStream ->
                if (imageStream == null) {
                    Log.w(TAG, "openInputStream(uri) failed for orientation")
                } else {
                    val exif = ExifInterface(imageStream)
                    flipped = exif.isFlipped
                    rotationDeg = exif.rotationDegrees
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "exception when getting orientation: $e")
        }
        Log.d(TAG, "flipped:$flipped rotationDeg:$rotationDeg")

        var bitmap: Bitmap?
        try {
            requireContext().contentResolver.openInputStream(uri).use { imageStream ->
                if (imageStream == null) {
                    Log.w(TAG, "openInputStream(uri) failed")
                    return
                }
                bitmap = BitmapFactory.decodeStream(imageStream)
            }
        } catch (e: IOException) {
            Log.w(TAG, "exception when opening stream: $e")
            return
        }

        var oriented = bitmap
        if (oriented == null) {
            Log.w(TAG, "decode to bitmap failed")
            return
        }

        if (flipped || rotationDeg > 0) {
            val matrix = Matrix()
            if (flipped) {
                // first flip horizontally, following {@link ExifInterface#getRotationDegrees()}
                matrix.postScale(-1f, 1f, oriented.width / 2f, oriented.height / 2f)
            }
            if (rotationDeg > 0) {
                matrix.postRotate(rotationDeg.toFloat())
            }
            oriented = Bitmap.createBitmap(
                oriented, 0, 0, oriented.width, oriented.height,
                matrix, false
            )
        }

        val source = oriented ?: throw NullPointerException("bitmap")
        val resized = BitmapUtils.resizeKeepingAspect(source, 1600, 1600)

        val preview = ImageView(requireContext())
        preview.setImageBitmap(resized)
        preview.adjustViewBounds = true
        preview.scaleType = ImageView.ScaleType.FIT_CENTER
        preview.maxHeight = requireContext().resources.displayMetrics.heightPixels / 3

        MaterialAlertDialogBuilder(requireContext())
            .setMessage(R.string.image_confirm_send)
            .setPositiveButton(android.R.string.ok) { _, _ -> onImageConfirmed(resized) }
            .setNegativeButton(android.R.string.cancel, null)
            .setView(preview)
            .create()
            .show()
    }

    private fun onImageConfirmed(initialBitmap: Bitmap) {
        val service = getService()
        if (service == null) {
            Log.w(TAG, "onImageConfirmed: service or session is null")
            return
        }
        val serverSettings = service.HumlaSession().getServerSettings()
        if (serverSettings == null) {
            Log.w(TAG, "onImageConfirmed: service or session is null")
            return
        }

        val maxSize = serverSettings.imageMessageLength

        var currentBitmap: Bitmap? = initialBitmap
        var formattedMessage: String? = null

        while (currentBitmap != null && formattedMessage == null) {
            var quality = 97
            do {
                val stream = ByteArrayOutputStream()
                if (!currentBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) {
                    Log.w(TAG, "compress failed, quality==$quality")
                } else {
                    val compressed = stream.toByteArray()
                    val imageStr = Base64.encodeToString(compressed, Base64.NO_WRAP)
                    val encoded: String = try {
                        URLEncoder.encode(imageStr, StandardCharsets.UTF_8.name())
                    } catch (e: UnsupportedEncodingException) {
                        @Suppress("DEPRECATION")
                        URLEncoder.encode(imageStr)
                    }
                    val candidateMessage = "<img src=\"data:image/jpeg;base64," + encoded + "\"/>"
                    if (maxSize == 0 || candidateMessage.length <= maxSize) {
                        formattedMessage = candidateMessage
                        break
                    } else {
                        Log.d(TAG, "compress(quality==$quality) candidate length " + candidateMessage.length + " >= " + maxSize + " bytes")
                    }
                }
                quality -= 15
            } while (quality > 10)

            if (formattedMessage == null && maxSize > 0) {
                val bitmap = currentBitmap ?: throw NullPointerException("currentBitmap")
                val currentWidth = bitmap.width
                val currentHeight = bitmap.height
                if (currentWidth > 320 && currentHeight > 320) {
                    val nextWidth = Math.max(320, currentWidth * 2 / 3)
                    val nextHeight = Math.max(320, currentHeight * 2 / 3)
                    currentBitmap = BitmapUtils.resizeKeepingAspect(bitmap, nextWidth, nextHeight)
                } else {
                    break
                }
            }
        }

        val message = formattedMessage
        if (message == null) {
            Log.w(TAG, "all compress attempts failed")
            val ctx = context
            if (ctx != null) {
                Toast.makeText(ctx, R.string.image_too_large, Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            sendMessage(message)
        } catch (e: HumlaDisconnectedException) {
            Log.d(TAG, "exception from sendMessage: $e")
        }
    }

    /**
     * Sends the message currently in
     * {@link se.lublin.mumla.channel.ChannelChatFragment#mChatTextEdit}
     * to the remote server. Clears the message box if the message was sent successfully.
     */
    private fun sendMessageFromEditor(v: View?) {
        // NPE-exact: the original dereferenced mChatTextEdit unconditionally.
        val chatTextEdit = mChatTextEdit ?: throw NullPointerException("mChatTextEdit")
        if (chatTextEdit.length() == 0) {
            return
        }
        val message = chatTextEdit.text.toString()
        try {
            sendMessage(message)
            chatTextEdit.setText("")
        } catch (e: HumlaDisconnectedException) {
            Log.d(TAG, "exception from sendMessage: $e")
        }
    }

    /**
     * Sends a message to the remote server.
     *
     * @param message The message to send.
     * @throws HumlaDisconnectedException If the service is disconnected.
     */
    @Throws(HumlaDisconnectedException::class)
    private fun sendMessage(message: String) {
        val service = getService()
        if (service == null) {
            Log.d(TAG, "getService()==null in sendMessage")
            return
        }
        val session = service.HumlaSession()

        val formattedMessage = markupOutgoingMessage(message)
        // NPE-exact: the original dereferenced mTargetProvider unconditionally.
        val provider = mTargetProvider ?: throw NullPointerException("mTargetProvider")
        val target = provider.getChatTarget()
        val responseMessage = if (target == null) {
            // NPE-exact: the original dereferenced getSessionChannel() unconditionally.
            val sessionChannel = session.getSessionChannel() ?: throw NullPointerException("sessionChannel")
            session.sendChannelTextMessage(sessionChannel.id, formattedMessage, false)
        } else if (target.getUser() != null) {
            val targetUser = target.getUser() ?: throw NullPointerException("targetUser")
            session.sendUserTextMessage(targetUser.session, formattedMessage)
        } else if (target.getChannel() != null) {
            val targetChannel = target.getChannel() ?: throw NullPointerException("targetChannel")
            session.sendChannelTextMessage(targetChannel.id, formattedMessage, false)
        } else {
            null
        }
        addChatMessage(IChatMessage.TextMessage(responseMessage, true), true)
    }

    /**
     * Adds HTML markup to the message, replacing links and newlines.
     *
     * @param message The message to markup.
     * @return HTML data.
     */
    private fun markupOutgoingMessage(message: String): String {
        val matcher = LINK_PATTERN.matcher(message)
        return matcher.replaceAll("<a href=\"$1\">$1</a>")
            .replace("\n", "<br>")
    }

    fun clear() {
        val adapter = mChatAdapter
        if (adapter != null) {
            adapter.clear()
        }
        val service = getService()
        if (service != null) {
            service.clearMessageLog()
        }
    }

    /**
     * Updates hint displaying chat target.
     */
    fun updateChatTargetText(target: ChatTargetProvider.ChatTarget?) {
        val service = getService()
        if (service == null || !service.isConnected()) {
            return
        }

        val session = service.HumlaSession()
        val hint = if (target == null && session.getSessionChannel() != null) {
            val sessionChannel = session.getSessionChannel() ?: throw NullPointerException("sessionChannel")
            getString(R.string.messageToChannel, sessionChannel.name)
        } else if (target != null && target.getUser() != null) {
            val targetUser = target.getUser() ?: throw NullPointerException("targetUser")
            getString(R.string.messageToUser, targetUser.name)
        } else if (target != null && target.getChannel() != null) {
            val targetChannel = target.getChannel() ?: throw NullPointerException("targetChannel")
            getString(R.string.messageToChannel, targetChannel.name)
        } else {
            null
        }
        // NPE-exact: the original dereferenced mChatTextEdit unconditionally.
        val chatTextEdit = mChatTextEdit ?: throw NullPointerException("mChatTextEdit")
        chatTextEdit.hint = hint
        chatTextEdit.requestLayout() // Needed to update bounds after hint change.
    }

    override fun onServiceBound(service: IHumlaService) {
        val svc = getService()
        if (svc == null) {
            return
        }

        val oldAdapter = mChatAdapter
        if (oldAdapter != null) {
            oldAdapter.shutdown()
        }
        // NPE-exact: the original passed getActivity() straight through.
        val host = activity ?: throw NullPointerException("activity")
        mChatAdapter = ChannelChatAdapter(host, service, svc.getMessageLog())
        val chatList = mChatList ?: throw NullPointerException("mChatList")
        val adapter = mChatAdapter ?: throw NullPointerException("mChatAdapter")
        chatList.adapter = adapter
        chatList.post {
            chatList.setSelection(adapter.count - 1)
        }
    }

    override fun getServiceObserver(): IHumlaObserver {
        return mServiceObserver
    }

    override fun onChatTargetSelected(target: ChatTargetProvider.ChatTarget?) {
        updateChatTargetText(target)
    }

    private class ChannelChatAdapter(context: Context, service: IHumlaService, messages: List<IChatMessage>) :
        ArrayAdapter<IChatMessage>(context, 0, ArrayList(messages)) {
        private val mImageGetter: MumbleImageGetter = MumbleImageGetter(context, this::notifyDataSetChanged)
        private val mService: IHumlaService = service
        private val mDateFormat: DateFormat = SimpleDateFormat.getTimeInstance()

        fun shutdown() {
            mImageGetter.shutdown()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            var v = convertView
            if (v == null) {
                v = LayoutInflater.from(context).inflate(R.layout.list_chat_item, parent, false)
            }
            // NPE-exact: inflate never returns null here; the original dereferenced v.
            val row = v ?: throw NullPointerException("view")

            val chatBox = row.findViewById(R.id.list_chat_item_box) as LinearLayout
            val targetText = row.findViewById(R.id.list_chat_item_target) as TextView
            val messageText = row.findViewById(R.id.list_chat_item_text) as TextView
            val timeText = row.findViewById(R.id.list_chat_item_time) as TextView

            // NPE-exact: the original dereferenced getItem(position) unconditionally.
            val message = getItem(position) ?: throw NullPointerException("item")
            message.accept(object : IChatMessage.Visitor {
                override fun visit(message: IChatMessage.TextMessage) {
                    // NPE-exact: the original dereferenced getMessage() unconditionally.
                    val textMessage = message.getMessage() ?: throw NullPointerException("message")
                    var targetMessage: String? = context.getString(R.string.unknown)
                    var selfAuthored = message.isSelfAuthored
                    if (!selfAuthored && mService.isConnected()) {
                        try {
                            val session = mService.HumlaSession()
                            val sessionUser = session.getSessionUser()
                            selfAuthored = sessionUser != null &&
                                sessionUser.name == textMessage.actorName
                        } catch (e: HumlaDisconnectedException) {
                            selfAuthored = false
                        } catch (e: IllegalStateException) {
                            selfAuthored = false
                        }
                    }

                    if (textMessage.targetChannels.isNotEmpty()) {
                        val currentChannel = textMessage.targetChannels[0]
                        if (currentChannel.name != null) {
                            targetMessage = context.getString(R.string.chat_message_to, textMessage.actorName, currentChannel.name)
                        }
                    } else if (textMessage.targetTrees.isNotEmpty()) {
                        val currentChannel = textMessage.targetTrees[0]
                        if (currentChannel.name != null) {
                            targetMessage = context.getString(R.string.chat_message_to, textMessage.actorName, currentChannel.name)
                        }
                    } else if (textMessage.targetUsers.isNotEmpty()) {
                        val user = textMessage.targetUsers[0]
                        if (user.name != null) {
                            targetMessage = context.getString(R.string.chat_message_to, textMessage.actorName, user.name)
                        }
                    } else {
                        targetMessage = textMessage.actorName
                    }

                    val gravity = if (selfAuthored) Gravity.RIGHT else Gravity.LEFT
                    chatBox.gravity = gravity
                    messageText.gravity = gravity
                    targetText.text = targetMessage
                    targetText.visibility = View.VISIBLE
                }

                override fun visit(message: IChatMessage.InfoMessage) {
                    targetText.visibility = View.GONE
                    chatBox.gravity = Gravity.LEFT
                    messageText.gravity = Gravity.LEFT
                }
            })
            timeText.text = mDateFormat.format(Date(message.receivedTime))
            val body = message.body ?: throw NullPointerException("body")
            val rendered = HtmlCompat.fromHtml(body, HtmlCompat.FROM_HTML_MODE_LEGACY, mImageGetter, null)
            messageText.text = sanitizeChatLinks(rendered)
            messageText.movementMethod = LinkMovementMethod.getInstance()

            return row
        }

        override fun areAllItemsEnabled(): Boolean {
            return false
        }

        override fun isEnabled(position: Int): Boolean {
            return false // Makes links clickable.
        }

        companion object {
            /**
             * Replaces URLSpans from server-controlled HTML: http(s) links open
             * via an ACTION_VIEW chooser, every other scheme becomes inert text.
             * Opens the actual href, never the display text.
             */
            private fun sanitizeChatLinks(rendered: Spanned): Spannable {
                val spannable = SpannableString(rendered)
                val urlSpans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
                for (span in urlSpans) {
                    val start = spannable.getSpanStart(span)
                    val end = spannable.getSpanEnd(span)
                    val flags = spannable.getSpanFlags(span)
                    spannable.removeSpan(span)
                    val target = ChatLinkPolicy.handleClick(span.url)
                    if (target == null) {
                        continue
                    }
                    spannable.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            val context = widget.context
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target))
                            context.startActivity(Intent.createChooser(intent, null))
                        }
                    }, start, end, flags)
                }
                return spannable
            }
        }
    }

    companion object {
        private val TAG: String = ChannelChatFragment::class.java.name

        private val LINK_PATTERN: Pattern = Pattern.compile("(https?://\\S+)")
    }
}
