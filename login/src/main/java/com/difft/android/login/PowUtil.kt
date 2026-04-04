package com.difft.android.login

import com.difft.android.base.log.lumberjack.L
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield


object PowUtil {

    private const val ALGORITHM = "SHA-256"

    // 根据 uuid, timestamp, version 和 solution 进行 PoW 验证
    private fun verifySolution(uuid: String, timestamp: Long, version: Int, solution: String, difficulty: Int): Boolean {
        return try {
            val data = "$uuid$timestamp$version$solution"

            // 计算 SHA-256 哈希值
            val digest = MessageDigest.getInstance(ALGORITHM)
            val hash = digest.digest(data.toByteArray())

            // 将哈希转换为十六进制字符串
            val hexHash = hash.joinToString("") { String.format("%02x", it) }

            // 检查哈希值的前 difficulty 位是否为 "1"
            hexHash.startsWith("1".repeat(difficulty))
        } catch (e: NoSuchAlgorithmException) {
            L.w { "[PowUtil] error: ${e.stackTraceToString()}" }
            false
        }
    }

    // Generate a solution meeting the difficulty requirement (runs on Dispatchers.Default to avoid blocking the main thread)
    suspend fun generateSolution(uuid: String, timestamp: Long, version: Int, difficulty: Int): String = withContext(Dispatchers.Default) {
        L.i { "[PowUtil] start generate solution, difficulty=$difficulty" }

        val randomString = RandomString(30)
        var attempts = 0
        val startTime = System.currentTimeMillis()
        var result: String? = null

        while (result == null) {
            val candidate = randomString.nextString()
            if (verifySolution(uuid, timestamp, version, candidate, difficulty)) {
                result = candidate
            }
            attempts++
            // Yield every 1000 iterations to support cooperative cancellation
            if (attempts % 1000 == 0) yield()
        }

        L.i { "[PowUtil] Solution found, attempts=$attempts, timeTaken=${System.currentTimeMillis() - startTime}ms" }
        result
    }
}

class RandomString(private val length: Int) {
    private val characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    fun nextString(): String {
        val random = Random.Default
        return (1..length)
            .map { characters[random.nextInt(characters.length)] }
            .joinToString("")
    }
}