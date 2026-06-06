package com.mtos.web.browser.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class Bookmark(
    @PrimaryKey val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "history_items")
data class HistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val url: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "browser_tabs")
data class TabEntity(
    @PrimaryKey val id: String,
    val url: String,
    val title: String,
    val jsEnabled: Boolean,
    val desktopMode: Boolean,
    val lastActive: Boolean,
    val displayOrder: Int
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val url: String,
    val size: String,
    val progress: Float,
    val isCompleted: Boolean,
    val filePath: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "password_credentials")
data class PasswordCredential(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val websiteUrl: String,
    val username: String,
    val encryptedPassword: String,
    val labelName: String = "",
    val timestamp: Long = System.currentTimeMillis()
)



