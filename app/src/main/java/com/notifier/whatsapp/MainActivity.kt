package com.notifier.whatsapp

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.notifier.whatsapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val statusChecker = object : Runnable {
        override fun run() {
            updatePermissionStatus()
            updateMatchHistory()
            updatePreLlmMatches()
            updateCapturedNotifications()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AlertNotifier.createNotificationChannel(this)
        requestPostNotificationsIfNeeded()
        loadConfig()
        setupListeners()

        // Debug-only diagnostic cards.
        val debugVis =
            if (BuildConfig.DEBUG_ACCEPT_ALL_PACKAGES) android.view.View.VISIBLE
            else android.view.View.GONE
        binding.cardPreLlm.visibility = debugVis
        binding.cardCaptured.visibility = debugVis
    }

    private fun requestPostNotificationsIfNeeded() {
        // POST_NOTIFICATIONS became a runtime permission in Android 13 (API 33).
        // Without it, manager.notify(...) is silently dropped by the OS.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIF
            )
        }
    }

    companion object {
        private const val REQ_POST_NOTIF = 1001
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
        binding.editTargetGroups.setText(AppConfig.getTargetGroupsRaw(this))
        binding.editTargetIndividuals.setText(AppConfig.getTargetIndividualsRaw(this))
        binding.editAllowedSenders.setText(AppConfig.getAllowedSendersRaw(this))
        binding.editApiKey.setText(AppConfig.getApiKey(this))
        binding.editApiBaseUrl.setText(AppConfig.getApiBaseUrl(this))
        binding.editModel.setText(AppConfig.getModel(this))
        // Render multi-prompt config with newlines between entries.
        val promptsRaw = AppConfig.getMatchPromptsRaw(this)
        binding.editMatchPrompts.setText(promptsRaw.replace('|', '\n'))
    }

    private fun setupListeners() {
        binding.btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnSaveConfig.setOnClickListener {
            AppConfig.save(
                context = this,
                targetGroups = binding.editTargetGroups.text.toString().trim(),
                targetIndividuals = binding.editTargetIndividuals.text.toString().trim(),
                allowedSenders = binding.editAllowedSenders.text.toString().trim(),
                apiKey = binding.editApiKey.text.toString().trim(),
                apiBaseUrl = binding.editApiBaseUrl.text.toString().trim(),
                model = binding.editModel.text.toString().trim(),
                matchPrompts = binding.editMatchPrompts.text.toString().trim()
            )
            Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearCaptured.setOnClickListener {
            CapturedNotifications.clear(this)
            updateCapturedNotifications()
            Toast.makeText(this, "Captured log cleared", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearPreLlm.setOnClickListener {
            PreLlmMatches.clear(this)
            updatePreLlmMatches()
            Toast.makeText(this, "Pre-LLM log cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCapturedNotifications() {
        if (!BuildConfig.DEBUG_ACCEPT_ALL_PACKAGES) return
        val log = CapturedNotifications.getLog(this)
        binding.textCaptured.text = log.ifBlank { "Nothing captured yet." }
    }

    private fun updatePreLlmMatches() {
        if (!BuildConfig.DEBUG_ACCEPT_ALL_PACKAGES) return
        val log = PreLlmMatches.getLog(this)
        binding.textPreLlm.text = log.ifBlank {
            val groups = AppConfig.getTargetGroups(this)
            val individualsWild = AppConfig.individualsWildcard(this)
            val individuals = AppConfig.getTargetIndividuals(this)
            val senders = AppConfig.getAllowedSenders(this)
            val promptCount = AppConfig.getMatchPrompts(this).size
            buildString {
                append("No pre-LLM candidates yet.\n\n")
                append("Active config:\n")
                append(" • Groups: ")
                append(if (groups.isEmpty()) "(any)" else groups.joinToString(", "))
                append('\n')
                append(" • 1:1 contacts: ")
                append(when {
                    individualsWild -> "(any)"
                    individuals.isEmpty() -> "(none — 1:1 messages are ignored)"
                    else -> individuals.joinToString(", ")
                })
                append('\n')
                append(" • Sender allow-list (groups): ")
                append(if (senders.isEmpty()) "(any)" else senders.joinToString(", "))
                append('\n')
                append(" • Match prompts configured: $promptCount\n\n")
                append("Total stored entries: ${PreLlmMatches.count(this@MainActivity)}")
            }
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
