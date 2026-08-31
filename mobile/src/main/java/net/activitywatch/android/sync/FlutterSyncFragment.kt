package net.activitywatch.android.sync

import io.flutter.embedding.android.FlutterFragment

/**
 * 局域网同步页（Flutter 实现），替换原指向 "$baseURL/#/sync/" 的 WebUI 页面。
 * AAR 由 flutter_sync 模块产出（flutter build aar），webui 的 /#/sync/ 路由保留作回退。
 */
class FlutterSyncFragment : FlutterFragment() {
    companion object {
        fun newInstance(): FlutterSyncFragment = FlutterSyncFragment()
    }
}
