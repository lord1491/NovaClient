package nova.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import nova.modules.ClientModules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public abstract class Mod {
    protected static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final Logger LOG = LoggerFactory.getLogger("nova");

    public final String name, desc;
    public final Category category;
    public boolean enabled;
    public int key = -1;
    public final List<Setting<?>> settings = new ArrayList<>();

    protected Mod(String name, Category category, String desc) {
        this.name = name; this.category = category; this.desc = desc;
    }

    protected Setting.Bool bool(String n, boolean d) { var s = new Setting.Bool(n, d); settings.add(s); return s; }
    protected Setting.Num num(String n, double d, double min, double max, double step) {
        var s = new Setting.Num(n, d, min, max, step); settings.add(s); return s;
    }
    protected Setting.Mode mode(String n, String d, String... modes) {
        var s = new Setting.Mode(n, d, modes); settings.add(s); return s;
    }
    protected Setting.Action action(String n, Runnable r) { var s = new Setting.Action(n, r); settings.add(s); return s; }

    public void toggle() { setEnabled(!enabled); }

    public void setEnabled(boolean e) {
        if (e == enabled) return;
        enabled = e;
        try { if (e) onEnable(); else onDisable(); }
        catch (Throwable t) { LOG.error("Module {} failed in {}", name, e ? "onEnable" : "onDisable", t); }
        if (category != Category.CLIENT && !ModuleManager.quiet) ClientModules.Notify.push(name, enabled);
    }

    /** Disables the module, clears its keybind and restores every setting to its default. */
    public void reset() {
        setEnabled(false);
        key = -1;
        for (Setting<?> s : settings) s.reset();
    }

    public void onEnable() {}
    public void onDisable() {}
    public void onTick() {}
    public void onHud(DrawContext ctx) {}
}
