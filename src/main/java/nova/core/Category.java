package nova.core;

public enum Category {
    COMBAT("Combat"), MOVEMENT("Movement"), PLAYER("Player"), RENDER("Render"),
    WORLD("World"), MISC("Misc"), HUD("HUD"), CLIENT("Client");

    public final String label;
    Category(String label) { this.label = label; }
}
