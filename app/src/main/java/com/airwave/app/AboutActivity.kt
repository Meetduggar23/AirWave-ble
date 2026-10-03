package com.airwave.app

import android.os.Bundle
import com.airwave.app.databinding.ActivityAboutBinding

class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }

        // v3.2.6: show the real version (was a hardcoded "Version 2.0").
        binding.versionText.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)
    }
}
