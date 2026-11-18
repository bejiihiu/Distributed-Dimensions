/**
 * Transfer lifecycle events, following the Bukkit event model (static {@code getHandlerList()},
 * synchronous dispatch via the plugin manager).
 *
 * <p>{@code DimensionTransferStartEvent} fires on the source backend before the proxy request is
 * sent; {@code DimensionTransferCompleteEvent} / {@code DimensionTransferFailedEvent} fire on the
 * backend that terminally records the outcome; {@code EntityTransferEvent} and the
 * {@code Portal*Event} types carry finer-grained materialization details.
 *
 * <p>The portal events are marked unstable and are not intended for third-party listening; the
 * transfer events are the supported surface.
 */
package kz.bejiihiu.dimensions.api.event;
