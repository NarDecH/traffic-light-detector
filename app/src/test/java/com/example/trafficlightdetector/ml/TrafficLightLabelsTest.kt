package com.example.trafficlightdetector.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficLightLabelsTest {

    @Test
    fun `parses labels and ignores comments and blank lines`() {
        val labels = TrafficLightLabels.fromLines(
            listOf(
                "# class labels",
                "red_light",
                "",
                "yellow_light   # inline comment",
                "green_light",
            ),
        )
        assertEquals(3, labels.size)
        assertEquals("red_light", labels.labelAt(0))
        assertEquals("yellow_light", labels.labelAt(1))
        assertEquals("green_light", labels.labelAt(2))
        assertNull(labels.labelAt(3))
    }

    @Test
    fun `maps indices to traffic light classes by label string`() {
        // A model whose label map is ordered alphabetically still resolves correctly.
        val labels = TrafficLightLabels.fromLines(
            listOf("green_light", "red_light", "yellow_light"),
        )
        assertEquals(TrafficLightClass.GREEN_LIGHT, labels.classAt(0))
        assertEquals(TrafficLightClass.RED_LIGHT, labels.classAt(1))
        assertEquals(TrafficLightClass.YELLOW_LIGHT, labels.classAt(2))
        assertNull(labels.classAt(7))
    }

    @Test
    fun `unknown labels are reported as no class`() {
        val labels = TrafficLightLabels.fromLines(listOf("red_light", "background", "stop_sign"))
        assertEquals(TrafficLightClass.RED_LIGHT, labels.classAt(0))
        assertNull(labels.classAt(1))
        assertNull(labels.classAt(2))
        assertEquals("background", labels.labelAt(1))
    }

    @Test
    fun `label matching tolerates case separators and padding`() {
        val labels = TrafficLightLabels.fromLines(listOf(" Red Light ", "GREEN-LIGHT", "Yellow Light"))
        assertEquals(TrafficLightClass.RED_LIGHT, labels.classAt(0))
        assertEquals(TrafficLightClass.GREEN_LIGHT, labels.classAt(1))
        assertEquals(TrafficLightClass.YELLOW_LIGHT, labels.classAt(2))
    }

    @Test
    fun `falls back to the default map when there are no usable lines`() {
        assertEquals(TrafficLightLabels.DEFAULT.size, TrafficLightLabels.fromLines(listOf("# nothing here")).size)
        assertEquals(TrafficLightClass.RED_LIGHT, TrafficLightLabels.DEFAULT.classAt(0))
        assertEquals(TrafficLightClass.YELLOW_LIGHT, TrafficLightLabels.DEFAULT.classAt(1))
        assertEquals(TrafficLightClass.GREEN_LIGHT, TrafficLightLabels.DEFAULT.classAt(2))
    }

    @Test
    fun `class constants match the shipped label file names`() {
        assertEquals("red_light", TrafficLightClass.RED_LIGHT.modelLabel)
        assertEquals("yellow_light", TrafficLightClass.YELLOW_LIGHT.modelLabel)
        assertEquals("green_light", TrafficLightClass.GREEN_LIGHT.modelLabel)
    }
}
