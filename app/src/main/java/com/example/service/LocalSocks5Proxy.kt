package com.example.service

import android.util.Log
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class LocalSocks5Proxy(
    private val port: Int,
    private val sessionProvider: () -> Session?
) {
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private val threadPool = Executors.newCachedThreadPool()

    fun start() {
        if (isRunning) return
        isRunning = true
        threadPool.execute {
            try {
                // Bind to localhost (127.0.0.1) only for security, as is standard
                serverSocket = ServerSocket(port, 50, java.net.InetAddress.getByName("127.0.0.1"))
                Log.i("LocalSocks5Proxy", "SOCKS5 Proxy Server started on 127.0.0.1:$port")
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    threadPool.execute {
                        handleClient(clientSocket)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e("LocalSocks5Proxy", "Error in SOCKS5 server loop", e)
                }
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("LocalSocks5Proxy", "Error closing SOCKS5 server socket", e)
        }
        serverSocket = null
        try {
            threadPool.shutdownNow()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30000 // 30 seconds idle timeout initially
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // 1. Handshake
            val version = input.read()
            if (version != 5) {
                Log.w("LocalSocks5Proxy", "Invalid SOCKS version: $version")
                client.close()
                return
            }

            val nmethods = input.read()
            if (nmethods <= 0) {
                client.close()
                return
            }

            val methods = ByteArray(nmethods)
            var readMethods = 0
            while (readMethods < nmethods) {
                val r = input.read(methods, readMethods, nmethods - readMethods)
                if (r == -1) break
                readMethods += r
            }

            // Respond with method 0x00 (NO AUTHENTICATION REQUIRED)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // 2. Request
            val reqVersion = input.read()
            if (reqVersion == -1) return
            val command = input.read()
            val reserved = input.read()
            val addressType = input.read()

            if (reqVersion != 5 || command != 1) { // Command 1 = CONNECT
                Log.w("LocalSocks5Proxy", "Unsupported SOCKS request. Version: $reqVersion, cmd: $command")
                // Reply with command not supported (0x07) or general failure (0x01)
                output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                client.close()
                return
            }

            val targetHost = when (addressType) {
                1 -> { // IPv4
                    val ipBytes = ByteArray(4)
                    var readIp = 0
                    while (readIp < 4) {
                        val r = input.read(ipBytes, readIp, 4 - readIp)
                        if (r == -1) break
                        readIp += r
                    }
                    java.net.InetAddress.getByAddress(ipBytes).hostAddress
                }
                3 -> { // Domain name
                    val len = input.read()
                    if (len == -1) return
                    val hostBytes = ByteArray(len)
                    var readHost = 0
                    while (readHost < len) {
                        val r = input.read(hostBytes, readHost, len - readHost)
                        if (r == -1) break
                        readHost += r
                    }
                    String(hostBytes, Charsets.US_ASCII)
                }
                else -> {
                    Log.w("LocalSocks5Proxy", "Unsupported SOCKS address type: $addressType")
                    output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                    client.close()
                    return
                }
            }

            val p1 = input.read()
            val p2 = input.read()
            if (p1 == -1 || p2 == -1) return
            val targetPort = (p1 shl 8) or p2

            // Establish SSH Direct tunnel forwarding
            val sshSession = sessionProvider()
            if (sshSession == null || !sshSession.isConnected) {
                Log.e("LocalSocks5Proxy", "Cannot tunnel: SSH Session is disconnected")
                // Reply with host unreachable (0x03)
                output.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                client.close()
                return
            }

            val channel = sshSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(targetHost)
            channel.setPort(targetPort)

            // Connect channel with timeout
            try {
                channel.connect(10000)
            } catch (e: Exception) {
                Log.e("LocalSocks5Proxy", "Failed to connect tunnel to $targetHost:$targetPort", e)
                // Reply with Connection refused (0x05)
                output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                client.close()
                return
            }

            // SOCKS Successful response
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()

            client.soTimeout = 0 // Remove timeout for active piping session

            val localToRemote = threadPool.submit {
                pipe(input, channel.outputStream)
            }
            val remoteToLocal = threadPool.submit {
                pipe(channel.inputStream, output)
            }

            // Wait for transfer streams to finish/terminate
            localToRemote.get()
            remoteToLocal.get()

        } catch (e: Exception) {
            Log.e("LocalSocks5Proxy", "Exception in client proxy socket handling", e)
        } finally {
            try {
                client.close()
            } catch (ignored: Exception) {}
        }
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(32768)
        try {
            while (isRunning) {
                val bytesRead = from.read(buffer)
                if (bytesRead == -1) break
                to.write(buffer, 0, bytesRead)
                to.flush()
            }
        } catch (e: Exception) {
            // normal stream shutdown or socket closing
        } finally {
            try {
                from.close()
            } catch (ignored: Exception) {}
            try {
                to.close()
            } catch (ignored: Exception) {}
        }
    }
}
