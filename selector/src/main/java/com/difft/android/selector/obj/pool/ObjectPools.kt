package com.difft.android.selector.obj.pool

import java.util.LinkedList

class ObjectPools {

    interface Pool<T> {
        fun acquire(): T?

        fun release(obj: T): Boolean

        fun destroy()
    }

    open class SimpleObjectPool<T> : Pool<T> {
        private val mPool = LinkedList<T>()

        override fun acquire(): T? {
            return mPool.poll()
        }

        override fun release(obj: T): Boolean {
            if (isInPool(obj)) {
                return false
            }
            return mPool.add(obj)
        }

        override fun destroy() {
            mPool.clear()
        }

        private fun isInPool(obj: T): Boolean {
            return mPool.contains(obj)
        }
    }

    class SynchronizedPool<T> : SimpleObjectPool<T>() {
        private val mLock = Any()

        override fun acquire(): T? {
            synchronized(mLock) {
                return super.acquire()
            }
        }

        override fun release(obj: T): Boolean {
            synchronized(mLock) {
                return super.release(obj)
            }
        }

        override fun destroy() {
            synchronized(mLock) {
                super.destroy()
            }
        }
    }
}
