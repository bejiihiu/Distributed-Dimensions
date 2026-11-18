package kz.bejiihiu.dimensions.admin;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MirageLinesTest {

    private static Messages messages() {
        return new Messages(Messages.class.getClassLoader(), message -> { });
    }

    private static String joined(MirageInfo info, Messages messages) {
        PlainTextComponentSerializer serializer = PlainTextComponentSerializer.plainText();
        return MirageLines.build(info, messages).stream()
                .map(serializer::serialize)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    @Test
    void disabledMiragePrintsASingleExplanatoryLine() {
        String text = joined(new MirageInfo(false, List.of(), List.of()), messages());
        assertTrue(text.contains("disabled"));
        assertTrue(text.contains("mirage.enabled=false"));
        assertEquals(1, text.lines().count());
    }

    @Test
    void entriesShowEnvironmentDimensionAndKind() {
        MirageInfo info = new MirageInfo(true, List.of(
                new MirageInfo.Entry("world_nether", "NETHER", "minecraft:the_nether", true),
                new MirageInfo.Entry("world", "NORMAL", "minecraft:overworld", false)),
                List.of());
        String text = joined(info, messages());
        assertTrue(text.contains("world_nether"));
        assertTrue(text.contains("NETHER"));
        assertTrue(text.contains("the_nether"));
        assertTrue(text.contains("mirage"));
        assertTrue(text.contains("real world"));
        assertTrue(text.contains("total: 1 mirage(s), 1 pre-existing real world slot(s)"));
    }

    @Test
    void failuresArePrinted() {
        MirageInfo info = new MirageInfo(true, List.of(),
                List.of("mirage 'world_the_end' failed: boom"));
        String text = joined(info, messages());
        assertTrue(text.contains("world_the_end"));
        assertTrue(text.contains("boom"));
    }

    @Test
    void russianDisabledMirageIsLocalized() {
        Messages messages = messages();
        messages.select("ru");
        String text = joined(new MirageInfo(false, List.of(), List.of()), messages);
        assertTrue(text.contains("отключён"));
    }
}
