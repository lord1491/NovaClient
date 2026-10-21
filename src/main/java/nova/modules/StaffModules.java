package nova.modules;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.PlayerSkinDrawer;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.scoreboard.Team;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Vec3d;
import nova.core.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Staff tools: AdminList (names other modules ignore), AntiVanish (spots staff that vanished) and AdminHud (live panel).
 * Written for Nova from the feature descriptions; nothing is copied from other clients.
 */
public final class StaffModules {
    private StaffModules() {}

    /** A staff member found in the tab list. */
    record Row(String name, SkinTextures skin) {}

    // ------------------------------------------------------------------ helpers

    /** Name of a tab entry. GameProfile is a record in new versions (name()) and a class in old ones (getName()), so ask by reflection. */
    static String nameOf(PlayerListEntry e) {
        Object gp = e.getProfile();
        for (String m : new String[]{"name", "getName"}) {
            try {
                Object r = gp.getClass().getMethod(m).invoke(gp);
                if (r instanceof String s && !s.isEmpty()) return s;
            } catch (Throwable ignored) {}
        }
        Text dn = e.getDisplayName();
        return dn == null ? "?" : dn.getString();
    }

    /** What the tab list shows for this player: custom display name, else name with team prefix / suffix (where most servers put ranks). */
    static String tabText(PlayerListEntry e, String name) {
        try {
            Text dn = e.getDisplayName();
            if (dn != null) return dn.getString();
            return Team.decorateName(e.getScoreboardTeam(), Text.literal(name)).getString();
        } catch (Throwable t) {
            return name;
        }
    }

    static SkinTextures skinOf(PlayerListEntry e) {
        try { return e.getSkinTextures(); } catch (Throwable t) { return null; }
    }

    static boolean rankMatch(PlayerListEntry e, String name) { return ExtraMore.STAFF.matcher(tabText(e, name)).find(); }

    /** Everyone in the tab list who is on the admin list or has a staff rank word. */
    static List<Row> staffInTab() {
        List<Row> out = new ArrayList<>();
        MinecraftClient mc = MinecraftClient.getInstance();
        var h = mc.getNetworkHandler();
        if (h == null || mc.player == null) return out;
        String me = mc.player.getName().getString();
        for (PlayerListEntry e : h.getPlayerList()) {
            String n = nameOf(e);
            if (n.equalsIgnoreCase(me)) continue;
            if (AdminList.contains(n) || rankMatch(e, n)) out.add(new Row(n, skinOf(e)));
        }
        return out;
    }

    /** Draws the 8x8 face (with hat layer) of a skin; falls back to a coloured square with the first letter. */
    static void face(DrawContext ctx, SkinTextures skin, String name, int x, int y, int size) {
        if (skin != null) {
            try { PlayerSkinDrawer.draw(ctx, skin, x, y, size); return; } catch (Throwable ignored) {}
        }
        Gfx.rrect(ctx, x, y, size, size, 2, 0xFF3A3A44);
        String c = name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        U.text(ctx, c, x + (size - U.width(c)) / 2, y + (size - 8) / 2, 0xFFFFFFFF);
    }

    // ------------------------------------------------------------------ AdminList

    /** Names of admins / staff. Other modules (PlayerDetection, AdminHud, AntiVanish) use it. Saved in config/nova/admins.txt. */
    public static class AdminList extends Mod {
        static AdminList INSTANCE;
        static final Set<String> NAMES = new LinkedHashSet<>();   // lower case
        Setting.Bool ranks = bool("Also count tab list ranks", true);
        Setting.Action addLook = action("Add player I look at", this::addLooked);
        Setting.Action addNear = action("Add nearest player", this::addNearest);
        Setting.Action addTab = action("Add staff from tab list", this::addFromTab);
        Setting.Action remLook = action("Remove player I look at", this::removeLooked);
        Setting.Action show = action("Show list in chat", this::showList);
        Setting.Action clear = action("Clear list", () -> { NAMES.clear(); save(); U.chat("Admin list cleared."); });

        public AdminList() {
            super("AdminList", Category.MISC, "Names of admins that other modules ignore (PlayerDetection) and AdminHud / AntiVanish watch.");
            INSTANCE = this;
            load();
        }

        static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("nova").resolve("admins.txt"); }

        static void load() {
            try {
                if (!Files.exists(file())) return;
                NAMES.clear();
                for (String l : Files.readAllLines(file())) { l = l.trim(); if (!l.isEmpty()) NAMES.add(l.toLowerCase(Locale.ROOT)); }
            } catch (Exception e) { e.printStackTrace(); }
        }

        static void save() {
            try {
                Files.createDirectories(file().getParent());
                Files.write(file(), NAMES);
            } catch (Exception e) { e.printStackTrace(); }
        }

        /** On the list (works even while the module is off; used for staff detection). */
        public static boolean contains(String name) { return name != null && NAMES.contains(name.toLowerCase(Locale.ROOT)); }

        /** True when the module is on and this player is an admin (list or tab rank). Used by modules that should ignore admins. */
        public static boolean isAdmin(Entity e) {
            AdminList a = INSTANCE;
            if (a == null || !a.enabled || !(e instanceof PlayerEntity p)) return false;
            String n = p.getName().getString();
            if (contains(n)) return true;
            if (!a.ranks.get()) return false;
            var h = MinecraftClient.getInstance().getNetworkHandler();
            if (h == null) return false;
            PlayerListEntry le = h.getPlayerListEntry(p.getUuid());
            return le != null && rankMatch(le, n);
        }

        void add(String n) {
            if (n == null || n.isBlank()) return;
            if (NAMES.add(n.toLowerCase(Locale.ROOT))) { save(); U.chat(n + " added to the admin list."); }
            else U.chat(n + " is already on the list.");
        }

        void addLooked() {
            if (mc.player == null) return;
            if (mc.crosshairTarget instanceof EntityHitResult h && h.getEntity() instanceof PlayerEntity p) add(p.getName().getString());
            else U.chat("Look at a player first.");
        }

        void addNearest() {
            if (mc.player == null || mc.world == null) return;
            PlayerEntity best = null;
            for (PlayerEntity p : mc.world.getPlayers())
                if (p != mc.player && (best == null || mc.player.distanceTo(p) < mc.player.distanceTo(best))) best = p;
            if (best == null) U.chat("No other player nearby."); else add(best.getName().getString());
        }

        void addFromTab() {
            if (mc.player == null) return;
            var h = mc.getNetworkHandler();
            if (h == null) return;
            int n = 0;
            String me = mc.player.getName().getString();
            for (PlayerListEntry e : h.getPlayerList()) {
                String name = nameOf(e);
                if (name.equalsIgnoreCase(me) || !rankMatch(e, name)) continue;
                if (NAMES.add(name.toLowerCase(Locale.ROOT))) n++;
            }
            if (n > 0) save();
            U.chat(n == 0 ? "No new staff found in the tab list." : n + " staff added from the tab list.");
        }

        void removeLooked() {
            if (mc.player == null) return;
            if (mc.crosshairTarget instanceof EntityHitResult h && h.getEntity() instanceof PlayerEntity p) {
                String n = p.getName().getString();
                if (NAMES.remove(n.toLowerCase(Locale.ROOT))) { save(); U.chat(n + " removed from the admin list."); }
                else U.chat(n + " is not on the list.");
            } else U.chat("Look at a player first.");
        }

        void showList() { U.chat(NAMES.isEmpty() ? "Admin list is empty." : "Admins (" + NAMES.size() + "): " + String.join(", ", NAMES)); }
    }

    // ------------------------------------------------------------------ AntiVanish

    /**
     * Spots staff that went into vanish. Two signals:
     * 1. a player disappears from the tab list but no leave message shows up in chat;
     * 2. a player is standing in the world but has no tab list entry.
     * It cannot see through vanish plugins that also send a fake leave message and keep the tab entry.
     */
    public static class AntiVanish extends Mod {
        /** Names flagged as vanished, with the time they were flagged (read by AdminHud). */
        public static final Map<String, Long> VANISHED = new LinkedHashMap<>();

        record Chat(String text, long at) {}
        static final List<Chat> LINES = new ArrayList<>();
        static final Pattern LEAVE = Pattern.compile("\\b(left|quit|disconnected|logged out|leaves)\\b", Pattern.CASE_INSENSITIVE);
        static boolean hooked;
        static int leaveSeen;

        record Seen(String name, boolean staff) {}
        record Pending(Seen who, long at) {}

        Setting.Bool onlyStaff = bool("Only staff", true), chat = bool("Chat alert", true), sound = bool("Sound", true),
                hidden = bool("Check players missing from tab", true);
        Setting.Num forget = num("Forget after (min)", 30, 1, 120, 1);

        final Map<String, Seen> tab = new HashMap<>();      // lower name -> info, current tab list
        final Map<String, Seen> known = new HashMap<>();    // every name seen, keeps the staff flag after they leave
        final List<Pending> pending = new ArrayList<>();
        final Map<UUID, Integer> hiddenAge = new HashMap<>();
        final Set<UUID> hiddenFlagged = new HashSet<>();
        Object worldRef;
        boolean first = true;
        int t;

        public AntiVanish() {
            super("AntiVanish", Category.MISC, "Tells you when staff vanish: gone from the tab list without a leave message, or in the world but not in the tab.");
            if (!hooked) {
                hooked = true;
                ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) line(msg.getString()); });
            }
        }

        static void line(String s) {
            LINES.add(new Chat(s.toLowerCase(Locale.ROOT), System.currentTimeMillis()));
            while (LINES.size() > 60) LINES.remove(0);
            if (LEAVE.matcher(s).find()) leaveSeen++;
        }

        static boolean leaveMessageFor(String name, long from, long to) {
            String n = name.toLowerCase(Locale.ROOT);
            for (Chat c : LINES) if (c.at() >= from && c.at() <= to && c.text().contains(n) && LEAVE.matcher(c.text()).find()) return true;
            return false;
        }

        void clearState() {
            tab.clear(); known.clear(); pending.clear(); hiddenAge.clear(); hiddenFlagged.clear();
            VANISHED.clear(); first = true; leaveSeen = 0; t = 0;
        }

        @Override public void onEnable() { clearState(); worldRef = mc.world; }

        void flag(String name, String how) {
            VANISHED.put(name, System.currentTimeMillis());
            if (chat.get()) U.chat(name + " " + how);
            if (sound.get()) mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_BELL, 1.0f));
        }

        @Override public void onTick() {
            if (worldRef != mc.world) { clearState(); worldRef = mc.world; }
            var h = mc.getNetworkHandler();
            if (h == null || t++ % 4 != 0) return;
            long now = System.currentTimeMillis();
            VANISHED.values().removeIf(ts -> now - ts > (long) (forget.get() * 60_000));

            // snapshot of the tab list
            Map<String, Seen> cur = new HashMap<>();
            String me = mc.player.getName().getString();
            for (PlayerListEntry e : h.getPlayerList()) {
                String n = nameOf(e);
                if (n.equalsIgnoreCase(me)) continue;
                Seen s = new Seen(n, AdminList.contains(n) || rankMatch(e, n));
                cur.put(n.toLowerCase(Locale.ROOT), s);
                known.put(n.toLowerCase(Locale.ROOT), s);
            }
            if (!first) {
                for (var en : tab.entrySet()) if (!cur.containsKey(en.getKey())) pending.add(new Pending(en.getValue(), now));
                for (var en : cur.entrySet()) {
                    if (tab.containsKey(en.getKey())) continue;
                    String n = en.getValue().name();
                    boolean was = VANISHED.keySet().removeIf(k -> k.equalsIgnoreCase(n));
                    if (was && chat.get()) U.chat(n + " is visible again.");
                }
            }
            tab.clear(); tab.putAll(cur); first = false;

            // a player that left the tab list: wait a moment for the leave message before calling it a vanish
            for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
                Pending p = it.next();
                if (now - p.at() < 2500) continue;
                it.remove();
                if (tab.containsKey(p.who().name().toLowerCase(Locale.ROOT))) continue;
                if (leaveSeen == 0) continue;   // this server never prints leave messages, so a missing one means nothing
                if (onlyStaff.get() && !p.who().staff()) continue;
                if (leaveMessageFor(p.who().name(), p.at() - 3000, p.at() + 2500)) continue;
                flag(p.who().name(), "vanished (left the tab list without a leave message).");
            }

            // players you can see that the tab list does not know
            if (!hidden.get()) return;
            Set<UUID> inWorld = new HashSet<>();
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player || DefenseModules.AntiBot.isBot(p)) continue;
                UUID id = p.getUuid();
                inWorld.add(id);
                if (h.getPlayerListEntry(id) != null) { hiddenAge.remove(id); hiddenFlagged.remove(id); continue; }
                int age = hiddenAge.merge(id, 4, Integer::sum);
                if (age < 60 || !hiddenFlagged.add(id)) continue;
                String n = p.getName().getString();
                Seen s = known.get(n.toLowerCase(Locale.ROOT));
                boolean staff = AdminList.contains(n) || (s != null && s.staff());
                if (onlyStaff.get() && s != null && !staff) continue;
                flag(n, "is standing here but is not in the tab list (vanished?).");
            }
            hiddenAge.keySet().retainAll(inWorld);
        }
    }

    // ------------------------------------------------------------------ AdminHud

    /** Panel with every staff member in the tab list (skin face), those flagged by AntiVanish, and who is looking at you. */
    public static class AdminHud extends Mod {
        Setting.Mode corner = mode("Corner", "Top left", "Top left", "Top right", "Bottom left", "Bottom right");
        Setting.Bool faces = bool("Skin faces", true), watching = bool("Show who is watching you", true),
                vanished = bool("Show vanished (needs AntiVanish)", true);
        Setting.Num range = num("Watch range", 64, 8, 128, 8), angle = num("Watch angle", 25, 5, 90, 5);

        record L(String name, SkinTextures skin, int state, int dist) {}   // state: 0 online, 1 vanished, 2 watching you
        List<L> lines = new ArrayList<>();
        final Map<String, SkinTextures> skins = new HashMap<>();
        int t;

        public AdminHud() { super("AdminHud", Category.HUD, "Staff online with skin faces, flagged as vanished or watching you."); }

        @Override public void onEnable() { t = 0; lines = new ArrayList<>(); }

        boolean isWatching(PlayerEntity p) {
            Vec3d to = mc.player.getEyePos().subtract(p.getEyePos());
            double d = to.length();
            if (d < 0.5 || d > range.get()) return false;
            double dot = p.getRotationVec(1.0f).dotProduct(to.multiply(1.0 / d));
            return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot)))) <= angle.get();
        }

        @Override public void onTick() {
            if (t++ % 5 != 0) return;
            List<L> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Row r : staffInTab()) {
                String low = r.name().toLowerCase(Locale.ROOT);
                seen.add(low);
                if (r.skin() != null) skins.put(low, r.skin());
                int st = 0, dist = -1;
                for (PlayerEntity p : mc.world.getPlayers()) {
                    if (p == mc.player || !p.getName().getString().equalsIgnoreCase(r.name())) continue;
                    dist = (int) mc.player.distanceTo(p);
                    if (watching.get() && isWatching(p)) st = 2;
                }
                out.add(new L(r.name(), r.skin(), st, dist));
            }
            if (vanished.get())
                for (String n : AntiVanish.VANISHED.keySet())
                    if (!seen.contains(n.toLowerCase(Locale.ROOT))) out.add(new L(n, skins.get(n.toLowerCase(Locale.ROOT)), 1, -1));
            out.sort(Comparator.<L>comparingInt(l -> -l.state()).thenComparing(L::name, String.CASE_INSENSITIVE_ORDER));
            lines = out;
        }

        static String label(L l) {
            return switch (l.state()) {
                case 2 -> "watching" + (l.dist() >= 0 ? " " + l.dist() + "m" : "");
                case 1 -> "vanished";
                default -> l.dist() >= 0 ? "near " + l.dist() + "m" : "online";
            };
        }

        static int color(L l) { return l.state() == 2 ? 0xFFFF5A5A : l.state() == 1 ? 0xFFFFB84D : 0xFF4DE08A; }

        @Override public void onHud(DrawContext ctx) {
            List<L> ls = lines;
            if (ls.isEmpty()) return;
            int fs = faces.get() ? 10 : 0, pad = 6, rowH = 13, gap = faces.get() ? 4 : 0;
            String head = "Admins (" + ls.size() + ")";
            int w = U.width(head);
            for (L l : ls) w = Math.max(w, fs + gap + U.width(l.name()) + 12 + U.width(label(l)));
            w += pad * 2;
            int h = 18 + ls.size() * rowH;
            int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
            int x = corner.value.endsWith("right") ? sw - w - 6 : 6;
            int y = corner.value.startsWith("Top") ? 26 : sh - h - 6;
            Gfx.rrect(ctx, x, y, w, h, 5, 0xCC141414);
            U.text(ctx, head, x + pad, y + 4, ClientModules.Theme.accent());
            for (int i = 0; i < ls.size(); i++) {
                L l = ls.get(i);
                int ry = y + 17 + i * rowH;
                if (faces.get()) face(ctx, l.skin(), l.name(), x + pad, ry, fs);
                U.text(ctx, l.name(), x + pad + fs + gap, ry + 1, 0xFFFFFFFF);
                String lab = label(l);
                U.text(ctx, lab, x + w - pad - U.width(lab), ry + 1, color(l));
            }
        }
    }
}
