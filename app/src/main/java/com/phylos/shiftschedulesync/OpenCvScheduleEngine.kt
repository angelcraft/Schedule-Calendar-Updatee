package com.phylos.shiftschedulesync

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object OpenCvScheduleEngine {

    data class GridResult(val rows: List<List<Bitmap>>)

    fun ensureInitialized() {
        check(OpenCVLoader.initLocal()) { "OpenCV failed to initialize" }
    }

    fun preprocessSchedule(input: Bitmap): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(input.copy(Bitmap.Config.ARGB_8888, false), src)

        val resized = resizeIfLarge(src, 1800)
        val warped = warpLargestTable(resized) ?: resized

        val gray = Mat()
        Imgproc.cvtColor(warped, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)

        val norm = Mat()
        Imgproc.adaptiveThreshold(
            gray,
            norm,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            31,
            12.0
        )
        Core.bitwise_not(norm, norm)

        val finalMat = Mat()
        Core.bitwise_not(norm, finalMat)

        return finalMat.toBitmap()
    }

    fun extractGrid(preprocessed: Bitmap): GridResult {
        val src = Mat()
        Utils.bitmapToMat(preprocessed.copy(Bitmap.Config.ARGB_8888, false), src)

        val gray = Mat()
        if (src.channels() == 4) {
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        } else {
            src.copyTo(gray)
        }

        val bin = Mat()
        Imgproc.threshold(gray, bin, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)

        val verticalKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(max(3.0, bin.cols() / 80.0), 1.0)
        )
        val horizontalKernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(1.0, max(3.0, bin.rows() / 80.0))
        )

        val vertical = Mat()
        val horizontal = Mat()
        Imgproc.morphologyEx(bin, vertical, Imgproc.MORPH_OPEN, verticalKernel)
        Imgproc.morphologyEx(bin, horizontal, Imgproc.MORPH_OPEN, horizontalKernel)

        val xLines = mergeClose(findLinePositions(vertical, true), 18)
        val yLines = mergeClose(findLinePositions(horizontal, false), 18)

        if (xLines.size < 3 || yLines.size < 3) return GridResult(emptyList())

        val rows = mutableListOf<List<Bitmap>>()
        for (yi in 0 until yLines.size - 1) {
            val top = yLines[yi]
            val bottom = yLines[yi + 1]
            if (bottom - top < 20) continue

            val cells = mutableListOf<Bitmap>()
            for (xi in 0 until xLines.size - 1) {
                val left = xLines[xi]
                val right = xLines[xi + 1]
                if (right - left < 20) continue

                val insetX = max(2, ((right - left) * 0.05).toInt())
                val insetY = max(2, ((bottom - top) * 0.08).toInt())

                val x1 = min(src.cols() - 1, left + insetX)
                val y1 = min(src.rows() - 1, top + insetY)
                val x2 = max(x1 + 1, right - insetX)
                val y2 = max(y1 + 1, bottom - insetY)

                val rect = Rect(x1, y1, x2 - x1, y2 - y1)
                val cell = Mat(src, rect).clone()
                cells += cell.toBitmap()
            }
            if (cells.isNotEmpty()) rows += cells
        }

        return GridResult(rows)
    }

    private fun resizeIfLarge(src: Mat, targetMax: Int): Mat {
        val maxSide = max(src.cols(), src.rows())
        if (maxSide <= targetMax) return src
        val scale = targetMax.toDouble() / maxSide.toDouble()
        val out = Mat()
        Imgproc.resize(src, out, Size(src.cols() * scale, src.rows() * scale))
        return out
    }

    private fun warpLargestTable(src: Mat): Mat? {
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        var bestQuad: MatOfPoint2f? = null
        var bestArea = 0.0

        for (contour in contours) {
            val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.02 * peri, true)
            if (approx.total() == 4L) {
                val area = Imgproc.contourArea(approx)
                if (area > bestArea) {
                    bestArea = area
                    bestQuad = approx
                }
            }
        }

        val quad = bestQuad ?: return null
        val ordered = orderPoints(quad.toArray())

        val widthA = distance(ordered[2], ordered[3])
        val widthB = distance(ordered[1], ordered[0])
        val maxWidth = max(widthA, widthB).toInt()

        val heightA = distance(ordered[1], ordered[2])
        val heightB = distance(ordered[0], ordered[3])
        val maxHeight = max(heightA, heightB).toInt()

        if (maxWidth < 200 || maxHeight < 200) return null

        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxWidth - 1.0, 0.0),
            Point(maxWidth - 1.0, maxHeight - 1.0),
            Point(0.0, maxHeight - 1.0)
        )

        val matrix = Imgproc.getPerspectiveTransform(MatOfPoint2f(*ordered), dst)
        val warped = Mat()
        Imgproc.warpPerspective(src, warped, matrix, Size(maxWidth.toDouble(), maxHeight.toDouble()))
        return warped
    }

    private fun orderPoints(points: Array<Point>): Array<Point> {
        val sorted = points.sortedBy { it.x + it.y }
        val tl = sorted.first()
        val br = sorted.last()

        val rem = points.toMutableList()
        rem.remove(tl)
        rem.remove(br)

        val tr = rem.minBy { it.y - it.x }
        val bl = rem.maxBy { it.y - it.x }

        return arrayOf(tl, tr, br, bl)
    }

    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun findLinePositions(mat: Mat, vertical: Boolean): List<Int> {
        val positions = mutableListOf<Int>()
        if (vertical) {
            for (x in 0 until mat.cols()) {
                val nonZero = Core.countNonZero(mat.col(x))
                if (nonZero > mat.rows() * 0.35) positions += x
            }
        } else {
            for (y in 0 until mat.rows()) {
                val nonZero = Core.countNonZero(mat.row(y))
                if (nonZero > mat.cols() * 0.35) positions += y
            }
        }
        return positions
    }

    private fun mergeClose(values: List<Int>, tolerance: Int): List<Int> {
        if (values.isEmpty()) return emptyList()
        val sorted = values.sorted()
        val out = mutableListOf<Int>()
        var group = mutableListOf(sorted.first())

        for (i in 1 until sorted.size) {
            if (abs(sorted[i] - group.last()) <= tolerance) {
                group += sorted[i]
            } else {
                out += group.average().toInt()
                group = mutableListOf(sorted[i])
            }
        }
        out += group.average().toInt()
        return out
    }

    private fun Mat.toBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(cols(), rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(this, bmp)
        return bmp
    }
}
