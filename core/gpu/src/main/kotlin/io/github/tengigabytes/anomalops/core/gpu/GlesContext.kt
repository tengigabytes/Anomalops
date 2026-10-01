// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.gpu

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface

/**
 * A headless OpenGL ES 3.1 context on a 1 x 1 pbuffer for compute shaders (ADR-0017). It is current on the thread
 * that created it until [close]; every GPU object of this module must be used on that thread. The display is not
 * terminated on close: EGL displays are shared by the whole process.
 */
class GlesContext private constructor(
    private val display: EGLDisplay,
    private val surface: EGLSurface,
    private val context: EGLContext,
) : AutoCloseable {

    override fun close() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
    }

    companion object {
        private const val GLES_MAJOR = 3

        fun create(): GlesContext {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize: ${eglError()}" }
            val config = chooseConfig(display)
            val context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, GLES_MAJOR, EGL14.EGL_NONE),
                0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext: ${eglError()}" }
            val surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface: ${eglError()}" }
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent: ${eglError()}" }
            return GlesContext(display, surface, context)
        }

        private fun chooseConfig(display: EGLDisplay): EGLConfig {
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE,
                EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
                "eglChooseConfig: ${eglError()}"
            }
            return checkNotNull(configs[0])
        }

        private fun eglError() = "0x" + Integer.toHexString(EGL14.eglGetError())
    }
}
