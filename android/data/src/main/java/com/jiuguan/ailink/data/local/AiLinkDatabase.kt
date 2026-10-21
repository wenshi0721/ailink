package com.jiuguan.ailink.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        AiProfileEntity::class,
        ConversationEntity::class,
        MessageEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AiLinkDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun aiProfileDao(): AiProfileDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        private const val DB_NAME = "ailink.db"

        /**
         * v1 → v2：新增 messages.origin，用于区分 AI 自动回复与手动发送。
         * 用 ALTER TABLE 保留历史消息，避免 fallbackToDestructiveMigration 清库。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN origin TEXT NOT NULL DEFAULT 'MANUAL'"
                )
            }
        }

        @Volatile
        private var instance: AiLinkDatabase? = null

        fun get(context: Context): AiLinkDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AiLinkDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
