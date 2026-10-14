package com.andyyhope.featureflag.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The icons material-icons-core lacks, drawn from Material Icons' path data
 * (https://github.com/google/material-design-icons, Apache License 2.0). Each is a 24 dp square on a
 * 24-unit viewport, filled black and tinted by `Icon`.
 */
internal object FlagIcons {

    /** The Overrides tab. Material's "tune" has a single form, so it serves selected and unselected alike. */
    val Tune: ImageVector by lazy {
        icon(
            "Filled.Tune",
            "M3,17v2h6v-2L3,17zM3,5v2h10L13,5L3,5zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2L3,11v2h4v2h2L9,9L7,9z" +
                "M21,13v-2L11,11v2h10zM15,9h2L17,7h4L21,5h-4L17,3h-2v6z",
        )
    }

    /** The Flags tab, unselected; also the status screens' symbol. */
    val FlagOutlined: ImageVector by lazy {
        icon("Outlined.Flag", "M12.36,6l0.4,2H18v6h-3.36l-0.4,-2H7V6H12.36M14,4H5v17h2v-7h5.6l0.4,2h7V6h-5.6L14,4L14,4z")
    }

    /** The Flags tab, selected. */
    val FlagFilled: ImageVector by lazy {
        icon("Filled.Flag", "M14.4,6L14,4H5v17h2v-7h5.6l0.4,2h7V6z")
    }

    /** A Detail tab, unselected. */
    val LayersOutlined: ImageVector by lazy {
        icon(
            "Outlined.Layers",
            "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27zM12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.27L12,16z" +
                "M12,4.53L17.74,9 12,13.47 6.26,9 12,4.53z",
        )
    }

    /** A Detail tab, selected. */
    val LayersFilled: ImageVector by lazy {
        icon(
            "Filled.Layers",
            "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27 -7.38,5.74zM12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.27L12,16z",
        )
    }

    /** "Show QR code". */
    val QrCode: ImageVector by lazy {
        icon(
            "Filled.QrCode",
            "M3,11h8V3H3V11zM5,5h4v4H5V5z",
            "M3,21h8v-8H3V21zM5,15h4v4H5V15z",
            "M13,3v8h8V3H13zM19,9h-4V5h4V9z",
            "M19,19h2v2h-2z",
            "M13,13h2v2h-2z",
            "M15,15h2v2h-2z",
            "M13,17h2v2h-2z",
            "M15,19h2v2h-2z",
            "M17,17h2v2h-2z",
            "M17,13h2v2h-2z",
            "M19,15h2v2h-2z",
        )
    }

    /** Every copy button. */
    val ContentCopy: ImageVector by lazy {
        icon(
            "Filled.ContentCopy",
            "M16,1L4,1c-1.1,0 -2,0.9 -2,2v14h2L4,3h12L16,1zM19,5L8,5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11" +
                "c1.1,0 2,-0.9 2,-2L21,7c0,-1.1 -0.9,-2 -2,-2zM19,21L8,21L8,7h11v14z",
        )
    }

    /** The reset arrow (iOS arrow.uturn.backward). */
    val Undo: ImageVector by lazy {
        icon(
            "Filled.Undo",
            "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 " +
                "3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z",
        )
    }

    /** "Import…" and the QR sheet's "Save" (iOS square.and.arrow.down). */
    val Download: ImageVector by lazy {
        icon("Filled.Download", "M5,20h14v-2H5V20zM19,9h-4V3H9v6H5l7,7L19,9z")
    }

    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { path -> addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)) }
        }.build()
}
