package dev.remux.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun authToString(a: AuthType): String = a.name
    @TypeConverter fun stringToAuth(s: String): AuthType = AuthType.valueOf(s)
    @TypeConverter fun modeToString(m: ConnectMode): String = m.name
    @TypeConverter fun stringToMode(s: String): ConnectMode = ConnectMode.valueOf(s)
}

@Database(entities = [HostEntity::class, RelayDeviceEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class RemuxDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun relayDeviceDao(): RelayDeviceDao

    companion object {
        @Volatile private var instance: RemuxDatabase? = null

        fun get(context: Context): RemuxDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                RemuxDatabase::class.java,
                "remux.db",
            ).build().also { instance = it }
        }
    }
}
