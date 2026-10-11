package com.tyust.course.ui.system
import org.junit.Assert.*
import org.junit.Test
class PickerRowRevealTest {
    @Test fun completedAnimationMakesAllHundredRowsReadable() {
        (0..100).forEach { assertEquals(1f, pickerRowReveal(it,0,6,true,true,false,0.5f,1f,1f),0f) }
    }
    @Test fun lastViewportUsesRelativeIndexAndNewlyScrolledRowsAreImmediate() {
        assertEquals(pickerRowReveal(1,0,6,true,false,false,.4f,1f,1f), pickerRowReveal(96,95,6,true,false,false,.4f,1f,1f),0f)
        assertEquals(1f,pickerRowReveal(90,95,6,true,false,false,.1f,.5f,.5f),0f)
    }
    @Test fun reverseAndReducedMotionFinishInTheCorrectState() {
        assertEquals(0f,pickerRowReveal(99,95,6,false,true,false,0f,0f,0f),0f)
        assertEquals(1f,pickerRowReveal(99,95,6,true,true,false,0f,1f,1f),0f)
        assertEquals(1f,pickerRowReveal(99,0,6,true,true,true,0f,0f,0f),0f)
    }
}
