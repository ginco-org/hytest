package gg.ginco.hytest.runtime

import com.hypixel.hytale.server.core.event.events.BootEvent
import com.hypixel.hytale.server.core.plugin.JavaPlugin
import com.hypixel.hytale.server.core.plugin.JavaPluginInit
import gg.ginco.hytest.runtime.runner.TestRunner

class HytestPlugin(init: JavaPluginInit) : JavaPlugin(init) {

    companion object {
        @Volatile
        var EVENTS: com.hypixel.hytale.event.EventRegistry? = null
            private set
    }

    override fun setup() {
        EVENTS = eventRegistry
        eventRegistry.register(BootEvent::class.java) {
            if (System.getProperty("hytest.run")?.toBoolean() == true) {
                TestRunner.runAsync()
            }
        }
    }
}
