package kz.bejiihiu.dimensions.velocity.support;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;

/** Plain-text extraction for components built by the command, for assertions. */
public final class Components {

    private Components() {
    }

    public static String plain(Component component) {
        StringBuilder out = new StringBuilder();
        append(component, out);
        return out.toString();
    }

    public static String plain(java.util.List<Component> components) {
        StringBuilder out = new StringBuilder();
        for (Component c : components) {
            append(c, out);
            out.append('\n');
        }
        return out.toString();
    }

    private static void append(Component component, StringBuilder out) {
        if (component instanceof TextComponent text) {
            out.append(text.content());
        }
        for (Component child : component.children()) {
            append(child, out);
        }
    }
}
