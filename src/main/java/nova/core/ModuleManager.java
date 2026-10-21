package nova.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.DrawContext;
import nova.gui.NovaScreen;
import nova.modules.*;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public final class ModuleManager {
    public static final List<Mod> MODULES = new ArrayList<>();
    public static boolean quiet;
    private static final Map<Mod, Boolean> keyDown = new HashMap<>();
    private static final Logger LOG = LoggerFactory.getLogger("nova");
    private static boolean guiKeyDown;
    private static final Set<Mod> hudFailed = new HashSet<>();
    private ModuleManager() {}

    private static void add(Mod... ms) { MODULES.addAll(Arrays.asList(ms)); }

    public static void init() {
        add(new CombatModules.KillAura(), new CombatModules.AimAssist(), new CombatModules.TriggerBot(),
            new CombatModules.AutoClicker(), new CombatModules.AutoTotem(), new CombatModules.AutoArmor(),
            new CombatModules.AutoWeapon(), new CombatModules.Surround(),
            new ExtraCombat.FriendGuard(), new ExtraCombat.Mace(), new ExtraCombat.WTap(), new ExtraCombat.JumpReset(),
            new ExtraCombat.ShieldBreaker(), new ExtraCombat.CrystalAura(), new MaceBot(),
            new DefenseModules.AntiBot(), new DefenseModules.AutoShield(),
            new DefenseModules.AutoGapple(), new DefenseModules.AutoPotion());
        add(new MovementModules.Flight(), new MovementModules.Speed(), new MovementModules.Sprint(),
            new MovementModules.AutoJump(),
            new ExtraMovement.ElytraFly(), new ExtraMovement.BoatFly(),
            new ExtraMovement.EntitySpeed(), new ExtraMovement.ClickTP(), new ExtraMovement.TridentBoost(),
            new ExtraMovement.AirStuck(), new ExtraFun.GodTrident());
        add(new PlayerModules.NoFall(), new PlayerModules.Scaffold(), new PlayerModules.AutoEat(),
            new PlayerModules.AutoTool(), new PlayerModules.ChestStealer(), new PlayerModules.AutoReplenish());
        add(new RenderModules.ESP(), new RenderModules.Tracers(), new RenderModules.StorageESP(),
            new RenderModules.OreESP(), new RenderModules.HoleESP(), new RenderModules.GlowESP(),
            new RenderModules.Fullbright(), new RenderModules.NoBob(), new RenderModules.FovChanger(),
            new RenderModules.NameTags(), new RenderModules.DropESP(),
            new ExtraRender.Trajectories(), new ExtraRender.PearlTrajectory(), new ExtraRender.TargetESP(),
            new ExtraRender.BlockOverlay(), new ExtraRender.JumpCircles(), new ExtraRender.HitParticles(),
            new ExtraRender.LowHealthWarning(), new ExtraRender.Zoom(),
            new ExtraMore.BlockESP(), new ExtraMore.SpawnerNametags(), new ExtraMore.Freecam(), new ExtraMore.Freelook(),
            new ExtraMore.SlowSwing());
        add(new BuildModules.Schematic(), new WorldModules.Nuker(), new WorldModules.OreScan(),
            new WorldModules.StashFinder(), new WorldModules.BaseFinder(), new WorldModules.SpawnerFinder(),
            new ExtraMore.SpawnerProtect(), new ExtraMore.SpawnerNotifier());
        add(new MiscModules.AutoRespawn(), new MiscModules.AntiAFK(),
            new ExtraMisc.PlayerDetection(), new ExtraMisc.CoordSnapper(),
            new ExtraMore.AutoReconnect(), new ExtraMore.AutoRelog(), new ExtraMore.StaffList(),
            new StaffModules.AdminList(), new StaffModules.AntiVanish(), new ExtraFun.RainNoti(), new ExtraFun.FakeBanScreen());
        add(new HudModules.Watermark(), new HudModules.ModuleList(), new HudModules.Coordinates(),
            new HudModules.ArmorHud(), new HudModules.PlayerRadar(), new HudModules.PotionHud(),
            new HudModules.SpeedHud(), new HudModules.PingHud(), new ExtraMore.RegionMap(),
            new StaffModules.AdminHud(), new ExtraFun.SpotifyHud(), new HudModules.Minimap());
        add(new ClientModules.Theme(), new ClientModules.Layout(), new ClientModules.Background(), new ClientModules.Gui(), new ClientModules.Notify());
        for (Mod m : MODULES) if (m.category == Category.CLIENT) m.enabled = true;
    }

    public static void resetAll() {
        quiet = true;
        for (Mod m : MODULES) { m.reset(); if (m.category == Category.CLIENT) m.enabled = true; }
        quiet = false;
    }

    public static List<Mod> in(Category c) {
        List<Mod> out = new ArrayList<>();
        for (Mod m : MODULES) if (m.category == c) out.add(m);
        return out;
    }

    public static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        Proj.tick();
        boolean inWorld = mc.player != null && mc.world != null;

        // Right Shift opens the menu in a world (no screen open) and on the title screen, so you can also change the look before joining
        long w = mc.getWindow().getHandle();
        boolean g = GLFW.glfwGetKey(w, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
        boolean opened = false;
        boolean canOpen = inWorld ? mc.currentScreen == null : mc.currentScreen instanceof TitleScreen;
        if (canOpen && g && !guiKeyDown) { mc.setScreen(new NovaScreen()); opened = true; }
        guiKeyDown = g;
        if (!inWorld) return;
        if (mc.currentScreen == null && !opened) {
            for (Mod m : MODULES) {
                if (m.key < 0) continue;
                boolean d = GLFW.glfwGetKey(w, m.key) == GLFW.GLFW_PRESS;
                if (d && !keyDown.getOrDefault(m, false)) m.toggle();
                keyDown.put(m, d);
            }
        }

        for (Mod m : MODULES) {
            if (!m.enabled) continue;
            try { m.onTick(); }
            catch (Throwable t) { LOG.error("Mod {} crashed and was disabled", m.name, t); m.setEnabled(false); }
        }
    }

    public static void hud(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        for (Mod m : MODULES) {
            if (!m.enabled) continue;
            try { HudLayout.begin(m, ctx); m.onHud(ctx); }
            catch (Throwable t) { if (hudFailed.add(m)) LOG.error("HUD of {} threw (reported once)", m.name, t); }
            finally { HudLayout.end(ctx); }
        }
    }
}
