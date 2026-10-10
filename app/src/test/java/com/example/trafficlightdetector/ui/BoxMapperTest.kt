package com.example.trafficlightdetector.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxMapperTest {

    @Test
    fun `same aspect ratio fills the whole view`() {
        val rect = BoxMapper.contentRect(
            viewWidth = 1080f,
            viewHeight = 1440f,
            imageWidth = 480,
            imageHeight = 640,
        )
        assertEquals(0f, rect.left, 1e-4f)
        assertEquals(0f, rect.top, 1e-4f)
        assertEquals(1080f, rect.right, 1e-4f)
        assertEquals(1440f, rect.bottom, 1e-4f)
    }

    @Test
    fun `wider frame than view leaves bars top and bottom`() {
        // 4:3 frame inside a 1:1 view -> height = width / (4/3)
        val rect = BoxMapper.contentRect(
            viewWidth = 1000f,
            viewHeight = 1000f,
            imageWidth = 640,
            imageHeight = 480,
        )
        assertEquals(0f, rect.left, 1e-4f)
        assertEquals(1000f, rect.right, 1e-4f)
        assertEquals(750f, rect.height, 1e-4f)
        assertEquals(125f, rect.top, 1e-4f)
        assertEquals(875f, rect.bottom, 1e-4f)
    }

    @Test
    fun `taller frame than view leaves bars left and right`() {
        // 3:4 frame (portrait) inside a 2:1 view
        val rect = BoxMapper.contentRect(
            viewWidth = 2000f,
            viewHeight = 1000f,
            imageWidth = 480,
            imageHeight = 640,
        )
        assertEquals(0f, rect.top, 1e-4f)
        assertEquals(1000f, rect.bottom, 1e-4f)
        assertEquals(750f, rect.width, 1e-4f)
        assertEquals(625f, rect.left, 1e-4f)
    }

    @Test
    fun `maps normalised coordinates into the content rect`() {
        val rect = BoxMapper.contentRect(
            viewWidth = 1000f,
            viewHeight = 1000f,
            imageWidth = 640,
            imageHeight = 480,
        )
        assertEquals(500f, rect.mapX(0.5f), 1e-4f)
        assertEquals(125f, rect.mapY(0f), 1e-4f)
        assertEquals(875f, rect.mapY(1f), 1e-4f)
    }

    @Test
    fun `degenerate inputs never produce negative or off screen rects`() {
        val notMeasured = BoxMapper.contentRect(0f, 0f, 640, 480)
        assertEquals(0f, notMeasured.width, 1e-4f)
        assertEquals(0f, notMeasured.height, 1e-4f)

        // Before the first frame the whole view is used.
        val noFrameYet = BoxMapper.contentRect(100f, 200f, 0, 0)
        assertEquals(100f, noFrameYet.width, 1e-4f)
        assertEquals(200f, noFrameYet.height, 1e-4f)
    }

    @Test
    fun `content rect always stays inside the view`() {
        val rect = BoxMapper.contentRect(777f, 1234f, 1080, 1920)
        assertTrue(rect.left >= 0f)
        assertTrue(rect.top >= 0f)
        assertTrue(rect.right <= 777.001f)
        assertTrue(rect.bottom <= 1234.001f)
    }
}
