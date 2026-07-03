package gg.ginco.hytest.runtime;

import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import gg.ginco.hytest.runtime.runner.TestRunner;

/**
 * Runtime entrypoint. When the system property {@code hytest.run=true} is set,
 * the runtime discovers and runs all {@link gg.ginco.hytest.runtime.api.HytaleTest}
 * methods after server boot, reports results on raw stdout, and shuts the server
 * down with an exit code derived from the failure count.
 *
 * <p>Without {@code hytest.run=true} the plugin is inert, so it is safe to leave
 * deployed during normal development.
 */
public final class HytestPlugin extends JavaPlugin {

    public HytestPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        getEventRegistry().register(BootEvent.class, event -> {
            if (Boolean.getBoolean("hytest.run")) {
                TestRunner.runAsync();
            }
        });
    }
}
