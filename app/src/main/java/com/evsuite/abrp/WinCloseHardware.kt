package com.evsuite.abrp

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.evsuite.hardware.EVHardware
import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.VehicleWriteGate
import java.lang.reflect.Proxy

/**
 * Adapted from ../winclose/app/src/main/java/com/mg4/winclose/WindowHardware.kt.
 * Keeps WinClose's SWI69 CarAdapterClient clients, CarState transaction 6 (0=open),
 * and setVehicleWindowStatus(area, command). No alternative door property is used.
 * Speed/time arming, delay, beeps, AUTO and per-window settings are intentionally absent.
 * WinClose identifies this event as the driver door; it carries no per-door area ID.
 */
internal class WinCloseHardware(
    context: Context,
    private val onOpening: () -> Unit,
    private val report: (String) -> Unit,
) : AutoCloseable {
    private val context = context.applicationContext
    private val thread = HandlerThread("winclose-hardware").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var closed = false
    @Volatile private var vsm: Any? = null
    @Volatile private var state: Any? = null
    private var vsmBinder: IBinder? = null
    private var stateBinder: IBinder? = null
    private var callbackProxy: Any? = null
    private val doorSignal = WinCloseDoorSignal()
    @Volatile private var registeredAt = Long.MIN_VALUE
    private var initialized = false
    private val retry = object : Runnable {
        override fun run() {
            if (closed) return
            try {
                if (!initialized) {
                    EVHardware.init(context)
                    initialized = true
                }
                if (FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI69) connect()
            } catch (e: Exception) {
                report("WinClose connection: ${e.javaClass.simpleName}")
            }
            if (!closed) handler.postDelayed(this, 5_000L)
        }
    }

    fun start() { handler.post(retry) }

    private fun connect() {
        if (vsmBinder?.isBinderAlive == true && stateBinder?.isBinderAlive == true) return
        val launcher = context.createPackageContext("com.saicmotor.launcher",
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val loader = launcher.classLoader
        val adapterClass = loader.loadClass("com.saicmotor.carapi.CarAdapterClient")
        val adapter = adapterClass.getMethod("getInstance", Context::class.java)
            .invoke(null, context) ?: return
        // The same adapter startup and queryClient codes as WinClose.
        adapterClass.getMethod("start").invoke(adapter)
        val query = adapterClass.getMethod("queryClient", INT)
        if (vsmBinder?.isBinderAlive != true) {
            vsm = null
            val binder = query.invoke(adapter, 0x8) as? IBinder
            if (binder != null) {
                val cls = loader.loadClass("com.saicmotor.carapi.client.CarVehicleSettingClient")
                vsm = cls.getConstructor(IBinder::class.java).newInstance(binder)
                vsmBinder = binder
                report("WinClose VSM ready")
            }
        }
        if (stateBinder?.isBinderAlive == true) return
        detachListener()
        val binder = query.invoke(adapter, 0xb) as? IBinder ?: return
        val cls = loader.loadClass("com.saicmotor.carapi.client.CarStateClient")
        val client = cls.getConstructor(IBinder::class.java).newInstance(binder)
        val register = cls.methods.firstOrNull {
            it.name in listOf("registerListener", "registListener") && it.parameterCount == 1
        } ?: return
        val iface = register.parameterTypes[0]
        if (!iface.isInterface) return
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == IBinder.INTERFACE_TRANSACTION) {
                    reply?.writeString(DESCRIPTOR)
                    return true
                }
                if (code !in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION)
                    return super.onTransact(code, data, reply, flags)
                try {
                    data.enforceInterface(DESCRIPTOR)
                    // Acknowledge the other CarState callbacks as WinClose does, but only
                    // TX_DOOR_SENSOR=6 is a trigger. It is not a generic property or bitmask.
                    if (code == 6) {
                        val raw = data.readInt()
                        val receivedAt = SystemClock.elapsedRealtime()
                        handler.post {
                            if (closed || stateBinder !== binder) return@post
                            val sinceRegistration = receivedAt - registeredAt
                            if (sinceRegistration < REGISTRATION_REPLAY_MS) {
                                // Whatever arrives this soon after registering describes the
                                // door as it already is, not a door someone just opened.
                                doorSignal.seed(raw)
                                report("WinClose door state seeded: raw=$raw")
                                return@post
                            }
                            val fresh = SystemClock.elapsedRealtime() - receivedAt < 1_500L
                            val opening = doorSignal.opening(raw)
                            if (opening) report("WinClose door opening: raw=$raw fresh=$fresh")
                            if (fresh && opening) onOpening()
                        }
                    }
                    reply?.writeNoException()
                } catch (_: Exception) {
                    // An unreadable callback must not re-arm an already-open door.
                    reply?.writeNoException()
                }
                return true
            }
        }
        val proxy = Proxy.newProxyInstance(loader, arrayOf(iface)) { self, method, args ->
            when (method.name) {
                "asBinder" -> callback
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.firstOrNull()
                "toString" -> "WinCloseCarStateListener"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    else -> null
                }
            }
        }
        doorSignal.reset()
        state = client
        stateBinder = binder
        callbackProxy = proxy
        // Stamped before the call: the replay can arrive inside register.invoke itself.
        registeredAt = SystemClock.elapsedRealtime()
        try {
            register.invoke(client, proxy)
        } catch (e: Exception) {
            detachListener()
            throw e
        }
        report("WinClose CarState door listener ready")
    }

    fun isReady(): Boolean = vsm != null && state != null

    fun isParked(): Boolean? {
        val client = state ?: return null
        return try {
            val gear = client.javaClass.getMethod("getGearState").invoke(client) as? Int
            gear?.takeIf { it >= 0 }?.let { it == 1 }
        } catch (_: Exception) { null }
    }

    fun closeAllWindowsPulsed(keepRunning: () -> Boolean): Boolean {
        val client = vsm ?: return false
        val setter = try {
            client.javaClass.getMethod("setVehicleWindowStatus", INT, INT)
        } catch (_: Exception) { return false }
        return WindowClosePulse.run({
            !closed && keepRunning() && FirmwareInfo.getGeneration() == FirmwareInfo.Gen.SWI69
                && isParked() == true && VehicleWriteGate.allow("WinClose setVehicleWindowStatus")
        }, { area, command ->
            try {
                setter.invoke(client, area, command)
                true
            } catch (e: Exception) {
                report("WinClose command failed: area=$area command=$command ${e.javaClass.simpleName}")
                false
            }
        })
    }

    private fun detachListener() {
        val client = state
        val proxy = callbackProxy
        if (client != null && proxy != null) runCatching {
            client.javaClass.methods.firstOrNull {
                it.name in listOf("unregisterListener", "unregistListener")
                    && it.parameterCount == 1 && it.parameterTypes[0].isInstance(proxy)
            }?.invoke(client, proxy)
        }
        state = null
        stateBinder = null
        callbackProxy = null
        registeredAt = Long.MIN_VALUE
        doorSignal.reset()
    }

    override fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        handler.post {
            detachListener()
            vsm = null
            vsmBinder = null
            thread.quitSafely()
        }
    }

    private companion object {
        val INT = Int::class.javaPrimitiveType!!
        const val DESCRIPTOR = "com.saicmotor.carapi.carstate.ICarStateCallback"

        /** How long after registering a door callback still counts as the state replay. */
        const val REGISTRATION_REPLAY_MS = 2_000L
    }
}
