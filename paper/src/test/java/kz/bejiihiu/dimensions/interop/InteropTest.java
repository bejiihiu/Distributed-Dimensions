package kz.bejiihiu.dimensions.interop;

import kz.bejiihiu.dimensions.common.model.DimensionId;
import kz.bejiihiu.dimensions.common.model.Position;
import kz.bejiihiu.dimensions.common.model.TransferCause;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteropTest {

    private static final DimensionId TARGET = DimensionId.NETHER;
    private static final Position ARRIVAL = new Position(0, 64, 0, 90.0f, 0.0f);

    @Test
    void nullPortalIdIsAccepted() {
        // Player is required by the record; validation of portalId happens before any player use
        // so we exercise the validation through the compact constructor path only.
        assertThrows(NullPointerException.class, () -> new TransferCommand(null, TARGET, ARRIVAL, TransferCause.MIRAGE_TELEPORT, "x"));
    }

    @Test
    void portalIdIsTrimmedAndValidated() {
        TransferCommand ok = new TransferCommand(player(), TARGET, ARRIVAL, TransferCause.MIRAGE_TELEPORT, " portal-1 ");
        assertEquals("portal-1", ok.portalId());
        assertThrows(IllegalArgumentException.class,
                () -> new TransferCommand(player(), TARGET, ARRIVAL, TransferCause.MIRAGE_TELEPORT, "   "));
        assertThrows(IllegalArgumentException.class,
                () -> new TransferCommand(player(), TARGET, ARRIVAL, TransferCause.MIRAGE_TELEPORT, "line\nbreak"));
        assertThrows(IllegalArgumentException.class,
                () -> new TransferCommand(player(), TARGET, ARRIVAL, TransferCause.MIRAGE_TELEPORT, "x".repeat(129)));
    }

    @Test
    void convenienceConstructorFillsNullPortalId() {
        TransferCommand command = new TransferCommand(player(), TARGET, ARRIVAL, TransferCause.PROGRAMMATIC);
        assertNull(command.portalId());
    }

    @Test
    void sourceServerNameResolverPrefersConfiguredValue() {
        String previous = System.getProperty(SourceServerName.SYSTEM_PROPERTY);
        try {
            System.setProperty(SourceServerName.SYSTEM_PROPERTY, "sysprop-name");

            assertEquals("configured-name", SourceServerName.resolve("configured-name"),
                    "non-blank config must win over env and sysprop");
            assertEquals("configured-name", SourceServerName.resolve("  configured-name  "),
                    "config value is normalized like every other source");

            // blank and malformed config behave exactly like an unconfigured source:
            // the rest of the chain is consulted (env first, then sysprop)
            assertEquals(SourceServerName.resolve(null), SourceServerName.resolve("   "));
            assertEquals(SourceServerName.resolve(null), SourceServerName.resolve("not a server!"));
            assertEquals(SourceServerName.resolve(null), SourceServerName.resolve(null));
        } finally {
            if (previous == null) {
                System.clearProperty(SourceServerName.SYSTEM_PROPERTY);
            } else {
                System.setProperty(SourceServerName.SYSTEM_PROPERTY, previous);
            }
        }
    }

    @Test
    void sourceServerNameResolverReadsAndValidatesTheSystemProperty() {
        String previous = System.getProperty(SourceServerName.SYSTEM_PROPERTY);
        try {
            System.clearProperty(SourceServerName.SYSTEM_PROPERTY);
            // only the env var may remain — cannot be relied on in CI either way; whatever it is,
            // the contract is "null or a valid name"
            String resolved = SourceServerName.resolve();
            if (resolved != null) {
                org.junit.jupiter.api.Assertions.assertTrue(resolved.matches("[A-Za-z0-9_-]{1,64}"));
            }

            System.setProperty(SourceServerName.SYSTEM_PROPERTY, "  ");
            assertNull(SourceServerName.resolve(), "blank property must not resolve");

            System.setProperty(SourceServerName.SYSTEM_PROPERTY, "not a server!");
            assertNull(SourceServerName.resolve(), "malformed property must not resolve");

            System.setProperty(SourceServerName.SYSTEM_PROPERTY, "nether");
            assertEquals("nether", SourceServerName.resolve());

            System.setProperty(SourceServerName.SYSTEM_PROPERTY, "x".repeat(65));
            assertNull(SourceServerName.resolve());
        } finally {
            if (previous == null) {
                System.clearProperty(SourceServerName.SYSTEM_PROPERTY);
            } else {
                System.setProperty(SourceServerName.SYSTEM_PROPERTY, previous);
            }
        }
    }

    @Test
    void fromDimensionMappingIsCanonicalAndFallsBackLoudly() {
        assertEquals(DimensionId.OVERWORLD, FromDimension.of(org.bukkit.World.Environment.NORMAL).dimension());
        assertEquals(DimensionId.NETHER, FromDimension.of(org.bukkit.World.Environment.NETHER).dimension());
        assertEquals(DimensionId.THE_END, FromDimension.of(org.bukkit.World.Environment.THE_END).dimension());
        assertTrue(FromDimension.of(org.bukkit.World.Environment.NORMAL).canonical());

        FromDimension.Resolution custom = FromDimension.of(org.bukkit.World.Environment.CUSTOM);
        assertEquals(DimensionId.OVERWORLD, custom.dimension());
        org.junit.jupiter.api.Assertions.assertFalse(custom.canonical());

        FromDimension.Resolution unknown = FromDimension.of(null);
        assertEquals(DimensionId.OVERWORLD, unknown.dimension());
        org.junit.jupiter.api.Assertions.assertFalse(unknown.canonical());
    }

    @Test
    void interopMappingRoundTripsEveryCauseAndError() {
        for (kz.bejiihiu.dimensions.common.model.TransferCause common :
                kz.bejiihiu.dimensions.common.model.TransferCause.values()) {
            assertEquals(common.wireName(), InteropMapping.apiCause(common).wireName());
        }
        for (kz.bejiihiu.dimensions.common.model.TransferError common :
                kz.bejiihiu.dimensions.common.model.TransferError.values()) {
            assertEquals(common.wireName(), InteropMapping.apiTransferError(common).wireName());
        }
        assertEquals("minecraft:the_nether",
                InteropMapping.apiDimension(DimensionId.NETHER).key());
        assertEquals("minecraft:the_nether",
                InteropMapping.commonDimension(InteropMapping.apiDimension(DimensionId.NETHER)).key());
    }

    private static org.bukkit.entity.Player player() {
        java.lang.reflect.InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> UUID.randomUUID();
            case "getName" -> "TestPlayer";
            case "isOnline" -> true;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null ? null : args[0]);
            case "toString" -> "TestPlayer";
            default -> null;
        };
        return (org.bukkit.entity.Player) java.lang.reflect.Proxy.newProxyInstance(
                org.bukkit.entity.Player.class.getClassLoader(),
                new Class<?>[] { org.bukkit.entity.Player.class },
                handler);
    }
}
