package com.example.messaging_app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.example.messaging_app.MessagingApplication
import com.example.messaging_app.R
import com.example.messaging_app.data.AppThemeMode
import com.example.messaging_app.data.LoraRadioConfig
import com.example.messaging_app.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: SettingsViewModel by viewModels {
        val container = (requireActivity().application as MessagingApplication).container
        SettingsViewModel.Factory(container.chatRepository, container.userPreferences)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        renderLoraRadio(LoraRadioConfig())

        val version = runCatching {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
        binding.settingsVersion.text = getString(R.string.settings_version, version)

        binding.settingsManageDevices.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_devices)
        }
        binding.settingsProfile.setOnClickListener {
            findNavController().navigate(R.id.action_settings_to_profile)
        }
        binding.settingsReconnect.setOnClickListener {
            (activity as? MainActivityHost)?.ensureBleReady {
                viewModel.reconnect()
            }
        }
        binding.settingsDisconnect.setOnClickListener {
            viewModel.disconnect()
        }

        binding.themeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.themeLight -> AppThemeMode.LIGHT
                R.id.themeSystem -> AppThemeMode.SYSTEM
                else -> AppThemeMode.DARK
            }
            if (viewModel.themeMode.value != mode) {
                viewModel.setTheme(mode)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.boundDevice.collect { device ->
                        binding.settingsBoundDevice.text = if (device == null) {
                            getString(R.string.not_bound)
                        } else {
                            getString(R.string.bound_to, device.name)
                        }
                    }
                }
                launch {
                    viewModel.statusMessage.collect { status ->
                        binding.settingsConnectionStatus.text =
                            "${getString(R.string.settings_connection_status)}: $status"
                    }
                }
                launch {
                    viewModel.connectionState.collect { /* keep collector alive for UI consistency */ }
                }
                launch {
                    viewModel.themeMode.collect { mode ->
                        val id = when (mode) {
                            AppThemeMode.LIGHT -> R.id.themeLight
                            AppThemeMode.SYSTEM -> R.id.themeSystem
                            AppThemeMode.DARK -> R.id.themeDark
                        }
                        if (binding.themeToggle.checkedButtonId != id) {
                            binding.themeToggle.check(id)
                        }
                    }
                }
                launch {
                    viewModel.loraRadio.collect { radio ->
                        renderLoraRadio(radio)
                    }
                }
            }
        }
    }

    private fun renderLoraRadio(radio: LoraRadioConfig) {
        val freqMhz = radio.frequencyHz / 1_000_000.0
        binding.loraFrequency.text = String.format(
            Locale.US,
            "%s: %.3f MHz",
            getString(R.string.settings_frequency),
            freqMhz
        )
        binding.loraSf.text = "${getString(R.string.settings_sf)}: SF${radio.spreadingFactor}"
        binding.loraBw.text = "${getString(R.string.settings_bw)}: ${radio.bandwidthKhz} kHz"
        binding.loraCr.text = "${getString(R.string.settings_cr)}: 4/${radio.codingRate}"
        binding.loraTx.text = "${getString(R.string.settings_tx)}: ${radio.txPowerDbm} dBm"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
