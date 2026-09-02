package net.activitywatch.android.queryexplorer

/**
 * 预置的 query2 脚本。
 *
 * bucket id 用的是本 app 的 watcher 实际写入的那几个
 * （见 UsageStatsWatcher / ChromeWatcher），换成桌面端 bucket 需要自行改写。
 */
data class QueryPreset(val title: String, val script: String)

val QUERY_PRESETS = listOf(
    QueryPreset(
        "应用时长榜",
        """
        events = query_bucket("aw-watcher-android-test");
        merged = merge_events_by_keys(events, ["app"]);
        RETURN = sort_by_duration(merged);
        """.trimIndent()
    ),
    QueryPreset(
        "网站时长榜",
        """
        events = query_bucket("aw-watcher-android-web-chrome");
        merged = merge_events_by_keys(events, ["url"]);
        RETURN = sort_by_duration(merged);
        """.trimIndent()
    ),
    QueryPreset(
        "最近 20 条事件",
        """
        events = query_bucket("aw-watcher-android-test");
        RETURN = limit_events(events, 20);
        """.trimIndent()
    ),
    QueryPreset(
        "总记录时长",
        """
        events = query_bucket("aw-watcher-android-test");
        RETURN = sum_durations(events);
        """.trimIndent()
    ),
)
