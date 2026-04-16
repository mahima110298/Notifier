package com.notifier.whatsapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.notifier.whatsapp.databinding.ActivityMainBinding

/**
 * Hosts the tabbed UI:
 *   • Tab 1: Setup (always)
 *   • Tab 2: History (debug builds only)
 *
 * In release the History tab and the TabLayout are hidden entirely, so the
 * user sees only the setup form.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        AlertNotifier.createNotificationChannel(this)
        requestPostNotificationsIfNeeded()

        val pages = buildList {
            add(Page("Setup") { SetupFragment() })
            if (BuildConfig.DEBUG_ACCEPT_ALL_PACKAGES) {
                add(Page("LLM") { LlmSettingsFragment() })
                add(Page("History") { HistoryFragment() })
            }
        }

        binding.pager.adapter = PagerAdapter(this, pages)
        // Hide the tab bar when there's only one tab — no value in showing it.
        if (pages.size <= 1) {
            binding.tabs.visibility = android.view.View.GONE
        } else {
            TabLayoutMediator(binding.tabs, binding.pager) { tab, pos ->
                tab.text = pages[pos].title
            }.attach()
        }
    }

    private fun requestPostNotificationsIfNeeded() {
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

    private data class Page(val title: String, val create: () -> Fragment)

    private class PagerAdapter(
        activity: FragmentActivity,
        private val pages: List<Page>
    ) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = pages.size
        override fun createFragment(position: Int): Fragment = pages[position].create()
    }

    companion object {
        private const val REQ_POST_NOTIF = 1001
    }
}
