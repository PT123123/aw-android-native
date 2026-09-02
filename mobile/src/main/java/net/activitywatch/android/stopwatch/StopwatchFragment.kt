package net.activitywatch.android.stopwatch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.activitywatch.android.dashboard.formatHms
import net.activitywatch.android.databinding.FragmentStopwatchBinding

/**
 * 秒表页（对应 aw-webui 的 Stopwatch）。
 *
 * 手动计时，停止后把一段事件写回本机 aw-server 的 aw-stopwatch-android bucket，
 * 因此这些时长会立刻出现在活动页的统计里。
 */
class StopwatchFragment : Fragment() {
    private var _binding: FragmentStopwatchBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: StopwatchViewModel
    private lateinit var recordAdapter: StopwatchRecordAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentStopwatchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[StopwatchViewModel::class.java]

        recordAdapter = StopwatchRecordAdapter()
        binding.rvRecords.adapter = recordAdapter

        binding.btnToggle.setOnClickListener {
            if (viewModel.isRunning) viewModel.pause() else viewModel.start()
            renderControls()
        }
        binding.btnStop.setOnClickListener {
            viewModel.save(binding.etLabel.text?.toString().orEmpty())
            renderControls()
        }
        binding.btnReset.setOnClickListener {
            viewModel.reset()
            renderControls()
        }

        startTicker()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.records.collect {
                recordAdapter.submit(it)
                binding.tvEmpty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.status.collect { msg ->
                if (!msg.isNullOrBlank()) binding.tvStatus.text = msg
            }
        }

        renderControls()
    }

    /** 计时读数的刷新循环：ViewModel 只存起点，读数现算，所以这里只管刷新。 */
    private fun startTicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val b = _binding ?: break
                b.tvClock.text = formatHms(viewModel.currentMs())
                b.btnStop.isEnabled = viewModel.currentMs() > 0
                delay(200)
            }
        }
    }

    private fun renderControls() {
        binding.btnToggle.text = if (viewModel.isRunning) "暂停" else "开始"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
