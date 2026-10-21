package nova.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;

public abstract class Setting<T> {
    public final String name;
    public T value;
    private T def;

    protected Setting(String name, T def) { this.name = name; this.value = def; this.def = def; }

    public void reset() { value = def; }
    public void setDefault(T v) { def = v; value = v; }
    public abstract JsonElement save();
    public abstract void load(JsonElement e);

    public static class Bool extends Setting<Boolean> {
        public Bool(String n, boolean d) { super(n, d); }
        public boolean get() { return value; }
        @Override public JsonElement save() { return new JsonPrimitive(value); }
        @Override public void load(JsonElement e) { value = e.getAsBoolean(); }
    }

    public static class Num extends Setting<Double> {
        public final double min, max, step;
        public Num(String n, double d, double min, double max, double step) {
            super(n, d); this.min = min; this.max = max; this.step = step;
        }
        public double get() { return value; }
        public int getInt() { return (int) Math.round(value); }
        public void set(double v) {
            v = Math.round(v / step) * step;
            value = Math.max(min, Math.min(max, v));
        }
        @Override public JsonElement save() { return new JsonPrimitive(value); }
        @Override public void load(JsonElement e) { set(e.getAsDouble()); }
    }

    public static class Mode extends Setting<String> {
        public String[] modes;
        private String pending;
        public Mode(String n, String d, String... modes) { super(n, d); this.modes = modes; }
        public boolean is(String m) { return value.equals(m); }
        public void cycle(int dir) {
            int i = 0;
            for (int k = 0; k < modes.length; k++) if (modes[k].equals(value)) i = k;
            value = modes[(i + dir + modes.length) % modes.length];
        }
        /** Replaces the option list (for lists only known at runtime, like schematic files). */
        public void setModes(String... m) {
            modes = m;
            if (pending != null) { for (String s : m) if (s.equals(pending)) value = s; pending = null; }
            boolean ok = false;
            for (String s : m) if (s.equals(value)) ok = true;
            if (!ok && m.length > 0) value = m[0];
        }
        @Override public JsonElement save() { return new JsonPrimitive(value); }
        @Override public void load(JsonElement e) {
            String s = e.getAsString();
            boolean found = false;
            for (String m : modes) if (m.equals(s)) { value = s; found = true; }
            if (!found) pending = s;
        }
    }

    /** A button inside a module's settings. Not saved. */
    public static class Action extends Setting<Boolean> {
        public final Runnable run;
        public Action(String n, Runnable run) { super(n, false); this.run = run; }
        @Override public JsonElement save() { return JsonNull.INSTANCE; }
        @Override public void load(JsonElement e) {}
    }
}
