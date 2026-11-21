package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DocumentEntity::class,
        PageEntity::class,
        OcrResultEntity::class,
        GeminiAnalysisEntity::class,
        ReviewItemEntity::class,
        PreferencesEntity::class,
        ApiKeySlotEntity::class,
    ],
    version = 7,
    exportSchema = false,
)
abstract class MuharrirDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun pageDao(): PageDao
    abstract fun ocrResultDao(): OcrResultDao
    abstract fun geminiAnalysisDao(): GeminiAnalysisDao
    abstract fun reviewItemDao(): ReviewItemDao
    abstract fun preferencesDao(): PreferencesDao
    abstract fun apiKeySlotDao(): ApiKeySlotDao

    companion object {
        @Volatile
        private var instance: MuharrirDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN ocrStatus TEXT NOT NULL DEFAULT 'PENDING'")
                db.execSQL("ALTER TABLE pages ADD COLUMN ocrEngineUsed TEXT")
                db.execSQL("ALTER TABLE pages ADD COLUMN ocrErrorMessageAr TEXT")
                db.execSQL("ALTER TABLE pages ADD COLUMN ocrUpdatedAtMillis INTEGER")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ocr_results (
                        pageId TEXT NOT NULL PRIMARY KEY,
                        documentId TEXT NOT NULL,
                        pageNumber INTEGER NOT NULL,
                        engineType TEXT NOT NULL,
                        recognizedText TEXT NOT NULL,
                        segmentsJson TEXT NOT NULL,
                        confidence REAL,
                        imageWidthPx INTEGER NOT NULL,
                        imageHeightPx INTEGER NOT NULL,
                        recognizedAtMillis INTEGER NOT NULL,
                        FOREIGN KEY(pageId) REFERENCES pages(pageId) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ocr_results_documentId_pageNumber ON ocr_results(documentId, pageNumber)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS gemini_page_analyses (
                        pageId TEXT NOT NULL PRIMARY KEY,
                        documentId TEXT NOT NULL,
                        pageNumber INTEGER NOT NULL,
                        modelId TEXT NOT NULL,
                        usedKeyLabel TEXT NOT NULL,
                        rawValidatedJson TEXT NOT NULL,
                        itemsCount INTEGER NOT NULL,
                        analyzedAtMillis INTEGER NOT NULL,
                        FOREIGN KEY(pageId) REFERENCES pages(pageId) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_gemini_page_analyses_documentId_pageNumber ON gemini_page_analyses(documentId, pageNumber)",
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS review_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        document_id TEXT NOT NULL,
                        page_id TEXT NOT NULL,
                        ocr_result_id TEXT,
                        gemini_analysis_id TEXT,
                        page_number INTEGER NOT NULL,
                        x1 INTEGER NOT NULL,
                        y1 INTEGER NOT NULL,
                        x2 INTEGER NOT NULL,
                        y2 INTEGER NOT NULL,
                        ocr_text TEXT NOT NULL,
                        suggested_text TEXT,
                        ocr_confidence REAL,
                        gemini_confidence REAL,
                        category TEXT NOT NULL,
                        priority INTEGER NOT NULL,
                        review_status TEXT NOT NULL,
                        user_text TEXT,
                        evidence TEXT NOT NULL,
                        reason_type TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        resolved_at INTEGER,
                        block_index INTEGER NOT NULL,
                        line_index INTEGER NOT NULL,
                        element_index INTEGER NOT NULL,
                        stale_note_ar TEXT,
                        FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE,
                        FOREIGN KEY(page_id) REFERENCES pages(pageId) ON DELETE CASCADE,
                        FOREIGN KEY(ocr_result_id) REFERENCES ocr_results(pageId) ON DELETE SET NULL,
                        FOREIGN KEY(gemini_analysis_id) REFERENCES gemini_page_analyses(pageId) ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_review_items_document_id ON review_items(document_id)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_review_items_page_id ON review_items(page_id)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_review_items_review_status ON review_items(review_status)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_review_items_priority ON review_items(priority)",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN reviewApprovedAt INTEGER")
                db.execSQL("ALTER TABLE documents ADD COLUMN exportedDocxPath TEXT")
                db.execSQL("ALTER TABLE documents ADD COLUMN exportedMode TEXT")
                db.execSQL("ALTER TABLE documents ADD COLUMN exportedAtMillis INTEGER")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN hasMissingContent INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN failureReason TEXT")
            }
        }

        fun getInstance(context: Context): MuharrirDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MuharrirDatabase::class.java,
                    "muharrir_ai.db",
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                    )
                    .build()
                    .also { instance = it }
            }
        }
    }
}
