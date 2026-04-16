package com.notifier.whatsapp

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.notifier.whatsapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val statusChecker = object : Runnable {
        override fun run() {
            updatePermissionStatus()
            updateMatchHistory()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AlertNotifier.createNotificationChannel(this)
        loadConfig()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusChecker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusChecker)
    }

    private fun loadConfig() {
        binding.editTargetGroup.setText(AppConfig.getTargetGroup(this))
        val senders = AppConfig.getAllowedSenders(this)
        binding.editAllowedSenders.setText(
            if (senders.isEmpty()) "all" else senders.joinToString(", ")
        )
        binding.editApiKey.setText(AppConfig.getApiKey(this))
        binding.editApiBaseUrl.setText(AppConfig.getApiBaseUrl(this))
        binding.editModel.setText(AppConfig.getModel(this))
        binding.editMatchPrompt.setText(AppConfig.getMatchPrompt(this))
    }

    private fun setupListeners() {
        binding.btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnSaveConfig.setOnClickListener {
            AppConfig.save(
                context = this,
                targetGroup = binding.editTargetGroup.text.toString().trim(),
                allowedSenders = binding.editAllowedSenders.text.toString().trim(),
                apiKey = binding.editApiKey.text.toString().trim(),
                apiBaseUrl = binding.editApiBaseUrl.text.toString().trim(),
                model = binding.editModel.text.toString().trim(),
                matchPrompt = binding.editMatchPrompt.text.toString().trim()
            )
            Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePermissionStatus() {
        val enabled = isNotificationListenerEnabled()
        if (enabled) {
            binding.statusText.text = "Active — listening for WhatsApp notifications"
            binding.statusDot.background.setTint(
                ContextCompat.getColor(this, R.color.status_active)
            )
            binding.btnGrantPermission.text = "Settings"
        } else {
            binding.statusText.text = "Not granted — tap Grant to enable"
            binding.statusDot.background.setTint(
                ContextCompat.getColor(this, R.color.status_inactive)
            )
            binding.btnGrantPermission.text = "Grant"
        }
    }

    private fun updateMatchHistory() {
        val history = MatchHistory.getHistory(this)
        binding.textRecentMatches.text = history.ifBlank {
            "No matches yet. Waiting for notifications..."
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(this, WhatsAppNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }
}
