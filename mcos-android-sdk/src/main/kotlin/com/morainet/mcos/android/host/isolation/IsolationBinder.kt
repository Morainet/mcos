package com.morainet.mcos.android.host.isolation

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException

/**
 * The Android Binder shell of isolation slice 3b-final — deliberately the
 * ONLY untested layer of the isolation stack: each member does nothing but
 * shuttle one frame string through [Parcel] and delegate to a pure,
 * JVM-tested core ([BinderWire] framing, [WireService] serving,
 * [IsolatedFacadeServer] / [IsolatedPluginRunner] semantics). What cannot be
 * exercised on the JVM — the Binder kernel itself, process separation,
 * `getCallingUid` — is exactly what on-device verification (the remaining
 * slice-3b-final item) covers.
 */

/**
 * [WirePipe] over a remote [IBinder]: one transaction per exchange, frame as
 * the single Parcel string. [RemoteException] (incl. `DeadObjectException`
 * — the plugin process died) becomes a plain runtime exception so
 * [TransportIsolationHost] maps it to an honest `PLUGIN_ERROR`
 * `isolation_transport_failure` instead of the runtime crashing
 * ([08-security.md §8.1]).
 */
class BinderWirePipe(private val remote: IBinder) : WirePipe {

    override fun exchange(code: Int, request: String): String {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeString(request)
            if (!remote.transact(code, data, reply, 0)) {
                throw IllegalStateException("binder transact (code $code) returned false")
            }
            reply.readString()
                ?: throw IllegalStateException("binder transact (code $code) returned an empty reply")
        } catch (e: RemoteException) {
            throw IllegalStateException("binder transport failure (code $code): ${e.message}", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

/**
 * Main-process endpoint: serves [BinderWire.CODE_FACADE] frames through the
 * §8.2 gate + host facade. The caller identity is taken from the Binder
 * kernel (`Binder.getCallingUid()`) — it never travels inside the frame —
 * and [IsolatedFacadeServer.handle] enforces it FIRST, so a frame from any
 * process other than the admitted plugin UID never touches the host facade
 * ([08-security.md §8.2 check 1).
 */
class FacadeBinderEndpoint(private val server: IsolatedFacadeServer) : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != BinderWire.CODE_FACADE) return super.onTransact(code, data, reply, flags)
        val frame = data.readString() ?: return false
        // Runs on a Binder thread; the blocking serve matches the synchronous
        // transact on the plugin side.
        reply?.writeString(WireService.serveFacade(frame, server, Binder.getCallingUid()))
        return true
    }
}

/**
 * Plugin-process endpoint: serves [BinderWire.CODE_INVOKE] frames by
 * delegating to a callback that owns this connection's [IsolatedPluginRunner]
 * — every service connection gets its own endpoint bound to its own runner,
 * so multiple plugins can share the plugin process without crossing.
 */
class InvokeBinderEndpoint(private val serve: (frame: String) -> String) : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code != BinderWire.CODE_INVOKE) return super.onTransact(code, data, reply, flags)
        val frame = data.readString() ?: return false
        reply?.writeString(serve(frame))
        return true
    }
}
