package com.difft.android.call.util

object StringUtil {

    /**
     * 截断字符串并在末尾添加省略号（...），如果字符串长度超过指定最大值
     *
     * @param text 要处理的原始字符串，如果为空字符串则直接返回
     * @param maxLength 允许的最大长度（必须为正数），如果<=0则：
     *   - 当text非空时返回"..."
     *   - 当text为空时返回空字符串
     * @return 处理后的字符串：
     *   - 如果原始字符串为空或maxLength<=0，返回特殊处理结果（见参数说明）
     *   - 如果原始字符串长度 > maxLength，返回截取的子串+"..."
     *   - 否则返回原始字符串
     */
    fun truncateWithEllipsis(text: String, maxLength: Int): String {
        if (text.isEmpty() || maxLength <= 0) {
            return if (maxLength > 0 && text.isNotEmpty()) "..." else text
        }
        return if (text.length > maxLength) text.substring(0, maxLength) + "..." else text
    }

    /**
     * 将字符串拆分为：前面的文本 + 末尾的一个完整 Emoji（如果存在）
     *
     * 示例：
     * "Agree ✅"      -> ("Agree ", "✅")
     * "Stop ⛔️"      -> ("Stop ", "⛔️")
     * "Nice 👍🏻"     -> ("Nice ", "👍🏻")
     * "Hello"        -> ("Hello", null)
     */
    fun splitTextAndTrailingEmoji(input: String): Pair<String, String?> {
        if (input.isEmpty()) return input to null

        val iterator = java.text.BreakIterator.getCharacterInstance()
        iterator.setText(input)

        val end = iterator.last()
        val start = iterator.previous()
        if (start == java.text.BreakIterator.DONE) {
            return input to null
        }

        val lastGrapheme = input.substring(start, end)

        return if (isEmojiGrapheme(lastGrapheme)) {
            input.substring(0, start) to lastGrapheme
        } else {
            input to null
        }
    }


    /**
     * 判断一个 grapheme 是否是 Emoji
     * 核心规则：
     * 1️⃣ 含 FE0F（emoji 变体选择符） → 一定是 emoji
     * 2️⃣ 含 Emoji Unicode 区段
     * 3️⃣ 覆盖 ZWJ / 国旗 / 修饰符
     */
    fun isEmojiGrapheme(grapheme: String): Boolean {
        val codePoints = grapheme.codePoints().toArray()

        // 1️⃣ Emoji 变体选择符（⛔️ ☎️ ⚠️ ❤️）
        if (codePoints.any { it == 0xFE0F }) return true

        // 2️⃣ 主 Emoji 区段
        if (codePoints.any { it in 0x1F300..0x1FAFF }) return true

        // 3️⃣ 杂项符号（包含 ⛔ ☎ ⚠ 等）
        if (codePoints.any { it in 0x2600..0x27BF }) return true

        // 4️⃣ 区域指示符（🇨🇳 🇺🇸）
        if (codePoints.size == 2 &&
            codePoints.all { it in 0x1F1E6..0x1F1FF }
        ) return true

        return false
    }

}