package kz.bejiihiu.dimensions.paper.sync;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Local Bukkit events used only by the reflection-integration tests — stand-ins
 * for foreign sync plugins' completion events, so the bridge can be exercised
 * without any of them installed.
 */
final class TestSyncEvents {

    private TestSyncEvents() {
    }

    /** {@code getPlayer()} returns a UUID — the getter-as-UUID shape. */
    public static final class UuidPlayerEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final UUID uuid;

        public UuidPlayerEvent(UUID uuid) {
            this.uuid = uuid;
        }

        public UUID getPlayer() {
            return uuid;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /** {@code getUser()} returns an object with {@code getUuid()} — the HuskSync shape. */
    public static final class UserSyncEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final UUID uuid;

        public UserSyncEvent(UUID uuid) {
            this.uuid = uuid;
        }

        public TestUser getUser() {
            return new TestUser(uuid);
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /** {@code getPlayer()} returns the Player itself. */
    public static final class PlayerReturningEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final org.bukkit.entity.Player player;

        public PlayerReturningEvent(org.bukkit.entity.Player player) {
            this.player = player;
        }

        public org.bukkit.entity.Player getPlayer() {
            return player;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /** An unrelated event that the listener must ignore entirely. */
    public static final class UnrelatedEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /** {@code explode()} throws on invocation — the getter-blows-up path. */
    public static final class ExplodingGetterEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        public UUID explode() {
            throw new IllegalStateException("boom");
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /** {@code getAnswer()} returns something with no getUuid and not a Player/UUID. */
    public static final class UncoercibleGetterEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        public Integer getAnswer() {
            return 42;
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }

        public static HandlerList getHandlerList() {
            return HANDLERS;
        }
    }

    /** Inner stand-in for a sync plugin's user handle (HuskSync's OnlineUser shape). */
    public static final class TestUser {
        private final UUID uuid;

        public TestUser(UUID uuid) {
            this.uuid = uuid;
        }

        public UUID getUuid() {
            return uuid;
        }
    }
}
