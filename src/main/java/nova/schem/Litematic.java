package nova.schem;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Reads Litematica's .litematic files (gzipped NBT with bit-packed block palettes) without game NBT classes. */
public final class Litematic {
    public record Entry(int x, int y, int z, BlockState state) {}

    public final String name;
    public int sx, sy, sz;
    public final List<Entry> blocks = new ArrayList<>();
    /** Front text of signs (4 lines, plain text), keyed by key(x, y, z) of the schematic position. */
    public final Map<Long, String[]> signs = new HashMap<>();

    public static long key(int x, int y, int z) { return ((long) y << 42) | ((long) z << 21) | (long) x; }

    private Litematic(String name) { this.name = name; }

    @SuppressWarnings("unchecked")
    public static Litematic load(Path file) throws IOException {
        Map<String, Object> root;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            if (in.readUnsignedByte() != 10) throw new IOException("Not an NBT file");
            in.readUTF();
            root = (Map<String, Object>) readPayload(in, 10);
        }
        Object regionsObj = root.get("Regions");
        if (!(regionsObj instanceof Map<?, ?> regions) || regions.isEmpty()) throw new IOException("No regions in this schematic");

        int gx = Integer.MAX_VALUE, gy = Integer.MAX_VALUE, gz = Integer.MAX_VALUE;
        int hx = Integer.MIN_VALUE, hy = Integer.MIN_VALUE, hz = Integer.MIN_VALUE;
        for (Object ro : regions.values()) {
            Map<String, Object> r = (Map<String, Object>) ro;
            int[] p = vec(r.get("Position")), s = vec(r.get("Size"));
            for (int a = 0; a < 3; a++) {
                int lo = p[a] + (s[a] < 0 ? s[a] + 1 : 0), hi = lo + Math.abs(s[a]);
                if (a == 0) { gx = Math.min(gx, lo); hx = Math.max(hx, hi); }
                if (a == 1) { gy = Math.min(gy, lo); hy = Math.max(hy, hi); }
                if (a == 2) { gz = Math.min(gz, lo); hz = Math.max(hz, hi); }
            }
        }
        String n = file.getFileName().toString();
        Litematic out = new Litematic(n.endsWith(".litematic") ? n.substring(0, n.length() - 10) : n);
        out.sx = hx - gx; out.sy = hy - gy; out.sz = hz - gz;

        for (Object ro : regions.values()) {
            Map<String, Object> r = (Map<String, Object>) ro;
            int[] p = vec(r.get("Position")), s = vec(r.get("Size"));
            List<Object> palette = (List<Object>) r.get("BlockStatePalette");
            long[] arr = (long[]) r.get("BlockStates");
            if (palette == null || arr == null) continue;
            BlockState[] states = new BlockState[palette.size()];
            for (int i = 0; i < states.length; i++) states[i] = parseState((Map<String, Object>) palette.get(i));

            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, states.length) - 1));
            long mask = (1L << bits) - 1;
            int ax = Math.abs(s[0]), ay = Math.abs(s[1]), az = Math.abs(s[2]);
            int ox = p[0] + (s[0] < 0 ? s[0] + 1 : 0) - gx, oy = p[1] + (s[1] < 0 ? s[1] + 1 : 0) - gy, oz = p[2] + (s[2] < 0 ? s[2] + 1 : 0) - gz;
            long total = (long) ax * ay * az;
            for (int i = 0; i < total; i++) {
                int idx = get(arr, bits, mask, i);
                if (idx < 0 || idx >= states.length) continue;
                BlockState st = states[idx];
                if (st.isAir()) continue;
                int x = i % ax, z = (i / ax) % az, y = i / (ax * az);
                out.blocks.add(new Entry(ox + x, oy + y, oz + z, st));
            }
            if (r.get("TileEntities") instanceof List<?> tiles) {
                for (Object to : tiles) {
                    if (!(to instanceof Map<?, ?> m) || !(m.get("x") instanceof Number tx) || !(m.get("y") instanceof Number ty) || !(m.get("z") instanceof Number tz)) continue;
                    String[] lines = signLines(m);
                    if (lines != null) out.signs.put(key(ox + tx.intValue(), oy + ty.intValue(), oz + tz.intValue()), lines);
                }
            }
        }
        sort(out.blocks);
        return out;
    }

    /** The 4 front lines of a sign block entity (1.20+ front_text.messages or the old Text1..Text4), null when it has no text. */
    private static String[] signLines(Map<?, ?> m) {
        String[] res = new String[]{"", "", "", ""};
        boolean any = false;
        if (m.get("front_text") instanceof Map<?, ?> f && f.get("messages") instanceof List<?> ms) {
            for (int i = 0; i < Math.min(4, ms.size()); i++) if (ms.get(i) instanceof String js) { res[i] = plain(js); any |= !res[i].isEmpty(); }
        } else {
            for (int i = 0; i < 4; i++) if (m.get("Text" + (i + 1)) instanceof String js) { res[i] = plain(js); any |= !res[i].isEmpty(); }
        }
        return any ? res : null;
    }

    /** Text of a JSON text component ("abc", {"text":"abc"} or plain). Colours / formatting are dropped. */
    private static String plain(String j) {
        j = j.trim();
        if (j.isEmpty()) return "";
        if (j.startsWith("{")) {
            var mt = java.util.regex.Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(j);
            return mt.find() ? unescape(mt.group(1)) : "";
        }
        if (j.length() >= 2 && j.startsWith("\"") && j.endsWith("\"")) return unescape(j.substring(1, j.length() - 1));
        return j;
    }

    private static String unescape(String t) {
        return t.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", " ");
    }

    public static void sort(List<Entry> list) {
        list.sort(Comparator.comparingInt((Entry e) -> e.y()).thenComparingInt(Entry::z).thenComparingInt(Entry::x));
    }

    private static int get(long[] arr, int bits, long mask, int i) {
        long start = (long) i * bits;
        int a = (int) (start >> 6), b = (int) (((long) (i + 1) * bits - 1) >> 6);
        int off = (int) (start & 63);
        if (a >= arr.length) return -1;
        if (a == b) return (int) ((arr[a] >>> off) & mask);
        if (b >= arr.length) return -1;
        return (int) (((arr[a] >>> off) | (arr[b] << (64 - off))) & mask);
    }

    private static int[] vec(Object o) {
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
        return new int[]{((Number) m.get("x")).intValue(), ((Number) m.get("y")).intValue(), ((Number) m.get("z")).intValue()};
    }

    @SuppressWarnings("unchecked")
    private static BlockState parseState(Map<String, Object> e) {
        Identifier id = Identifier.tryParse((String) e.get("Name"));
        Block b = id == null ? Blocks.AIR : Registries.BLOCK.get(id);
        BlockState s = b.getDefaultState();
        Object po = e.get("Properties");
        if (po instanceof Map<?, ?> props) {
            for (Property<?> p : s.getProperties()) {
                Object v = props.get(p.getName());
                if (v instanceof String str) s = with(s, p, str);
            }
        }
        return s;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String v) {
        return p.parse(v).map(x -> s.with(p, x)).orElse(s);
    }

    private static Object readPayload(DataInputStream in, int type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { int n = in.readInt(); byte[] b = new byte[n]; in.readFully(b); return b; }
            case 8: return in.readUTF();
            case 9: {
                int t = in.readUnsignedByte(), n = in.readInt();
                List<Object> l = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) l.add(readPayload(in, t));
                return l;
            }
            case 10: {
                Map<String, Object> m = new HashMap<>();
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == 0) break;
                    String k = in.readUTF();
                    m.put(k, readPayload(in, t));
                }
                return m;
            }
            case 11: { int n = in.readInt(); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = in.readInt(); return a; }
            case 12: { int n = in.readInt(); long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = in.readLong(); return a; }
            default: throw new IOException("Unknown NBT tag " + type);
        }
    }
}
