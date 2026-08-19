package eu.odran.archipelago

import android.content.Context

/** Installs the service-owned GDB connection behind the upstream DME module name. */
object DolphinMemoryEngineBridge {
    fun attach(context: Context, client: DolphinGdbClient) = synchronized(OfflineGenerator.runtimeLock) {
        OfflineGenerator.python(context)
            .getModule("dolphin_memory_engine")
            .callAttr("configure_backend", client)
        Unit
    }
}
