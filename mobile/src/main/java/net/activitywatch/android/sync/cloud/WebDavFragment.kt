package net.activitywatch.android.sync.cloud

/** WebDAV 云备份设置页（实验性） */
class WebDavFragment : CloudSyncBaseFragment() {

    override val toolbarTitle = "WebDAV（实验性）"
    override val isWebDav = true
    override val prefPrefix = "webdav_"

    override fun loadFields() {
        binding.tetWebdavUrl.setText(prefs.getString("${prefPrefix}url", ""))
        binding.tetWebdavUser.setText(prefs.getString("${prefPrefix}user", ""))
        binding.tetWebdavPass.setText(prefs.getString("${prefPrefix}pass", ""))
        binding.tetWebdavDir.setText(prefs.getString("${prefPrefix}dir", ""))
    }

    override fun saveFields() {
        prefs.edit()
            .putString("${prefPrefix}url", binding.tetWebdavUrl.text?.toString()?.trim().orEmpty())
            .putString("${prefPrefix}user", binding.tetWebdavUser.text?.toString()?.trim().orEmpty())
            .putString("${prefPrefix}pass", binding.tetWebdavPass.text?.toString().orEmpty())
            .putString("${prefPrefix}dir", binding.tetWebdavDir.text?.toString()?.trim().orEmpty())
            .putString("${prefPrefix}file", fileName())
            .apply()
    }

    override fun makeClient(): CloudClient {
        val url = binding.tetWebdavUrl.text?.toString()?.trim().orEmpty()
        if (url.isEmpty()) throw CloudSyncException("请先填写服务器地址")
        return WebDavClient(
            baseUrl = url,
            username = binding.tetWebdavUser.text?.toString()?.trim().orEmpty(),
            password = binding.tetWebdavPass.text?.toString().orEmpty(),
            remoteDir = binding.tetWebdavDir.text?.toString()?.trim()?.trim('/').orEmpty()
        )
    }
}
