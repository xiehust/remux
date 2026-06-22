package dev.remux.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY label")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun byId(id: Long): HostEntity?

    @Upsert
    suspend fun upsert(host: HostEntity): Long

    @Delete
    suspend fun delete(host: HostEntity)
}

@Dao
interface RelayDeviceDao {
    @Query("SELECT * FROM relay_devices ORDER BY name")
    fun observeAll(): Flow<List<RelayDeviceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(devices: List<RelayDeviceEntity>)

    @Query("DELETE FROM relay_devices")
    suspend fun clear()
}
