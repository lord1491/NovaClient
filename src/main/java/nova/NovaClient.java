package nova;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import nova.core.Config;
import nova.core.ModuleManager;
import nova.gui.SplashScreen;
import nova.modules.ClientModules;
import nova.modules.ExtraMore;

public class NovaClient implements ClientModInitializer {
    private static boolean splashShown;

    @Override
    public void onInitializeClient() {
        ModuleManager.init();
        Config.load();
        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            ModuleManager.tick();
            try { ExtraMore.offlineTick(c); } catch (Throwable t) { t.printStackTrace(); }
            showSplash(c);
        });
        HudRenderCallback.EVENT.register((ctx, tc) -> ModuleManager.hud(ctx));
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> Config.save());
    }

    /** Shows the Nova splash once, the first time the title screen is up. */
    private static void showSplash(MinecraftClient c) {
        if (splashShown || c.getOverlay() != null || !(c.currentScreen instanceof TitleScreen)) return;
        splashShown = true;
        if (ClientModules.Gui.splashOn()) c.setScreen(new SplashScreen(c.currentScreen));
    }
}
