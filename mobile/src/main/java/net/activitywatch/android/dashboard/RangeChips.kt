package net.activitywatch.android.dashboard

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch
import net.activitywatch.android.R

/**
 * 时间范围 chips 与 DashboardViewModel 的绑定：用户点选触发 load(range)，
 * VM 的 range 变化（含其他 Tab 改的）回写选中态。syncing 防止程序化
 * check 触发的回调再次 load 造成循环。
 *
 * 概览 / 时间线 / 趋势 三个 Tab 各调一次；碎片页不绑（按天翻天导航）。
 */
fun bindRangeChips(chips: ChipGroup, owner: LifecycleOwner, vm: DashboardViewModel) {
    val ids = mapOf(
        R.id.chip_today to TimeRange.TODAY,
        R.id.chip_yesterday to TimeRange.YESTERDAY,
        R.id.chip_last7 to TimeRange.LAST7,
        R.id.chip_last30 to TimeRange.LAST30,
        R.id.chip_all to TimeRange.ALL,
    )
    var syncing = false
    chips.setOnCheckedChangeListener { _, checkedId ->
        if (syncing) return@setOnCheckedChangeListener
        ids[checkedId]?.let(vm::load)
    }
    owner.lifecycleScope.launch {
        vm.state.collect { s ->
            val target = ids.entries.firstOrNull { it.value == s.range }?.key ?: return@collect
            if (chips.checkedChipId != target) {
                syncing = true
                chips.check(target)
                syncing = false
            }
        }
    }
}
