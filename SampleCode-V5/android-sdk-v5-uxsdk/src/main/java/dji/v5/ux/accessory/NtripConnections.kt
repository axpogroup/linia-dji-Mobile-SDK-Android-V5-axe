package dji.v5.ux.accessory

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Finds the TCP connections of this app to an NTRIP caster and shuts them down.
 *
 * Stopping the DJI custom network RTK service leaves its connection to the caster open, and so
 * does a connection the caster has closed. The caster keeps the login in use while such a
 * connection is open, so casters that allow one connection per login reject the next start.
 * Shutting the connection down frees the login. The file descriptors stay open, because they
 * belong to the SDK.
 *
 * Blocks on the DNS lookup of the caster, so it must not be called on the main thread.
 */
internal object NtripConnections {

    /** Describes the open connections to [host]:[port]. */
    fun find(host: String, port: Int): List<String> = forEachConnection(host, port) { }

    /** Shuts down the open connections to [host]:[port] and describes them. */
    fun shutDown(host: String, port: Int): List<String> = forEachConnection(host, port) {
        Os.shutdown(it, OsConstants.SHUT_RDWR)
    }

    private fun forEachConnection(host: String, port: Int, action: (FileDescriptor) -> Unit): List<String> {
        val casterAddresses = InetAddress.getAllByName(host).toSet()
        val connections = mutableListOf<String>()
        File("/proc/self/fd").list()?.forEach { name ->
            try {
                if (!Os.readlink("/proc/self/fd/$name").startsWith("socket:")) return@forEach
                // A duplicate of the descriptor, closing it leaves the SDK's descriptor open
                ParcelFileDescriptor.fromFd(name.toInt()).use { socket ->
                    val peer = Os.getpeername(socket.fileDescriptor) as? InetSocketAddress ?: return@use
                    if (peer.port != port || peer.address !in casterAddresses) return@use
                    val local = Os.getsockname(socket.fileDescriptor) as? InetSocketAddress
                    action(socket.fileDescriptor)
                    connections += "local port ${local?.port} to ${peer.address.hostAddress}:${peer.port}"
                }
            } catch (e: Exception) {
                // The descriptor was closed meanwhile, or it is no connected socket
            }
        }
        return connections
    }
}
