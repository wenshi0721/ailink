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
    version = 5,
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

        /**
         * v2 → v3：新增 ai_profiles.visionEnabled，作为识图能力的用户开关。
         * 默认 1（自动检测后启用），既有配置升级后行为与升级前一致。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE ai_profiles ADD COLUMN visionEnabled INTEGER NOT NULL DEFAULT 1"
                )
            }
        }

        /**
         * v3 → v4：新增 messages.imagesJson，把消息附带的图片引用持久化。
         * 默认空串表示"无图"，与升级前行为一致；历史消息不回填（当时也没存）。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN imagesJson TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        /**
         * v4 → v5：AI 配置可自由化——协议、认证方式、接口路径、自定义请求头、
         * 超时、重试、启用/默认、测试结论全部落库。
         *
         * 关键安全约束：旧版本保存过的远程配置一律 `enabled = 0` 且 `needsReview = 1`。
         * 这些地址与密钥可能是过期接口，绝不能在升级后悄悄继续生效；用户必须在
         * 「AI 服务」页确认并手动启用。纯本地模拟回复（baseUrl 为空）保持可用。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_profiles ADD COLUMN protocol TEXT NOT NULL DEFAULT 'OPENAI_COMPAT'")
                db.execSQL("ALTER TABLE ai_profiles ADD COLUMN authStyle TEXT NOT NULL DEFAULT 'BEARER'")
                db.execSQL("ALTER TABLE ai_profiles ADD COLUMN chatPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE ai_profiles ADD COLUMN extraHeaders TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE ai_profiles ADD COLUMN timeoutSec INTEGER NOT NULL DEFAULT 60")
                db.execSQL("ALTER TABLE ai_profiles ADD COLUMN maxRetries INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE ai_profiles ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE ai_profiles ADD COLUMN isDefault INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ai_profiles ADD COLUMN needsReview INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ai_profiles ADD COLUMN lastTestAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ai_profiles ADD COLUMN lastTestOk INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE ai_profiles ADD COLUMN lastTestMessage TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "UPDATE ai_profiles SET enabled = 0, needsReview = 1 " +
                        "WHERE baseUrl IS NOT NULL AND TRIM(baseUrl) <> ''"
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
