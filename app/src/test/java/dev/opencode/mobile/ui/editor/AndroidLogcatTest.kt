package dev.opencode.mobile.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidLogcatTest {
    @Test
    fun compactsThreadtimeLines() {
        assertEquals(
            "17:46:29 I ReactNativeJS: hello from the phone editor",
            AndroidController.compactLogcat("11-18 17:46:29.042  4561 16184 I ReactNativeJS: hello from the phone editor"),
        )
    }

    @Test
    fun leavesOtherLinesAlone() {
        assertEquals("--------- beginning of crash", AndroidController.compactLogcat("--------- beginning of crash"))
        assertEquals("\tat com.app.Main.run(Main.java:3)", AndroidController.compactLogcat("\tat com.app.Main.run(Main.java:3)"))
    }
}
