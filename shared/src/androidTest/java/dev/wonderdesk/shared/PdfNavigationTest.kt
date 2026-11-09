package dev.wonderdesk.shared

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfNavigationTest {
    @Test fun pageNumbersAreOneBasedAndBounded() {
        assertEquals(0, pdfPageIndex("1", 5))
        assertEquals(4, pdfPageIndex(" 5 ", 5))
        assertThrows(IllegalArgumentException::class.java) { pdfPageIndex("0", 5) }
        assertThrows(IllegalArgumentException::class.java) { pdfPageIndex("6", 5) }
        assertThrows(IllegalArgumentException::class.java) { pdfPageIndex("abc", 5) }
        assertThrows(IllegalArgumentException::class.java) { pdfPageIndex("1", 0) }
    }
}
