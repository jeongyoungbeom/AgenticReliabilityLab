package com.project.agenticreliabilitylab.target.http

import com.project.agenticreliabilitylab.target.domain.RegisteredTarget
import com.project.agenticreliabilitylab.target.domain.TargetReadResponse
import com.project.agenticreliabilitylab.target.domain.TargetReadTransport
import com.project.agenticreliabilitylab.target.domain.TargetReadTransportException
import com.project.agenticreliabilitylab.target.domain.TargetNetworkPolicy
import org.springframework.stereotype.Component
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A deliberately small HTTP/1.1 client that connects to the CIDR-validated IP
 * instead of resolving the hostname a second time inside an HTTP client.
 */
@Component
@Suppress("TooManyFunctions", "ThrowsCount") // Parser branches remain explicit at the target trust boundary.
class PinnedTargetHttpTransport(
    private val targetNetworkPolicy: TargetNetworkPolicy,
) : TargetReadTransport {
    override fun send(
        target: RegisteredTarget,
        uri: URI,
        method: String,
        headers: Map<String, String>,
        body: ByteArray,
        timeout: Duration,
    ): TargetReadResponse {
        val deadlineNanos = System.nanoTime() + timeout.toNanos()
        val address = resolveAddressBeforeDeadline(uri, target, remainingMilliseconds(deadlineNanos)).first()
        val timeoutMillis = remainingMilliseconds(deadlineNanos)
        val finished = AtomicBoolean(false)
        var watchdog: Thread? = null
        try {
            val response = Socket().use { socket ->
                watchdog = Thread.ofVirtual().name("target-http-deadline-").start {
                    try {
                        Thread.sleep(timeoutMillis.toLong())
                        if (!finished.get()) socket.close()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                socket.connect(InetSocketAddress(address, uri.effectivePort()), timeoutMillis)
                socket.soTimeout = timeoutMillis
                val connection = if (uri.scheme.equals("https", ignoreCase = true)) {
                    socket.toTlsSocket(uri, timeoutMillis)
                } else {
                    socket
                }
                connection.use {
                    writeRequest(connection, uri, method, headers, body)
                    readResponse(connection, MAX_RESPONSE_BYTES)
                }
            }
            return response
        } catch (exception: TargetHttpTransportException) {
            throw exception
        } catch (exception: Exception) {
            throw TargetHttpTransportException("Pinned target HTTP request failed: ${exception.javaClass.simpleName}", exception)
        } finally {
            finished.set(true)
            watchdog?.interrupt()
        }
    }

    private fun writeRequest(
        socket: Socket,
        uri: URI,
        method: String,
        headers: Map<String, String>,
        body: ByteArray,
    ) {
        val host = uri.host.let { if (':' in it) "[$it]" else it } +
            if (uri.hasExplicitNonDefaultPort()) ":${uri.port}" else ""
        val requestTarget = buildString {
            append(uri.rawPath.takeUnless { it.isNullOrBlank() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        val request = buildString {
            append(method).append(' ').append(requestTarget).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("Connection: close\r\n")
            append("Accept-Encoding: identity\r\n")
            headers.forEach { (name, value) ->
                require(name.isSafeHeaderValue() && value.isSafeHeaderValue()) { "Unsafe target request header" }
                append(name).append(": ").append(value).append("\r\n")
            }
            append("Content-Length: ").append(body.size).append("\r\n\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        socket.outputStream.write(request)
        socket.outputStream.write(body)
        socket.outputStream.flush()
    }

    private fun readResponse(socket: Socket, maxBytes: Int): TargetReadResponse {
        val input = BufferedInputStream(socket.inputStream)
        val statusLine = input.readHeaderLine()
            ?: throw TargetHttpTransportException("Target closed the connection without an HTTP response")
        val parts = statusLine.split(' ', limit = 3)
        val statusCode = parts.getOrNull(1)?.toIntOrNull()
            ?: throw TargetHttpTransportException("Target returned an invalid HTTP status line")
        val headers = linkedMapOf<String, String>()
        var headerBytes = statusLine.length + HTTP_LINE_TERMINATOR_BYTES
        var headerCount = 0
        while (true) {
            val line = input.readHeaderLine() ?: throw TargetHttpTransportException("Target closed the response headers early")
            if (line.isEmpty()) break
            headerBytes += line.length + HTTP_LINE_TERMINATOR_BYTES
            headerCount += 1
            if (headerBytes > MAX_RESPONSE_HEADER_BYTES || headerCount > MAX_RESPONSE_HEADER_COUNT) {
                throw TargetHttpTransportException("Target response headers exceed the configured limit")
            }
            val separator = line.indexOf(':')
            if (separator <= 0) throw TargetHttpTransportException("Target returned an invalid HTTP header")
            headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
        }
        val body = when {
            headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> input.readChunked(maxBytes)
            headers["content-length"] != null -> input.readExactlyBounded(headers.getValue("content-length"), maxBytes)
            else -> input.readUntilEndBounded(maxBytes)
        }
        return TargetReadResponse(statusCode, body)
    }

    private fun BufferedInputStream.readHeaderLine(): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val value = read()
            if (value == -1) return if (bytes.size() == 0) null else throw EOFException("Unexpected end of HTTP line")
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.write(value)
            if (bytes.size() > MAX_HEADER_LINE_BYTES) throw TargetHttpTransportException("Target response header is too large")
        }
        return bytes.toString(StandardCharsets.ISO_8859_1)
    }

    private fun BufferedInputStream.readExactlyBounded(lengthText: String, maxBytes: Int): ByteArray {
        val length = lengthText.toLongOrNull()
            ?.takeIf { it in 0..maxBytes.toLong() }
            ?: throw TargetHttpTransportException("Target response content length exceeds the configured limit")
        return ByteArray(length.toInt()).also { bytes ->
            var offset = 0
            while (offset < bytes.size) {
                val count = read(bytes, offset, bytes.size - offset)
                if (count == -1) throw TargetHttpTransportException("Target closed the response body early")
                offset += count
            }
        }
    }

    private fun BufferedInputStream.readChunked(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            val line = readHeaderLine() ?: throw TargetHttpTransportException("Target closed a chunked response early")
            val chunkSize = line.substringBefore(';').trim().toIntOrNull(16)
                ?: throw TargetHttpTransportException("Target returned an invalid chunk size")
            if (chunkSize == 0) {
                readBoundedTrailers()
                return output.toByteArray()
            }
            if (chunkSize < 0 || output.size() + chunkSize > maxBytes) {
                throw TargetHttpTransportException("Target response exceeds the configured limit")
            }
            output.write(readExactlyBounded(chunkSize.toString(), maxBytes))
            if (read() != '\r'.code || read() != '\n'.code) {
                throw TargetHttpTransportException("Target returned an invalid chunk delimiter")
            }
        }
    }

    private fun BufferedInputStream.readBoundedTrailers() {
        var trailerBytes = 0
        var trailerCount = 0
        while (true) {
            val line = readHeaderLine() ?: throw TargetHttpTransportException("Target closed response trailers early")
            if (line.isEmpty()) return
            trailerBytes += line.length + HTTP_LINE_TERMINATOR_BYTES
            trailerCount += 1
            if (trailerBytes > MAX_RESPONSE_HEADER_BYTES || trailerCount > MAX_RESPONSE_HEADER_COUNT) {
                throw TargetHttpTransportException("Target response trailers exceed the configured limit")
            }
            if (line.indexOf(':') <= 0) throw TargetHttpTransportException("Target returned an invalid HTTP trailer")
        }
    }

    private fun BufferedInputStream.readUntilEndBounded(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val count = read(buffer)
            if (count == -1) return output.toByteArray()
            if (output.size() + count > maxBytes) throw TargetHttpTransportException("Target response exceeds the configured limit")
            output.write(buffer, 0, count)
        }
    }

    private fun Socket.toTlsSocket(uri: URI, timeoutMillis: Int): SSLSocket {
        val tlsSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(this, uri.host, uri.effectivePort(), true) as SSLSocket
        tlsSocket.soTimeout = timeoutMillis
        tlsSocket.sslParameters = tlsSocket.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
            serverNames = listOf(SNIHostName(uri.host))
        }
        tlsSocket.startHandshake()
        return tlsSocket
    }

    private fun URI.effectivePort(): Int = if (port >= 0) port else if (scheme.equals("https", true)) 443 else 80

    private fun URI.hasExplicitNonDefaultPort(): Boolean =
        port >= 0 && port != if (scheme.equals("https", true)) 443 else 80

    private fun resolveAddressBeforeDeadline(uri: URI, target: RegisteredTarget, timeoutMillis: Int): List<InetAddress> {
        val result = CompletableFuture<List<InetAddress>>()
        Thread.ofVirtual().name("target-dns-deadline-").start {
            try {
                result.complete(targetNetworkPolicy.resolveAllowed(uri, target))
            } catch (exception: Exception) {
                result.completeExceptionally(exception)
            }
        }
        return try {
            result.get(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        } catch (exception: Exception) {
            result.cancel(true)
            throw TargetHttpTransportException("Target DNS resolution exceeded the request deadline", exception)
        }
    }

    private fun remainingMilliseconds(deadlineNanos: Long): Int {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
        if (remaining <= 0) throw TargetHttpTransportException("Target request exceeded its execution deadline")
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun String.isSafeHeaderValue(): Boolean = none { it == '\r' || it == '\n' }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576
        const val MAX_HEADER_LINE_BYTES = 8_192
        const val MAX_RESPONSE_HEADER_BYTES = 32_768
        const val MAX_RESPONSE_HEADER_COUNT = 64
        const val HTTP_LINE_TERMINATOR_BYTES = 2
        const val BUFFER_SIZE = 8_192
    }
}

typealias PinnedHttpResponse = TargetReadResponse
typealias TargetHttpTransportException = TargetReadTransportException
