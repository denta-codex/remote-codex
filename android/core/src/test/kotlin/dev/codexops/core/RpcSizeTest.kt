package dev.codexops.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcSizeTest {
    @Test fun utf8LimitMatchesEncoderAtBoundaries() {
        for (text in listOf("", "ascii", "é漢😀", "\uD800", "\uDC00", "a\uD800b", "x".repeat(1_000_000))) {
            val size = text.toByteArray(Charsets.UTF_8).size
            assertFalse(exceedsUtf8Limit(text, size))
            assertFalse(exceedsUtf8Limit(text, size + 1))
            if (size > 0) assertTrue(exceedsUtf8Limit(text, size - 1))
        }
    }
}
