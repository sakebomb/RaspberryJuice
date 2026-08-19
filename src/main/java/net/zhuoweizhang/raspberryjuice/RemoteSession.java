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
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
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
import org.bukkit.entity.Mob;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
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

	// pure coordinate math bound to `origin`; rebuilt via updateOrigin() whenever origin changes
	private RelativeGeometry geometry;

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

	// #13 reactive events, snapshotted at queue time (raw Bukkit events are transient/mutable).
	// LinkedBlockingQueue (O(1) size) so they can be bounded cheaply - see enqueueEvent.
	private static final int MAX_EVENTS_PER_QUEUE = 10000;
	protected java.util.Queue<RecordedEvent> moveQueue = new LinkedBlockingQueue<RecordedEvent>();
	protected java.util.Queue<RecordedEvent> blockPlaceQueue = new LinkedBlockingQueue<RecordedEvent>();
	protected java.util.Queue<RecordedEvent> blockBreakQueue = new LinkedBlockingQueue<RecordedEvent>();
	protected java.util.Queue<RecordedEvent> deathQueue = new LinkedBlockingQueue<RecordedEvent>();

	/** Snapshot of a player-triggered event: a location, the player name, and an optional block id (-1 = none). */
	static final class RecordedEvent {
		final Location loc;
		final String playerName;
		final int blockId;
		RecordedEvent(Location loc, String playerName, int blockId) {
			this.loc = loc;
			this.playerName = playerName;
			this.blockId = blockId;
		}
	}

	private int maxCommandsPerTick = DEFAULT_MAX_COMMANDS_PER_TICK;

	// cumulative blocks consumed by cuboid ops (getBlocks/setBlocks/clone) in the current tick;
	// reset to 0 at the top of every tick() and charged against getMaxBlocksPerTick().
	private long blocksUsedThisTick = 0;

	private volatile boolean closed = false;

	private Player attachedPlayer = null;

	// The player this connection has EXPLICITLY bound to via setPlayer(name), by UUID (stable across
	// relogs; a UUID compare is cheap enough for the per-event scoping hot path). null until bound.
	// Distinct from attachedPlayer, which latches to the host (first-online) player for command
	// execution: only an explicit bind is trusted for scoping reactive events (see isForCurrentPlayer).
	private java.util.UUID boundPlayerId = null;

	// the per-session programmable agent (turtle), null until agent.spawn() is called
	private Agent agent = null;

	// ids of entities THIS session spawned (via world.spawnEntity). Only the owning session may
	// MUTATE an entity - so one client can't kill/move another client's mobs. Reads are open.
	// Main-thread-only access (all commands run on the tick), so a plain HashSet is fine.
	protected final java.util.Set<Integer> ownedEntities = new java.util.HashSet<Integer>();

	// optional shared-secret auth (config auth-token): true from the start when no token is set
	private boolean authenticated = true;
	private int authFailures = 0;
	private static final int MAX_AUTH_FAILURES = 3;

	/** One protocol command's parsing + execution. Bodies throw only unchecked exceptions, which
	 *  handleCommand's outer catch turns into a "Fail" response - same as the former handle* chain. */
	@FunctionalInterface
	interface CommandHandler {
		void handle(String[] args, World world, Server server);
	}

	// Command name -> handler, replacing the old seven-way if/else dispatch ladder (#46). Built once
	// per session from method references; lookup in handleCommand is O(1).
	private final java.util.Map<String, CommandHandler> commandRegistry = buildCommandRegistry();

	public RemoteSession(RaspberryJuicePlugin plugin, Socket socket) throws IOException {
		this.socket = socket;
		this.plugin = plugin;
		this.locationType = plugin.getLocationType();
		String token = plugin.getAuthToken();
		this.authenticated = (token == null || token.isEmpty()); // require auth only if a token is configured
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

	// the session's coordinate math, bound to the current origin (see updateOrigin)
	RelativeGeometry geometry() {
		return geometry;
	}

	public void setOrigin(Location origin) {
		updateOrigin(origin);
	}

	// the sole writer of `origin` - keeps origin and its geometry in lockstep
	private void updateOrigin(Location o) {
		this.origin = o;
		this.geometry = new RelativeGeometry(o);
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

	public void queuePlayerMove(Player p, Location to) {
		enqueueEvent(moveQueue, new RecordedEvent(to.clone(), PlainText.plain(p.playerListName()), -1));
	}

	public void queueBlockBreak(Player p, Block block) {
		enqueueEvent(blockBreakQueue, new RecordedEvent(block.getLocation(), PlainText.plain(p.playerListName()), LegacyBlocks.legacyId(block)));
	}

	public void queueBlockPlace(Player p, Block block) {
		enqueueEvent(blockPlaceQueue, new RecordedEvent(block.getLocation(), PlainText.plain(p.playerListName()), LegacyBlocks.legacyId(block)));
	}

	public void queuePlayerDeath(Player p) {
		enqueueEvent(deathQueue, new RecordedEvent(p.getLocation(), PlainText.plain(p.playerListName()), -1));
	}

	/** Adds an event, dropping the oldest first so an unpolled queue can't exhaust memory. */
	private void enqueueEvent(java.util.Queue<RecordedEvent> queue, RecordedEvent e) {
		while (queue.size() >= MAX_EVENTS_PER_QUEUE) queue.poll();
		queue.add(e);
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
					updateOrigin(new Location(plugin.getServer().getWorlds().get(0), 0, 0, 0));
					break;
				case RELATIVE:
					updateOrigin(plugin.getServer().getWorlds().get(0).getSpawnLocation());
					break;
				default:
					throw new IllegalArgumentException("Unknown location type " + locationType);
			}
		}
		blocksUsedThisTick = 0; // reset the per-tick cuboid budget before draining the queue
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

		// optional auth handshake: auth(<token>) is always handled; until authenticated, every
		// other command is rejected (so an unauthorized connection can do nothing).
		if (c.equals("auth")) {
			tryAuth(args);
			return;
		}
		if (!authenticated) {
			send("Fail");
			return;
		}

		try {
			// get the server
			Server server = plugin.getServer();

			// get the world
			World world = origin.getWorld();
			// O(1) registry lookup replaces the former seven-way handle*() if/else ladder (#46).
			CommandHandler handler = commandRegistry.get(c);
			if (handler == null) {
				plugin.getLogger().warning(c + " is not supported.");
				send("Fail");
				return;
			}
			handler.handle(args, world, server);
		} catch (Exception e) {
			//log with the offending command and full context instead of dumping to stdout
			plugin.getLogger().log(java.util.logging.Level.WARNING, "Error handling command: " + c, e);
			send("Fail");
		}
	}

	/** Handle the auth(<token>) handshake: constant-time compare, kick after too many failures. */
	private void tryAuth(String[] args) {
		if (authenticated) {
			send("1");
			return;
		}
		String provided = args.length > 0 ? args[0] : "";
		byte[] a = provided.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] b = plugin.getAuthToken().getBytes(java.nio.charset.StandardCharsets.UTF_8);
		if (java.security.MessageDigest.isEqual(a, b)) {
			authenticated = true;
			send("1");
		} else {
			authFailures++;
			send("Fail");
			if (authFailures >= MAX_AUTH_FAILURES) {
				plugin.getLogger().warning("Closing " + socket.getRemoteSocketAddress()
					+ " after " + authFailures + " failed auth attempts.");
				close();
			}
		}
	}

	// Builds the command name -> handler map (#46). Grouped by domain for readability; a
	// LinkedHashMap keeps a stable, greppable order. Each value is a method reference to a small
	// cmd* method holding what was previously one branch of a handle* if/else ladder.
	private java.util.Map<String, CommandHandler> buildCommandRegistry() {
		java.util.Map<String, CommandHandler> r = new java.util.LinkedHashMap<>();

		// world.* blocks + bulk-entity queries
		r.put("world.getBlock", this::cmdWorldGetBlock);
		r.put("world.getBlocks", this::cmdWorldGetBlocks);
		r.put("world.getBlockWithData", this::cmdWorldGetBlockWithData);
		r.put("world.setBlock", this::cmdWorldSetBlock);
		r.put("world.setBlocks", this::cmdWorldSetBlocks);
		r.put("world.getPlayerIds", this::cmdWorldGetPlayerIds);
		r.put("world.getPlayerId", this::cmdWorldGetPlayerId);
		r.put("entity.getName", this::cmdEntityGetName);
		r.put("world.getEntities", this::cmdWorldGetEntities);
		r.put("world.removeEntity", this::cmdWorldRemoveEntity);
		r.put("world.removeEntities", this::cmdWorldRemoveEntities);
		r.put("world.getHeight", this::cmdWorldGetHeight);
		r.put("world.setSign", this::cmdWorldSetSign);
		r.put("world.spawnEntity", this::cmdWorldSpawnEntity);
		r.put("world.getEntityTypes", this::cmdWorldGetEntityTypes);

		// world & player control (#15)
		r.put("world.setTime", this::cmdWorldSetTime);
		r.put("world.getTime", this::cmdWorldGetTime);
		r.put("world.setWeather", this::cmdWorldSetWeather);
		r.put("world.clone", this::cmdWorldClone);
		r.put("player.setGameMode", this::cmdPlayerSetGameMode);
		r.put("player.give", this::cmdPlayerGive);

		// chat + session identity
		r.put("chat.post", this::cmdChatPost);
		r.put("setPlayer", this::cmdSetPlayer);

		// event polls: global, per-entity, per-player, and the #13 snapshot streams
		r.put("events.clear", this::cmdEventsClear);
		r.put("events.block.hits", this::cmdEventsBlockHits);
		r.put("events.chat.posts", this::cmdEventsChatPosts);
		r.put("events.projectile.hits", this::cmdEventsProjectileHits);
		r.put("entity.events.clear", this::cmdEntityEventsClear);
		r.put("entity.events.block.hits", this::cmdEntityEventsBlockHits);
		r.put("entity.events.chat.posts", this::cmdEntityEventsChatPosts);
		r.put("entity.events.projectile.hits", this::cmdEntityEventsProjectileHits);
		r.put("player.events.block.hits", this::cmdPlayerEventsBlockHits);
		r.put("player.events.chat.posts", this::cmdPlayerEventsChatPosts);
		r.put("player.events.projectile.hits", this::cmdPlayerEventsProjectileHits);
		r.put("player.events.clear", this::cmdPlayerEventsClear);
		r.put("events.player.moves", this::cmdEventsPlayerMoves);
		r.put("events.block.places", this::cmdEventsBlockPlaces);
		r.put("events.block.breaks", this::cmdEventsBlockBreaks);
		r.put("events.player.deaths", this::cmdEventsPlayerDeaths);

		// player.* pose + queries
		r.put("player.getTile", this::cmdPlayerGetTile);
		r.put("player.setTile", this::cmdPlayerSetTile);
		r.put("player.getAbsPos", this::cmdPlayerGetAbsPos);
		r.put("player.setAbsPos", this::cmdPlayerSetAbsPos);
		r.put("player.getPos", this::cmdPlayerGetPos);
		r.put("player.setPos", this::cmdPlayerSetPos);
		r.put("player.setDirection", this::cmdPlayerSetDirection);
		r.put("player.getDirection", this::cmdPlayerGetDirection);
		r.put("player.setRotation", this::cmdPlayerSetRotation);
		r.put("player.getRotation", this::cmdPlayerGetRotation);
		r.put("player.setPitch", this::cmdPlayerSetPitch);
		r.put("player.getPitch", this::cmdPlayerGetPitch);
		r.put("player.getEntities", this::cmdPlayerGetEntities);
		r.put("player.removeEntities", this::cmdPlayerRemoveEntities);

		// entity.* pose + queries (id-targeted; mutators are ownership-gated via controllableEntity)
		r.put("entity.getTile", this::cmdEntityGetTile);
		r.put("entity.setTile", this::cmdEntitySetTile);
		r.put("entity.getPos", this::cmdEntityGetPos);
		r.put("entity.setPos", this::cmdEntitySetPos);
		r.put("entity.setDirection", this::cmdEntitySetDirection);
		r.put("entity.getDirection", this::cmdEntityGetDirection);
		r.put("entity.setRotation", this::cmdEntitySetRotation);
		r.put("entity.getRotation", this::cmdEntityGetRotation);
		r.put("entity.setPitch", this::cmdEntitySetPitch);
		r.put("entity.getPitch", this::cmdEntityGetPitch);
		r.put("entity.getEntities", this::cmdEntityGetEntities);
		r.put("entity.removeEntities", this::cmdEntityRemoveEntities);

		// entity/mob control (#14)
		r.put("entity.moveTo", this::cmdEntityMoveTo);
		r.put("entity.lookAt", this::cmdEntityLookAt);
		r.put("entity.getHealth", this::cmdEntityGetHealth);
		r.put("entity.setHealth", this::cmdEntitySetHealth);
		r.put("entity.setName", this::cmdEntitySetName);
		r.put("entity.setAI", this::cmdEntitySetAI);

		// programmable agent / turtle
		r.put("agent.spawn", this::cmdAgentSpawn);
		r.put("agent.despawn", this::cmdAgentDespawn);
		r.put("agent.getPos", this::cmdAgentGetPos);
		r.put("agent.getRotation", this::cmdAgentGetRotation);
		r.put("agent.forward", this::cmdAgentForward);
		r.put("agent.back", this::cmdAgentBack);
		r.put("agent.up", this::cmdAgentUp);
		r.put("agent.down", this::cmdAgentDown);
		r.put("agent.turnLeft", this::cmdAgentTurnLeft);
		r.put("agent.turnRight", this::cmdAgentTurnRight);
		r.put("agent.setBlock", this::cmdAgentSetBlock);

		return java.util.Collections.unmodifiableMap(r);
	}

	// ==== command handlers: world blocks + bulk-entity queries ====

	void cmdWorldGetBlock(String[] args, World world, Server server) {
		Location loc = geometry.parseRelativeBlockLocation(args[0], args[1], args[2]);
		send(LegacyBlocks.legacyId(world.getBlockAt(loc)));
	}

	void cmdWorldGetBlocks(String[] args, World world, Server server) {
		Location loc1 = geometry.parseRelativeBlockLocation(args[0], args[1], args[2]);
		Location loc2 = geometry.parseRelativeBlockLocation(args[3], args[4], args[5]);
		if (exceedsBlockLimit(loc1, loc2)) {
			plugin.getLogger().warning("world.getBlocks request of " + RelativeGeometry.blockVolume(loc1, loc2)
				+ " blocks exceeds max-blocks (" + plugin.getMaxBlocks() + "); rejected.");
			send("Fail");
		} else if (!reserveBlockBudget(RelativeGeometry.blockVolume(loc1, loc2))) {
			plugin.getLogger().warning("world.getBlocks request of " + RelativeGeometry.blockVolume(loc1, loc2)
				+ " blocks exceeds the per-tick budget (max-blocks-per-tick="
				+ plugin.getMaxBlocksPerTick() + "); rejected.");
			send("Fail");
		} else {
			send(getBlocks(loc1, loc2));
		}
	}

	void cmdWorldGetBlockWithData(String[] args, World world, Server server) {
		Location loc = geometry.parseRelativeBlockLocation(args[0], args[1], args[2]);
		Block block = world.getBlockAt(loc);
		send(LegacyBlocks.legacyId(block) + "," + LegacyBlocks.legacyData(block));
	}

	void cmdWorldSetBlock(String[] args, World world, Server server) {
		Location loc = geometry.parseRelativeBlockLocation(args[0], args[1], args[2]);
		updateBlock(world, loc, Integer.parseInt(args[3]), (args.length > 4? Byte.parseByte(args[4]) : (byte) 0));
	}

	void cmdWorldSetBlocks(String[] args, World world, Server server) {
		Location loc1 = geometry.parseRelativeBlockLocation(args[0], args[1], args[2]);
		Location loc2 = geometry.parseRelativeBlockLocation(args[3], args[4], args[5]);
		int blockType = Integer.parseInt(args[6]);
		byte data = args.length > 7? Byte.parseByte(args[7]) : (byte) 0;
		if (exceedsBlockLimit(loc1, loc2)) {
			plugin.getLogger().warning("world.setBlocks request of " + RelativeGeometry.blockVolume(loc1, loc2)
				+ " blocks exceeds max-blocks (" + plugin.getMaxBlocks() + "); rejected.");
		} else if (!reserveBlockBudget(RelativeGeometry.blockVolume(loc1, loc2))) {
			plugin.getLogger().warning("world.setBlocks request of " + RelativeGeometry.blockVolume(loc1, loc2)
				+ " blocks exceeds the per-tick budget (max-blocks-per-tick="
				+ plugin.getMaxBlocksPerTick() + "); rejected.");
		} else {
			setCuboid(loc1, loc2, blockType, data);
		}
	}

	void cmdWorldGetPlayerIds(String[] args, World world, Server server) {
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
	}

	void cmdWorldGetPlayerId(String[] args, World world, Server server) {
		Player p = plugin.getNamedPlayer(args[0]);
		if (p != null) {
			send(p.getEntityId());
		} else {
			plugin.getLogger().info("Player [" + args[0] + "] not found.");
			send("Fail");
		}
	}

	void cmdEntityGetName(String[] args, World world, Server server) {
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
	}

	void cmdWorldGetEntities(String[] args, World world, Server server) {
		int entityType = Integer.parseInt(args[0]);
		send(getEntities(world, entityType));
	}

	void cmdWorldRemoveEntity(String[] args, World world, Server server) {
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
	}

	void cmdWorldRemoveEntities(String[] args, World world, Server server) {
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
	}

	// ==== command handlers: chat + session identity ====

	void cmdChatPost(String[] args, World world, Server server) {
		//create chat message from args as it was split by ,
		String chatMessage = "";
		int count;
		for(count=0;count<args.length;count++){
			chatMessage = chatMessage + args[count] + ",";
		}
		chatMessage = chatMessage.substring(0, chatMessage.length() - 1);
		//interpret legacy section (§) colour codes so chat.post renders as it did with broadcastMessage(String)
		server.broadcast(PlainText.legacy(chatMessage));
	}

	// setPlayer(name): bind this connection to a named online player. Drives both command
	// execution (attachedPlayer) and reactive-event scoping (boundPlayerId, matched by UUID
	// so it survives relogs). Replies "1" on success, "Fail" if that player isn't online. #44
	void cmdSetPlayer(String[] args, World world, Server server) {
		Player target = plugin.getNamedPlayer(args.length > 0 ? args[0] : null);
		if (target != null) {
			attachedPlayer = target;
			boundPlayerId = target.getUniqueId();
			send("1");
		} else {
			send("Fail");
		}
	}

	// ==== command handlers: event polls (global / per-entity / per-player) ====

	void cmdEventsClear(String[] args, World world, Server server) {
		interactEventQueue.clear();
		chatPostedQueue.clear();
		projectileHitQueue.clear();
		moveQueue.clear();
		blockPlaceQueue.clear();
		blockBreakQueue.clear();
		deathQueue.clear();
	}

	void cmdEventsBlockHits(String[] args, World world, Server server) {
		send(getBlockHits());
	}

	void cmdEventsChatPosts(String[] args, World world, Server server) {
		send(getChatPosts());
	}

	void cmdEventsProjectileHits(String[] args, World world, Server server) {
		send(getProjectileHits());
	}

	void cmdEntityEventsClear(String[] args, World world, Server server) {
		int entityId = Integer.parseInt(args[0]);
		clearEntityEvents(entityId);
	}

	void cmdEntityEventsBlockHits(String[] args, World world, Server server) {
		int entityId = Integer.parseInt(args[0]);
		send(getBlockHits(entityId));
	}

	void cmdEntityEventsChatPosts(String[] args, World world, Server server) {
		int entityId = Integer.parseInt(args[0]);
		send(getChatPosts(entityId));
	}

	void cmdEntityEventsProjectileHits(String[] args, World world, Server server) {
		int entityId = Integer.parseInt(args[0]);
		send(getProjectileHits(entityId));
	}

	void cmdPlayerEventsBlockHits(String[] args, World world, Server server) {
		Player currentPlayer = getCurrentPlayer();
		send(getBlockHits(currentPlayer.getEntityId()));
	}

	void cmdPlayerEventsChatPosts(String[] args, World world, Server server) {
		Player currentPlayer = getCurrentPlayer();
		send(getChatPosts(currentPlayer.getEntityId()));
	}

	void cmdPlayerEventsProjectileHits(String[] args, World world, Server server) {
		Player currentPlayer = getCurrentPlayer();
		send(getProjectileHits(currentPlayer.getEntityId()));
	}

	void cmdPlayerEventsClear(String[] args, World world, Server server) {
		Player currentPlayer = getCurrentPlayer();
		clearEntityEvents(currentPlayer.getEntityId());
	}

	// ==== command handlers: player.* pose + queries ====

	void cmdPlayerGetTile(String[] args, World world, Server server) {
		send(entityGetTile(getCurrentPlayer()));
	}

	void cmdPlayerSetTile(String[] args, World world, Server server) {
		entitySetTile(getCurrentPlayer(), args[0], args[1], args[2]);
	}

	void cmdPlayerGetAbsPos(String[] args, World world, Server server) {
		Player currentPlayer = getCurrentPlayer();
		//send absolute coordinates as "x,y,z" (not Location.toString())
		Location loc = currentPlayer.getLocation();
		send(loc.getX() + "," + loc.getY() + "," + loc.getZ());
	}

	void cmdPlayerSetAbsPos(String[] args, World world, Server server) {
		String x = args[0], y = args[1], z = args[2];
		Player currentPlayer = getCurrentPlayer();
		//get players current location, so when they are moved we will use the same pitch and yaw (rotation)
		Location loc = currentPlayer.getLocation();
		loc.setX(Double.parseDouble(x));
		loc.setY(Double.parseDouble(y));
		loc.setZ(Double.parseDouble(z));
		currentPlayer.teleport(loc);
	}

	void cmdPlayerGetPos(String[] args, World world, Server server) {
		send(entityGetPos(getCurrentPlayer()));
	}

	void cmdPlayerSetPos(String[] args, World world, Server server) {
		entitySetPos(getCurrentPlayer(), args[0], args[1], args[2]);
	}

	void cmdPlayerSetDirection(String[] args, World world, Server server) {
		entitySetDirection(getCurrentPlayer(), args[0], args[1], args[2]);
	}

	void cmdPlayerGetDirection(String[] args, World world, Server server) {
		send(entityGetDirection(getCurrentPlayer()));
	}

	void cmdPlayerSetRotation(String[] args, World world, Server server) {
		entitySetRotation(getCurrentPlayer(), args[0]);
	}

	// player.getRotation flips a negative yaw to positive (flipYaw=true) - the sole player-vs-entity
	// asymmetry; entity.getRotation passes false.
	void cmdPlayerGetRotation(String[] args, World world, Server server) {
		send(entityGetRotation(getCurrentPlayer(), true));
	}

	void cmdPlayerSetPitch(String[] args, World world, Server server) {
		entitySetPitch(getCurrentPlayer(), args[0]);
	}

	void cmdPlayerGetPitch(String[] args, World world, Server server) {
		send(entityGetPitch(getCurrentPlayer()));
	}

	void cmdPlayerGetEntities(String[] args, World world, Server server) {
		Player currentPlayer = getCurrentPlayer();
		int distance = Integer.parseInt(args[0]);
		int entityTypeId = Integer.parseInt(args[1]);

		send(getEntities(world, currentPlayer.getEntityId(), distance, entityTypeId));
	}

	void cmdPlayerRemoveEntities(String[] args, World world, Server server) {
		Player currentPlayer = getCurrentPlayer();
		int distance = Integer.parseInt(args[0]);
		int entityType = Integer.parseInt(args[1]);

		send(removeEntities(world, currentPlayer.getEntityId(), distance, entityType));
	}

	// ==== command handlers: entity.* pose + queries, world height/sign/spawn ====
	// "get" queries resolve any entity by id (reads are open); "set" mutators go through
	// controllableEntity (ownership-gated, players excluded). setTile/setPos send "Fail" on a
	// missing/uncontrollable entity; setDirection/setRotation/setPitch stay silent (entitySkipped false).

	void cmdWorldGetHeight(String[] args, World world, Server server) {
		send(world.getHighestBlockYAt(geometry.parseRelativeBlockLocation(args[0], "0", args[1])) - origin.getBlockY());
	}

	void cmdEntityGetTile(String[] args, World world, Server server) {
		Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
		if (entity != null) send(entityGetTile(entity));
		else entitySkipped("entity.getTile", args[0], true);
	}

	void cmdEntitySetTile(String[] args, World world, Server server) {
		Entity entity = controllableEntity(args[0]);
		if (entity != null) entitySetTile(entity, args[1], args[2], args[3]);
		else entitySkipped("entity.setTile", args[0], true);
	}

	void cmdEntityGetPos(String[] args, World world, Server server) {
		Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
		if (entity != null) send(entityGetPos(entity));
		else entitySkipped("entity.getPos", args[0], true);
	}

	void cmdEntitySetPos(String[] args, World world, Server server) {
		Entity entity = controllableEntity(args[0]);
		if (entity != null) entitySetPos(entity, args[1], args[2], args[3]);
		else entitySkipped("entity.setPos", args[0], true);
	}

	void cmdEntitySetDirection(String[] args, World world, Server server) {
		Entity entity = controllableEntity(args[0]);
		if (entity != null) entitySetDirection(entity, args[1], args[2], args[3]);
		else entitySkipped("entity.setDirection", args[0], false);
	}

	void cmdEntityGetDirection(String[] args, World world, Server server) {
		Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
		if (entity != null) send(entityGetDirection(entity));
		else entitySkipped("entity.getDirection", args[0], true);
	}

	void cmdEntitySetRotation(String[] args, World world, Server server) {
		Entity entity = controllableEntity(args[0]);
		if (entity != null) entitySetRotation(entity, args[1]);
		else entitySkipped("entity.setRotation", args[0], false);
	}

	void cmdEntityGetRotation(String[] args, World world, Server server) {
		Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
		if (entity != null) send(entityGetRotation(entity, false));
		else entitySkipped("entity.getRotation", args[0], true);
	}

	void cmdEntitySetPitch(String[] args, World world, Server server) {
		Entity entity = controllableEntity(args[0]);
		if (entity != null) entitySetPitch(entity, args[1]);
		else entitySkipped("entity.setPitch", args[0], false);
	}

	void cmdEntityGetPitch(String[] args, World world, Server server) {
		Entity entity = plugin.getEntity(Integer.parseInt(args[0]));
		if (entity != null) send(entityGetPitch(entity));
		else entitySkipped("entity.getPitch", args[0], true);
	}

	void cmdEntityGetEntities(String[] args, World world, Server server) {
		int entityId = Integer.parseInt(args[0]);
		int distance = Integer.parseInt(args[1]);
		int entityTypeId = Integer.parseInt(args[2]);

		send(getEntities(world, entityId, distance, entityTypeId));
	}

	void cmdEntityRemoveEntities(String[] args, World world, Server server) {
		int entityId = Integer.parseInt(args[0]);
		int distance = Integer.parseInt(args[1]);
		int entityType = Integer.parseInt(args[2]);

		send(removeEntities(world, entityId, distance, entityType));
	}

	void cmdWorldSetSign(String[] args, World world, Server server) {
		Location loc = geometry.parseRelativeBlockLocation(args[0], args[1], args[2]);
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
	}

	void cmdWorldSpawnEntity(String[] args, World world, Server server) {
		Location loc = geometry.parseRelativeBlockLocation(args[0], args[1], args[2]);
		Entity entity = world.spawnEntity(loc, LegacyEntities.fromId(Integer.parseInt(args[3])));
		ownedEntities.add(entity.getEntityId()); // this session owns what it spawns
		send(entity.getEntityId());
	}

	void cmdWorldGetEntityTypes(String[] args, World world, Server server) {
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
	}

	// ==== command handlers: reactive event polls (#13) ====
	// player moves / block places / block breaks / player deaths - same drain-on-poll model as events.*

	void cmdEventsPlayerMoves(String[] args, World world, Server server) {
		send(drainEvents(moveQueue));
	}

	void cmdEventsBlockPlaces(String[] args, World world, Server server) {
		send(drainEvents(blockPlaceQueue));
	}

	void cmdEventsBlockBreaks(String[] args, World world, Server server) {
		send(drainEvents(blockBreakQueue));
	}

	void cmdEventsPlayerDeaths(String[] args, World world, Server server) {
		send(drainEvents(deathQueue));
	}

	/** Drain a snapshot-event queue to "x,y,z[,blockId],name" records joined by "|" (relative coords). */
	private String drainEvents(java.util.Queue<RecordedEvent> queue) {
		StringBuilder b = new StringBuilder();
		for (java.util.Iterator<RecordedEvent> it = queue.iterator(); it.hasNext(); ) {
			RecordedEvent e = it.next();
			b.append(geometry.blockLocationToRelative(e.loc));
			if (e.blockId >= 0) b.append(",").append(e.blockId);
			b.append(",").append(e.playerName).append("|");
			it.remove();
		}
		if (b.length() > 0) b.deleteCharAt(b.length() - 1);
		return b.toString();
	}

	// ==== command handlers: world & player control (#15) ====

	void cmdWorldSetTime(String[] args, World world, Server server) {
		world.setTime(Long.parseLong(args[0]));
	}

	void cmdWorldGetTime(String[] args, World world, Server server) {
		send(world.getTime());
	}

	// world.setWeather(0=clear, 1=rain, 2=thunder)
	void cmdWorldSetWeather(String[] args, World world, Server server) {
		int w = Integer.parseInt(args[0]);
		world.setStorm(w >= 1);
		world.setThundering(w >= 2);
	}

	// world.clone(x1,y1,z1,x2,y2,z2,dx,dy,dz) - copy a cuboid to a destination corner
	void cmdWorldClone(String[] args, World world, Server server) {
		Location a = geometry.parseRelativeBlockLocation(args[0], args[1], args[2]);
		Location b = geometry.parseRelativeBlockLocation(args[3], args[4], args[5]);
		Location dest = geometry.parseRelativeBlockLocation(args[6], args[7], args[8]);
		if (exceedsBlockLimit(a, b)) {
			// silent like world.setBlocks - clone is fire-and-forget, a stray "Fail" would desync the client
			plugin.getLogger().warning("world.clone of " + RelativeGeometry.blockVolume(a, b)
				+ " blocks exceeds max-blocks (" + plugin.getMaxBlocks() + "); rejected.");
		} else if (!reserveBlockBudget(RelativeGeometry.blockVolume(a, b))) {
			// reserve before cloneRegion allocates its snapshot arrays, so a flood of clones -
			// or a huge clone under max-blocks=0 - can't OOM the tick
			plugin.getLogger().warning("world.clone of " + RelativeGeometry.blockVolume(a, b)
				+ " blocks exceeds the per-tick budget (max-blocks-per-tick="
				+ plugin.getMaxBlocksPerTick() + "); rejected.");
		} else {
			cloneRegion(world, a, b, dest);
		}
	}

	// player.setGameMode(0=survival,1=creative,2=adventure,3=spectator)
	void cmdPlayerSetGameMode(String[] args, World world, Server server) {
		if (!plugin.isOpCommandsEnabled()) return; // gated by enable-op-commands
		GameMode gm = gameMode(Integer.parseInt(args[0]));
		if (gm != null) getCurrentPlayer().setGameMode(gm);
	}

	// player.give(blockId[,count]) - give the current player blocks
	void cmdPlayerGive(String[] args, World world, Server server) {
		if (!plugin.isOpCommandsEnabled()) return; // gated by enable-op-commands
		BlockData bd = LegacyBlocks.toBlockData(Integer.parseInt(args[0]), (byte) 0);
		if (bd != null) {
			int count = (args.length > 1 && !args[1].isEmpty()) ? Integer.parseInt(args[1]) : 1;
			getCurrentPlayer().getInventory().addItem(new ItemStack(bd.getMaterial(), count));
		}
	}

	/** Copy the cuboid [a..b] to the destination corner. Snapshots first so overlaps are safe. */
	private void cloneRegion(World world, Location a, Location b, Location dest) {
		int minX = Math.min(a.getBlockX(), b.getBlockX());
		int maxX = Math.max(a.getBlockX(), b.getBlockX());
		int minY = Math.min(a.getBlockY(), b.getBlockY());
		int maxY = Math.max(a.getBlockY(), b.getBlockY());
		int minZ = Math.min(a.getBlockZ(), b.getBlockZ());
		int maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
		int sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
		int[] ids = new int[sx * sy * sz];
		byte[] datas = new byte[sx * sy * sz];
		int i = 0;
		for (int y = 0; y < sy; y++) {
			for (int x = 0; x < sx; x++) {
				for (int z = 0; z < sz; z++) {
					Block src = world.getBlockAt(minX + x, minY + y, minZ + z);
					ids[i] = LegacyBlocks.legacyId(src);
					datas[i] = LegacyBlocks.legacyData(src);
					i++;
				}
			}
		}
		int dx = dest.getBlockX(), dy = dest.getBlockY(), dz = dest.getBlockZ();
		i = 0;
		for (int y = 0; y < sy; y++) {
			for (int x = 0; x < sx; x++) {
				for (int z = 0; z < sz; z++) {
					updateBlock(world, dx + x, dy + y, dz + z, ids[i], datas[i]);
					i++;
				}
			}
		}
	}

	private GameMode gameMode(int v) {
		switch (v) {
			case 0: return GameMode.SURVIVAL;
			case 1: return GameMode.CREATIVE;
			case 2: return GameMode.ADVENTURE;
			case 3: return GameMode.SPECTATOR;
			default: return null;
		}
	}

	// ==== command handlers: entity/mob control (#14) ====
	// drive spawned entities - pathfinding movement, facing, health, name, AI toggle. "set" commands
	// are fire-and-forget (log-and-skip on a missing/unsuitable entity, never send a stray response);
	// entity.getHealth is a query and answers a value or "Fail".

	// entity.moveTo(id,x,y,z) - walk the mob to a point using real pathfinding
	void cmdEntityMoveTo(String[] args, World world, Server server) {
		Entity e = controllableEntity(args[0]);
		if (e instanceof Mob mob) {
			mob.getPathfinder().moveTo(geometry.parseRelativeLocation(args[1], args[2], args[3]));
		} else {
			entitySkipped("entity.moveTo", args[0], false);
		}
	}

	// entity.lookAt(id,x,y,z) - face a point
	void cmdEntityLookAt(String[] args, World world, Server server) {
		Entity e = controllableEntity(args[0]);
		if (e != null) {
			Location loc = e.getLocation();
			Vector dir = geometry.parseRelativeLocation(args[1], args[2], args[3]).toVector().subtract(loc.toVector());
			if (dir.lengthSquared() > 0) {
				loc.setDirection(dir);
				e.teleport(loc);
			}
		} else {
			entitySkipped("entity.lookAt", args[0], false);
		}
	}

	void cmdEntityGetHealth(String[] args, World world, Server server) {
		Entity e = plugin.getEntity(Integer.parseInt(args[0]));
		if (e instanceof LivingEntity le) {
			send(le.getHealth());
		} else {
			send("Fail");
		}
	}

	// entity.setHealth(id,health) - clamped to [0, max]
	void cmdEntitySetHealth(String[] args, World world, Server server) {
		Entity e = controllableEntity(args[0]);
		if (e instanceof LivingEntity le) {
			double requested = Double.parseDouble(args[1]);
			if (!Double.isFinite(requested)) return; // ignore NaN/Infinity
			double health = Math.max(0.0, Math.min(requested, maxHealth(le)));
			le.setHealth(health);
		} else {
			entitySkipped("entity.setHealth", args[0], false);
		}
	}

	// entity.setName(id,name) - visible name tag (name is a single token, no commas)
	void cmdEntitySetName(String[] args, World world, Server server) {
		Entity e = controllableEntity(args[0]);
		if (e != null) {
			e.customName(Component.text(args[1]));
			e.setCustomNameVisible(true);
		} else {
			entitySkipped("entity.setName", args[0], false);
		}
	}

	// entity.setAI(id,0|1) - freeze/unfreeze the mob's AI
	void cmdEntitySetAI(String[] args, World world, Server server) {
		Entity e = controllableEntity(args[0]);
		if (e instanceof LivingEntity le) {
			le.setAI(args[1].equals("1") || args[1].equalsIgnoreCase("true"));
		} else {
			entitySkipped("entity.setAI", args[0], false);
		}
	}

	/** Max health from the entity's attribute, falling back to its current health. */
	private double maxHealth(LivingEntity le) {
		AttributeInstance a = le.getAttribute(Attribute.MAX_HEALTH);
		return a != null ? a.getValue() : le.getHealth();
	}

	/** Resolves an entity by id for id-targeted MUTATION, refusing players so a client can't
	 *  kill/teleport/harass players by id (use the self-targeted player.* commands instead). */
	private org.bukkit.entity.Entity controllableEntity(String idArg) {
		int id = Integer.parseInt(idArg);
		// only entities this session spawned may be mutated (per-session ownership), and never players
		if (!ownedEntities.contains(id)) return null;
		org.bukkit.entity.Entity e = plugin.getEntity(id);
		return (e instanceof Player) ? null : e;
	}

	// ==== command handlers: programmable agent / turtle ====
	// a per-session marker driven by relative commands. Movement/query before agent.spawn() answers
	// "Fail" (via requireAgent); agent.* is an additive namespace so existing mcpi clients are unaffected.

	// agent.spawn - at given block, else at the current player
	void cmdAgentSpawn(String[] args, World world, Server server) {
		int bx, by, bz;
		float yaw;
		if (args.length >= 3 && !args[0].isEmpty()) {
			Location loc = geometry.parseRelativeBlockLocation(args[0], args[1], args[2]);
			bx = loc.getBlockX(); by = loc.getBlockY(); bz = loc.getBlockZ();
			yaw = 0f;
		} else {
			Location loc = getCurrentPlayer().getLocation();
			bx = loc.getBlockX(); by = loc.getBlockY(); bz = loc.getBlockZ();
			yaw = loc.getYaw();
		}
		if (agent != null) agent.remove();
		agent = Agent.spawn(plugin, world, bx, by, bz, yaw);
	}

	void cmdAgentDespawn(String[] args, World world, Server server) {
		if (agent != null) { agent.remove(); agent = null; }
	}

	// agent.getPos - block position, in the session's relative frame
	void cmdAgentGetPos(String[] args, World world, Server server) {
		if (!requireAgent()) return;
		send(geometry.blockLocationToRelative(new Location(world, agent.x(), agent.y(), agent.z())));
	}

	// agent.getRotation - facing as a cardinal yaw (0=S,90=W,180=N,270=E)
	void cmdAgentGetRotation(String[] args, World world, Server server) {
		if (!requireAgent()) return;
		send(agent.facing());
	}

	// relative movement (n optional, default 1)
	void cmdAgentForward(String[] args, World world, Server server) {
		if (!requireAgent()) return;
		agent.forward(stepArg(args));
	}

	void cmdAgentBack(String[] args, World world, Server server) {
		if (!requireAgent()) return;
		agent.back(stepArg(args));
	}

	void cmdAgentUp(String[] args, World world, Server server) {
		if (!requireAgent()) return;
		agent.up(stepArg(args));
	}

	void cmdAgentDown(String[] args, World world, Server server) {
		if (!requireAgent()) return;
		agent.down(stepArg(args));
	}

	void cmdAgentTurnLeft(String[] args, World world, Server server) {
		if (!requireAgent()) return;
		agent.turnLeft();
	}

	void cmdAgentTurnRight(String[] args, World world, Server server) {
		if (!requireAgent()) return;
		agent.turnRight();
	}

	// agent.setBlock - place a block at the agent's position (id 0 clears to air)
	void cmdAgentSetBlock(String[] args, World world, Server server) {
		if (!requireAgent()) return;
		int id = Integer.parseInt(args[0]);
		byte data = (args.length > 1 ? Byte.parseByte(args[1]) : (byte) 0);
		updateBlock(world, new Location(world, agent.x(), agent.y(), agent.z()), id, data);
	}

	/** Answers "Fail" and clears a dead/never-spawned agent; true only if a live agent exists. */
	private boolean requireAgent() {
		if (agent == null || !agent.isValid()) {
			agent = null;
			send("Fail");
			return false;
		}
		return true;
	}

	/** Parses an optional step count (default 1) from an agent movement command. */
	private int stepArg(String[] args) {
		if (args.length >= 1 && !args[0].isEmpty()) return Integer.parseInt(args[0]);
		return 1;
	}

	// true if the cuboid is larger than the configured max-blocks limit (0 = unlimited)
	boolean exceedsBlockLimit(Location p1, Location p2) {
		int max = plugin.getMaxBlocks();
		return max > 0 && RelativeGeometry.blockVolume(p1, p2) > max;
	}

	// Charge `volume` blocks against this tick's cumulative cuboid budget. Returns true and
	// records the spend if it fits; returns false (spending nothing) if it would exceed
	// max-blocks-per-tick. This bounds a flood of individually-legal cuboid ops in one tick,
	// and - because callers reserve BEFORE allocating/iterating - also caps clone's snapshot
	// allocation even when max-blocks is 0 (unlimited). 0 = unlimited per-tick budget.
	boolean reserveBlockBudget(long volume) {
		long cap = plugin.getMaxBlocksPerTick();
		if (cap <= 0) return true; // per-tick budget disabled
		// blocksUsedThisTick never exceeds cap, and volume is saturated to Long.MAX_VALUE, so
		// compare via subtraction to avoid overflow in blocksUsedThisTick + volume.
		if (volume > cap - blocksUsedThisTick) return false;
		blocksUsedThisTick += volume;
		return true;
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

	// True if `p` is this session's player - used to scope reactive event streams so a session only
	// sees its own player's moves/deaths/block edits (unless allow-global-events is on). Runs in hot
	// event handlers as a passive filter, so it never latches state. Fails CLOSED (#44): an unbound
	// session on a multi-player server matches nobody, so it can't observe an arbitrary real player.
	//   1. explicitly bound (setPlayer) -> match that player, independent of who else is online;
	//   2. unbound but <=1 player online -> match (unambiguous: single-player / lone-user case);
	//   3. unbound with several players online -> false (no silent fallback to the first-online player).
	boolean isForCurrentPlayer(Player p) {
		if (p == null) return false;
		if (boundPlayerId != null) return boundPlayerId.equals(p.getUniqueId());
		return plugin.getServer().getOnlinePlayers().size() <= 1;
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

	// ---- shared entity geometry --------------------------------------------
	// The player.* and entity.* pos/tile/direction/rotation/pitch commands differ only in how
	// the target is resolved (attached player vs. lookup-by-id) and in their null policy; the
	// geometry is identical, so it lives here once. getRotation is the sole asymmetry: the
	// player variant flips a negative yaw to positive, the entity variant does not (flipYaw).

	private String entityGetPos(Entity target) {
		return geometry.locationToRelative(target.getLocation());
	}

	private void entitySetPos(Entity target, String x, String y, String z) {
		Location loc = target.getLocation();
		// keep current pitch/yaw so teleporting only moves the target
		target.teleport(geometry.parseRelativeLocation(x, y, z, loc.getPitch(), loc.getYaw()));
	}

	private String entityGetTile(Entity target) {
		return geometry.blockLocationToRelative(target.getLocation());
	}

	private void entitySetTile(Entity target, String x, String y, String z) {
		Location loc = target.getLocation();
		target.teleport(geometry.parseRelativeBlockLocation(x, y, z, loc.getPitch(), loc.getYaw()));
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

	/** Logs that an entity command was skipped (missing/unusable target); optionally answers "Fail". */
	private void entitySkipped(String command, String id, boolean sendFail) {
		plugin.getLogger().info(command + ": entity [" + id + "] not found or unusable");
		if (sendFail) send("Fail");
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
				RelativeGeometry.getDistance(playerEntity, e) <= distance) {
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
			if ((entityType == -1 || LegacyEntities.typeId(e.getType()) == entityType) && RelativeGeometry.getDistance(playerEntityId, e) <= distance)
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
				b.append(geometry.blockLocationToRelative(loc));
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
					b.append(geometry.blockLocationToRelative(loc));
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

	/** Test-only view of the dispatch registry (#46) - guards against a command being dropped or
	 *  misregistered when the registry is edited. */
	java.util.Set<String> registeredCommandsForTest() {
		return commandRegistry.keySet();
	}

	public void close() {
		if (closed) return;
		running = false;
		pendingRemoval = true;

		// remove the agent marker so it doesn't orphan (close() runs on the main thread)
		if (agent != null) { agent.remove(); agent = null; }

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
