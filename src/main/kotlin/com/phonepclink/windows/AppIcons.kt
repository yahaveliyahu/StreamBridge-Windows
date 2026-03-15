package com.phonepclink.windows

import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.shape.SVGPath
import javafx.scene.transform.Scale

/**
 * Utility object that converts the Android vector drawables (24×24 viewport)
 * directly into JavaFX nodes. The SVG path data is identical between the two
 * platforms — only the wrapper format differs.
 *
 * Usage:
 *   AppIcons.send(size = 20.0, color = Color.web("#0078D7"))
 *   AppIcons.image(size = 24.0)       // defaults to black
 */
object AppIcons {

    // ── Raw path data (copied verbatim from ic_*.xml) ─────────────────────────

    private const val PATH_CAMERA =
        "M20,5h-3.17l-1.84-2H9.01L7.17,5H4c-1.1,0-2,0.9-2,2v11c0,1.1,0.9,2,2,2h16" +
                "c1.1,0,2-0.9,2-2V7c0-1.1-0.9-2-2-2z" +
                "M12,17c-2.76,0-5-2.24-5-5s2.24-5,5-5 5,2.24 5,5-2.24,5-5,5z" +
                "M12,9c-1.66,0-3,1.34-3,3s1.34,3,3,3 3-1.34,3-3-1.34-3-3-3z"

    private const val PATH_CONTACTS =
        "M20,0H4C2.9,0 2,0.9 2,2v20l4,-4h14c1.1,0 2,-0.9 2,-2V2c0,-1.1 -0.9,-2 -2,-2z" +
                "M12,4a3,3 0,1 1,0 6a3,3 0,0 1,0 -6z" +
                "M6,16c0,-2 4,-3 6,-3s6,1 6,3v1H6z"

    // ic_file has two separate paths — combine into one compound path
    private const val PATH_FILE =
        "M14,2H6c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V8z " +
                "M14,2v6h6"

    private const val PATH_FOLDER =
        "M10,4l2,2h8c1.1,0 2,0.9 2,2v10c0,1.1 -0.9,2 -2,2H4c-1.1,0 -2,-0.9 -2,-2V6c0,-1.1 0.9,-2 2,-2h6z"

    private const val PATH_IMAGE =
        "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2z" +
                "M8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z"

    private const val PATH_MUSIC =
        "M12,3v10.55A4,4 0,1 0,14 17V7h4V3z"

    private const val PATH_NOTES =
        "M19,3H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2z" +
                "M7,7h10v2H7z" +
                "M7,11h10v2H7z" +
                "M7,15h7v2H7z"

    private const val PATH_SEND =
        "M2,21l21,-9L2,3v7l15,2 -15,2z"

    private const val PATH_VIDEO =
        "M17,10.5V6c0,-1.1 -0.9,-2 -2,-2H5C3.9,4 3,4.9 3,6v12c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2v-4.5l4,4v-11z"

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Creates a StackPane containing an SVGPath scaled to [size]×[size] pixels.
     * The Android icons use a 24×24 viewport, so scale = size/24.
     *
     * @param pathData  raw SVG path string
     * @param size      desired display size in pixels (default 20)
     * @param color     fill color (default black)
     */
    private fun make(
        pathData: String,
        size: Double = 20.0,
        color: Color = Color.BLACK
    ): StackPane {
        val svgPath = SVGPath().apply {
            content = pathData
            fill = color
            stroke = Color.TRANSPARENT
        }

        val scale = size / 24.0
        svgPath.transforms.add(Scale(scale, scale, 0.0, 0.0))

        // StackPane fixes the layout bounds to exactly size×size so that
        // callers can rely on predictable preferred dimensions.
        return StackPane(svgPath).apply {
            prefWidth = size
            prefHeight = size
            maxWidth = size
            maxHeight = size
            isMouseTransparent = true
        }
    }

    // ── Named shortcuts ───────────────────────────────────────────────────────

    fun camera  (size: Double = 20.0, color: Color = Color.BLACK) = make(PATH_CAMERA,   size, color)
    fun contacts(size: Double = 20.0, color: Color = Color.BLACK) = make(PATH_CONTACTS, size, color)
    fun file    (size: Double = 20.0, color: Color = Color.BLACK) = make(PATH_FILE,     size, color)
    fun folder  (size: Double = 20.0, color: Color = Color.BLACK) = make(PATH_FOLDER,   size, color)
    fun image   (size: Double = 20.0, color: Color = Color.BLACK) = make(PATH_IMAGE,    size, color)
    fun music   (size: Double = 20.0, color: Color = Color.BLACK) = make(PATH_MUSIC,    size, color)
    fun notes   (size: Double = 20.0, color: Color = Color.BLACK) = make(PATH_NOTES,    size, color)
    fun send    (size: Double = 20.0, color: Color = Color.BLACK) = make(PATH_SEND,     size, color)
    fun video   (size: Double = 20.0, color: Color = Color.BLACK) = make(PATH_VIDEO,    size, color)
}