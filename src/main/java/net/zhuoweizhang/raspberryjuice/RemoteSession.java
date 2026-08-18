package net.zhuoweizhang.raspberryjuice;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.ProjectileHitEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;

public class RemoteSession {

	/** IP_TOS bits requesting low-delay handling for the API socket. */
	private static final int IP_TOS_LOW_DELAY = 0x10;

	/** Default cap on commands processed per server tick before warning. */
	private static final int DEFAULT_MAX_COMMANDS_PER_TICK = 9000;

	private final LocationType locationType;

	private Location origin;

	private Socket socket;

	private BufferedReader in;

	private BufferedWriter out;
	
	private Thread inThread;
	
	private Thread outThread;

	private ConcurrentLinkedQueue<String> inQueue = new ConcurrentLinkedQueue<String>();

	private LinkedBlockingQueue<String> outQueue = new LinkedBlockingQueue<String>();

	public volatile boolean running = true;

	public volatile boolean pendingRemoval = false;

	public RaspberryJuicePlugin plugin;

	protected ConcurrentLinkedQueue<PlayerInteractEvent> interactEventQueue = new ConcurrentLinkedQueue<PlayerInteractEvent>();

	protected ConcurrentLinkedQueue<AsyncChatEvent> chatPostedQueue = new ConcurrentLinkedQueue<AsyncChatEvent>();

	protected ConcurrentLinkedQueue<ProjectileHitEvent> projectileHitQueue = new ConcurrentLinkedQueue<ProjectileHitEvent>();

	private int maxCommandsPerTick = DEFAULT_MAX_COMMANDS_PER_TICK;

	private volatile boolean closed = false;

	private Player attachedPlayer = null;

	public RemoteSession(RaspberryJuicePlugin plugin, Socket socket) throws IOException {
		this.socket = socket;
		this.plugin = plugin;
		this.locationType = plugin.getLocationType();
		init();
	}

	public void init() throws IOException {
		socket.setTcpNoDelay(true);
		socket.setKeepAlive(true);
		socket.setTrafficClass(IP_TOS_LOW_DELAY);
		this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
		this.out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"));
		startThreads();
		plugin.getLogger().info("Opened connection to" + socket.getRemoteSocketAddress() + ".");
	}

	protected void startThreads() {
		inThread = new Thread(new InputThread());
		inThread.start();
		outThread = new Thread(new OutputThread());
		outThread.start();
	}


	public Location getOrigin() {
		return origin;
	}

	public void setOrigin(Location origin) {
		this.origin = origin;
	}

	public Socket getSocket() {
		return socket;
	}

	public void queuePlayerInteractEvent(PlayerInteractEvent event) {
		interactEventQueue.add(event);
	}

	public void queueChatPostedEvent(AsyncChatEvent event) {
		chatPostedQueue.add(event);
	}
	
	public void queueProjectileHitEvent(ProjectileHitEvent event) {

		if (event.getEntityType() == EntityType.ARROW) {
			Arrow arrow = (Arrow) event.getEntity();
			if (arrow.getShooter() instanceof Player) {
				projectileHitQueue.add(event);
			}
		}
	}

	/** called from the server main thread */
	public void tick() {
		if (origin == null) {
			switch (locationType) {
				case ABSOLUTE:
					this.origin = new Location(plugin.getServer().getWorlds().get(0), 0, 0, 0);
					break;
				case RELATIVE:
					this.origin = plugin.getServer().getWorlds().get(0).getSpawnLocation();
					break;
				default:
					throw new IllegalArgumentException("Unknown location type " + locationType);
			}
		}
		int processedCount = 0;
		String message;
		while ((message = inQueue.poll()) != null) {
			handleLine(message);
			processedCount++;
			if (processedCount >= maxCommandsPerTick) {
				plugin.getLogger().warning("Over " + maxCommandsPerTick +
					" commands were queued - deferring " + inQueue.size() + " to next tick");
				break;
			}
		}

		if (!running && inQueue.size() <= 0) {
			pendingRemoval = true;
		}
	}

	protected void handleLine(String line) {
		int openParen = line.indexOf("(");
		// reject malformed input up front so a single bad packet can't throw out of the tick loop
		if (openParen < 0 || !line.endsWith(")")) {
			plugin.getLogger().warning("Ignoring malformed command: " + line);
			send("Fail");
			return;
		}
		String methodName = line.substring(0, openParen);
		// split args on commas (note: commas inside argument values are not escaped)
		String[] args = line.substring(openParen + 1, line.length() - 1).split(",");
		handleCommand(methodName, args);
	}

	protected void handleCommand(String c, String[] args) {
		
		try {
			// get the server
			Server server = plugin.getServer();
			
			// get the world
			World world = origin.getWorld();
			if (handleWorldAndBulkEntityCommands(c, args, world)
					|| handleEventAndPlayerCommands(c, args, world, server)
					|| handleEntityAndWorldExtraCommands(c, args, world)) {
				return;
			}
			plugin.getLogger().warning(c + " is not supported.");
			send("Fail");
		} catch (Exception e) {
			//log with the offending command and full context instead of dumping to stdout
			plugin.getLogger().log(java.util.logging.Level.WARNING, "Error handling command: " + c, e);
			send("Fail");
		}
	}

	// The command handlers below are split from the original single dispatch chain into three
	// contiguous groups; each returns true if it handled the command. Bodies are unchanged.

	private boolean handleWorldAndBulkEntityCommands(String c, String[] args, World world) {
			// world.getBlock
			if (c.equals("world.getBlock")) {
				Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
				send(LegacyBlocks.legacyId(world.getBlockAt(loc)));

			// world.getBlocks
			} else if (c.equals("world.getBlocks")) {
				Location loc1 = parseRelativeBlockLocation(args[0], args[1], args[2]);
				Location loc2 = parseRelativeBlockLocation(args[3], args[4], args[5]);
				if (exceedsBlockLimit(loc1, loc2)) {
					plugin.getLogger().warning("world.getBlocks request of " + blockVolume(loc1, loc2)
						+ " blocks exceeds max-blocks (" + plugin.getMaxBlocks() + "); rejected.");
					send("Fail");
				} else {
					send(getBlocks(loc1, loc2));
				}

			// world.getBlockWithData
			} else if (c.equals("world.getBlockWithData")) {
				Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
				Block block = world.getBlockAt(loc);
				send(LegacyBlocks.legacyId(block) + "," + LegacyBlocks.legacyData(block));
				
			// world.setBlock
			} else if (c.equals("world.setBlock")) {
				Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
				updateBlock(world, loc, Integer.parseInt(args[3]), (args.length > 4? Byte.parseByte(args[4]) : (byte) 0));
				
			// world.setBlocks
			} else if (c.equals("world.setBlocks")) {
				Location loc1 = parseRelativeBlockLocation(args[0], args[1], args[2]);
				Location loc2 = parseRelativeBlockLocation(args[3], args[4], args[5]);
				int blockType = Integer.parseInt(args[6]);
				byte data = args.length > 7? Byte.parseByte(args[7]) : (byte) 0;
				if (exceedsBlockLimit(loc1, loc2)) {
					plugin.getLogger().warning("world.setBlocks request of " + blockVolume(loc1, loc2)
						+ " blocks exceeds max-blocks (" + plugin.getMaxBlocks() + "); rejected.");
				} else {
					setCuboid(loc1, loc2, blockType, data);
				}
				
			// world.getPlayerIds
			} else if (c.equals("world.getPlayerIds")) {
				StringBuilder bdr = new StringBuilder();
				Collection<? extends Player> players = Bukkit.getOnlinePlayers();
				if (players.size() > 0) {
					for (Player p: players) {
						bdr.append(p.getEntityId());
						bdr.append("|");
					}
					bdr.deleteCharAt(bdr.length()-1);
					send(bdr.toString());
				} else {
					send("Fail");
				}
				
			// world.getPlayerId
			} else if (c.equals("world.getPlayerId")) {
				Player p = plugin.getNamedPlayer(args[0]);
				if (p != null) {
					send(p.getEntityId());
				} else {
					plugin.getLogger().info("Player [" + args[0] + "] not found.");
					send("Fail");
				}
				
			// entity.getListName
			} else if (c.equals("entity.getName")) {
				Entity e = plugin.getEntity(Integer.parseInt(args[0]));
				if (e == null) {
					plugin.getLogger().info("Player (or Entity) [" + args[0] + "] not found in entity.getName.");
				} else if (e instanceof Player) {
					Player p = (Player) e;
					//sending list name because plugin.getNamedPlayer() uses list name
					send(PlainText.plain(p.playerListName()));
				} else if (e != null) {
					send(e.getName());
				}
				
			// world.getEntities
			} else if (c.equals("world.getEntities")) {
				int entityType = Integer.parseInt(args[0]);
				send(getEntities(world, entityType));
				
			// world.removeEntity
			} else if (c.equals("world.removeEntity")) {
				int result = 0;
				for (Entity e : world.getEntities()) {
					if (e.getEntityId() == Integer.parseInt(args[0]))
					{
						e.remove();
						result = 1;
						break;
					}
				}
				send(result);
				
			// world.removeEntities
			} else if (c.equals("world.removeEntities")) {
				int entityType = Integer.parseInt(args[0]);
				int removedEntitiesCount = 0;
				for (Entity e : world.getEntities()) {
					if (entityType == -1 || LegacyEntities.typeId(e.getType()) == entityType)
					{
						e.remove();
						removedEntitiesCount++;
					}
				}
				send(removedEntitiesCount);

			} else {
				return false;
			}
			return true;
	}

	private boolean handleEventAndPlayerCommands(String c, String[] args, World world, Server server) {
			// chat.post
			if (c.equals("chat.post")) {
				//create chat message from args as it was split by ,
				String chatMessage = "";
				int count;
				for(count=0;count<args.length;count++){
					chatMessage = chatMessage + args[count] + ",";
				}
				chatMessage = chatMessage.substring(0, chatMessage.length() - 1);
				//interpret legacy section (§) colour codes so chat.post renders as it did with broadcastMessage(String)
				server.broadcast(PlainText.legacy(chatMessage));

			// events.clear
			} else if (c.equals("events.clear")) {
				interactEventQueue.clear();
				chatPostedQueue.clear();
				
			// events.block.hits
			} else if (c.equals("events.block.hits")) {
				send(getBlockHits());
				
			// events.chat.posts
			} else if (c.equals("events.chat.posts")) {
				send(getChatPosts());
				
			// events.projectile.hits
			} else if(c.equals("events.projectile.hits")) {
				send(getProjectileHits());
				
			// entity.events.clear
			} else if (c.equals("entity.events.clear")) {
				int entityId = Integer.parseInt(args[0]);
				clearEntityEvents(entityId);
				
			// entity.events.block.hits
			} else if (c.equals("entity.events.block.hits")) {
				int entityId = Integer.parseInt(args[0]);
				send(getBlockHits(entityId));
				
			// entity.events.chat.posts
			} else if (c.equals("entity.events.chat.posts")) {
				int entityId = Integer.parseInt(args[0]);
				send(getChatPosts(entityId));
				
			// entity.events.projectile.hits
			} else if(c.equals("entity.events.projectile.hits")) {
				int entityId = Integer.parseInt(args[0]);
				send(getProjectileHits(entityId));
			
			// player.getTile
			}else if (c.equals("player.getTile")) {
				send(entityGetTile(getCurrentPlayer()));
				
			// player.setTile
			} else if (c.equals("player.setTile")) {
				entitySetTile(getCurrentPlayer(), args[0], args[1], args[2]);
				
			// player.getAbsPos
			} else if (c.equals("player.getAbsPos")) {
				Player currentPlayer = getCurrentPlayer();
				//send absolute coordinates as "x,y,z" (not Location.toString())
				Location loc = currentPlayer.getLocation();
				send(loc.getX() + "," + loc.getY() + "," + loc.getZ());
				
			// player.setAbsPos
			} else if (c.equals("player.setAbsPos")) {
				String x = args[0], y = args[1], z = args[2];
				Player currentPlayer = getCurrentPlayer();
				//get players current location, so when they are moved we will use the same pitch and yaw (rotation)
				Location loc = currentPlayer.getLocation();
				loc.setX(Double.parseDouble(x));
				loc.setY(Double.parseDouble(y));
				loc.setZ(Double.parseDouble(z));
				currentPlayer.teleport(loc);

			// player.getPos
			} else if (c.equals("player.getPos")) {
				send(entityGetPos(getCurrentPlayer()));

			// player.setPos
			} else if (c.equals("player.setPos")) {
				entitySetPos(getCurrentPlayer(), args[0], args[1], args[2]);

			// player.setDirection
			} else if (c.equals("player.setDirection")) {
				entitySetDirection(getCurrentPlayer(), args[0], args[1], args[2]);

			// player.getDirection
			} else if (c.equals("player.getDirection")) {
				send(entityGetDirection(getCurrentPlayer()));

			// player.setRotation
			} else if (c.equals("player.setRotation")) {
				entitySetRotation(getCurrentPlayer(), args[0]);

			// player.getRotation
			} else if (c.equals("player.getRotation")) {
				send(entityGetRotation(getCurrentPlayer(), true));

			// player.setPitch
			} else if (c.equals("player.setPitch")) {
				entitySetPitch(getCurrentPlayer(), args[0]);
				
			// player.getPitch
			} else if (c.equals("player.getPitch")) {
				send(entityGetPitch(getCurrentPlayer()));

			// player.getEntities
			} else if (c.equals("player.getEntities")) {
				Player currentPlayer = getCurrentPlayer();
				int distance = Integer.parseInt(args[0]);
				int entityTypeId = Integer.parseInt(args[1]);

				send(getEntities(world, currentPlayer.getEntityId(), distance, entityTypeId));

			// player.removeEntities
			} else if (c.equals("player.removeEntities")) {
				Player currentPlayer = getCurrentPlayer();
				int distance = Integer.parseInt(args[0]);
				int entityType = Integer.parseInt(args[1]);

				send(removeEntities(world, currentPlayer.getEntityId(), distance, entityType));

			// player.events.block.hits
			} else if (c.equals("player.events.block.hits")) {
				Player currentPlayer = getCurrentPlayer();
				send(getBlockHits(currentPlayer.getEntityId()));
				
			// player.events.chat.posts
			} else if (c.equals("player.events.chat.posts")) {
				Player currentPlayer = getCurrentPlayer();
				send(getChatPosts(currentPlayer.getEntityId()));
				
			// player.events.projectile.hits
			} else if(c.equals("player.events.projectile.hits")) {
				Player currentPlayer = getCurrentPlayer();
				send(getProjectileHits(currentPlayer.getEntityId()));
			
			// player.events.clear
			} else if (c.equals("player.events.clear")) {
				Player currentPlayer = getCurrentPlayer();
				clearEntityEvents(currentPlayer.getEntityId());

			} else {
				return false;
			}
			return true;
	}

	private boolean handleEntityAndWorldExtraCommands(String c, String[] args, World world) {
			// world.getHeight
			if (c.equals("world.getHeight")) {
				send(world.getHighestBlockYAt(parseRelativeBlockLocation(args[0], "0", args[1])) - origin.getBlockY());
				
			// entity.getTile
			} else if (c.equals("entity.getTile")) {
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) send(entityGetTile(entity));
				else entityNotFound(args[0], true);
				
			// entity.setTile
			} else if (c.equals("entity.setTile")) {
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) entitySetTile(entity, args[1], args[2], args[3]);
				else entityNotFound(args[0], true);

			// entity.getPos
			} else if (c.equals("entity.getPos")) {
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) send(entityGetPos(entity));
				else entityNotFound(args[0], true);
			
			// entity.setPos
			} else if (c.equals("entity.setPos")) {
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) entitySetPos(entity, args[1], args[2], args[3]);
				else entityNotFound(args[0], true);

			// entity.setDirection
			} else if (c.equals("entity.setDirection")) {
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) entitySetDirection(entity, args[1], args[2], args[3]);
				else entityNotFound(args[0], false);
				
			// entity.getDirection
			} else if (c.equals("entity.getDirection")) {
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) send(entityGetDirection(entity));
				else entityNotFound(args[0], true);

			// entity.setRotation
			} else if (c.equals("entity.setRotation")) {
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) entitySetRotation(entity, args[1]);
				else entityNotFound(args[0], false);

			// entity.getRotation
			} else if (c.equals("entity.getRotation")) {
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) send(entityGetRotation(entity, false));
				else entityNotFound(args[0], true);
			
			// entity.setPitch
			} else if (c.equals("entity.setPitch")) {
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) entitySetPitch(entity, args[1]);
				else entityNotFound(args[0], false);

			// entity.getPitch
			} else if (c.equals("entity.getPitch")) {
				Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
				if (entity != null) send(entityGetPitch(entity));
				else entityNotFound(args[0], true);
				
			// entity.getEntities
			} else if (c.equals("entity.getEntities")) {
				int entityId = Integer.parseInt(args[0]);
				int distance = Integer.parseInt(args[1]);
				int entityTypeId = Integer.parseInt(args[2]);

				send(getEntities(world, entityId, distance, entityTypeId));
					
			// entity.removeEntities
			} else if (c.equals("entity.removeEntities")) {
				int entityId = Integer.parseInt(args[0]);
				int distance = Integer.parseInt(args[1]);
				int entityType = Integer.parseInt(args[2]);

				send(removeEntities(world, entityId, distance, entityType));
				
			// world.setSign
			} else if (c.equals("world.setSign")) {
				Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
				Block thisBlock = world.getBlockAt(loc);
				//blockType should be 68 for wall sign or 63 for standing sign
				int blockType = Integer.parseInt(args[3]);
				//facing direction for wall sign : 2=north, 3=south, 4=west, 5=east
				//rotation 0 - to 15 for standing sign : 0=south, 4=west, 8=north, 12=east
				byte blockData = Byte.parseByte(args[4]);
				BlockData signData = LegacyBlocks.toBlockData(blockType, blockData);
				if (signData != null && !thisBlock.getBlockData().equals(signData)) {
					thisBlock.setBlockData(signData, true);
				}
				if ( thisBlock.getState() instanceof Sign ) {
					Sign sign = (Sign) thisBlock.getState();
					SignSide front = sign.getSide(Side.FRONT);
					for ( int i = 5; i-5 < 4 && i < args.length; i++) {
						front.line(i-5, Component.text(args[i]));
					}
					sign.update();
				}
			
			// world.spawnEntity
			} else if (c.equals("world.spawnEntity")) {
				Location loc = parseRelativeBlockLocation(args[0], args[1], args[2]);
				Entity entity = world.spawnEntity(loc, LegacyEntities.fromId(Integer.parseInt(args[3])));
				send(entity.getEntityId());

			// world.getEntityTypes
			} else if (c.equals("world.getEntityTypes")) {
				StringBuilder bdr = new StringBuilder();				
				for (EntityType entityType : EntityType.values()) {
					if ( entityType.isSpawnable() && LegacyEntities.typeId(entityType) >= 0 ) {
						bdr.append(LegacyEntities.typeId(entityType));
						bdr.append(",");
						bdr.append(entityType.toString());
						bdr.append("|");
					}
				}
				send(bdr.toString());

			} else {
				return false;
			}
			return true;
	}

	// number of blocks spanned by the cuboid between two corners (inclusive).
	// Saturates to Long.MAX_VALUE on overflow so a huge span can't wrap to a small
	// value and slip past the limit check (coords are clamped to int range, so an
	// absurd request could otherwise multiply out to exactly 0).
	long blockVolume(Location p1, Location p2) {
		long dx = Math.abs((long) p1.getBlockX() - p2.getBlockX()) + 1;
		long dy = Math.abs((long) p1.getBlockY() - p2.getBlockY()) + 1;
		long dz = Math.abs((long) p1.getBlockZ() - p2.getBlockZ()) + 1;
		try {
			return Math.multiplyExact(Math.multiplyExact(dx, dy), dz);
		} catch (ArithmeticException overflow) {
			return Long.MAX_VALUE;
		}
	}

	// true if the cuboid is larger than the configured max-blocks limit (0 = unlimited)
	boolean exceedsBlockLimit(Location p1, Location p2) {
		int max = plugin.getMaxBlocks();
		return max > 0 && blockVolume(p1, p2) > max;
	}

	// create a cuboid of lots of blocks
	private void setCuboid(Location pos1, Location pos2, int blockType, byte data) {
		int minX, maxX, minY, maxY, minZ, maxZ;
		World world = pos1.getWorld();
		minX = pos1.getBlockX() < pos2.getBlockX() ? pos1.getBlockX() : pos2.getBlockX();
		maxX = pos1.getBlockX() >= pos2.getBlockX() ? pos1.getBlockX() : pos2.getBlockX();
		minY = pos1.getBlockY() < pos2.getBlockY() ? pos1.getBlockY() : pos2.getBlockY();
		maxY = pos1.getBlockY() >= pos2.getBlockY() ? pos1.getBlockY() : pos2.getBlockY();
		minZ = pos1.getBlockZ() < pos2.getBlockZ() ? pos1.getBlockZ() : pos2.getBlockZ();
		maxZ = pos1.getBlockZ() >= pos2.getBlockZ() ? pos1.getBlockZ() : pos2.getBlockZ();

		for (int x = minX; x <= maxX; ++x) {
			for (int z = minZ; z <= maxZ; ++z) {
				for (int y = minY; y <= maxY; ++y) {
					updateBlock(world, x, y, z, blockType, data);
				}
			}
		}
	}

	// get a cuboid of lots of blocks
	private String getBlocks(Location pos1, Location pos2) {
		StringBuilder blockData = new StringBuilder();

		int minX, maxX, minY, maxY, minZ, maxZ;
		World world = pos1.getWorld();
		minX = pos1.getBlockX() < pos2.getBlockX() ? pos1.getBlockX() : pos2.getBlockX();
		maxX = pos1.getBlockX() >= pos2.getBlockX() ? pos1.getBlockX() : pos2.getBlockX();
		minY = pos1.getBlockY() < pos2.getBlockY() ? pos1.getBlockY() : pos2.getBlockY();
		maxY = pos1.getBlockY() >= pos2.getBlockY() ? pos1.getBlockY() : pos2.getBlockY();
		minZ = pos1.getBlockZ() < pos2.getBlockZ() ? pos1.getBlockZ() : pos2.getBlockZ();
		maxZ = pos1.getBlockZ() >= pos2.getBlockZ() ? pos1.getBlockZ() : pos2.getBlockZ();

		for (int y = minY; y <= maxY; ++y) {
			 for (int x = minX; x <= maxX; ++x) {
				 for (int z = minZ; z <= maxZ; ++z) {
					blockData.append(LegacyBlocks.legacyId(world.getBlockAt(x, y, z))).append(",");
				}
			}
		}

		return blockData.substring(0, blockData.length() > 0 ? blockData.length() - 1 : 0);	// We don't want last comma
	}

	// updates a block
	private void updateBlock(World world, Location loc, int blockType, byte blockData) {
		Block thisBlock = world.getBlockAt(loc);
		updateBlock(thisBlock, blockType, blockData);
	}
	
	private void updateBlock(World world, int x, int y, int z, int blockType, byte blockData) {
		Block thisBlock = world.getBlockAt(x,y,z);
		updateBlock(thisBlock, blockType, blockData);
	}
	
	private void updateBlock(Block thisBlock, int blockType, byte blockData) {
		BlockData target = LegacyBlocks.toBlockData(blockType, blockData);
		if (target == null) return;
		// check to see if the block is different - otherwise leave it
		if (!thisBlock.getBlockData().equals(target)) {
			thisBlock.setBlockData(target, true);
		}
	}
	
	// gets the current player
	public Player getCurrentPlayer() {
		Player player = attachedPlayer;
		// if the player hasnt already been retreived for this session, go and get it.
		if (player == null) {
			player = plugin.getHostPlayer();
			attachedPlayer = player;
		}
		return player;
	}
	
	public Player getCurrentPlayer(String name) {
		// if a named player is returned use that
		Player player = plugin.getNamedPlayer(name);
		// otherwise if there is an attached player for this session use that
		if (player == null) {
			player = attachedPlayer;
			// otherwise go and get the host player and make that the attached player
			if (player == null) {
				player = plugin.getHostPlayer();
				attachedPlayer = player;
			}
		}
		return player;
	}


	public Location parseRelativeBlockLocation(String xstr, String ystr, String zstr) {
		// floor (not truncate-toward-zero) so negative fractional coords map to the right block
		int x = (int) Math.floor(Double.parseDouble(xstr));
		int y = (int) Math.floor(Double.parseDouble(ystr));
		int z = (int) Math.floor(Double.parseDouble(zstr));
		return parseLocation(origin.getWorld(), x, y, z, origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
	}

	public Location parseRelativeLocation(String xstr, String ystr, String zstr) {
		double x = Double.parseDouble(xstr);
		double y = Double.parseDouble(ystr);
		double z = Double.parseDouble(zstr);
		return parseLocation(origin.getWorld(), x, y, z, origin.getX(), origin.getY(), origin.getZ());
	}

	public Location parseRelativeBlockLocation(String xstr, String ystr, String zstr, float pitch, float yaw) {
		Location loc = parseRelativeBlockLocation(xstr, ystr, zstr);
		loc.setPitch(pitch);
		loc.setYaw(yaw);
		return loc;
	}

	public Location parseRelativeLocation(String xstr, String ystr, String zstr, float pitch, float yaw) {
		Location loc = parseRelativeLocation(xstr, ystr, zstr);
		loc.setPitch(pitch);
		loc.setYaw(yaw);
		return loc;
	}
	
	public String blockLocationToRelative(Location loc) {
		return parseLocation(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), origin.getBlockX(), origin.getBlockY(), origin.getBlockZ());
	}

	public String locationToRelative(Location loc) {
		return parseLocation(loc.getX(), loc.getY(), loc.getZ(), origin.getX(), origin.getY(), origin.getZ());
	}

	// ---- shared entity geometry --------------------------------------------
	// The player.* and entity.* pos/tile/direction/rotation/pitch commands differ only in how
	// the target is resolved (attached player vs. lookup-by-id) and in their null policy; the
	// geometry is identical, so it lives here once. getRotation is the sole asymmetry: the
	// player variant flips a negative yaw to positive, the entity variant does not (flipYaw).

	private String entityGetPos(Entity target) {
		return locationToRelative(target.getLocation());
	}

	private void entitySetPos(Entity target, String x, String y, String z) {
		Location loc = target.getLocation();
		// keep current pitch/yaw so teleporting only moves the target
		target.teleport(parseRelativeLocation(x, y, z, loc.getPitch(), loc.getYaw()));
	}

	private String entityGetTile(Entity target) {
		return blockLocationToRelative(target.getLocation());
	}

	private void entitySetTile(Entity target, String x, String y, String z) {
		Location loc = target.getLocation();
		target.teleport(parseRelativeBlockLocation(x, y, z, loc.getPitch(), loc.getYaw()));
	}

	private String entityGetDirection(Entity target) {
		return target.getLocation().getDirection().toString();
	}

	private void entitySetDirection(Entity target, String x, String y, String z) {
		Location loc = target.getLocation();
		loc.setDirection(new Vector(Double.parseDouble(x), Double.parseDouble(y), Double.parseDouble(z)));
		target.teleport(loc);
	}

	private float entityGetRotation(Entity target, boolean flipYaw) {
		float yaw = target.getLocation().getYaw();
		// player.getRotation turns bukkit's 0..-360 into positive numbers; entity.getRotation does not
		if (flipYaw && yaw < 0) yaw = yaw * -1;
		return yaw;
	}

	private void entitySetRotation(Entity target, String yaw) {
		Location loc = target.getLocation();
		loc.setYaw(Float.parseFloat(yaw));
		target.teleport(loc);
	}

	private float entityGetPitch(Entity target) {
		return target.getLocation().getPitch();
	}

	private void entitySetPitch(Entity target, String pitch) {
		Location loc = target.getLocation();
		loc.setPitch(Float.parseFloat(pitch));
		target.teleport(loc);
	}

	/** Logs a not-found for an entity command; some commands also answer "Fail", others stay silent. */
	private void entityNotFound(String id, boolean sendFail) {
		plugin.getLogger().info("Entity [" + id + "] not found.");
		if (sendFail) send("Fail");
	}

	private String parseLocation(double x, double y, double z, double originX, double originY, double originZ) {
		return (x - originX) + "," + (y - originY) + "," + (z - originZ);
	}

	private Location parseLocation(World world, double x, double y, double z, double originX, double originY, double originZ) {
		return new Location(world, originX + x, originY + y, originZ + z);
	}

	private String parseLocation(int x, int y, int z, int originX, int originY, int originZ) {
		return (x - originX) + "," + (y - originY) + "," + (z - originZ);
	}

	private Location parseLocation(World world, int x, int y, int z, int originX, int originY, int originZ) {
		return new Location(world, originX + x, originY + y, originZ + z);
	}

	private double getDistance(Entity ent1, Entity ent2) {
		if (ent1 == null || ent2 == null)
			return -1;
		double dx = ent2.getLocation().getX() - ent1.getLocation().getX();
		double dy = ent2.getLocation().getY() - ent1.getLocation().getY();
		double dz = ent2.getLocation().getZ() - ent1.getLocation().getZ();
		return Math.sqrt(dx*dx + dy*dy + dz*dz);
	}

	private String getEntities(World world, int entityType) {
		StringBuilder bdr = new StringBuilder();				
		for (Entity e : world.getEntities()) {
			if (((entityType == -1 && LegacyEntities.typeId(e.getType()) >= 0) || LegacyEntities.typeId(e.getType()) == entityType) && 
				e.getType().isSpawnable()) {
				bdr.append(getEntityMsg(e));
			}
		}
		return bdr.toString();
	}
	
	private String getEntities(World world, int entityId, int distance, int entityType) {
		Entity playerEntity = plugin.getEntity(entityId);
		StringBuilder bdr = new StringBuilder();
		for (Entity e : world.getEntities()) {
			if (((entityType == -1 && LegacyEntities.typeId(e.getType()) >= 0) || LegacyEntities.typeId(e.getType()) == entityType) && 
				e.getType().isSpawnable() && 
				getDistance(playerEntity, e) <= distance) {
				bdr.append(getEntityMsg(e));
			}
		}
		return bdr.toString();
	}

	private String getEntityMsg(Entity entity) {
		StringBuilder bdr = new StringBuilder();
		bdr.append(entity.getEntityId());
		bdr.append(",");
		bdr.append(LegacyEntities.typeId(entity.getType()));
		bdr.append(",");
		bdr.append(entity.getType().toString());
		bdr.append(",");
		bdr.append(entity.getLocation().getX());
		bdr.append(",");
		bdr.append(entity.getLocation().getY());
		bdr.append(",");
		bdr.append(entity.getLocation().getZ());
		bdr.append("|");
		return bdr.toString();
	}

	private int removeEntities(World world, int entityId, int distance, int entityType) {
		int removedEntitiesCount = 0;
		Entity playerEntityId = plugin.getEntity(entityId);
		for (Entity e : world.getEntities()) {
			if ((entityType == -1 || LegacyEntities.typeId(e.getType()) == entityType) && getDistance(playerEntityId, e) <= distance)
			{
				e.remove();
				removedEntitiesCount++;
			}
		}
		return removedEntitiesCount;
	}

	private String getBlockHits() {
		return getBlockHits(-1);
	}

	private String getBlockHits(int entityId) {
		StringBuilder b = new StringBuilder();
		for (Iterator<PlayerInteractEvent> iter = interactEventQueue.iterator(); iter.hasNext(); ) {
			PlayerInteractEvent event = iter.next();
			if (entityId == -1 || event.getPlayer().getEntityId() == entityId) {
				Block block = event.getClickedBlock();
				Location loc = block.getLocation();
				b.append(blockLocationToRelative(loc));
				b.append(",");
				b.append(blockFaceToNotch(event.getBlockFace()));
				b.append(",");
				b.append(event.getPlayer().getEntityId());
				b.append("|");
				iter.remove();
			}
		}
		if (b.length() > 0)
			b.deleteCharAt(b.length() - 1);

		return b.toString();
	}

	private String getChatPosts() {
		return getChatPosts(-1);
	}

	private String getChatPosts(int entityId) {
		StringBuilder b = new StringBuilder();
		for (Iterator<AsyncChatEvent> iter = chatPostedQueue.iterator(); iter.hasNext(); ) {
			AsyncChatEvent event = iter.next();
			if (entityId == -1 || event.getPlayer().getEntityId() == entityId) {
				b.append(event.getPlayer().getEntityId());
				b.append(",");
				b.append(PlainText.plain(event.message()));
				b.append("|");
				iter.remove();
			}
		}
		if (b.length() > 0)
			b.deleteCharAt(b.length() - 1);
		 return b.toString();
	}

	private String getProjectileHits() {
		return getProjectileHits(-1);
	}

	private String getProjectileHits(int entityId) {
		StringBuilder b = new StringBuilder();
		for (Iterator<ProjectileHitEvent> iter = projectileHitQueue.iterator(); iter.hasNext(); ) {
			ProjectileHitEvent event = iter.next();
			Arrow arrow = (Arrow) event.getEntity();
			LivingEntity shooter = (LivingEntity)arrow.getShooter();
			if (entityId == -1 || shooter.getEntityId() == entityId) {
				if (shooter instanceof Player) {
					Player player = (Player)shooter;
					List<Block> attachedBlocks = arrow.getAttachedBlocks();
					Block block = attachedBlocks.isEmpty()
						? arrow.getLocation().getBlock()
						: attachedBlocks.get(0);
					Location loc = block.getLocation();
					b.append(blockLocationToRelative(loc));
					b.append(",");
					b.append(1); //blockFaceToNotch(event.getBlockFace()), but don't really care
					b.append(",");
					b.append(PlainText.plain(player.playerListName()));
					b.append(",");
					Entity hitEntity = event.getHitEntity();
					if(hitEntity!=null){
						if(hitEntity instanceof Player){	
							Player hitPlayer = (Player)hitEntity;
							b.append(PlainText.plain(hitPlayer.playerListName()));
						}else{
							b.append(hitEntity.getName());
						}
					}
				}
				b.append("|");
				arrow.remove();
				iter.remove();
			}						
		}
		if (b.length() > 0)
			b.deleteCharAt(b.length() - 1);
		return b.toString();
	
	}

	private void clearEntityEvents(int entityId) {
		for (Iterator<PlayerInteractEvent> iter = interactEventQueue.iterator(); iter.hasNext(); ) {
			PlayerInteractEvent event = iter.next();
			if (event.getPlayer().getEntityId() == entityId)
				iter.remove();
		}
		for (Iterator<AsyncChatEvent> iter = chatPostedQueue.iterator(); iter.hasNext(); ) {
			AsyncChatEvent event = iter.next();
			if (event.getPlayer().getEntityId() == entityId)
				iter.remove();
		}
		for (Iterator<ProjectileHitEvent> iter = projectileHitQueue.iterator(); iter.hasNext(); ) {
			ProjectileHitEvent event = iter.next();
			Arrow arrow = (Arrow) event.getEntity();
			LivingEntity shooter = (LivingEntity)arrow.getShooter();
			if (shooter.getEntityId() == entityId)
				iter.remove();
		}
	}
				
	public void send(Object a) {
		send(String.valueOf(a));
	}

	public void send(String a) {
		if (pendingRemoval) return;
		outQueue.add(a);
	}

	/** Visible for testing: drains responses queued for the socket without starting the I/O threads. */
	java.util.List<String> drainSentForTest() {
		java.util.List<String> sent = new java.util.ArrayList<String>();
		outQueue.drainTo(sent);
		return sent;
	}

	public void close() {
		if (closed) return;
		running = false;
		pendingRemoval = true;

		//wait for threads to stop
		try {
			inThread.join(2000);
			outThread.join(2000);
		}
		catch (InterruptedException e) {
			plugin.getLogger().warning("Failed to stop in/out thread");
			e.printStackTrace();
		}

		try {
			socket.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		plugin.getLogger().info("Closed connection to" + socket.getRemoteSocketAddress() + ".");
	}

	public void kick(String reason) {
		try {
			out.write(reason);
			out.flush();
		} catch (Exception e) {
		}
		close();
	}

	/** socket listening thread */
	private class InputThread implements Runnable {
		public void run() {
			plugin.getLogger().info("Starting input thread");
			while (running) {
				try {
					String newLine = in.readLine();
					if (newLine == null) {
						running = false;
					} else {
						inQueue.add(newLine);
					}
				} catch (Exception e) {
					// if its running raise an error
					if (running) {
						if ("Connection reset".equals(e.getMessage())) {
							plugin.getLogger().info("Connection reset");
						} else {
							e.printStackTrace();
						}
						running = false;
					}
				} 
			}
			//close in buffer
			try {
				in.close();
			} catch (Exception e) {
				plugin.getLogger().warning("Failed to close in buffer");
				e.printStackTrace();
			}
		}
	}

	private class OutputThread implements Runnable {
		public void run() {
			plugin.getLogger().info("Starting output thread!");
			while (running) {
				try {
					// block until a line is available (up to 200ms) instead of busy-waiting;
					// the timeout lets the loop notice running=false on shutdown
					String line = outQueue.poll(200, TimeUnit.MILLISECONDS);
					if (line == null) continue;
					out.write(line);
					out.write('\n');
					// drain any further queued lines, then flush once
					while ((line = outQueue.poll()) != null) {
						out.write(line);
						out.write('\n');
					}
					out.flush();
				} catch (Exception e) {
					// if its running raise an error
					if (running) {
						e.printStackTrace();
						running = false;
					}
				}
			}
			//close out buffer
			try {
				out.close();
			} catch (Exception e) {
				plugin.getLogger().warning("Failed to close out buffer");
				e.printStackTrace();
			}
		}
	}

	/** from CraftBukkit's org.bukkit.craftbukkit.block.CraftBlock.blockFactToNotch */
	public static int blockFaceToNotch(BlockFace face) {
		switch (face) {
		case DOWN:
			return 0;
		case UP:
			return 1;
		case NORTH:
			return 2;
		case SOUTH:
			return 3;
		case WEST:
			return 4;
		case EAST:
			return 5;
		default:
			return 7; // Good as anything here, but technically invalid
		}
	}

}
