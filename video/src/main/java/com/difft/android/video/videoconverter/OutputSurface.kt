/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * This file has been modified by Signal.
 */

package com.difft.android.video.videoconverter

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.view.Surface
import com.difft.android.base.log.lumberjack.L
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
















internal class OutputSurface : SurfaceTexture.OnFrameAvailableListener {

    private var mEGL: EGL10? = null
    private var mEGLDisplay: EGLDisplay? = null
    private var mEGLContext: EGLContext? = null
    private var mEGLSurface: EGLSurface? = null

    private var mSurfaceTexture: SurfaceTexture? = null
    private var mSurface: Surface? = null

    private val mFrameSyncObject = Any() 
    private var mFrameAvailable = false

    private var mTextureRender: TextureRender? = null

    




    constructor(width: Int, height: Int, flipX: Boolean) {
        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException()
        }

        eglSetup(width, height)
        makeCurrent()

        setup(flipX)
    }

    



    constructor() {
        setup(false)
    }

    



    private fun setup(flipX: Boolean) {
        mTextureRender = TextureRender(flipX)
        mTextureRender!!.surfaceCreated()

        
        
        
        
        if (VERBOSE) L.d { TAG + "textureID=" + mTextureRender!!.getTextureId() }
        mSurfaceTexture = SurfaceTexture(mTextureRender!!.getTextureId())

        
        
        
        
        
        
        
        
        
        
        
        mSurfaceTexture!!.setOnFrameAvailableListener(this)

        mSurface = Surface(mSurfaceTexture)
    }

    


    private fun eglSetup(width: Int, height: Int) {
        mEGL = EGLContext.getEGL() as EGL10
        mEGLDisplay = mEGL!!.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        if (!mEGL!!.eglInitialize(mEGLDisplay, null)) {
            throw TranscodingException("unable to initialize EGL10")
        }

        
        
        val attribList = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL10.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!mEGL!!.eglChooseConfig(mEGLDisplay, attribList, configs, 1, numConfigs)) {
            throw TranscodingException("unable to find RGB888+pbuffer EGL config")
        }

        
        val attrib_list = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL10.EGL_NONE
        )
        mEGLContext = mEGL!!.eglCreateContext(
            mEGLDisplay, configs[0], EGL10.EGL_NO_CONTEXT,
            attrib_list
        )
        checkEglError("eglCreateContext")
        if (mEGLContext == null) {
            throw TranscodingException("null context")
        }

        
        
        val surfaceAttribs = intArrayOf(
            EGL10.EGL_WIDTH, width,
            EGL10.EGL_HEIGHT, height,
            EGL10.EGL_NONE
        )
        mEGLSurface = mEGL!!.eglCreatePbufferSurface(mEGLDisplay, configs[0], surfaceAttribs)
        checkEglError("eglCreatePbufferSurface")
        if (mEGLSurface == null) {
            throw TranscodingException("surface was null")
        }
    }

    


    fun release() {
        if (mEGL != null) {
            if (mEGL!!.eglGetCurrentContext() == mEGLContext) {
                
                mEGL!!.eglMakeCurrent(
                    mEGLDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_CONTEXT
                )
            }
            mEGL!!.eglDestroySurface(mEGLDisplay, mEGLSurface)
            mEGL!!.eglDestroyContext(mEGLDisplay, mEGLContext)
        }

        mSurface!!.release()

        

        
        mEGLDisplay = null
        mEGLContext = null
        mEGLSurface = null
        mEGL = null

        mTextureRender = null
        mSurface = null
        mSurfaceTexture = null
    }

    


    private fun makeCurrent() {
        if (mEGL == null) {
            throw TranscodingException("not configured for makeCurrent")
        }
        checkEglError("before makeCurrent")
        if (!mEGL!!.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
            throw TranscodingException("eglMakeCurrent failed")
        }
    }

    


    fun getSurface(): Surface {
        return mSurface!!
    }

    


    fun changeFragmentShader(fragmentShader: String) {
        mTextureRender!!.changeFragmentShader(fragmentShader)
    }

    




    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    fun awaitNewImage() {
        val TIMEOUT_MS = 750

        synchronized(mFrameSyncObject) {
            val expireTime = System.currentTimeMillis() + TIMEOUT_MS

            while (!mFrameAvailable) {
                try {
                    
                    
                    
                    (mFrameSyncObject as java.lang.Object).wait(TIMEOUT_MS.toLong())

                    if (!mFrameAvailable && System.currentTimeMillis() > expireTime) {
                        throw TranscodingException("Surface frame wait timed out")
                    }
                } catch (ie: InterruptedException) {
                    
                    throw TranscodingException(ie)
                }
            }
            mFrameAvailable = false
        }

        
        TextureRender.checkGlError("before updateTexImage")
        mSurfaceTexture!!.updateTexImage()
    }

    


    fun drawImage() {
        mTextureRender!!.drawFrame(mSurfaceTexture!!)
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    override fun onFrameAvailable(st: SurfaceTexture) {
        if (VERBOSE) L.d { TAG + "new frame available" }
        synchronized(mFrameSyncObject) {
            if (mFrameAvailable) {
                try {
                    throw TranscodingException("mFrameAvailable already set, frame could be dropped")
                } catch (e: TranscodingException) {
                    L.w(e) { "[OutputSurface] awaitNewImage frame dropped" }
                }
            }
            mFrameAvailable = true
            
            (mFrameSyncObject as java.lang.Object).notifyAll()
        }
    }

    


    private fun checkEglError(msg: String) {
        var failed = false
        while (true) {
            val error = mEGL!!.eglGetError()
            if (error == EGL10.EGL_SUCCESS) break
            L.e { TAG + msg + ": EGL error: 0x" + Integer.toHexString(error) }
            failed = true
        }
        if (failed) {
            throw TranscodingException("EGL error encountered (see log)")
        }
    }

    companion object {
        private const val TAG = "OutputSurface"
        private const val VERBOSE = false

        private const val EGL_OPENGL_ES2_BIT = 4
    }
}
