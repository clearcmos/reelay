package com.clearcmos.reelay

import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Minimal HTTP/1.1 server on a loopback port for downloader tests. Android's unit-test
 * compile classpath has no `com.sun.net.httpserver`, so this uses plain sockets.
 */
class FakeHttpServer : Closeable {
    private class Route(val status: Int, val body: ByteArray)

    private val routes = ConcurrentHashMap<String, Route>()
    private val socket = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    private val pool = Executors.newCachedThreadPool()

    /** Request header values seen per path, keyed by lower-case header name. */
    val requestHeaders = ConcurrentHashMap<String, Map<String, String>>()

    val port: Int get() = socket.localPort

    fun url(path: String) = "http://127.0.0.1:$port$path"

    fun serve(path: String, status: Int, body: ByteArray) {
        routes[path] = Route(status, body)
    }

    fun start() {
        pool.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { handle(client) }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { connection ->
            val reader = BufferedReader(InputStreamReader(connection.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(' ').getOrNull(1) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            requestHeaders[path] = headers
            val route = routes[path] ?: Route(404, ByteArray(0))
            val reason = if (route.status in 200..299) "OK" else "Error"
            val head =
                "HTTP/1.1 ${route.status} $reason\r\n" +
                    "Content-Type: video/mp4\r\nContent-Length: ${route.body.size}\r\nConnection: close\r\n\r\n"
            connection.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(route.body)
                flush()
            }
        }
    }

    override fun close() {
        socket.close()
        pool.shutdownNow()
    }
}
