package com.difft.android.base.utils

import java.math.BigInteger

object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE = BigInteger.valueOf(58)

    fun encode(input: ByteArray): String {
        var value = BigInteger(1, input)
        val result = StringBuilder()

        while (value > BigInteger.ZERO) {
            val (quotient, remainder) = value.divideAndRemainder(BASE)
            value = quotient
            val index = remainder.toInt()
            result.insert(0, ALPHABET[index])
        }

        // Pad leading zeros
        for (b in input) {
            if (b == 0x00.toByte()) {
                result.insert(0, ALPHABET[0])
            } else {
                break
            }
        }

        return result.toString()
    }

    fun encode(input: Long): String {
        var value = BigInteger.valueOf(input)
        val result = StringBuilder()

        while (value > BigInteger.ZERO) {
            val (quotient, remainder) = value.divideAndRemainder(BASE)
            value = quotient
            val index = remainder.toInt()
            result.insert(0, ALPHABET[index])
        }

        return result.toString()
    }

    fun decode(input: String): ByteArray {
        var value = BigInteger.ZERO

        for (c in input) {
            val digit = ALPHABET.indexOf(c)
            if (digit == -1) {
                throw IllegalArgumentException("Invalid Base58 character: $c")
            }
            value = value.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }

        var bytes = value.toByteArray()

        // Remove the leading zero byte added by BigInteger.toByteArray()
        if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }

        // Pad leading zeros
        for (c in input) {
            if (c == ALPHABET[0]) {
                val tmp = ByteArray(bytes.size + 1)
                System.arraycopy(bytes, 0, tmp, 1, bytes.size)
                bytes = tmp
            } else {
                break
            }
        }

        return bytes
    }

    fun decodeToLong(input: String): Long {
        var value = BigInteger.ZERO

        for (c in input) {
            val digit = ALPHABET.indexOf(c)
            if (digit == -1) {
                throw IllegalArgumentException("Invalid Base58 character: $c")
            }
            value = value.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }

        return value.toLong()
    }
}
