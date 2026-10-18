package dev.ninecontroller.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LinkVectorTest {
    private val dir = File(System.getProperty("vectors.dir"))
    private fun load(n: String) = Json.parseToJsonElement(File(dir, "$n.json").readText())
    private fun JsonObject.hex(k: String) = this[k]!!.jsonPrimitive.content.hexToByteArray()

    @Test fun recordsAndSegmentsByteExact() {
        val v = load("link").jsonObject
        val control = v.hex("control"); val dgram = v.hex("datagram")
        assertContentEquals(v.hex("stream"), Record.Control(control).encode() + Record.Datagram(dgram).encode())
        val r = RecordReader()
        v.hex("stream").forEach { b -> r.push(byteArrayOf(b)) }
        val stream = v.hex("stream")
        val rr = RecordReader(); rr.push(stream)
        assertContentEquals(control, (rr.next() as Record.Control).body)
        assertContentEquals(dgram, (rr.next() as Record.Datagram).body)
        val seg = Segmenter()
        val s23 = seg.segment(control, 23); val s62 = seg.segment(dgram, 62)
        assertEquals(v["segments_mtu23"]!!.jsonArray.map { it.jsonPrimitive.content }, s23.map { it.toHexString() })
        assertEquals(v["segments_mtu62_after"]!!.jsonArray.map { it.jsonPrimitive.content }, s62.map { it.toHexString() })
        val re = Reassembler(65535)
        assertContentEquals(control, s23.mapNotNull { re.push(it) }.single())
        assertContentEquals(dgram, s62.mapNotNull { re.push(it) }.single())
        assertFailsWith<LinkException> { RecordReader().apply { push(byteArrayOf(7, 0, 1, 0)) }.next() }
        assertEquals(LinkConst.BLE_SERVICE, v["uuids"]!!.jsonObject["service"]!!.jsonPrimitive.content)
    }

    @Test fun policyDecisionsMatchRust() {
        val json = Json { ignoreUnknownKeys = true }
        for ((i, c) in load("policy").jsonArray.map { it.jsonObject }.withIndex()) {
            val cands = c["candidates"]!!.jsonArray.map { json.decodeFromJsonElement<LinkStats>(it) }
            val st = json.decodeFromJsonElement<PolicyState>(c["state"]!!)
            val scores = c["scores"]!!.jsonArray.map { if (it is JsonNull) null else it.jsonPrimitive.double }
            assertEquals(scores, cands.map { LinkPolicy.score(it) }, "case $i scores")
            assertEquals(c["rank"]!!.jsonArray.map { json.decodeFromJsonElement<LinkKind>(it) }, LinkPolicy.rank(cands), "case $i rank")
            val d = c["decision"]!!.jsonObject
            val want = when (d["action"]!!.jsonPrimitive.content) {
                "stay" -> Decision.Stay
                "switch" -> Decision.Switch(json.decodeFromJsonElement(d["to"]!!))
                "failover" -> Decision.Failover(d["order"]!!.jsonArray.map { json.decodeFromJsonElement<LinkKind>(it) })
                else -> Decision.Unavailable
            }
            assertEquals(want, LinkPolicy.decide(cands, st), "case $i decision")
        }
    }

    @Test fun multiAddressUri() {
        val v = load("pairing").jsonObject
        val u = PairUri.parse(v["uri_multi"]!!.jsonPrimitive.content)!!
        assertEquals(v["uri_multi_extra"]!!.jsonArray.map { it.jsonPrimitive.content }, u.extra)
        assertEquals(v["uri_multi"]!!.jsonPrimitive.content, u.toUri())
        assertEquals(listOf("192.168.1.20", "172.20.10.2", "192.168.42.10"), u.addresses)
    }
}
