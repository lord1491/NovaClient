package nova.core;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/** Shared state for Freecam / Freelook. The camera mixin reads it, the mouse mixin writes the rotation. */
public final class CamState {
    private CamState() {}

    public static boolean freecam, freelook;
    public static double fx, fy, fz;
    public static float yaw, pitch;
    public static double speed = 8;   // blocks per second
    private static long lastNs;

    public static boolean active() { return freecam || freelook; }

    public static void begin() { lastNs = 0; }

    /** Mouse movement while the camera is detached (same scale as vanilla). */
    public static void look(double dx, double dy) {
        yaw += (float) (dx * 0.15);
        pitch = MathHelper.clamp(pitch + (float) (dy * 0.15), -90f, 90f);
    }

    private static double key(long w, int k) { return GLFW.glfwGetKey(w, k) == GLFW.GLFW_PRESS ? 1 : 0; }

    /** Called once per rendered frame from the camera mixin: moves the freecam smoothly. */
    public static void step() {
        long now = System.nanoTime();
        double dt = lastNs == 0 ? 0 : Math.min(0.1, (now - lastNs) / 1e9);
        lastNs = now;
        if (!freecam) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.currentScreen != null) return;
        long w = mc.getWindow().getHandle();
        double f = key(w, GLFW.GLFW_KEY_W) - key(w, GLFW.GLFW_KEY_S);
        double s = key(w, GLFW.GLFW_KEY_D) - key(w, GLFW.GLFW_KEY_A);
        double u = key(w, GLFW.GLFW_KEY_SPACE) - key(w, GLFW.GLFW_KEY_LEFT_SHIFT);
        double mul = key(w, GLFW.GLFW_KEY_LEFT_CONTROL) > 0 ? 2.5 : 1.0;
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        Vec3d look = new Vec3d(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
        Vec3d right = new Vec3d(-Math.cos(yr), 0, -Math.sin(yr));
        Vec3d v = look.multiply(f).add(right.multiply(s)).add(0, u, 0);
        if (v.lengthSquared() < 1e-6) return;
        v = v.normalize().multiply(speed * mul * dt);
        fx += v.x; fy += v.y; fz += v.z;
    }
}
