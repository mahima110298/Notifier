package com.notifier.whatsapp

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.notifier.whatsapp.databinding.FragmentHistoryBinding

/**
 * Diagnostic view — Recent Matches (LLM-approved) and Captured Notifications
 * (raw feed). Debug builds only. Hosted as the second tab by MainActivity.
 */
class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnClearMatches.setOnClickListener {
            MatchHistory.clear(requireContext())
            refresh()
            Toast.makeText(requireContext(), "Match history cleared", Toast.LENGTH_SHORT).show()
        }
        binding.btnClearCaptured.setOnClickListener {
            CapturedNotifications.clear(requireContext())
            refresh()
            Toast.makeText(requireContext(), "Captured log cleared", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refresh() {
        if (_binding == null) return
        binding.textRecentMatches.text = MatchHistory.getHistory(requireContext())
            .ifBlank { "No matches yet. Waiting for notifications…" }
        binding.textCaptured.text = CapturedNotifications.getLog(requireContext())
            .ifBlank { "Nothing captured yet." }
    }
}
