/**
 * The plugin's own state store: the transfer-operation journal (anti-duplication source of
 * truth) and the optional portal index (an accelerating index, never a replacement for world
 * checks). Two interchangeable backends sit behind one interface — SQL via HikariCP
 * (MySQL/MariaDB/PostgreSQL, one shared standard dialect) and MongoDB (sync driver).
 */
package kz.bejiihiu.dimensions.common.persistence;
