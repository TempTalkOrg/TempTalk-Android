package com.difft.android.chat.common

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import java.util.Random

class RandomAvatarGenerator {

    companion object {
        // 默认的大小
        private const val DEFAULT_SIZE = 8

        // 随机种子
        private val random = Random()

        /**
         * 生成一个随机的 Blockies 头像 Bitmap
         * 不需要传入任何参数
         */
        fun generateRandomBitmap(): Bitmap {
            // 使用随机种子生成 Blockies 数据
            val seed = generateRandomSeed()
            val data = RandomAvatarData(seed, DEFAULT_SIZE)

            // 获取生成的图像数据和颜色
            val imageData = data.getImageData()
            val colors = data.getColors()

            // 创建 Bitmap
            val size = DEFAULT_SIZE
            val blockSize = 32 // 每个块的大小
            val bitmap = Bitmap.createBitmap(size * blockSize, size * blockSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            val paint = Paint()
            val backgroundPaint = Paint()
            backgroundPaint.color = colors[1]

            val spotPaint = Paint()
            spotPaint.color = colors[2]

            // 背景颜色填充
            canvas.drawColor(colors[1])

            // 绘制区块
            val blockRect = RectF()
            for (i in imageData.indices) {
                val x = (i % size) * blockSize.toFloat()
                val y = (i / size) * blockSize.toFloat()
                blockRect.set(x, y, x + blockSize, y + blockSize)

                when (imageData[i]) {
                    1 -> {
                        paint.color = colors[0]
                        canvas.drawRect(blockRect, paint)
                    }

                    2 -> {
                        canvas.drawRect(blockRect, spotPaint)
                    }
                }
            }

            return bitmap
        }

        /**
         * 生成一个随机的种子字符串
         */
        private fun generateRandomSeed(): String {
            val seedChars = "abcdefghijklmnopqrstuvwxyz0123456789"
            val seedLength = 8 // 可以调整种子长度
            val seedBuilder = StringBuilder()

            for (i in 0 until seedLength) {
                val randomIndex = random.nextInt(seedChars.length)
                seedBuilder.append(seedChars[randomIndex])
            }

            return seedBuilder.toString()
        }
    }
}

/**
 * Blockies 头像数据生成器
 */
class RandomAvatarData(seed: String, size: Int) {
    private val randSeed = IntArray(4)
    private var imageData: IntArray
    private val color: Int
    private val bgColor: Int
    private val spotColor: Int

    init {
        seedRand(seed)
        imageData = createImageData(size)
        color = createColor()
        bgColor = createColor()
        spotColor = createColor()
    }

    private fun seedRand(seed: String) {
        for (i in seed.indices) {
            randSeed[i % 4] = ((randSeed[i % 4] shl 5) - randSeed[i % 4]) + seed[i].toInt()
        }
    }

    private fun rand(): Double {
        val t = randSeed[0] xor (randSeed[0] shl 11)
        randSeed[0] = randSeed[1]
        randSeed[1] = randSeed[2]
        randSeed[2] = randSeed[3]
        randSeed[3] = randSeed[3] xor (randSeed[3] ushr 19) xor t xor (t ushr 8)
        val num = (randSeed[3] ushr 0).toDouble()
        val den = (1 shl 31).toDouble()
        return Math.abs(num / den)
    }

    private fun createColor(): Int {
        val h = (rand() * 360).toFloat()
        val s = (rand() * 60 + 40) / 100f
        val l = ((rand() + rand() + rand() + rand()) * 25) / 100f
        return ColorUtils.HSLToColor(floatArrayOf(h, s.toFloat(), l.toFloat()))
    }

    private fun createImageData(size: Int): IntArray {
        val width = size
        val height = size
        val dataWidth = Math.ceil((width / 2).toDouble()).toInt()
        val mirrorWidth = width - dataWidth
        val data = mutableListOf<Int>()

        for (y in 0 until height) {
            val row = mutableListOf<Int>()
            for (x in 0 until dataWidth) {
                val r = rand() * 2.3
                val d = Math.floor(r).toInt()
                row.add(d)
            }

            val mirroredRow = row.take(mirrorWidth).reversed()
            data.addAll(row + mirroredRow)
        }

        return data.toIntArray()
    }

    fun getImageData(): IntArray = imageData

    fun getColors(): IntArray = intArrayOf(color, bgColor, spotColor)
}
