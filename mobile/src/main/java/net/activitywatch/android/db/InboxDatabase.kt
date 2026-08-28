package net.activitywatch.android.db

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

@Database(
    entities = [
        InboxNoteEntity::class,
        SyncStateEntity::class,
        SyncDeviceEntity::class,
        SyncConflictEntity::class,
        SyncLogEntity::class,
        NoteSyncMapEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class InboxDatabase : RoomDatabase() {
    abstract fun inboxNoteDao(): InboxNoteDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun syncDeviceDao(): SyncDeviceDao
    abstract fun syncConflictDao(): SyncConflictDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun noteSyncMapDao(): NoteSyncMapDao

    companion object {
        @Volatile private var INSTANCE: InboxDatabase? = null

        fun getInstance(context: Context): InboxDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    InboxDatabase::class.java,
                    "inbox_database"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            CoroutineScope(Dispatchers.IO).launch {
                                // 初始化当前设备
                                val dao = INSTANCE?.syncDeviceDao()
                                dao?.upsert(SyncDeviceEntity(
                                    deviceId = DeviceIdProvider.getDeviceId(context),
                                    name = DeviceIdProvider.getDeviceName(context),
                                    platform = "android",
                                    lastSeenAt = System.currentTimeMillis(),
                                    isCurrent = true,
                                    status = "ONLINE"
                                ))
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

object DeviceIdProvider {
    private const val PREFS_NAME = "device_id_prefs"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var deviceId = prefs.getString(KEY_DEVICE_ID, null)
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        }
        return deviceId
    }

    fun getDeviceName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var deviceName = prefs.getString(KEY_DEVICE_NAME, null)
        if (deviceName == null || deviceName.isEmpty()) {
            deviceName = android.os.Build.MODEL
            prefs.edit().putString(KEY_DEVICE_NAME, deviceName).apply()
        }
        return deviceName!!
    }

    fun setDeviceName(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply()
    }
}