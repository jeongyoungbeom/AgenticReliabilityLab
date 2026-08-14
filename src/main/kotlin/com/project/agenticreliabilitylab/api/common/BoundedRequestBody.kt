package com.project.agenticreliabilitylab.api.common

import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import java.io.IOException

/** Applies a byte limit while Spring reads an untrusted request body. */
class BoundedRequestBody(
    request: HttpServletRequest,
    private val maxBytes: Long,
) : HttpServletRequestWrapper(request) {
    override fun getInputStream(): ServletInputStream = BoundedServletInputStream(super.getInputStream(), maxBytes)
}

class RequestBodyTooLargeException : IOException()

private class BoundedServletInputStream(
    private val delegate: ServletInputStream,
    private val maxBytes: Long,
) : ServletInputStream() {
    private var byteCount = 0L

    override fun read(): Int = delegate.read().also { value ->
        if (value >= 0) count(ONE_BYTE)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length).also(::count)

    override fun isFinished(): Boolean = delegate.isFinished

    override fun isReady(): Boolean = delegate.isReady

    override fun setReadListener(readListener: ReadListener) = delegate.setReadListener(readListener)

    private fun count(read: Int) {
        if (read > 0) byteCount += read.toLong()
        if (byteCount > maxBytes) throw RequestBodyTooLargeException()
    }

    private companion object { const val ONE_BYTE = 1 }
}
