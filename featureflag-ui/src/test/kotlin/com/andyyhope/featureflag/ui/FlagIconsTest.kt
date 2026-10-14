package com.andyyhope.featureflag.ui

import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class FlagIconsTest {

    @Test
    fun everyIconIsATwentyFourDpSquareWithPathsThatParse() {
        val icons = listOf(
            FlagIcons.Tune,
            FlagIcons.FlagOutlined,
            FlagIcons.FlagFilled,
            FlagIcons.LayersOutlined,
            FlagIcons.LayersFilled,
            FlagIcons.QrCode,
            FlagIcons.ContentCopy,
            FlagIcons.Undo,
            FlagIcons.Download,
        )

        icons.forEach { icon ->
            assertEquals(24.dp, icon.defaultWidth, icon.name)
            assertEquals(24.dp, icon.defaultHeight, icon.name)
            val paths = (0 until icon.root.size).map { icon.root[it] }.filterIsInstance<VectorPath>()
            assertTrue(paths.isNotEmpty() && paths.all { it.pathData.isNotEmpty() }, icon.name)
        }
    }
}
