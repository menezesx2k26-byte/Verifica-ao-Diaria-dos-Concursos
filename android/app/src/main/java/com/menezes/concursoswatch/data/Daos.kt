package com.menezes.concursoswatch.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ContestDao {
    @Query("SELECT * FROM contests WHERE active = 1 OR favorite = 1 ORDER BY unread DESC, priority DESC, first_seen DESC")
    suspend fun visible(): List<ContestEntity>

    @Query("SELECT * FROM contests")
    suspend fun all(): List<ContestEntity>

    @Query("SELECT * FROM contests WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): ContestEntity?

    @Upsert
    suspend fun upsert(items: List<ContestEntity>)

    @Query("UPDATE contests SET active = 0 WHERE sync_generation < :generation")
    suspend fun archiveMissing(generation: Long)

    @Query("UPDATE contests SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE contests SET unread = 0 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("UPDATE contests SET unread = 0")
    suspend fun markAllRead()
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts ORDER BY unread DESC, created_at DESC, id DESC")
    suspend fun all(): List<AlertEntity>
    @Query("SELECT * FROM alerts WHERE id = :id LIMIT 1") suspend fun byId(id: Int): AlertEntity?
    @Upsert suspend fun upsert(items: List<AlertEntity>)
    @Query("UPDATE alerts SET unread = 0 WHERE id = :id") suspend fun markRead(id: Int)
    @Query("UPDATE alerts SET unread = 0") suspend fun markAllRead()
}

@Dao
interface SourceHealthDao {
    @Query("SELECT * FROM source_health ORDER BY semantic_ok ASC, label ASC") suspend fun all(): List<SourceHealthEntity>
    @Upsert suspend fun upsert(items: List<SourceHealthEntity>)
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE `key` = :key LIMIT 1") suspend fun get(key: String): String?
    @Upsert suspend fun put(item: MetaEntity)
}
