package org.localdrop.app

import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.Socket
import java.security.SecureRandom
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket

class TransferEngine(
    private val context: Context,
    private val ownId: String,
    private val ownName: String,
    private val localization: Localization,
    private val verifyPeer: (String, String) -> Boolean,
    private val acceptFile: (String, String, Long) -> Boolean,
    private val status: (String) -> Unit,
    private val onReceived: () -> Unit,
) {
    private val credentials = LocalCredentials(context, ownId)
    private fun t(key: String, vararg args: Any) = localization.text(key, *args)
    private val active = AtomicBoolean(false)
    val isBusy: Boolean get() = active.get()
    @Volatile private var activeSocket: Socket? = null
    @Volatile private var cancelRequested = false

    fun cancel() {
        if (!active.get()) return
        cancelRequested = true
        val socket = activeSocket ?: return
        // Closing an SSLSocket can write a TLS close_notify. Conscrypt rejects that
        // network I/O on Android's main thread, where the Cancel button runs.
        Thread({
            try { socket.close() }
            catch (e: Exception) { Log.w(TAG, "event=cancel_socket_close_failed", e) }
        }, "LocalDrop-cancel").apply { isDaemon = true }.start()
    }

    fun receive(socket: Socket) = runSession(socket) {
        val tls = wrap(socket, false)
        tls.use {
            tls.needClientAuth = true
            tls.startHandshake()
            val peerHash = peerHash(tls)
            val client = readHello(tls)
            val known = credentials.isTrusted(client.id, peerHash)
            val serverNonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
            sendHello(tls, serverNonce, known)
            verify(tls, client.name, client.id, peerHash, client.known, known,
                LdProtocol.fromHex(client.nonce, 32), serverNonce, false)
            receiveFile(tls, client.name)
        }
    }

    fun send(peer: DiscoveryRecord, uri: Uri) {
        if (peer.capabilities != "file1" || peer.host == null) {
            status(t("peer_unavailable"))
            return
        }
        if (!active.compareAndSet(false, true)) { status(t("transfer_busy")); return }
        cancelRequested = false
        try {
            val (name, length) = metadata(uri)
            val source = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("File could not be read")
            source.use {
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(peer.host, peer.port), 15_000)
                activeSocket = socket
                socket.use {
                    val tls = wrap(socket, true)
                    tls.use {
                        tls.startHandshake()
                        val remoteHash = peerHash(tls)
                        val known = credentials.isTrusted(peer.id, remoteHash)
                        val clientNonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
                        sendHello(tls, clientNonce, known)
                        val server = readHello(tls)
                        if (server.id != peer.id) throw IllegalArgumentException("Device identity changed")
                        verify(tls, server.name, server.id, remoteHash, known, server.known,
                            clientNonce, LdProtocol.fromHex(server.nonce, 32), true)
                        val transferId = DiscoveryRecord.newId()
                        LdProtocol.write(tls.outputStream, JSONObject()
                            .put("kind", "request").put("id", transferId).put("name", name).put("length", length))
                        val reply = LdProtocol.read(tls.inputStream)
                        LdProtocol.requireKind(reply, "response")
                        if (!LdProtocol.requiredBoolean(reply, "accept")) {
                            status(t(if (reply.optString("reason") == "no_space") "receiver_space" else "receiver_declined"))
                            return
                        }
                        tls.soTimeout = 30_000
                        status(t("sending", name))
                        Log.i(TAG, "event=transfer_started id=$transferId")
                        var nextUpdate = 0L
                        val started = System.nanoTime()
                        val hash = LdProtocol.copyExactly(source, tls.outputStream, length) { sent ->
                            if (sent >= nextUpdate) {
                                status(progressText(t("sending", name), sent, length, started))
                                nextUpdate = sent + 1_048_576
                            }
                        }
                        LdProtocol.write(tls.outputStream, JSONObject()
                            .put("kind", "complete").put("sha256", hash))
                        val result = LdProtocol.read(tls.inputStream)
                        LdProtocol.requireKind(result, "result")
                        if (!LdProtocol.requiredBoolean(result, "ok"))
                            throw IllegalArgumentException("Receiver could not save the file")
                        status(t("sent", name))
                        Log.i(TAG, "event=transfer_completed id=$transferId")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "event=outgoing_transfer_failed", e)
            Log.e(TAG, "event=outgoing_failure_type type=${e.javaClass.simpleName} cause=${e.cause?.javaClass?.simpleName}")
            status(if (cancelRequested) t("transfer_cancelled") else userError(e))
        } finally { activeSocket = null; active.set(false) }
    }

    private fun runSession(socket: Socket, block: () -> Unit) {
        if (!active.compareAndSet(false, true)) { socket.close(); return }
        cancelRequested = false
        activeSocket = socket
        try { socket.use { block() } }
        catch (e: Exception) {
            Log.e(TAG, "event=incoming_transfer_failed", e)
            Log.e(TAG, "event=incoming_failure_type type=${e.javaClass.simpleName} cause=${e.cause?.javaClass?.simpleName}")
            status(if (cancelRequested) t("transfer_cancelled") else userError(e))
        } finally { activeSocket = null; active.set(false) }
    }

    private fun wrap(socket: Socket, client: Boolean): SSLSocket =
        (credentials.context().socketFactory.createSocket(socket,
            socket.inetAddress.hostAddress, socket.port, true) as SSLSocket).apply {
            useClientMode = client
            // TLS 1.2 is the cross-platform version verified for LD/1 development builds.
            // Expand this after TLS 1.3 interoperability tests on physical devices.
            enabledProtocols = arrayOf("TLSv1.2")
            soTimeout = 300_000
            activeSocket = this
        }

    private fun peerHash(tls: SSLSocket): String {
        val certificate = tls.session.peerCertificates.firstOrNull() as? X509Certificate
            ?: throw IllegalArgumentException("Missing peer certificate")
        return LdProtocol.hex(LdProtocol.sha256(certificate.encoded))
    }

    private data class Hello(val id: String, val name: String, val nonce: String, val known: Boolean)

    private fun readHello(tls: SSLSocket): Hello {
        val frame = LdProtocol.read(tls.inputStream)
        LdProtocol.requireKind(frame, "hello")
        if (frame.optInt("pv", -1) != 1) throw IllegalArgumentException("Unsupported protocol version")
        val id = LdProtocol.requiredString(frame, "id", 32)
        LdProtocol.fromHex(id, 16)
        val name = LdProtocol.requiredString(frame, "name", 64)
        if (name.isBlank() || name.any { it.isISOControl() }) throw IllegalArgumentException("Invalid device name")
        val type = LdProtocol.requiredString(frame, "type", 16)
        if (type !in setOf("windows", "android")) throw IllegalArgumentException("Invalid device type")
        val nonce = LdProtocol.requiredString(frame, "nonce", 64)
        LdProtocol.fromHex(nonce, 32)
        return Hello(id, name, nonce, LdProtocol.requiredBoolean(frame, "known"))
    }

    private fun sendHello(tls: SSLSocket, nonce: ByteArray, known: Boolean) {
        LdProtocol.write(tls.outputStream, JSONObject().put("kind", "hello").put("pv", 1)
            .put("id", ownId).put("name", ownName).put("type", "android")
            .put("nonce", LdProtocol.hex(nonce)).put("known", known))
    }

    private fun verify(tls: SSLSocket, peerName: String, peerId: String, peerHash: String,
                       clientKnown: Boolean, serverKnown: Boolean,
                       clientNonce: ByteArray, serverNonce: ByteArray, client: Boolean) {
        val ownHash = LdProtocol.sha256(credentials.certificate.encoded)
        val remoteHash = LdProtocol.fromHex(peerHash, 32)
        val code = LdProtocol.securityCode(if (client) ownHash else remoteHash,
            if (client) remoteHash else ownHash, clientNonce, serverNonce)
        val approved = if (clientKnown && serverKnown) true else verifyPeer(peerName, code)
        if (client) {
            LdProtocol.write(tls.outputStream, JSONObject().put("kind", "verify").put("accept", approved))
            val reply = LdProtocol.read(tls.inputStream)
            LdProtocol.requireKind(reply, "verify")
            if (!approved || !LdProtocol.requiredBoolean(reply, "accept"))
                throw IllegalArgumentException("Security verification declined")
        } else {
            val reply = LdProtocol.read(tls.inputStream)
            LdProtocol.requireKind(reply, "verify")
            LdProtocol.write(tls.outputStream, JSONObject().put("kind", "verify").put("accept", approved))
            if (!approved || !LdProtocol.requiredBoolean(reply, "accept"))
                throw IllegalArgumentException("Security verification declined")
        }
        credentials.trust(peerId, peerHash)
        Log.i(TAG, "event=peer_verified id=$peerId")
    }

    private fun metadata(uri: Uri): Pair<String, Long> {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = LdProtocol.validateFileName(cursor.getString(0))
                if (cursor.isNull(1)) throw IllegalArgumentException("File size is unavailable")
                val length = cursor.getLong(1)
                if (length !in 0..LdProtocol.MAX_FILE_BYTES) throw IllegalArgumentException("File is too large")
                return name to length
            }
        }
        throw IllegalArgumentException("File metadata is unavailable")
    }

    private fun receiveFile(tls: SSLSocket, sender: String) {
        val frame = LdProtocol.read(tls.inputStream)
        LdProtocol.requireKind(frame, "request")
        val transferId = LdProtocol.requiredString(frame, "id", 32)
        LdProtocol.fromHex(transferId, 16)
        val name = LdProtocol.validateFileName(LdProtocol.requiredString(frame, "name", 255))
        val length = LdProtocol.requiredLength(frame)
        Log.i(TAG, "event=transfer_requested id=$transferId")
        val storage = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (storage == null || storage.usableSpace < length) {
            LdProtocol.write(tls.outputStream, JSONObject().put("kind", "response")
                .put("accept", false).put("reason", "no_space"))
            status(t("no_space"))
            return
        }
        val accepted = acceptFile(sender, name, length)
        LdProtocol.write(tls.outputStream, JSONObject().put("kind", "response")
            .put("accept", accepted).put("reason", if (accepted) "" else "declined"))
        if (!accepted) { Log.i(TAG, "event=transfer_declined id=$transferId"); return }
        Log.i(TAG, "event=transfer_accepted id=$transferId")
        val target = newPendingFile(name)
        try {
            target.output.use { output ->
                var nextUpdate = 0L
                val started = System.nanoTime()
                tls.soTimeout = 30_000
                val hash = LdProtocol.copyExactly(tls.inputStream, output, length) { received ->
                    if (received >= nextUpdate) {
                        status(progressText(t("receiving", name), received, length, started))
                        nextUpdate = received + 1_048_576
                    }
                }
                output.flush()
                val complete = LdProtocol.read(tls.inputStream)
                LdProtocol.requireKind(complete, "complete")
                val expected = LdProtocol.fromHex(LdProtocol.requiredString(complete, "sha256", 64), 32)
                if (!MessageDigest.isEqual(expected, LdProtocol.fromHex(hash, 32)))
                    throw IllegalArgumentException("File checksum mismatch")
            }
            target.commit()
            LdProtocol.write(tls.outputStream, JSONObject().put("kind", "result").put("ok", true))
            status(t("received", name))
            onReceived()
            Log.i(TAG, "event=transfer_completed id=$transferId")
        } catch (e: Exception) {
            target.abort()
            throw e
        }
    }

    private class PendingFile(val output: OutputStream, val commit: () -> Unit, val abort: () -> Unit)

    private fun newPendingFile(name: String): PendingFile {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, ReceivedFiles.mime(name))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/LocalDrop/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalArgumentException("Could not create download")
            val output = context.contentResolver.openOutputStream(uri)
                ?: run { context.contentResolver.delete(uri, null, null); throw IllegalArgumentException("Could not write download") }
            return PendingFile(output,
                { check(context.contentResolver.update(uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1) {
                    "Could not publish download"
                } },
                { context.contentResolver.delete(uri, null, null) })
        }
        // API 26–28: app-specific storage avoids broad external-storage permissions.
        val parent = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalArgumentException("Download storage unavailable")
        val directory = File(parent, "LocalDrop")
        check(directory.mkdirs() || directory.isDirectory)
        val file = generateSequence(0) { it + 1 }.take(1000).map { i ->
            val stem = name.substringBeforeLast('.', name)
            val extension = if (stem == name) "" else "." + name.substringAfterLast('.')
            File(directory, if (i == 0) name else "$stem ($i)$extension")
        }.firstOrNull { !it.exists() } ?: throw IllegalArgumentException("Too many duplicate file names")
        val partial = File(directory, ".${DiscoveryRecord.newId()}.partial")
        return PendingFile(FileOutputStream(partial),
            { check(partial.renameTo(file)) { "Could not save download" } },
            { partial.delete() })
    }

    private fun userError(error: Exception): String = when (error) {
        is javax.net.ssl.SSLException -> if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val root = generateSequence(error as Throwable) { it.cause }.last()
            "${t("secure_error")} (${root.javaClass.simpleName}: ${root.message?.take(120)})"
        }
        else t("secure_error")
        is java.net.SocketException, is java.net.SocketTimeoutException -> t("network_lost")
        is java.io.IOException -> t("file_io")
        else -> t("transfer_failed")
    }

    private fun progressText(action: String, bytes: Long, total: Long, started: Long): String {
        val seconds = maxOf((System.nanoTime() - started) / 1_000_000_000.0, 0.001)
        val speed = bytes / seconds
        val percent = if (total == 0L) 100.0 else 100.0 * bytes / total
        val remaining = if (speed > 0) ((total - bytes) / speed).toLong() else 0L
        return t("progress", action, bytes, total, "%.1f".format(percent),
            "%.1f".format(speed / 1048576), remaining)
    }

    companion object { private const val TAG = "LocalDrop" }
}
