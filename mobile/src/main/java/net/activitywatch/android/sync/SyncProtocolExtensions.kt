package net.activitywatch.android.sync

import net.activitywatch.android.db.InboxNoteEntity
import org.threeten.bp.Instant

fun InboxNote.toEntity(deviceId: String): InboxNoteEntity {
    val tagsJson = com.google.gson.Gson().toJson(tags)
    return InboxNoteEntity(
        serverId = id,
        content = content,
        timestamp = Instant.parse(timestamp).toEpochMilli(),
        updatedAt = Instant.parse(updatedAt).toEpochMilli(),
        tags = tagsJson,
        version = version,
        deviceId = deviceId,
        deleted = deleted
    )
}

fun LocalNote.toInboxNote(): InboxNote {
    return InboxNote(
        id = serverId,
        content = content,
        timestamp = Instant.ofEpochMilli(timestamp).toString(),
        updatedAt = Instant.ofEpochMilli(updatedAt).toString(),
        tags = tags,
        version = version,
        deviceId = deviceId,
        deleted = deleted
    )
}

fun InboxNote.toLocalNote(deviceId: String): LocalNote {
    return LocalNote(
        serverId = id,
        content = content,
        timestamp = Instant.parse(timestamp).toEpochMilli(),
        updatedAt = Instant.parse(updatedAt).toEpochMilli(),
        tags = tags,
        version = version,
        deviceId = deviceId,
        deleted = deleted,
        syncedAt = syncedAt,
        pendingSync = false,
        localVersion = 0
    )
}