package com.notifier.whatsapp

import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.switchmaterial.SwitchMaterial
import com.notifier.whatsapp.databinding.FragmentSetupBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Notification-access status + preset management + LLM configuration form.
 * All user-facing configuration lives here.
 */
class SetupFragment : Fragment() {

    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val statusChecker = object : Runnable {
        override fun run() {
            updatePermissionStatus()
            renderPresets()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadConfig()
        setupListeners()
        renderPresets()
    }

    override fun onResume() {
        super.onResume()
        handler.post(statusChecker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusChecker)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
        _binding = null
    }

    // ---------------- Config form ----------------

    private fun loadConfig() {
        val ctx = requireContext()
        binding.editTargetGroups.setText(AppConfig.getTargetGroupsRaw(ctx))
        binding.editTargetIndividuals.setText(AppConfig.getTargetIndividualsRaw(ctx))
        binding.editAllowedSenders.setText(AppConfig.getAllowedSendersRaw(ctx))
        binding.editMatchPrompts.setText(AppConfig.getMatchPromptsRaw(ctx).replace('|', '\n'))
    }

    private fun setupListeners() {
        binding.btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        binding.btnSaveConfig.setOnClickListener {
            AppConfig.saveMatchingConfig(
                context = requireContext(),
                targetGroups = binding.editTargetGroups.text.toString().trim(),
                targetIndividuals = binding.editTargetIndividuals.text.toString().trim(),
                allowedSenders = binding.editAllowedSenders.text.toString().trim(),
                matchPrompts = binding.editMatchPrompts.text.toString().trim()
            )
            Toast.makeText(requireContext(), "Configuration saved", Toast.LENGTH_SHORT).show()
        }
        binding.btnSaveAsPreset.setOnClickListener { showSaveAsPresetDialog() }
        binding.btnGenerateFromNl.setOnClickListener { showGenerateFromNlDialog() }
    }

    // ---------------- Permission status indicator ----------------

    private fun updatePermissionStatus() {
        val ctx = requireContext()
        val cn = ComponentName(ctx, WhatsAppNotificationListener::class.java)
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        val enabled = flat?.contains(cn.flattenToString()) == true

        if (enabled) {
            binding.statusText.text = "Active — listening for WhatsApp notifications"
            binding.statusDot.background.setTint(ContextCompat.getColor(ctx, R.color.status_active))
            binding.btnGrantPermission.text = "Settings"
        } else {
            binding.statusText.text = "Not granted — tap Grant to enable"
            binding.statusDot.background.setTint(ContextCompat.getColor(ctx, R.color.status_inactive))
            binding.btnGrantPermission.text = "Grant"
        }
    }

    // ---------------- Presets: list rendering ----------------

    private fun renderPresets() {
        if (_binding == null) return
        val ctx = requireContext()
        val list = PresetStore.list(ctx)
        val activeId = PresetStore.activeId(ctx)
        val container = binding.presetsList
        container.removeAllViews()

        if (list.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = "No presets yet. Save your current config as the first preset, or generate one from a description."
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(0, 0, 0, 8.dp())
            })
            return
        }
        for (p in list) container.addView(buildPresetRow(p, isEditing = p.id == activeId))
    }

    private fun buildPresetRow(preset: ConfigPreset, isEditing: Boolean): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 6.dp() }
            setPadding(8.dp(), 6.dp(), 4.dp(), 6.dp())
            isClickable = true
            isFocusable = true
        }

        // Enable/disable switch — independent of editing. Multiple presets
        // can be enabled at once; listener iterates all enabled.
        val switch = SwitchMaterial(ctx).apply {
            isChecked = preset.enabled
            contentDescription = "Enable preset ${preset.name}"
            setOnCheckedChangeListener { _, checked ->
                PresetStore.save(ctx, preset.copy(enabled = checked))
                Toast.makeText(
                    ctx,
                    if (checked) "'${preset.name}' enabled" else "'${preset.name}' disabled",
                    Toast.LENGTH_SHORT
                ).show()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 10.dp() }
        }
        row.addView(switch)

        // Preset name. Bold when it's the one loaded in the form.
        row.addView(TextView(ctx).apply {
            text = preset.name
            textSize = 15f
            setTypeface(null, if (isEditing) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { activatePreset(preset) }
        })

        // "EDITING" badge when this preset's values populate the form.
        if (isEditing) {
            row.addView(TextView(ctx).apply {
                text = "EDITING"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(ctx, R.color.white))
                background = ContextCompat.getDrawable(ctx, R.drawable.badge_active)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8.dp() }
            })
        }

        row.addView(ImageButton(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundResource(android.R.color.transparent)
            contentDescription = "Delete preset ${preset.name}"
            setOnClickListener { confirmDeletePreset(preset) }
            layoutParams = LinearLayout.LayoutParams(36.dp(), 36.dp())
        })
        return row
    }

    private fun activatePreset(preset: ConfigPreset) {
        PresetStore.setActive(requireContext(), preset.id)
        loadConfig()
        renderPresets()
        Toast.makeText(requireContext(), "Loaded '${preset.name}' into form", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeletePreset(preset: ConfigPreset) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete preset?")
            .setMessage("'${preset.name}' will be removed. This can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                PresetStore.delete(requireContext(), preset.id)
                loadConfig()
                renderPresets()
                Toast.makeText(requireContext(), "Deleted", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---------------- Save-as-new dialog ----------------

    private fun showSaveAsPresetDialog() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "Preset name"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Save as new preset")
            .setMessage("Saves the current form values as a new named preset, and makes it active.")
            .setView(dialogPad(input))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) {
                    Toast.makeText(requireContext(), "Name required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val preset = ConfigPreset.newEmpty(name).copy(
                    targetGroups = binding.editTargetGroups.text.toString().trim(),
                    targetIndividuals = binding.editTargetIndividuals.text.toString().trim(),
                    allowedSenders = binding.editAllowedSenders.text.toString().trim(),
                    matchPrompts = binding.editMatchPrompts.text.toString().trim()
                )
                PresetStore.save(requireContext(), preset)
                PresetStore.setActive(requireContext(), preset.id)
                renderPresets()
                Toast.makeText(requireContext(), "Saved '$name'", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---------------- Generate-from-NL dialog ----------------

    private fun showGenerateFromNlDialog() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            hint = "e.g. Alert me when Alice or Bob in You n Me or Flatmates talk about rent or flatmate search, or when anyone in Project Team mentions an outage."
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Generate from description")
            .setMessage("Describe in natural language what you want to be alerted about. The LLM will fill the form — review before saving.")
            .setView(dialogPad(input))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Generate") { _, _ ->
                val nl = input.text.toString().trim()
                if (nl.isBlank()) {
                    Toast.makeText(requireContext(), "Description required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                generateFromNl(nl)
            }
            .show()
    }

    private fun generateFromNl(description: String) {
        val progress = AlertDialog.Builder(requireContext())
            .setTitle("Generating…")
            .setMessage("Calling the LLM.")
            .setCancelable(false)
            .show()

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ConfigGenerator.generate(requireContext(), description)
            }
            progress.dismiss()
            if (_binding == null) return@launch
            result.onSuccess { gen ->
                binding.editTargetGroups.setText(gen.targetGroups)
                binding.editTargetIndividuals.setText(gen.targetIndividuals)
                binding.editAllowedSenders.setText(gen.allowedSenders)
                binding.editMatchPrompts.setText(gen.matchPrompts.replace('|', '\n'))
                AlertDialog.Builder(requireContext())
                    .setTitle("Generated")
                    .setMessage(buildString {
                        append("Target Groups: ${gen.targetGroups.ifBlank { "(any)" }}\n")
                        append("1:1 Contacts:  ${gen.targetIndividuals.ifBlank { "(none)" }}\n")
                        append("Senders:       ${gen.allowedSenders}\n")
                        append("Match Prompts: ${gen.matchPrompts.split('|').joinToString(" | ")}\n")
                        if (gen.rationale.isNotBlank()) append("\nRationale: ${gen.rationale}\n")
                        append("\nReview the form. Tap 'Save Configuration' to apply, or 'Save As New' to create a named preset.")
                    })
                    .setPositiveButton("OK", null)
                    .show()
            }.onFailure { e ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Generation failed")
                    .setMessage(e.message ?: e.javaClass.simpleName)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    // ---------------- UI helpers ----------------

    private fun Int.dp(): Int =
        (this * resources.displayMetrics.density).toInt()

    private fun dialogPad(view: View): View = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp(), 8.dp(), 20.dp(), 0)
        addView(view, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }
}
