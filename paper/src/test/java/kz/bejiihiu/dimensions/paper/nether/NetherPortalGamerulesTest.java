package kz.bejiihiu.dimensions.paper.nether;

import kz.bejiihiu.dimensions.paper.TestWorlds;

import org.bukkit.World;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NetherPortalGamerulesTest {

    @Test
    void sanitizeClampsNonsenseValues() {
        assertEquals(80, NetherPortalGamerules.sanitize(null, 80));
        assertEquals(80, NetherPortalGamerules.sanitize(-5, 80));
        assertEquals(0, NetherPortalGamerules.sanitize(0, 80));
        assertEquals(33, NetherPortalGamerules.sanitize(33, 80));
    }

    @Test
    void vanillaDefaultsAreTheVanillaConstants() {
        NetherPortalGamerules defaults = NetherPortalGamerules.vanillaDefaults();
        assertEquals(80, defaults.playersNetherPortalDefaultDelay());
        assertEquals(1, defaults.playersNetherPortalCreativeDelay());
        assertEquals("playersNetherPortalDefaultDelay", NetherPortalGamerules.DEFAULT_DELAY_KEY);
        assertEquals("playersNetherPortalCreativeDelay", NetherPortalGamerules.CREATIVE_DELAY_KEY);
    }

    @Test
    void unreadableRulesFallBackToVanilla() {
        // the TestWorlds proxy refuses getGameRuleValue — the fallback must kick in, not an exception
        World proxyWorld = TestWorlds.create("world");
        BukkitNetherPortalGamerules rules = new BukkitNetherPortalGamerules(proxyWorld);
        assertEquals(80, rules.playersNetherPortalDefaultDelay());
        assertEquals(1, rules.playersNetherPortalCreativeDelay());
    }

    @Test
    void configuredRulesAreRead() {
        // The configured-value path needs the vanilla gamerule registry, which only exists on a
        // live server (GameRules.<clinit> bootstraps it); the sanitize() clamp and the fallback
        // are covered above, and the registry read is covered by the live-server smoke run.
        NetherPortalGamerules configured = new NetherPortalGamerules() {
            @Override
            public int playersNetherPortalDefaultDelay() {
                return NetherPortalGamerules.sanitize(0, VANILLA_DEFAULT_DELAY);
            }

            @Override
            public int playersNetherPortalCreativeDelay() {
                return NetherPortalGamerules.sanitize(15, VANILLA_CREATIVE_DELAY);
            }
        };
        assertEquals(0, configured.playersNetherPortalDefaultDelay());
        assertEquals(15, configured.playersNetherPortalCreativeDelay());
    }

    @Test
    void negativeConfiguredRuleFallsBack() {
        // on a bare JVM GameRules cannot initialise at all — the read must degrade, never throw
        World proxyWorld = TestWorlds.create("world");
        BukkitNetherPortalGamerules rules = new BukkitNetherPortalGamerules(proxyWorld);
        assertEquals(80, rules.playersNetherPortalDefaultDelay());
        assertEquals(1, rules.playersNetherPortalCreativeDelay());
    }
}
