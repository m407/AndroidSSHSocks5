package com.example.service

import android.util.Log
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
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

    @Throws(IOException::class)
    fun start() {
        if (isRunning) return

        // Bind synchronously so startup failures can be reported to the foreground service.
        serverSocket = ServerSocket(port, 50, InetAddress.getByName(LOOPBACK_ADDRESS))
        isRunning = true

        threadPool.execute {
            try {
                Log.i("LocalSocks5Proxy", "SOCKS5 Proxy Server started on $LOOPBACK_ADDRESS:$port")
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
        if (!isRunning && serverSocket == null) return
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("LocalSocks5Proxy", "Error closing SOCKS5 server socket", e)
        }
        serverSocket = null
        try {
            threadPool.shutdownNow()
        } catch (ignored: Exception) {
            // ignore
        }
    }

    private fun handleClient(client: Socket) {
        var channel: ChannelDirectTCPIP? = null
        try {
            client.soTimeout = INITIAL_SOCKET_TIMEOUT_MS
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val version = input.read()
            if (version != SOCKS_VERSION) {
                Log.w("LocalSocks5Proxy", "Invalid SOCKS version: $version")
                return
            }

            val nmethods = input.read()
            if (nmethods <= 0) {
                output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_ACCEPTABLE_METHODS.toByte()))
                output.flush()
                return
            }

            val methods = input.readFully(nmethods)
            if (!methods.contains(NO_AUTHENTICATION_REQUIRED.toByte())) {
                output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_ACCEPTABLE_METHODS.toByte()))
                output.flush()
                return
            }

            output.write(byteArrayOf(SOCKS_VERSION.toByte(), NO_AUTHENTICATION_REQUIRED.toByte()))
            output.flush()

            val reqVersion = input.read()
            val command = input.read()
            val reserved = input.read()
            val addressType = input.read()

            if (reqVersion != SOCKS_VERSION || reserved != 0) {
                Log.w("LocalSocks5Proxy", "Malformed SOCKS request. Version: $reqVersion, reserved: $reserved")
                output.writeSocksReply(GENERAL_FAILURE)
                return
            }

            if (command != CONNECT_COMMAND) {
                Log.w("LocalSocks5Proxy", "Unsupported SOCKS command: $command")
                output.writeSocksReply(COMMAND_NOT_SUPPORTED)
                return
            }

            val targetHost = when (addressType) {
                ADDRESS_TYPE_IPV4 -> InetAddress.getByAddress(input.readFully(IPV4_BYTE_COUNT)).hostAddress
                ADDRESS_TYPE_DOMAIN -> {
                    val length = input.read()
                    if (length <= 0) {
                        output.writeSocksReply(ADDRESS_TYPE_NOT_SUPPORTED)
                        return
                    }
                    String(input.readFully(length), Charsets.US_ASCII)
                }
                ADDRESS_TYPE_IPV6 -> InetAddress.getByAddress(input.readFully(IPV6_BYTE_COUNT)).hostAddress
                else -> {
                    Log.w("LocalSocks5Proxy", "Unsupported SOCKS address type: $addressType")
                    output.writeSocksReply(ADDRESS_TYPE_NOT_SUPPORTED)
                    return
                }
            }

            val targetPortBytes = input.readFully(2)
            val targetPort = ((targetPortBytes[0].toInt() and 0xFF) shl 8) or (targetPortBytes[1].toInt() and 0xFF)

            val sshSession = sessionProvider()
            if (sshSession == null || !sshSession.isConnected) {
                Log.e("LocalSocks5Proxy", "Cannot tunnel: SSH Session is disconnected")
                output.writeSocksReply(HOST_UNREACHABLE)
                return
            }

            channel = sshSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(targetHost)
            channel.setPort(targetPort)

            try {
                channel.connect(CHANNEL_CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.e("LocalSocks5Proxy", "Failed to connect tunnel to $targetHost:$targetPort", e)
                output.writeSocksReply(CONNECTION_REFUSED)
                return
            }

            output.writeSocksReply(SUCCEEDED)
            client.soTimeout = 0

            val localToRemote = threadPool.submit {
                pipe(input, channel.outputStream)
            }
            val remoteToLocal = threadPool.submit {
                pipe(channel.inputStream, output)
            }

            localToRemote.get()
            remoteToLocal.get()
        } catch (e: EOFException) {
            Log.w("LocalSocks5Proxy", "SOCKS client disconnected before completing request")
        } catch (e: Exception) {
            Log.e("LocalSocks5Proxy", "Exception in client proxy socket handling", e)
        } finally {
            try {
                channel?.disconnect()
            } catch (ignored: Exception) {
                // ignore
            }
            try {
                client.close()
            } catch (ignored: Exception) {
                // ignore
            }
        }
    }

    private fun pipe(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(PIPE_BUFFER_SIZE)
        try {
            while (isRunning) {
                val bytesRead = from.read(buffer)
                if (bytesRead == -1) break
                to.write(buffer, 0, bytesRead)
                to.flush()
            }
        } catch (ignored: Exception) {
            // normal stream shutdown or socket closing
        } finally {
            try {
                from.close()
            } catch (ignored: Exception) {
                // ignore
            }
            try {
                to.close()
            } catch (ignored: Exception) {
                // ignore
            }
        }
    }

    private fun InputStream.readFully(length: Int): ByteArray {
        val buffer = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = read(buffer, offset, length - offset)
            if (read == -1) throw EOFException("Expected $length bytes, received $offset")
            offset += read
        }
        return buffer
    }

    private fun OutputStream.writeSocksReply(replyCode: Int) {
        write(byteArrayOf(SOCKS_VERSION.toByte(), replyCode.toByte(), 0x00, ADDRESS_TYPE_IPV4.toByte(), 0, 0, 0, 0, 0, 0))
        flush()
    }

    companion object {
        private const val LOOPBACK_ADDRESS = "127.0.0.1"
        private const val INITIAL_SOCKET_TIMEOUT_MS = 30_000
        private const val CHANNEL_CONNECT_TIMEOUT_MS = 10_000
        private const val PIPE_BUFFER_SIZE = 32_768

        private const val SOCKS_VERSION = 0x05
        private const val NO_AUTHENTICATION_REQUIRED = 0x00
        private const val NO_ACCEPTABLE_METHODS = 0xFF
        private const val CONNECT_COMMAND = 0x01

        private const val SUCCEEDED = 0x00
        private const val GENERAL_FAILURE = 0x01
        private const val HOST_UNREACHABLE = 0x03
        private const val CONNECTION_REFUSED = 0x05
        private const val COMMAND_NOT_SUPPORTED = 0x07
        private const val ADDRESS_TYPE_NOT_SUPPORTED = 0x08

        private const val ADDRESS_TYPE_IPV4 = 0x01
        private const val ADDRESS_TYPE_DOMAIN = 0x03
        private const val ADDRESS_TYPE_IPV6 = 0x04
        private const val IPV4_BYTE_COUNT = 4
        private const val IPV6_BYTE_COUNT = 16
    }
}
