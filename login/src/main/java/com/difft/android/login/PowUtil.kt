package com.difft.android.login

import com.difft.android.base.log.lumberjack.L
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import kotlin.random.Random


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

            // 打印哈希值供调试使用
//            println("Hash: $hexHash")

            // 检查哈希值的前 difficulty 位是否为 "1"
            hexHash.startsWith("1".repeat(difficulty))
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
            false
        }
    }

    // 生成符合难度要求的 solution
    fun generateSolution(uuid: String, timestamp: Long, version: Int, difficulty: Int): String {
        L.i { "[PowUtil] start generate solution: $uuid $timestamp $version $difficulty" }

        var solution: String
        var attempts = 0
        val startTime = System.currentTimeMillis()

        while (true) {
            solution = RandomString(30).nextString()  // 生成一个 30 位的随机字符串

            // 验证当前 solution 是否符合 PoW 难度
            if (verifySolution(uuid, timestamp, version, solution, difficulty)) {
                L.i { "[PowUtil] Solution found: $solution Attempts: $attempts Time taken: ${System.currentTimeMillis() - startTime} ms" }
                return solution
            }
            attempts++
        }
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