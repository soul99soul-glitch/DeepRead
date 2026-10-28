package app.amber.feature.ui.pages.setting

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeCardColumnsTest {
    @Test
    fun widerWindowsAddColumnsInsteadOfStretchingCards() {
        // Content widths after the 16dp page insets.
        assertEquals(2, themeCardColumns(328.dp)) // small phone
        assertEquals(2, themeCardColumns(379.dp)) // phone
        assertEquals(3, themeCardColumns(669.dp)) // unfolded, portrait
        assertEquals(4, themeCardColumns(809.dp)) // unfolded, landscape
        assertEquals(4, themeCardColumns(1248.dp)) // tablet: capped
    }
}
