package com.igordanilcenko.speedtest.domain

import com.igordanilcenko.speedtest.domain.intent.distanceKm
import com.igordanilcenko.speedtest.domain.intent.selectNearestNodes
import com.igordanilcenko.speedtest.domain.model.Coordinates
import com.igordanilcenko.speedtest.domain.model.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FindNearestNodesTest {
    private val origin = Coordinates(0.0, 0.0)
    private fun node(index: Int) =
        Node("$index", "Server $index", "server$index.test", 80, Coordinates(0.0, index.toDouble()))

    @Test
    fun `sorts entire directory and limits result to five`() {
        val result = selectNearestNodes(origin, (9 downTo 0).map(::node))
        assertEquals(listOf("0", "1", "2", "3", "4"), result.map { it.node.id })
        assertTrue(result.zipWithNext().all { (a, b) -> a.distanceKm <= b.distanceKm })
    }

    @Test
    fun `returns fewer nodes without adding fake entries`() {
        assertEquals(2, selectNearestNodes(origin, listOf(node(2), node(1))).size)
        assertTrue(selectNearestNodes(origin, emptyList()).isEmpty())
    }

    @Test
    fun `duplicates do not consume result slots`() {
        assertEquals(listOf("1", "2"), selectNearestNodes(origin, listOf(node(2), node(1), node(1))).map { it.node.id })
    }

    @Test
    fun `distance handles identical points and equatorial degree`() {
        assertEquals(0.0, distanceKm(origin, origin), 0.0)
        assertEquals(111.195, distanceKm(origin, Coordinates(0.0, 1.0)), 0.001)
    }

    @Test
    fun `distance crosses dateline and remains finite at antipodes`() {
        assertEquals(222.390, distanceKm(Coordinates(0.0, 179.0), Coordinates(0.0, -179.0)), 0.001)
        assertEquals(20015.114, distanceKm(origin, Coordinates(0.0, 180.0)), 0.001)
        assertEquals(0.0, distanceKm(Coordinates(90.0, 0.0), Coordinates(90.0, 120.0)), 0.001)
    }

    @Test
    fun `equal distances have deterministic ordering`() {
        val a = node(1).copy(id = "a")
        val b = a.copy(id = "b")
        assertEquals(listOf("a", "b"), selectNearestNodes(origin, listOf(b, a)).map { it.node.id })
    }
}
