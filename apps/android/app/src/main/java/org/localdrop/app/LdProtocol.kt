package org.localdrop.app

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object LdProtocol {
    const val MAX_FRAME_BYTES = 16_384
    const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024 * 1024
    private val invalidFileChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    fun write(output: OutputStream, message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_FRAME_BYTES) { "Invalid control frame size" }
        DataOutputStream(output).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    fun read(input: InputStream): JSONObject {
        val size = DataInputStream(input).readInt()
        if (size !in 1..MAX_FRAME_BYTES) throw IllegalArgumentException("Invalid control frame size")
        val bytes = ByteArray(size)
        DataInputStream(input).readFully(bytes)
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            throw IllegalArgumentException("Invalid UTF-8", e)
        }
        // JSONObject silently keeps the last duplicate key; reject duplicates first.
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            if (reader.peek() != JsonToken.BEGIN_OBJECT) throw IllegalArgumentException("Invalid control frame object")
            validateKeys(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) throw IllegalArgumentException("Trailing JSON data")
        }
        return JSONObject(text)
    }

    private fun validateKeys(reader: JsonReader, depth: Int = 0) {
        if (depth > 64) throw IllegalArgumentException("Control frame is too deeply nested")
        when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val keys = mutableSetOf<String>()
                while (reader.hasNext()) {
                    if (!keys.add(reader.nextName())) throw IllegalArgumentException("Duplicate JSON key")
                    validateKeys(reader, depth + 1)
                }
                reader.endObject()
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) validateKeys(reader, depth + 1)
                reader.endArray()
            }
            else -> reader.skipValue()
        }
    }

    fun requireKind(frame: JSONObject, kind: String) {
        if (frame.optString("kind") != kind) throw IllegalArgumentException("Unexpected frame kind")
    }

    fun requiredString(frame: JSONObject, key: String, maxBytes: Int): String {
        val value = frame.opt(key)
        if (value !is String || value.toByteArray(Charsets.UTF_8).size > maxBytes)
            throw IllegalArgumentException("Invalid $key")
        return value
    }

    fun requiredBoolean(frame: JSONObject, key: String): Boolean =
        (frame.opt(key) as? Boolean) ?: throw IllegalArgumentException("Invalid $key")

    fun requiredLength(frame: JSONObject): Long {
        val value = frame.opt("length")
        if (value !is Number) throw IllegalArgumentException("Invalid file length")
        val length = value.toLong()
        if (length < 0 || length > MAX_FILE_BYTES || value.toString() != length.toString())
            throw IllegalArgumentException("Invalid file length")
        return length
    }

    fun validateFileName(name: String): String {
        val stem = name.substringBefore('.')
        if (name.isBlank() || name.toByteArray(Charsets.UTF_8).size > 255 ||
            name == "." || name == ".." || name.endsWith(' ') || name.endsWith('.') ||
            name.any { it.isISOControl() || it in invalidFileChars } ||
            stem.uppercase() in setOf("CON", "PRN", "AUX", "NUL") ||
            Regex("^(COM|LPT)[1-9]$", RegexOption.IGNORE_CASE).matches(stem))
            throw IllegalArgumentException("Invalid file name")
        return name
    }

    fun securityCode(clientCertHash: ByteArray, serverCertHash: ByteArray,
                     clientNonce: ByteArray, serverNonce: ByteArray): String {
        require(listOf(clientCertHash, serverCertHash, clientNonce, serverNonce).all { it.size == 32 })
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("LD/1 SAS\u0000".toByteArray(Charsets.UTF_8))
        digest.update(clientCertHash)
        digest.update(serverCertHash)
        digest.update(clientNonce)
        digest.update(serverNonce)
        val digits = BigInteger(1, digest.digest().copyOfRange(0, 8))
            .mod(BigInteger("1000000000000")).toString().padStart(12, '0')
        return "${digits.substring(0, 4)} ${digits.substring(4, 8)} ${digits.substring(8)}"
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun hex(data: ByteArray): String = data.joinToString("") { "%02x".format(it) }
    fun fromHex(value: String, size: Int): ByteArray {
        if (value.length != size * 2 || !value.matches(Regex("[0-9a-f]+")))
            throw IllegalArgumentException("Invalid hexadecimal field")
        return ByteArray(size) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun copyExactly(input: InputStream, output: OutputStream, length: Long,
                    progress: ((Long) -> Unit)? = null): String {
        require(length in 0..MAX_FILE_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (copied < length) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Transfer cancelled")
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), length - copied).toInt())
            if (count < 0) throw EOFException("File stream ended early")
            if (count == 0) continue
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            copied += count
            progress?.invoke(copied)
        }
        return hex(digest.digest())
    }
}
