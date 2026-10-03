package com.airwave.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.airwave.app.databinding.ActivityHistoryBinding
import com.airwave.app.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** v3.0 current-session connection history (session-only, like chats). */
class HistoryActivity : BaseActivity() {

    private lateinit var b: ActivityHistoryBinding
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.backButton.setOnClickListener { finish() }

        val events = AirWaveBle.history().reversed()
        b.historyList.adapter = HistoryAdapter(this, events, fmt)
        b.historyEmpty.visibility = if (events.isEmpty()) View.VISIBLE else View.GONE
    }

    private class HistoryAdapter(
        private val ctx: Context,
        private val events: List<AirWaveBle.HistoryEvent>,
        private val fmt: SimpleDateFormat
    ) : BaseAdapter() {
        override fun getCount() = events.size
        override fun getItem(position: Int) = events[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val b = (convertView?.tag as? ItemHistoryBinding)
                ?: ItemHistoryBinding.inflate(LayoutInflater.from(ctx), parent, false)
                    .also { it.root.tag = it }
            val e = getItem(position)
            b.eventText.text = e.text
            b.eventTime.text = fmt.format(Date(e.time))
            return b.root
        }
    }
}
