package nova.modules;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import nova.core.*;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Render modules ported from the Anubis module list. They draw on the HUD layer through Proj, like Nova's ESP. */
public final class ExtraRender {
    private ExtraRender() {}

    private static final int C_TRAJ = 0xFF55D8FF, C_HIT = 0xFFFF7A1A;

    /** Walks a projectile forward. Returns the path; the last point is the impact when {@code hit[0]} is set. */
    static List<Vec3d> simulate(Vec3d start, Vec3d vel, double gravity, double drag, int steps, boolean[] hit, Entity self) {
        List<Vec3d> pts = new ArrayList<>();
        Vec3d pos = start;
        pts.add(pos);
        for (int i = 0; i < steps; i++) {
            Vec3d next = pos.add(vel);
            BlockHitResult r = mc().world.raycast(new RaycastContext(pos, next, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, self));
            if (r.getType() != HitResult.Type.MISS) { pts.add(r.getPos()); hit[0] = true; return pts; }
            pts.add(next);
            pos = next;
            vel = vel.multiply(drag).subtract(0, gravity, 0);
            if (pos.y < mc().world.getBottomY() - 16) break;
        }
        return pts;
    }

    private static net.minecraft.client.MinecraftClient mc() { return net.minecraft.client.MinecraftClient.getInstance(); }

    static void drawPath(DrawContext ctx, Proj pr, List<Vec3d> pts, boolean hit, boolean marker, int color) {
        for (int i = 1; i < pts.size(); i++) pr.line3(ctx, pts.get(i - 1), pts.get(i), color);
        if (hit && marker && !pts.isEmpty()) {
            Vec3d e = pts.get(pts.size() - 1);
            pr.wire(ctx, new Box(e.x - 0.25, e.y - 0.05, e.z - 0.25, e.x + 0.25, e.y + 0.45, e.z + 0.25), C_HIT);
        }
    }

    public static class Trajectories extends Mod {
        Setting.Bool bows = bool("Bows and crossbows", true), throwables = bool("Pearls, snowballs, potions", true),
                trident = bool("Tridents", true), marker = bool("Landing marker", true);
        Setting.Num steps = num("Max steps", 120, 20, 300, 10);

        public Trajectories() { super("Trajectories", Category.RENDER, "Predicted path of the bow, crossbow, trident or throwable you hold."); }

        @Override public void onHud(DrawContext ctx) {
            Proj pr = Proj.begin();
            if (pr == null) return;
            var p = mc.player;
            ItemStack st = p.getMainHandStack();
            double speed, grav;
            if (st.isOf(Items.BOW) && bows.get()) {
                if (!p.isUsingItem() || !p.getActiveItem().isOf(Items.BOW)) return;
                float f = p.getItemUseTime() / 20.0f;
                f = Math.min(1f, (f * f + f * 2f) / 3f);
                if (f < 0.1f) return;
                speed = f * 3.0; grav = 0.05;
            } else if (st.isOf(Items.CROSSBOW) && bows.get()) {
                speed = 3.15; grav = 0.05;
            } else if (st.isOf(Items.TRIDENT) && trident.get()) {
                if (!p.isUsingItem()) return;
                speed = 2.5; grav = 0.05;
            } else if (throwables.get() && (st.isOf(Items.ENDER_PEARL) || st.isOf(Items.SNOWBALL) || st.isOf(Items.EGG))) {
                speed = 1.5; grav = 0.03;
            } else if (throwables.get() && (st.isOf(Items.SPLASH_POTION) || st.isOf(Items.LINGERING_POTION))) {
                speed = 0.5; grav = 0.05;
            } else if (throwables.get() && st.isOf(Items.EXPERIENCE_BOTTLE)) {
                speed = 0.7; grav = 0.07;
            } else return;
            Vec3d dir = p.getRotationVec(1.0F);
            Vec3d start = p.getEyePos().subtract(0, 0.1, 0);
            boolean[] hit = new boolean[1];
            List<Vec3d> pts = simulate(start, dir.multiply(speed), grav, 0.99, steps.getInt(), hit, p);
            drawPath(ctx, pr, pts, hit[0], marker.get(), C_TRAJ);
        }
    }

    public static class PearlTrajectory extends Mod {
        Setting.Bool marker = bool("Landing marker", true);
        Setting.Num range = num("Range", 64, 8, 200, 8);

        public PearlTrajectory() { super("PearlTrajectory", Category.RENDER, "Shows where every ender pearl in the air is going to land."); }

        @Override public void onHud(DrawContext ctx) {
            Proj pr = Proj.begin();
            if (pr == null) return;
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof EnderPearlEntity) || !e.isAlive() || mc.player.distanceTo(e) > range.get()) continue;
                boolean[] hit = new boolean[1];
                List<Vec3d> pts = simulate(Proj.lerpPos(e), e.getVelocity(), 0.03, 0.99, 200, hit, e);
                drawPath(ctx, pr, pts, hit[0], marker.get(), 0xFFB06BFF);
            }
        }
    }

    public static class TargetESP extends Mod {
        Setting.Mode style = mode("Style", "Ring", "Ring", "Halo", "Box");
        Setting.Bool crosshair = bool("Also crosshair target", true);

        public TargetESP() { super("TargetESP", Category.RENDER, "Marks the KillAura target (and what you aim at)."); }

        @Override public void onHud(DrawContext ctx) {
            Proj pr = Proj.begin();
            if (pr == null) return;
            LivingEntity t = CombatModules.KillAura.TARGET;
            if ((t == null || !t.isAlive()) && crosshair.get()
                    && mc.crosshairTarget instanceof EntityHitResult h && h.getEntity() instanceof LivingEntity le) t = le;
            if (t == null || !t.isAlive() || t == mc.player) return;
            Vec3d c = Proj.lerpPos(t);
            double time = System.currentTimeMillis() / 1000.0;
            double hgt = t.getHeight(), r = t.getWidth() * 0.8 + 0.15;
            switch (style.value) {
                case "Box" -> pr.wire(ctx, Proj.lerpBox(t), C_HIT);
                case "Halo" -> ring(ctx, pr, c.add(0, hgt + 0.25, 0), 0.3, time * 3, C_HIT);
                default -> {
                    double y = (Math.sin(time * 2.5) * 0.5 + 0.5) * hgt;
                    ring(ctx, pr, c.add(0, y, 0), r, time * 2, C_HIT);
                    ring(ctx, pr, c.add(0, y, 0), r, time * 2 + Math.PI, Gfx.a(C_HIT, 0.5f));
                }
            }
        }

        private static void ring(DrawContext ctx, Proj pr, Vec3d center, double radius, double phase, int color) {
            int n = 28;
            Vec3d prev = null;
            for (int i = 0; i <= n; i++) {
                double a = phase + i * (Math.PI * 2 / n);
                Vec3d cur = center.add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
                if (prev != null) pr.line3(ctx, prev, cur, color);
                prev = cur;
            }
        }
    }

    public static class BlockOverlay extends Mod {
        Setting.Bool both = bool("Show distance", false);

        public BlockOverlay() { super("BlockOverlay", Category.RENDER, "Outlines the block you are looking at."); }

        @Override public void onHud(DrawContext ctx) {
            Proj pr = Proj.begin();
            if (pr == null) return;
            if (!(mc.crosshairTarget instanceof BlockHitResult h) || h.getType() != HitResult.Type.BLOCK) return;
            int col = ClientModules.Theme.accent();
            pr.wire(ctx, new Box(h.getBlockPos()).expand(0.002), col);
            if (both.get()) {
                String s = String.format("%.1fm", h.getPos().distanceTo(mc.player.getEyePos()));
                U.text(ctx, s, pr.sw / 2 + 8, pr.sh / 2 + 8, 0xFFFFFFFF);
            }
        }
    }

    public static class JumpCircles extends Mod {
        Setting.Num life = num("Lifetime (ms)", 700, 200, 2000, 100), size = num("Size", 1.4, 0.5, 4, 0.1);
        Setting.Bool others = bool("Other players", true);
        record Ring(Vec3d pos, long t) {}
        final List<Ring> rings = new ArrayList<>();
        final Map<Integer, Boolean> ground = new HashMap<>();

        public JumpCircles() { super("JumpCircles", Category.RENDER, "A fading ring on the ground where a player jumped."); }

        @Override public void onEnable() { rings.clear(); ground.clear(); }
        @Override public void onDisable() { rings.clear(); ground.clear(); }

        @Override public void onTick() {
            long now = System.currentTimeMillis();
            Set<Integer> ids = new HashSet<>();
            for (PlayerEntity pl : mc.world.getPlayers()) {
                if (pl != mc.player && !others.get()) continue;
                ids.add(pl.getId());
                boolean g = pl.isOnGround();
                Boolean was = ground.put(pl.getId(), g);
                if (was != null && was && !g && pl.getVelocity().y > 0.1) rings.add(new Ring(U.pos(pl), now));
            }
            ground.keySet().retainAll(ids);
            rings.removeIf(r -> now - r.t() > life.get() + 100);
        }

        @Override public void onHud(DrawContext ctx) {
            Proj pr = Proj.begin();
            if (pr == null) return;
            long now = System.currentTimeMillis();
            int base = ClientModules.Theme.accent();
            for (Ring r : rings) {
                double age = (now - r.t()) / life.get();
                if (age < 0 || age > 1) continue;
                double rad = 0.3 + age * size.get();
                int col = Gfx.a(base, (float) (1.0 - age));
                Vec3d prev = null;
                int n = 24;
                for (int i = 0; i <= n; i++) {
                    double a = i * (Math.PI * 2 / n);
                    Vec3d cur = r.pos().add(Math.cos(a) * rad, 0.03, Math.sin(a) * rad);
                    if (prev != null) pr.line3(ctx, prev, cur, col);
                    prev = cur;
                }
            }
        }
    }

    public static class HitParticles extends Mod {
        Setting.Mode type = mode("Type", "Crit", "Crit", "Magic Crit", "Hearts", "Flames");
        Setting.Num amount = num("Amount", 10, 1, 40, 1), spread = num("Spread", 0.6, 0.1, 2, 0.1);
        Setting.Bool mine = bool("Only my hits", true);
        final Map<Integer, Integer> last = new HashMap<>();

        public HitParticles() { super("HitParticles", Category.RENDER, "Extra particles whenever something gets hit."); }

        @Override public void onDisable() { last.clear(); }

        @Override public void onTick() {
            var p = mc.player;
            Set<Integer> ids = new HashSet<>();
            ParticleEffect fx = switch (type.value) {
                case "Magic Crit" -> ParticleTypes.ENCHANTED_HIT;
                case "Hearts" -> ParticleTypes.HEART;
                case "Flames" -> ParticleTypes.FLAME;
                default -> ParticleTypes.CRIT;
            };
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            for (Entity e : mc.world.getEntities()) {
                if (!(e instanceof LivingEntity le) || le == p) continue;
                ids.add(le.getId());
                int prev = last.getOrDefault(le.getId(), 0);
                last.put(le.getId(), le.hurtTime);
                boolean fresh = le.hurtTime > prev && le.hurtTime >= le.maxHurtTime - 1;
                if (!fresh || p.distanceTo(le) > 16) continue;
                if (mine.get() && p.getAttackCooldownProgress(0f) > 0.6f) continue;
                for (int i = 0; i < amount.getInt(); i++) {
                    double s = spread.get();
                    mc.particleManager.addParticle(fx,
                            le.getX() + (rnd.nextDouble() - 0.5) * le.getWidth(),
                            le.getY() + rnd.nextDouble() * le.getHeight(),
                            le.getZ() + (rnd.nextDouble() - 0.5) * le.getWidth(),
                            (rnd.nextDouble() - 0.5) * s, rnd.nextDouble() * s * 0.6, (rnd.nextDouble() - 0.5) * s);
                }
            }
            last.keySet().retainAll(ids);
        }
    }

    public static class LowHealthWarning extends Mod {
        Setting.Num below = num("Below HP", 8, 1, 19, 1);
        Setting.Bool pulse = bool("Pulse", true);

        public LowHealthWarning() { super("LowHealthWarning", Category.RENDER, "Red screen edges when your health is low."); }

        @Override public void onHud(DrawContext ctx) {
            float hp = mc.player.getHealth();
            if (hp > below.get() || !mc.player.isAlive()) return;
            double k = 1.0 - hp / below.get();
            double beat = pulse.get() ? 0.65 + 0.35 * Math.sin(System.currentTimeMillis() / 160.0) : 1.0;
            int w = mc.getWindow().getScaledWidth(), h = mc.getWindow().getScaledHeight();
            int bands = 14, th = 5;
            for (int i = 0; i < bands; i++) {
                double a = (0.25 + 0.5 * k) * beat * (1.0 - i / (double) bands);
                int col = (Math.min(255, (int) (a * 255)) << 24) | 0x00FF2020;
                int t = i * th;
                ctx.fill(t, t, w - t, t + th, col);
                ctx.fill(t, h - t - th, w - t, h - t, col);
                ctx.fill(t, t + th, t + th, h - t - th, col);
                ctx.fill(w - t - th, t + th, w - t, h - t - th, col);
            }
        }
    }

    public static class Zoom extends Mod {
        Setting.Num zoomFov = num("Zoom FOV", 30, 30, 90, 1);
        Setting.Bool slow = bool("Lower mouse sensitivity", true);
        int oldFov = 70;
        double oldSens = 0.5, cur = 70;

        public Zoom() { super("Zoom", Category.RENDER, "Zooms in while on (bind a key, middle click in the menu)."); }

        @Override public void onEnable() {
            oldFov = mc.options.getFov().getValue();
            oldSens = mc.options.getMouseSensitivity().getValue();
            cur = oldFov;
            if (slow.get()) mc.options.getMouseSensitivity().setValue(oldSens * 0.35);
        }

        @Override public void onTick() {
            cur += (zoomFov.get() - cur) * 0.35;
            mc.options.getFov().setValue((int) Math.round(Math.max(30, cur)));
        }

        @Override public void onDisable() {
            mc.options.getFov().setValue(oldFov);
            mc.options.getMouseSensitivity().setValue(oldSens);
        }
    }
}
