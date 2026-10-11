package com.andyyhope.featureflag.android.signals

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.andyyhope.featureflag.signals.FlagSignal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Hands signals a companion sends to the host app's handlers, on the main thread.
 *
 * A host registers its handlers on [host] in `Application.onCreate`, and the bridge provider
 * calls [deliver] for each signal that arrives. A signal is delivered exactly once, to every
 * observer whose list names it, and only to observers registered when it arrives: nothing
 * is queued for a host that starts listening later.
 *
 * @param mainThread runs the handlers; null runs them inline, on the delivering thread.
 */
public class FlagSignalChannel internal constructor(private val mainThread: Executor?) {

    private val observers = CopyOnWriteArrayList<Observer<*>>()

    /**
     * Runs [handler] on the main thread for every delivered signal named in [signals]. Hold the
     * result; `close()` stops delivery.
     */
    public fun <S : FlagSignal> observe(signals: List<S>, handler: (S) -> Unit): AutoCloseable {
        val observer = Observer(signals.associateBy { it.name }, handler)
        observers.add(observer)
        return AutoCloseable {
            observer.closed = true
            observers.remove(observer)
        }
    }

    /**
     * Delivers the signal named [name] to every observer whose list contains it, waiting up to
     * [timeout] for the handlers.
     *
     * Returns true when at least one handler ran to completion. Called by FlagBridgeProvider;
     * safe from any thread. When called on the main thread it runs the handlers inline (no
     * deadlock). A delivery that times out is withdrawn, so its handlers do not run later.
     */
    public fun deliver(name: String, timeout: Duration = DELIVERY_TIMEOUT): Boolean {
        val matching = observers.filter { it.handles(name) }
        if (matching.isEmpty()) return false
        val withdrawn = AtomicBoolean(false)
        // Every matching handler runs, even after one has already succeeded, unless the delivery
        // timed out: then only a handler already running finishes.
        val run = { matching.count { !withdrawn.get() && it.run(name) } > 0 }
        if (mainThread == null || Looper.getMainLooper().isCurrentThread) return run()
        val task = FutureTask(run)
        mainThread.execute(task)
        return try {
            task.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (ignored: TimeoutException) {
            withdrawn.set(true)
            task.cancel(false)
            false
        } catch (e: ExecutionException) {
            Log.w(TAG, "The handler for the signal '$name' failed, so it was not handled.", e.cause)
            false
        }
    }

    private class Observer<S : FlagSignal>(private val signals: Map<String, S>, private val handler: (S) -> Unit) {
        @Volatile
        var closed = false

        fun handles(name: String): Boolean = name in signals

        /** Runs the handler for [name]. False when it is closed, or the handler threw. */
        @Suppress("TooGenericExceptionCaught") // A handler is app code; whatever it throws must not reach the provider.
        fun run(name: String): Boolean {
            val signal = signals[name] ?: return false
            if (closed) return false
            return try {
                handler(signal)
                true
            } catch (e: Exception) {
                Log.w(TAG, "The handler for the signal '$name' threw, so it was not handled.", e)
                false
            }
        }
    }

    public companion object {
        private const val TAG = "FeatureFlag"

        /** How long [deliver] waits for the handlers by default: 4 seconds, under the client's 5-second default. */
        public val DELIVERY_TIMEOUT: Duration = 4.seconds

        /** The host process's channel. Register observers in `Application.onCreate`. */
        public val host: FlagSignalChannel =
            FlagSignalChannel { command -> Handler(Looper.getMainLooper()).post(command) }
    }
}
