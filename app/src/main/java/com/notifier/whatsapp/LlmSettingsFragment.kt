package com.notifier.whatsapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.notifier.whatsapp.databinding.FragmentLlmSettingsBinding

/**
 * Debug-only LLM endpoint configuration. Release builds hide this tab and
 * fall back to BuildConfig defaults baked from .env at build time.
 */
class LlmSettingsFragment : Fragment() {

    private var _binding: FragmentLlmSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLlmSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        binding.editApiKey.setText(AppConfig.getApiKey(ctx))
        binding.editApiBaseUrl.setText(AppConfig.getApiBaseUrl(ctx))
        binding.editModel.setText(AppConfig.getModel(ctx))

        binding.btnSaveLlm.setOnClickListener {
            AppConfig.saveLlmSettings(
                context = ctx,
                apiKey = binding.editApiKey.text.toString().trim(),
                apiBaseUrl = binding.editApiBaseUrl.text.toString().trim(),
                model = binding.editModel.text.toString().trim()
            )
            Toast.makeText(ctx, "LLM settings saved", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
