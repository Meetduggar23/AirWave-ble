package com.airwave.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView

/** v4.1: pick a conversation to forward a message into. */
class ForwardActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_forward)
        val convs = AirWaveBle.conversationList()
        val list = findViewById<ListView>(R.id.forwardList)
        list.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, convs.map { it.second })
        list.setOnItemClickListener { _, _, pos, _ ->
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra("to_conv", convs[pos].first)
            )
            finish()
        }
    }
}
