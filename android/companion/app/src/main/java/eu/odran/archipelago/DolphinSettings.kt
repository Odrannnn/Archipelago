package eu.odran.archipelago

import android.content.Context

data class DolphinSettings(val gdbPort: Int) {
    companion object {
        const val DEFAULT_GDB_PORT = DolphinGdbClient.DEFAULT_PORT
        private const val PREFS = "dolphin_bridge"
        private const val GDB_PORT = "gdb_port"

        fun load(context: Context): DolphinSettings {
            val port = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(GDB_PORT, DEFAULT_GDB_PORT)
                .takeIf { it in 1..65535 }
                ?: DEFAULT_GDB_PORT
            return DolphinSettings(port)
        }

        fun save(context: Context, gdbPort: Int) {
            require(gdbPort in 1..65535) { "Dolphin GDB port must be between 1 and 65535" }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(GDB_PORT, gdbPort)
                .apply()
        }
    }
}
