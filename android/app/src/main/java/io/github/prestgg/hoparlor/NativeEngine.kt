// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.prestgg.hoparlor

/** Thin JNI wrapper over the C++ receiver + AAudio player (see app/src/main/cpp). */
object NativeEngine {
    init {
        System.loadLibrary("hoparlor")
    }

    external fun start(host: String, port: Int, targetMs: Int, adaptive: Boolean): Boolean
    external fun stop()
    external fun setTarget(targetMs: Int, adaptive: Boolean)
    external fun stats(out: FloatArray)

    // Must match enum Stat in engine.h.
    const val BUFFER_MS = 0
    const val TARGET_MS = 1
    const val OUTPUT_MS = 2
    const val NETWORK_MS = 3
    const val TOTAL_MS = 4
    const val PACKETS = 5
    const val LOST = 6
    const val UNDERRUNS = 7
    const val EXCLUSIVE = 8
    const val BURST = 9
    const val RATIO_PPM = 10
    const val RECEIVING = 11
    const val PACKET_MS = 12
    const val JITTER_MS = 13
    const val COUNT = 14
}
