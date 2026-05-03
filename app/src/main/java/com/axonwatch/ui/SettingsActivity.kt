package com.axonwatch.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.axonwatch.databinding.ActivitySettingsBinding
import com.axonwatch.settings.SettingsManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        settings = SettingsManager(this)

        binding.etMacPrefixes.setText(settings.macPrefixes.joinToString("\n"))
        binding.etServerUrl.setText(settings.serverUrl)
        binding.etApiToken.setText(settings.apiToken)
        binding.switchSound.isChecked = settings.soundEnabled
        binding.tvReporterId.text = "Device ID: ${settings.reporterId}"

        binding.btnSave.setOnClickListener { save() }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun save() {
        val prefixes = binding.etMacPrefixes.text.toString()
            .lines()
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }

        if (prefixes.isEmpty()) {
            Toast.makeText(this, "Enter at least one MAC prefix", Toast.LENGTH_SHORT).show()
            return
        }

        settings.macPrefixes = prefixes
        settings.serverUrl = binding.etServerUrl.text.toString()
        settings.apiToken = binding.etApiToken.text.toString()
        settings.soundEnabled = binding.switchSound.isChecked

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
