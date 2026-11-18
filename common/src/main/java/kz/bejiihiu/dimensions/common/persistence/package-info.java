/**
 * The plugin's own state store: the transfer-operation journal (anti-duplication source of
 * truth), the optional portal index (an accelerating index, never a replacement for world
 * checks), the entity-transfer payload store and the transfer-context store (the source
 * portal's relative-position context for portal-typed transfers). Interchangeable backends
 * sit behind one interface — SQL via HikariCP (MySQL/MariaDB/PostgreSQL, one shared standard
 * dialect) and MongoDB (sync driver).
 */
package kz.bejiihiu.dimensions.common.persistence;
