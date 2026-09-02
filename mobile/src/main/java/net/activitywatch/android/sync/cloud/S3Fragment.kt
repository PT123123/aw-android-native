package net.activitywatch.android.sync.cloud

/** S3 兼容存储云备份设置页（实验性） */
class S3Fragment : CloudSyncBaseFragment() {

    override val toolbarTitle = "S3（实验性）"
    override val isWebDav = false
    override val prefPrefix = "s3_"

    override fun loadFields() {
        binding.tetS3Endpoint.setText(prefs.getString("${prefPrefix}endpoint", ""))
        binding.tetS3Region.setText(prefs.getString("${prefPrefix}region", DEFAULT_REGION))
        binding.tetS3Bucket.setText(prefs.getString("${prefPrefix}bucket", ""))
        binding.tetS3Ak.setText(prefs.getString("${prefPrefix}ak", ""))
        binding.tetS3Sk.setText(prefs.getString("${prefPrefix}sk", ""))
        binding.tetS3Prefix.setText(prefs.getString("${prefPrefix}prefix", ""))
        binding.switchPathStyle.isChecked = prefs.getBoolean("${prefPrefix}path_style", true)
    }

    override fun saveFields() {
        prefs.edit()
            .putString("${prefPrefix}endpoint", binding.tetS3Endpoint.text?.toString()?.trim().orEmpty())
            .putString("${prefPrefix}region", region())
            .putString("${prefPrefix}bucket", binding.tetS3Bucket.text?.toString()?.trim().orEmpty())
            .putString("${prefPrefix}ak", binding.tetS3Ak.text?.toString()?.trim().orEmpty())
            .putString("${prefPrefix}sk", binding.tetS3Sk.text?.toString().orEmpty())
            .putString("${prefPrefix}prefix", binding.tetS3Prefix.text?.toString()?.trim().orEmpty())
            .putBoolean("${prefPrefix}path_style", binding.switchPathStyle.isChecked)
            .putString("${prefPrefix}file", fileName())
            .apply()
    }

    private fun region(): String =
        binding.tetS3Region.text?.toString()?.trim().orEmpty().ifEmpty { DEFAULT_REGION }

    override fun makeClient(): CloudClient {
        val endpoint = binding.tetS3Endpoint.text?.toString()?.trim().orEmpty()
        val bucket = binding.tetS3Bucket.text?.toString()?.trim().orEmpty()
        val ak = binding.tetS3Ak.text?.toString()?.trim().orEmpty()
        val sk = binding.tetS3Sk.text?.toString().orEmpty()
        if (endpoint.isEmpty()) throw CloudSyncException("请先填写 Endpoint")
        if (bucket.isEmpty()) throw CloudSyncException("请先填写 Bucket")
        if (ak.isEmpty() || sk.isEmpty()) throw CloudSyncException("请填写 Access Key 与 Secret Key")
        return S3Client(
            endpoint = endpoint,
            region = region(),
            bucket = bucket,
            accessKey = ak,
            secretKey = sk,
            prefix = binding.tetS3Prefix.text?.toString()?.trim().orEmpty(),
            pathStyle = binding.switchPathStyle.isChecked
        )
    }

    companion object {
        private const val DEFAULT_REGION = "us-east-1"
    }
}
