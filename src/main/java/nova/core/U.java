package nova.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public final class U {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private U() {}

    /** Entity position via getX/getY/getZ (does not depend on what getPos is called in this version). */
    public static Vec3d pos(Entity e) { return new Vec3d(e.getX(), e.getY(), e.getZ()); }

    /** Chunk coordinates without touching ChunkPos.x / .x() (it is a record in newer versions, a class with fields in older ones). */
    public static int cx(ChunkPos p) { return p.getStartX() >> 4; }
    public static int cz(ChunkPos p) { return p.getStartZ() >> 4; }

    public static boolean fwd() { return mc.options.forwardKey.isPressed(); }
    public static boolean back() { return mc.options.backKey.isPressed(); }
    public static boolean left() { return mc.options.leftKey.isPressed(); }
    public static boolean right() { return mc.options.rightKey.isPressed(); }
    public static boolean jump() { return mc.options.jumpKey.isPressed(); }
    public static boolean sneak() { return mc.options.sneakKey.isPressed(); }
    public static boolean moving() { return fwd() || back() || left() || right(); }

    public static Vec3d moveDir(double speed) {
        double f = (fwd() ? 1 : 0) - (back() ? 1 : 0);
        double s = (left() ? 1 : 0) - (right() ? 1 : 0);
        if (f == 0 && s == 0) return Vec3d.ZERO;
        double len = Math.sqrt(f * f + s * s);
        f /= len; s /= len;
        double r = Math.toRadians(mc.player.getYaw());
        double sin = Math.sin(r), cos = Math.cos(r);
        return new Vec3d((-sin * f + cos * s) * speed, 0, (cos * f + sin * s) * speed);
    }

    public static void chat(String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("\u00a75[Nova]\u00a7r " + s), false);
    }

    public static void text(DrawContext c, String s, int x, int y, int color) {
        HudLayout.track(x, y, mc.textRenderer.getWidth(s), 9);
        c.drawText(mc.textRenderer, s, x, y, color, true);
    }
    /** Text with a soft coloured halo. */
    public static void glowText(DrawContext c, String s, int x, int y, int color, float strength) {
        HudLayout.track(x, y, mc.textRenderer.getWidth(s), 9);
        int halo = Gfx.a((color & 0x00FFFFFF) | 0xFF000000, 0.22f * Math.min(1f, strength));
        c.drawText(mc.textRenderer, s, x - 1, y, halo, false);
        c.drawText(mc.textRenderer, s, x + 1, y, halo, false);
        c.drawText(mc.textRenderer, s, x, y - 1, halo, false);
        c.drawText(mc.textRenderer, s, x, y + 1, halo, false);
        c.drawText(mc.textRenderer, s, x, y, color, true);
    }

    public static int width(String s) { return mc.textRenderer.getWidth(s); }

    public static float[] rotTo(Vec3d t) {
        Vec3d e = mc.player.getEyePos();
        double dx = t.x - e.x, dy = t.y - e.y, dz = t.z - e.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{yaw, pitch};
    }

    public static boolean place(BlockPos pos) {
        if (!mc.world.getBlockState(pos).isReplaceable()) return false;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            if (mc.world.getBlockState(n).isReplaceable()) continue;
            Direction face = d.getOpposite();
            Vec3d hit = Vec3d.ofCenter(n).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(hit, face, n, false));
            mc.player.swingHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    public static boolean selectSlot(Predicate<ItemStack> p) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) if (p.test(inv.getStack(i))) { inv.setSelectedSlot(i); return true; }
        return false;
    }

    // ---------- smooth aiming helpers ----------
    public static double jitter(double amt) { return 1.0 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * amt; }
    public static double rand(double lo, double hi) { return lo + ThreadLocalRandom.current().nextDouble() * (hi - lo); }

    /** Smallest rotation step the mouse can produce with the current sensitivity. */
    public static double mouseStep() {
        double s = mc.options.getMouseSensitivity().getValue();
        double f = s * 0.6 + 0.2;
        return f * f * f * 8.0 * 0.15;
    }

    // Turn speed (deg/tick) and a slow drift are carried from call to call, so a turn speeds up, glides and settles
    // like a hand on a mouse instead of jumping to a fixed share of the remaining angle every tick.
    private static double aimVY, aimVP, driftY, driftP;
    private static int aimLastAge = Integer.MIN_VALUE;

    /** Moves the rotation towards the target with limited acceleration (capped speed), instead of snapping. */
    public static float[] smoothRot(float yaw, float pitch, float ty, float tp, double speedPct, double maxStep,
                                    boolean humanize, boolean mouseFix) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int age = mc.player == null ? 0 : mc.player.age;
        if (age - aimLastAge > 4 || age < aimLastAge) { aimVY = 0; aimVP = 0; }   // idle for a moment: start from rest
        aimLastAge = age;
        double dy = MathHelper.wrapDegrees(ty - yaw), dp = tp - pitch;
        double dist = Math.hypot(dy, dp);
        double f = Math.max(0.01, Math.min(1.0, speedPct / 100.0));
        double wy = dy * f, wpi = dp * f;
        double mag = Math.hypot(wy, wpi);
        if (mag > maxStep) { wy *= maxStep / mag; wpi *= maxStep / mag; }
        double sy, sp;
        if (humanize) {
            // slow wobble (low-passed noise, not white noise), fading out as the aim arrives
            driftY = driftY * 0.85 + rnd.nextGaussian() * 0.05;
            driftP = driftP * 0.85 + rnd.nextGaussian() * 0.035;
            double live = Math.min(1.0, dist / 8.0);
            wy += driftY * live; wpi += driftP * live;
            wpi *= 0.8;   // the view moves sideways more eagerly than up/down
            double maxAcc = Math.max(2.0, maxStep * 0.5);
            double ay = wy - aimVY, ap = wpi - aimVP, am = Math.hypot(ay, ap);
            if (am > maxAcc) { ay *= maxAcc / am; ap *= maxAcc / am; }
            aimVY += ay; aimVP += ap;
            sy = aimVY; sp = aimVP;
            // never fly past the target
            if (sy * dy > 0 && Math.abs(sy) > Math.abs(dy)) { sy = dy; aimVY = dy; }
            if (sp * dp > 0 && Math.abs(sp) > Math.abs(dp)) { sp = dp; aimVP = dp; }
        } else {
            sy = wy; sp = wpi; aimVY = sy; aimVP = sp;
        }
        if (mouseFix) {
            double step = mouseStep();
            sy = Math.round(sy / step) * step;
            sp = Math.round(sp / step) * step;
        }
        return new float[]{yaw + (float) sy, MathHelper.clamp(pitch + (float) sp, -90f, 90f)};
    }

    // ---------- glide: constant-feeling turning (used by the schematic builder) ----------
    // The turn speed is a state that only ever changes a little per tick (limited acceleration, low-passed), there is no random wobble and no
    // "share of the remaining angle" jump, so the head ramps up, cruises and settles instead of snapping. The vanilla camera interpolates
    // between the last and the new rotation, so small, even steps per tick look smooth at any frame rate.
    private static double gVY, gVP, gResY, gResP;
    private static int gLastAge = Integer.MIN_VALUE;

    /**
     * Smooth turn towards (ty, tp). gain = speedPct/100 (how hard it pulls towards the target), maxStep = top speed in degrees per tick.
     * Call it once per tick; a second call in the same tick returns the rotation unchanged. `humanize` is accepted for drop-in use and ignored on purpose.
     */
    public static float[] glideRot(float yaw, float pitch, float ty, float tp, double speedPct, double maxStep,
                                   boolean humanize, boolean mouseFix) {
        int age = mc.player == null ? 0 : mc.player.age;
        if (age == gLastAge) return new float[]{yaw, pitch};
        if (age - gLastAge > 3 || age < gLastAge) { gVY = 0; gVP = 0; gResY = 0; gResP = 0; }   // idle for a moment: start from rest
        gLastAge = age;

        double dy = MathHelper.wrapDegrees(ty - yaw), dp = tp - pitch;
        double dist = Math.hypot(dy, dp);
        if (dist < 0.02) { gVY = 0; gVP = 0; return new float[]{yaw, pitch}; }

        double vmax = Math.max(0.5, maxStep);                    // top speed, deg / tick
        double gain = Math.max(0.03, Math.min(1.0, speedPct / 100.0));
        double acc = Math.max(0.5, vmax * 0.2);                  // the speed may change by this much per tick
        // wanted speed: pulled by the distance, capped, and never more than we can still brake from
        // (the pull never drops below 0.4 so the last degrees settle in shrinking steps instead of crawling)
        double want = Math.min(vmax, Math.min(dist * Math.max(gain, 0.4), Math.sqrt(0.5 * acc * dist)));
        double wy = dy / dist * want, wp = dp / dist * want;

        // low-pass the speed towards the wanted one, then limit the change per tick
        double ay = (wy - gVY) * 0.7, ap = (wp - gVP) * 0.7, am = Math.hypot(ay, ap);
        if (am > acc) { ay *= acc / am; ap *= acc / am; }
        gVY += ay; gVP += ap;

        double sy = gVY, sp = gVP, sm = Math.hypot(sy, sp);
        if (sm > dist) { sy *= dist / sm; sp *= dist / sm; gVY = sy; gVP = sp; }   // arrive exactly, never fly past

        if (mouseFix) {   // whole mouse steps, but the part that gets rounded away is carried to the next tick (slow turns stay even)
            double step = mouseStep();
            double qy = sy + gResY, qp = sp + gResP;
            double ry = Math.round(qy / step) * step, rp = Math.round(qp / step) * step;
            gResY = qy - ry; gResP = qp - rp;
            sy = ry; sp = rp;
        }
        return new float[]{yaw + (float) sy, MathHelper.clamp(pitch + (float) sp, -90f, 90f)};
    }

    public static double angleTo(Vec3d t) {
        Vec3d look = mc.player.getRotationVec(1.0F);
        Vec3d d = t.subtract(mc.player.getEyePos()).normalize();
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, look.dotProduct(d)))));
    }

    private static double clampSafe(double v, double lo, double hi) {
        if (lo > hi) return (lo + hi) / 2;
        return Math.max(lo, Math.min(hi, v));
    }

    /** A point in the target's hitbox close to where you already look (little turning needed). */
    public static Vec3d aimPoint(Entity e) {
        Vec3d eye = mc.player.getEyePos();
        Box b = e.getBoundingBox();
        double m = 0.12, h = b.maxY - b.minY;
        return new Vec3d(clampSafe(eye.x, b.minX + m, b.maxX - m),
                         clampSafe(eye.y, b.minY + h * 0.25, b.minY + h * 0.85),
                         clampSafe(eye.z, b.minZ + m, b.maxZ - m));
    }

    public static boolean lookingAt(Entity e, double range) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(mc.player.getRotationVec(1.0F).multiply(range));
        return e.getBoundingBox().expand(0.05).raycast(eye, end).isPresent();
    }
}
