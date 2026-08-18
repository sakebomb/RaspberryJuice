package net.zhuoweizhang.raspberryjuice;

import org.bukkit.entity.EntityType;

/**
 * Bridges the mcpi wire protocol's numeric entity-type ids to modern {@link EntityType}.
 *
 * <p>Like block ids, the Pi/mcpi protocol identifies entity types by a small integer
 * ({@code world.spawnEntity(x,y,z,typeId)}, {@code world.getEntities(typeId)}). Bukkit still
 * exposes the legacy numeric ids via {@link EntityType#getTypeId()} / {@link EntityType#fromId(int)},
 * but both are deprecated and marked for removal. This class is the single place that depends
 * on them, so the blast radius is one file when Paper drops them.
 *
 * <p><b>Known limitation:</b> only entity types that existed in the classic Pi era have a legacy
 * numeric id; entities added later report {@code -1} and cannot be addressed through the numeric
 * protocol. A future string/key-based entity protocol (the entity analogue of BlockData) is the
 * real fix - tracked in the modernization epic.
 */
@SuppressWarnings({"deprecation", "removal"})
public final class LegacyEntities {

	private LegacyEntities() {
	}

	/** mcpi numeric entity-type id -> EntityType, or {@code null} if the id is unknown. */
	public static EntityType fromId(int id) {
		return EntityType.fromId(id);
	}

	/** Legacy numeric id for an entity type; {@code -1} if it has no legacy id. */
	public static int typeId(EntityType type) {
		return type.getTypeId();
	}
}
