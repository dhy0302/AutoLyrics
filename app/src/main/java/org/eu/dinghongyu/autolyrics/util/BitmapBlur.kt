/*
 * AutoLyrics — 安卓自动歌词
 * Copyright (C) 2026 丁宏宇
 *
 * 本程序遵循 GNU General Public License v3.0 或更高版本发布。
 * 详见仓库根目录的 LICENSE 文件。
 *
 * 部分歌词格式的解析流程参考了以下开源项目（详见 BUILD.md 的调研记录）：
 *   - lyswhut/lx-music-desktop (Apache-2.0)
 *   - Robotxm/ESLyric-LyricsSource (GPL-3.0)
 *   - jsososo/QQMusicApi (GPL-3.0)
 */

package org.eu.dinghongyu.autolyrics.util

import android.graphics.Bitmap

/**
 * 专辑封面的「大模糊」处理。
 *
 * 为什么不用 RenderEffect / Modifier.blur：那两套都只在 Android 12+ 生效，
 * 本项目 minSdk 26。这里改成与系统版本无关的通用做法：
 *   1) 先把原图降采样到极小尺寸（默认 40px），
 *   2) 在小图上做两趟盒式模糊（水平 + 垂直），
 *   3) 交给 Compose 放大铺满屏幕，双线性插值天然形成柔和的大模糊。
 * 小图只有 1600 个像素，计算量可忽略，还能被复用缓存。
 */
object BitmapBlur {

    fun blur(src: Bitmap, downSize: Int = 40, radius: Int = 3): Bitmap {
        if (src.width <= 0 || src.height <= 0) return src
        val small = Bitmap.createScaledBitmap(src, downSize, downSize, true)
        val horizontalPass = boxBlur(small, radius, horizontal = true)
        return boxBlur(horizontalPass, radius, horizontal = false)
    }

    /**
     * 一趟可分离盒式模糊。
     *
     * @param horizontal true=按行（水平方向）模糊，false=按列（垂直方向）模糊
     */
    private fun boxBlur(src: Bitmap, radius: Int, horizontal: Boolean): Bitmap {
        val w = src.width
        val h = src.height
        val srcPx = IntArray(w * h)
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)

        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h

        for (o in 0 until outer) {
            var sumA = 0
            var sumR = 0
            var sumG = 0
            var sumB = 0
            var count = 0
            for (i in -radius..radius) {
                val idx = pixelIndex(o, i, w, h, horizontal) ?: continue
                val c = srcPx[idx]
                sumA += (c ushr 24) and 0xFF
                sumR += (c ushr 16) and 0xFF
                sumG += (c ushr 8) and 0xFF
                sumB += c and 0xFF
                count++
            }
            for (i in 0 until inner) {
                if (count > 0) {
                    out[pixelIndex(o, i, w, h, horizontal)!!] =
                        ((sumA / count) shl 24) or ((sumR / count) shl 16) or
                                ((sumG / count) shl 8) or (sumB / count)
                }
                // 滑动窗口：移除离开的像素、加入进入的像素
                val leaveIdx = pixelIndex(o, i - radius, w, h, horizontal)
                if (leaveIdx != null) {
                    val c = srcPx[leaveIdx]
                    sumA -= (c ushr 24) and 0xFF
                    sumR -= (c ushr 16) and 0xFF
                    sumG -= (c ushr 8) and 0xFF
                    sumB -= c and 0xFF
                    count--
                }
                val enterIdx = pixelIndex(o, i + radius + 1, w, h, horizontal)
                if (enterIdx != null) {
                    val c = srcPx[enterIdx]
                    sumA += (c ushr 24) and 0xFF
                    sumR += (c ushr 16) and 0xFF
                    sumG += (c ushr 8) and 0xFF
                    sumB += c and 0xFF
                    count++
                }
            }
        }

        val dst = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        dst.setPixels(out, 0, w, 0, 0, w, h)
        return dst
    }

    /** 把「外圈下标 o + 内圈下标 i」换算成一维像素下标，越界返回 null。 */
    private fun pixelIndex(o: Int, i: Int, w: Int, h: Int, horizontal: Boolean): Int? {
        val x = if (horizontal) i else o
        val y = if (horizontal) o else i
        if (x < 0 || x >= w || y < 0 || y >= h) return null
        return y * w + x
    }
}
