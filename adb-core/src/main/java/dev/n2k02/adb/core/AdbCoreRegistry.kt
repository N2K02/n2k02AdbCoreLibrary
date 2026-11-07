package dev.n2k02.adb.core

/** Install once in Application.onCreate; no global credentials are stored here. */
object AdbCoreRegistry {
    @Volatile var current: AdbCore? = null
        private set
    fun install(core: AdbCore) { current = core }
    fun uninstall(core: AdbCore) {
        if (current === core) current = null
    }
}
