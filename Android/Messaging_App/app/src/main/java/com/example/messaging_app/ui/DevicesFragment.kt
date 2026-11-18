package com.example.messaging_app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.messaging_app.MessagingApplication
import com.example.messaging_app.R
import com.example.messaging_app.databinding.FragmentDevicesBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class DevicesFragment : Fragment() {

    private var _binding: FragmentDevicesBinding? = null
    private val binding get() = _binding!!

    private val viewModel: DevicesViewModel by viewModels {
        val repo = (requireActivity().application as MessagingApplication).container.chatRepository
        DevicesViewModel.Factory(repo)
    }

    private lateinit var adapter: DeviceAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDevicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DeviceAdapter { device ->
            viewModel.bind(device)
            Snackbar.make(
                binding.root,
                getString(R.string.device_bound, device.name),
                Snackbar.LENGTH_SHORT
            ).show()
        }
        binding.deviceList.layoutManager = LinearLayoutManager(requireContext())
        binding.deviceList.itemAnimator = null
        binding.deviceList.setHasFixedSize(true)
        binding.deviceList.adapter = adapter

        binding.scanButton.setOnClickListener {
            (activity as? MainActivityHost)?.ensureBleReady {
                viewModel.startScan()
            }
        }
        binding.swipeRefresh.setOnRefreshListener {
            (activity as? MainActivityHost)?.ensureBleReady {
                viewModel.startScan()
            }
        }
        binding.unbindButton.setOnClickListener { viewModel.unbind() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.scannedDevices.collect { devices ->
                        adapter.submitList(devices)
                        binding.emptyDevices.isVisible = devices.isEmpty()
                    }
                }
                launch {
                    viewModel.boundDevice.collect { device ->
                        if (device == null) {
                            binding.boundName.text = getString(R.string.not_bound)
                            binding.boundAddress.text = ""
                            binding.unbindButton.isVisible = false
                        } else {
                            binding.boundName.text = device.name
                            binding.boundAddress.text = device.address
                            binding.unbindButton.isVisible = true
                        }
                    }
                }
                launch {
                    viewModel.isScanning.collect { scanning ->
                        binding.swipeRefresh.isRefreshing = scanning
                        binding.scanButton.text = getString(
                            if (scanning) R.string.scanning else R.string.scan_devices
                        )
                        binding.scanButton.isEnabled = !scanning
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

interface MainActivityHost {
    fun ensureBleReady(onReady: () -> Unit)
}
