package kz.bejiihiu.dimensions.compat;

import kz.bejiihiu.dimensions.api.TransferCause;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdvancementGranterTest {

    @Test
    void netherPortalMapsToEnterTheNether() {
        AdvancementGranter.AdvancementRule rule = AdvancementGranter.ruleFor(TransferCause.NETHER_PORTAL);
        assertNotNull(rule);
        assertEquals("minecraft:story/enter_the_nether", rule.advancementKey());
        assertEquals("entered_nether", rule.criterion());
    }

    @Test
    void endPortalMapsToEnterTheEnd() {
        AdvancementGranter.AdvancementRule rule = AdvancementGranter.ruleFor(TransferCause.END_PORTAL);
        assertNotNull(rule);
        assertEquals("minecraft:story/enter_the_end", rule.advancementKey());
        assertEquals("entered_end", rule.criterion());
    }

    @Test
    void otherCausesGrantNothing() {
        assertNull(AdvancementGranter.ruleFor(TransferCause.RESPAWN));
        assertNull(AdvancementGranter.ruleFor(TransferCause.PROGRAMMATIC));
        assertNull(AdvancementGranter.ruleFor(TransferCause.MIRAGE_TELEPORT));
    }
}
