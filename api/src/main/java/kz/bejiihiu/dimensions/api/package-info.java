/**
 * Public api of the Distributed Dimensions backend plugin, describing operations and value types
 * exchanged between this plugin, the proxy, and third-party plugins listening to transfer events.
 *
 * <p>Everything in this package and its subpackages is public contract: semantic versioning
 * applies, and breaking changes require a major version bump. The api jar carries no
 * implementation dependencies — it compiles against Paper's api for event and entity types,
 * which every consumer already has.
 *
 * <p>Entry point: {@link kz.bejiihiu.dimensions.api.DistributedDimensionsAPI#getInstance()}.
 */
package kz.bejiihiu.dimensions.api;
