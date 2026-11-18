package com.example.messaging_app.ui

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.messaging_app.MessagingApplication
import com.example.messaging_app.R
import com.example.messaging_app.data.GpsFix
import com.example.messaging_app.databinding.FragmentMapBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * GPS screen for the local T-Beam NEO-6M fix delivered over BLE.
 */
class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MapViewModel by viewModels {
        val repo = (requireActivity().application as MessagingApplication).container.chatRepository
        MapViewModel.Factory(repo)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.gpsFix, viewModel.peers) { fix, peers ->
                    fix to peers.size
                }.collect { (fix, peerCount) ->
                    renderGps(fix)
                    binding.mapNodeHint.text = getString(
                        R.string.map_nodes_hint,
                        peerCount,
                        if (fix.hasFix) 1 else 0
                    )
                }
            }
        }
    }

    private fun renderGps(fix: GpsFix) {
        val unavailable = getString(R.string.value_unavailable)
        if (fix.updatedAtMillis == 0L) {
            binding.gpsFixStatus.text = "${getString(R.string.map_fix_status)}: $unavailable"
            binding.gpsLatitude.text = "${getString(R.string.map_latitude)}: $unavailable"
            binding.gpsLongitude.text = "${getString(R.string.map_longitude)}: $unavailable"
            binding.gpsSatellites.text = "${getString(R.string.map_satellites)}: $unavailable"
            binding.gpsSpeed.text = "${getString(R.string.map_speed)}: $unavailable"
            binding.gpsLastUpdate.text = "${getString(R.string.map_last_update)}: —"
            return
        }

        val status = if (fix.hasFix) {
            getString(R.string.map_fix_ok)
        } else {
            getString(R.string.map_fix_searching)
        }
        binding.gpsFixStatus.text = "${getString(R.string.map_fix_status)}: $status"

        if (fix.hasFix) {
            binding.gpsLatitude.text = String.format(
                Locale.US,
                "%s: %.6f",
                getString(R.string.map_latitude),
                fix.latitude
            )
            binding.gpsLongitude.text = String.format(
                Locale.US,
                "%s: %.6f",
                getString(R.string.map_longitude),
                fix.longitude
            )
        } else {
            binding.gpsLatitude.text = "${getString(R.string.map_latitude)}: $unavailable"
            binding.gpsLongitude.text = "${getString(R.string.map_longitude)}: $unavailable"
        }

        binding.gpsSatellites.text = "${getString(R.string.map_satellites)}: ${fix.satellites}"
        binding.gpsSpeed.text = String.format(
            Locale.US,
            "%s: %.1f km/h",
            getString(R.string.map_speed),
            fix.speedKmh
        )
        binding.gpsLastUpdate.text = "${getString(R.string.map_last_update)}: " +
            DateUtils.getRelativeTimeSpanString(
                fix.updatedAtMillis,
                System.currentTimeMillis(),
                DateUtils.SECOND_IN_MILLIS
            )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
