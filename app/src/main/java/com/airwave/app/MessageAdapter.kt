package com.airwave.app

import android.content.Context
import android.graphics.BitmapFactory
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.airwave.app.databinding.ItemMsgMineBinding
import com.airwave.app.databinding.ItemMsgSystemBinding
import com.airwave.app.databinding.ItemMsgTheirsBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Shared message list adapter for 1-to-1 and group chats. */
class MessageAdapter(private val context: Context) : BaseAdapter() {

    private var messages: List<AirWaveBle.ChatMessage> = emptyList()
    private var shown: List<AirWaveBle.ChatMessage> = emptyList()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var query: String = ""

    /** Show single/double delivery ticks on outgoing messages. */
    var showTicks: Boolean = true

    /** Long-press on a message bubble. */
    var onItemLongClick: ((AirWaveBle.ChatMessage) -> Unit)? = null

    /** Tap the retry icon on a failed message. */
    var onRetryClick: ((AirWaveBle.ChatMessage) -> Unit)? = null

    /** Tap an image thumbnail. */
    var onImageClick: ((AirWaveBle.ChatMessage) -> Unit)? = null

    /** Tap the reactions row to remove my own reaction. */
    var onReactionsClick: ((AirWaveBle.ChatMessage) -> Unit)? = null

    fun setMessages(m: List<AirWaveBle.ChatMessage>) {
        messages = m
        applyFilter()
    }

    /** Number of messages matching the current search query. */
    val matchCount: Int
        get() = shown.size

    fun setQuery(q: String) {
        query = q.trim()
        applyFilter()
    }

    private fun applyFilter() {
        shown = if (query.isEmpty()) {
            messages
        } else {
            messages.filter {
                it.text.contains(query, ignoreCase = true) ||
                    it.caption.contains(query, ignoreCase = true)
            }
        }
        notifyDataSetChanged()
    }

    override fun getCount() = shown.size
    override fun getItem(position: Int) = shown[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getViewTypeCount() = 3
    override fun getItemViewType(position: Int): Int {
        val m = getItem(position)
        return when {
            m.system -> 2
            m.mine -> 0
            else -> 1
        }
    }

    private fun bindQuote(
        box: View, sender: TextView, text: TextView,
        reply: AirWaveBle.ReplyInfo?
    ) {
        if (reply == null) {
            box.visibility = View.GONE
        } else {
            box.visibility = View.VISIBLE
            sender.text = reply.sender
            text.text = reply.text
        }
    }

    /** Highlight the search query inside a text view. */
    private fun setHighlighted(tv: TextView, full: String) {
        if (query.isEmpty()) {
            tv.text = full
            return
        }
        val span = SpannableString(full)
        val hl = context.attrColor(com.google.android.material.R.attr.colorPrimary) and 0x55FFFFFF.toInt()
        var start = full.indexOf(query, ignoreCase = true)
        while (start >= 0) {
            span.setSpan(
                BackgroundColorSpan(hl),
                start, start + query.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            start = full.indexOf(query, start + query.length, ignoreCase = true)
        }
        tv.text = span
    }

    /** Show "👍2 ❤️1" summary of reactions; tap removes my own. */
    private fun bindReactions(tv: TextView, msg: AirWaveBle.ChatMessage) {
        if (msg.reactions.isEmpty()) {
            tv.visibility = View.GONE
            return
        }
        tv.visibility = View.VISIBLE
        val counts = msg.reactions.values.groupingBy { it }.eachCount()
        val summary = counts.entries.joinToString(" ") { (e, c) ->
            if (c > 1) "$e$c" else e
        }
        val span = SpannableString(summary)
        if (msg.reactions.containsKey(msg.sender) || msg.reactions.isNotEmpty()) {
            span.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    onReactionsClick?.invoke(msg)
                }

                override fun updateDrawState(ds: TextPaint) {
                    ds.isUnderlineText = false
                }
            }, 0, summary.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.movementMethod = LinkMovementMethod.getInstance()
            tv.isClickable = true
            tv.isFocusable = true
        }
        tv.text = span
    }

    private fun bindImage(
        img: android.widget.ImageView,
        progress: android.widget.ProgressBar,
        progText: TextView,
        captionTv: TextView,
        msg: AirWaveBle.ChatMessage
    ) {
        if (!msg.isImage) {
            img.visibility = View.GONE
            progress.visibility = View.GONE
            progText.visibility = View.GONE
            captionTv.visibility = View.GONE
            return
        }
        val bmp = msg.imagePath?.let { p ->
            try {
                BitmapFactory.decodeFile(p)
            } catch (_: Exception) {
                null
            }
        }
        if (bmp != null) {
            img.visibility = View.VISIBLE
            img.setImageBitmap(bmp)
            progress.visibility = View.GONE
            progText.visibility = View.GONE
            img.setOnClickListener { onImageClick?.invoke(msg) }
        } else {
            img.visibility = View.GONE
            val done = msg.imgReceived >= msg.imgTotal && msg.imgTotal > 0
            if (done) {
                progress.visibility = View.GONE
                progText.visibility = View.VISIBLE
                progText.text = context.getString(R.string.image_failed)
            } else {
                progress.visibility = View.VISIBLE
                progText.visibility = View.VISIBLE
                progText.text = context.getString(
                    R.string.receiving_image, msg.imgReceived, msg.imgTotal
                )
                if (msg.imgTotal > 0) {
                    progress.max = msg.imgTotal
                    progress.progress = msg.imgReceived
                }
            }
        }
        if (msg.caption.isNotBlank()) {
            captionTv.visibility = View.VISIBLE
            captionTv.text = msg.caption
        } else {
            captionTv.visibility = View.GONE
        }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val msg = getItem(position)
        val time = timeFmt.format(Date(msg.time))
        val longClick = View.OnLongClickListener {
            onItemLongClick?.invoke(msg)
            onItemLongClick != null
        }
        return when (getItemViewType(position)) {
            0 -> {
                val b = (convertView?.tag as? ItemMsgMineBinding)
                    ?: ItemMsgMineBinding.inflate(LayoutInflater.from(context), parent, false)
                        .also { it.root.tag = it }
                setHighlighted(b.msgText, msg.text)
                b.msgText.visibility = if (msg.isImage) View.GONE else View.VISIBLE
                b.msgTime.text = time
                b.tickText.text = if (msg.delivered) "✓✓" else "✓"
                b.tickText.visibility = if (showTicks) View.VISIBLE else View.GONE
                bindQuote(b.quoteBox, b.quoteSender, b.quoteText, msg.replyTo)
                bindImage(b.msgImage, b.imgProgress, b.imgProgressText, b.imgCaption, msg)
                bindReactions(b.reactionsText, msg)
                b.pinIcon.visibility = if (msg.pinned) View.VISIBLE else View.GONE
                if (msg.failed) {
                    b.retryIcon.visibility = View.VISIBLE
                    b.retryIcon.setOnClickListener { onRetryClick?.invoke(msg) }
                } else {
                    b.retryIcon.visibility = View.GONE
                }
                b.root.setOnLongClickListener(longClick)
                b.root
            }
            2 -> {
                val b = (convertView?.tag as? ItemMsgSystemBinding)
                    ?: ItemMsgSystemBinding.inflate(LayoutInflater.from(context), parent, false)
                        .also { it.root.tag = it }
                b.sysText.text = msg.text
                b.root
            }
            else -> {
                val b = (convertView?.tag as? ItemMsgTheirsBinding)
                    ?: ItemMsgTheirsBinding.inflate(LayoutInflater.from(context), parent, false)
                        .also { it.root.tag = it }
                b.senderName.text = msg.sender
                b.senderName.visibility = if (msg.sender.isBlank()) View.GONE else View.VISIBLE
                b.senderName.setTextColor(AvatarUtil.colorFor(msg.sender))
                setHighlighted(b.msgText, msg.text)
                b.msgText.visibility = if (msg.isImage) View.GONE else View.VISIBLE
                b.msgTime.text = time
                bindQuote(b.quoteBox, b.quoteSender, b.quoteText, msg.replyTo)
                bindImage(b.msgImage, b.imgProgress, b.imgProgressText, b.imgCaption, msg)
                bindReactions(b.reactionsText, msg)
                b.pinIcon.visibility = if (msg.pinned) View.VISIBLE else View.GONE
                b.retryIcon.visibility = View.GONE
                b.root.setOnLongClickListener(longClick)
                b.root
            }
        }
    }
}
