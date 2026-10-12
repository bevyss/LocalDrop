package org.localdrop.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class LdProtocolTest {
    @Test fun sharedTransferVector() {
        val vector = JSONObject(javaClass.classLoader!!.getResource("transfer.json")!!.readText())
        assertEquals(vector.getString("securityCode"), LdProtocol.securityCode(
            LdProtocol.fromHex(vector.getString("clientCertificateSha256"), 32),
            LdProtocol.fromHex(vector.getString("serverCertificateSha256"), 32),
            LdProtocol.fromHex(vector.getString("clientNonce"), 32),
            LdProtocol.fromHex(vector.getString("serverNonce"), 32)))
        assertEquals("photo 例.jpg", LdProtocol.validateFileName(vector.getJSONObject("validRequest").getString("name")))
        val bad = vector.getJSONArray("invalidFileNames")
        for (i in 0 until bad.length()) {
            try { LdProtocol.validateFileName(bad.getString(i)); fail("Accepted invalid file name") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun frameRoundTripAndDuplicateRejected() {
        val out = ByteArrayOutputStream()
        LdProtocol.write(out, JSONObject().put("kind", "verify").put("accept", true))
        assertTrue(LdProtocol.requiredBoolean(LdProtocol.read(ByteArrayInputStream(out.toByteArray())), "accept"))
        val body = "{\"kind\":\"verify\",\"kind\":\"request\"}".toByteArray()
        val invalid = ByteArrayOutputStream()
        invalid.write(byteArrayOf(0, 0, 0, body.size.toByte()))
        invalid.write(body)
        try { LdProtocol.read(ByteArrayInputStream(invalid.toByteArray())); fail("Duplicate accepted") }
        catch (_: IllegalArgumentException) { }
        val nested = "{\"kind\":\"verify\",\"extra\":{\"x\":1,\"x\":2}}".toByteArray()
        val invalidNested = ByteArrayOutputStream()
        invalidNested.write(byteArrayOf(0, 0, 0, nested.size.toByte()))
        invalidNested.write(nested)
        try { LdProtocol.read(ByteArrayInputStream(invalidNested.toByteArray())); fail("Nested duplicate accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun streamsLargeFileWithBoundedBuffer() {
        var remaining = 128L * 1024 * 1024
        var largestRead = 0
        val source = object : InputStream() {
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                largestRead = maxOf(largestRead, length)
                val count = minOf(length.toLong(), remaining).toInt()
                if (count == 0) return -1
                buffer.fill(0, offset, offset + count)
                remaining -= count
                return count
            }
        }
        var written = 0L
        val sink = object : OutputStream() {
            override fun write(value: Int) { written++ }
            override fun write(buffer: ByteArray, offset: Int, length: Int) { written += length }
        }
        val hash = LdProtocol.copyExactly(source, sink, 128L * 1024 * 1024)
        assertEquals(128L * 1024 * 1024, written)
        assertTrue(largestRead <= 64 * 1024)
        assertEquals(64, hash.length)
    }

    @Test fun interruptedTransferStopsBeforeReading() {
        try {
            Thread.currentThread().interrupt()
            try {
                LdProtocol.copyExactly(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 1)
                fail("Interrupted transfer continued")
            } catch (_: InterruptedException) { }
        } finally { Thread.interrupted() }
    }
}
