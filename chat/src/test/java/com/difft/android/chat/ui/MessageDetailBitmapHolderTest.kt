package com.difft.android.chat.ui

import android.graphics.Bitmap
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config





@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageDetailBitmapHolderTest {

    private fun bitmap(): Bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)

    private companion object {
        const val MAX_OLD_KEYS = 100
    }

    @Test
    fun `two pages keep their own snapshot and none is recycled`() {
        val first = bitmap()
        val second = bitmap()
        val firstKey = MessageDetailBitmapHolder.put(first)
        val secondKey = MessageDetailBitmapHolder.put(second)

        assertSame(first, MessageDetailBitmapHolder.get(firstKey))
        assertSame(second, MessageDetailBitmapHolder.get(secondKey))
        assertFalse(first.isRecycled)

        MessageDetailBitmapHolder.release(firstKey)
        assertNull(MessageDetailBitmapHolder.get(firstKey))
        assertSame(second, MessageDetailBitmapHolder.get(secondKey))
        assertFalse("released, not recycled: a page may still draw it", first.isRecycled)
        MessageDetailBitmapHolder.release(secondKey)
    }

    @Test
    fun `a key from an earlier process never finds a new snapshot`() {
        val key = MessageDetailBitmapHolder.put(bitmap())

        
        assertTrue((1..MAX_OLD_KEYS).none { MessageDetailBitmapHolder.get("message-detail-$it") != null })
        MessageDetailBitmapHolder.release(key)
    }

    @Test
    fun `reaction tabs carry only the message and the emoji`() {
        val args = EmojiReactionTabFragment.newInstance("m1", "👍").requireArguments()

        assertTrue(args.keySet().all { args.get(it) is String })
    }

    @Test
    fun `at most four snapshots are kept, oldest first out`() {
        val keys = List(5) { MessageDetailBitmapHolder.put(bitmap()) }

        assertNull(MessageDetailBitmapHolder.get(keys.first()))
        keys.drop(1).forEach { assertNotNull(MessageDetailBitmapHolder.get(it)) }
        keys.forEach { MessageDetailBitmapHolder.release(it) }
    }

    @Test
    fun `a fold keeps the page's snapshot, closing the page releases it`() {
        val key = MessageDetailBitmapHolder.put(bitmap())
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        controller.get().supportFragmentManager.beginTransaction()
            .add(android.R.id.content, MessageDetailFragment.newInstance("m1", key), "detail").commitNow()

        controller.recreate()
        assertNotNull("kept across the recreation", MessageDetailBitmapHolder.get(key))
        val restored = controller.get().supportFragmentManager.findFragmentByTag("detail")!!

        controller.get().supportFragmentManager.beginTransaction().remove(restored).commitNow()
        assertNull("released when the page closes", MessageDetailBitmapHolder.get(key))
    }
}
