package com.magix.pistarlink

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.database.Cursor
import android.util.Log

class DbHandler(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_VERSION = 1
        private const val DATABASE_NAME = "example.db"

        // Configurations table constants
        private const val TABLE_CONFIGURATIONS = "configurations"
        private const val COLUMN_ID = "id"
        private const val COLUMN_CONFIG_NAME = "config_name"
        private const val COLUMN_CONFIG_VALUE = "config_value"
        private const val COLUMN_TIMESTAMP = "timestamp"
    }

    // SQL statement to create the configurations table
    private val CREATE_CONFIGURATIONS_TABLE = """
        CREATE TABLE $TABLE_CONFIGURATIONS (
            $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
            $COLUMN_CONFIG_NAME TEXT NOT NULL,
            $COLUMN_CONFIG_VALUE TEXT NOT NULL,
            $COLUMN_TIMESTAMP DATETIME DEFAULT CURRENT_TIMESTAMP
        )
    """

    private val DROP_CONFIGURATIONS_TABLE = "DROP TABLE IF EXISTS $TABLE_CONFIGURATIONS"

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_CONFIGURATIONS_TABLE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL(DROP_CONFIGURATIONS_TABLE)
        onCreate(db)
    }

    // Method to add a new configuration if it doesn't exist
    fun addConfiguration(configName: String, configValue: String) {
        Log.d("DbHandler","Inserting new config: $configName, $configValue")
        val db = this.writableDatabase

        // Check if the configuration already exists
        if (!isConfigurationExists(configName)) {
            val values = ContentValues().apply {
                put(COLUMN_CONFIG_NAME, configName)
                put(COLUMN_CONFIG_VALUE, configValue)
            }
            db.insert(TABLE_CONFIGURATIONS, null, values)
        }
        db.close()
    }

    // Method to check if a configuration exists
    private fun isConfigurationExists(configName: String): Boolean {
        val db = this.readableDatabase
        val cursor = db.query(
            TABLE_CONFIGURATIONS,
            arrayOf(COLUMN_ID),
            "$COLUMN_CONFIG_NAME=?",
            arrayOf(configName),
            null,
            null,
            null
        )
        val exists = cursor.count > 0
        cursor.close()
        return exists
    }

    // Method to get a configuration value by its name
    fun getConfiguration(configName: String): String? {
        val db = this.readableDatabase
        var configValue: String? = null
        val cursor = db.query(
            TABLE_CONFIGURATIONS,
            arrayOf(COLUMN_CONFIG_VALUE),
            "$COLUMN_CONFIG_NAME=?",
            arrayOf(configName),
            null,
            null,
            null
        )

        if (cursor.moveToFirst()) {
            configValue = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_CONFIG_VALUE))
        }

        cursor.close()
        return configValue
    }


    // Method to update a configuration
    fun updateConfiguration(configName: String, configValue: String): Int {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_CONFIG_NAME, configName)
            put(COLUMN_CONFIG_VALUE, configValue)
        }
        return db.update(
            TABLE_CONFIGURATIONS,
            values,
            "$COLUMN_CONFIG_NAME=?",
            arrayOf(configName)
        )
    }

    // Method to delete a configuration
    fun deleteConfiguration(id: Int): Int {
        val db = this.writableDatabase
        return db.delete(
            TABLE_CONFIGURATIONS,
            "$COLUMN_ID=?",
            arrayOf(id.toString())
        )
    }

    // Method to get all configurations
    fun getAllConfigurations(): Cursor? {
        val db = this.readableDatabase
        return db.query(
            TABLE_CONFIGURATIONS,
            arrayOf(COLUMN_ID, COLUMN_CONFIG_NAME, COLUMN_CONFIG_VALUE, COLUMN_TIMESTAMP),
            null,
            null,
            null,
            null,
            "$COLUMN_TIMESTAMP DESC" // Order by latest timestamp
        )
    }
}