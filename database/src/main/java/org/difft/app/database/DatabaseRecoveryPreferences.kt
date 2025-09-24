package org.difft.app.database

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseRecoveryPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val sharedPreferences: SharedPreferences by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREF_NAME = "wcdb_recovery_data"
        private const val KEY_NEED_RECOVERY_DATABASE = "needRecoveryDatabase"
        private const val KEY_DATABASE_RECOVERY_FAILURE_COUNT = "databaseRecoveryFailureCount"
    }

    /**
     * 检查是否需要恢复数据库
     */
    fun isRecoveryNeeded(): Boolean {
        return sharedPreferences.getBoolean(KEY_NEED_RECOVERY_DATABASE, false)
    }

    /**
     * 设置需要恢复数据库
     */
    fun setRecoveryNeeded() {
        return sharedPreferences.edit(commit = true) {
            putBoolean(KEY_NEED_RECOVERY_DATABASE, true)
            putInt(KEY_DATABASE_RECOVERY_FAILURE_COUNT, 0)
        }
    }

    /**
     * 清除恢复标记
     */
    fun clearRecoveryFlag() {
        return sharedPreferences.edit(commit = true) {
            putBoolean(KEY_NEED_RECOVERY_DATABASE, false)
            putInt(KEY_DATABASE_RECOVERY_FAILURE_COUNT, 0)
        }
    }

    /**
     * 获取恢复失败次数
     */
    fun getRecoveryFailureCount(): Int {
        return sharedPreferences.getInt(KEY_DATABASE_RECOVERY_FAILURE_COUNT, 0)
    }

    /**
     * 增加恢复失败次数
     */
    fun incrementRecoveryFailureCount() {
        val currentCount = getRecoveryFailureCount()
        return sharedPreferences.edit(commit = true) {
            putBoolean(KEY_NEED_RECOVERY_DATABASE, false) // 清除标记，下次重新检测
            putInt(KEY_DATABASE_RECOVERY_FAILURE_COUNT, currentCount + 1)
        }
    }

    /**
     * 重置恢复失败次数
     */
    fun resetRecoveryFailureCount() {
        return sharedPreferences.edit(commit = true) {
            putInt(KEY_DATABASE_RECOVERY_FAILURE_COUNT, 0)
        }
    }

    /**
     * 检查是否超过最大重试次数
     */
    fun isMaxRetriesReached(maxRetries: Int = 3): Boolean {
        return getRecoveryFailureCount() >= maxRetries
    }

    /**
     * 清除所有恢复相关数据
     */
    fun clearAllRecoveryData() {
        return sharedPreferences.edit(commit = true) {
            remove(KEY_NEED_RECOVERY_DATABASE)
            remove(KEY_DATABASE_RECOVERY_FAILURE_COUNT)
        }
    }
} 