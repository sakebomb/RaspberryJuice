package net.zhuoweizhang.raspberryjuice;

import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class RaspberryJuicePlugin extends JavaPlugin implements Listener {

	public static final Set<Material> blockBreakDetectionTools = EnumSet.of(
			Material.DIAMOND_SWORD,
			Material.GOLDEN_SWORD,
			Material.IRON_SWORD,
			Material.NETHERITE_SWORD,
			Material.STONE_SWORD,
			Material.WOODEN_SWORD);

	public ServerListenerThread serverThread;

	public List<RemoteSession> sessions;

	public Player hostPlayer = null;

	private LocationType locationType;

	private HitClickType hitClickType;

	private int maxBlocks;

	private long maxBlocksPerTick;

	// Distinct chunk columns one session may touch in a single tick (0 = unlimited). Bounds a
	// scatter of far-coordinate getBlock/setBlock/getHeight/… calls that would otherwise each
	// force a main-thread chunk generate. Per-tick, not a world-size cap. #58
	private int maxChunksPerTick;

	private boolean welcomeMessage;

	private boolean opCommandsEnabled;

	private boolean allowGlobalEvents;

	private String authToken;

	// Max concurrent socket sessions (0 = unlimited). Bounds thread/fd use from a connection flood.
	private int maxSessions;

	// Max entities one session may spawn via world.spawnEntity over its lifetime (0 = unlimited).
	// Bounds a spawn-flood DoS: a tick drains thousands of commands, each of which could otherwise
	// add a live entity with no cap. Lifetime count (ownedEntities is never shrunk) so removeEntity
	// cannot reset the quota. #57
	private int maxEntitiesPerSession;

	// Per-IP new-connection rate limiter (#56): blunts a connection flood and stops an attacker from
	// side-stepping the 3-strikes auth/setPlayer lockouts by just reconnecting for more guesses.
	private ConnectionRateLimiter connectionRateLimiter;

	// Per-player bind secrets from config (player-tokens: name -> token). Empty = per-player
	// authorization disabled (setPlayer binds by name, as before). Non-empty = setPlayer is
	// fail-closed: only a listed player, with its matching token, may be bound (#47).
	private final java.util.Map<String, String> playerTokens = new java.util.HashMap<>();

	// Classroom plots (#18). off() until onEnable. An empty map is not a sandbox.
	private PlotBounds.ParsedPlots plots = PlotBounds.ParsedPlots.off();

	// While the sandbox is on, world.setTime / world.setWeather are rejected. Ignored when off.
	private boolean sandboxLockWorldRules = true;

	// Tighter cuboid and command caps used only while the sandbox is on. 0 = no extra cap. #18
	private int sandboxMaxBlocks;
	private long sandboxMaxBlocksPerTick;
	private int sandboxMaxCommandsPerTick;

	// Spawn-block origin the in-game listener subtracts. Same triple RemoteSession.tick() latches.
	private int sandboxOriginBlockX;
	private int sandboxOriginBlockY;
	private int sandboxOriginBlockZ;

	// Players whose socket is frozen. Main-thread only. Restart clears it. #18
	private final Set<UUID> frozenPlayers = new HashSet<>();

	// Shared secret for socket classroom.freeze / unfreeze / reset. Empty = those commands Fail. #18
	private String classroomTeacherToken;

	public LocationType getLocationType() {
		return locationType;
	}
	public HitClickType getHitClickType() {
		return hitClickType;
	}
	public int getMaxBlocks() {
		return maxBlocks;
	}
	public long getMaxBlocksPerTick() {
		return maxBlocksPerTick;
	}
	public int getMaxChunksPerTick() {
		return maxChunksPerTick;
	}
	public int getMaxEntitiesPerSession() {
		return maxEntitiesPerSession;
	}
	public boolean isOpCommandsEnabled() {
		return opCommandsEnabled;
	}
	public boolean isGlobalEventsAllowed() {
		return allowGlobalEvents;
	}
	public String getAuthToken() {
		return authToken == null ? "" : authToken;
	}

	public String getClassroomTeacherToken() {
		return classroomTeacherToken == null ? "" : classroomTeacherToken;
	}

	// True when per-player authorization is in effect (the player-tokens map is non-empty). When
	// false, setPlayer binds by name with no token (single-player / trusted deploys, unchanged).
	public boolean isPlayerTokensConfigured() {
		return !playerTokens.isEmpty();
	}

	// The configured bind secret for a player name, or null if that player is not listed. A null
	// return under isPlayerTokensConfigured() means "unlisted" -> not bindable (fail closed).
	public String getPlayerToken(String name) {
		return playerTokens.get(name);
	}

	public boolean isSandboxEnabled() {
		return plots.enabled;
	}

	/** The bound player's plot, or null when the sandbox is off or that name has no entry. */
	public PlotBounds plotFor(String name) {
		if (!plots.enabled || name == null) return null;
		return plots.byName.get(name);
	}

	Map<String, PlotBounds> plotsByName() {
		return plots.byName;
	}

	public boolean locksWorldRules() {
		return sandboxLockWorldRules;
	}

	public int getSandboxMaxBlocks() {
		return sandboxMaxBlocks;
	}

	public long getSandboxMaxBlocksPerTick() {
		return sandboxMaxBlocksPerTick;
	}

	/** Commands-per-tick replacement. 0 keeps the built-in 9000. Read when the session is built. */
	public int getSandboxMaxCommandsPerTick() {
		return sandboxMaxCommandsPerTick;
	}

	/**
	 * Tighter of {@code global} and {@code extra} while the sandbox is on.
	 * A non-positive side is unlimited. Sandbox off ignores {@code extra}.
	 */
	static int effectiveCap(int global, int extra, boolean sandboxOn) {
		if (!sandboxOn || extra <= 0) return global;
		if (global <= 0) return extra;
		return Math.min(global, extra);
	}

	static long effectiveCap(long global, long extra, boolean sandboxOn) {
		if (!sandboxOn || extra <= 0) return global;
		if (global <= 0) return extra;
		return Math.min(global, extra);
	}

	/** Negative classroom caps become 0 (no extra cap) and emit one warning. */
	static long normalizeSandboxCap(String key, long value, java.util.function.Consumer<String> warn) {
		if (value < 0) {
			warn.accept(key + " is negative (" + value
				+ "); treating as 0 (no extra cap). Use 0 to disable the cap, not a negative.");
			return 0;
		}
		return value;
	}

	boolean isFrozen(UUID playerId) {
		return playerId != null && frozenPlayers.contains(playerId);
	}

	void freeze(UUID playerId) {
		if (playerId != null) frozenPlayers.add(playerId);
	}

	void unfreeze(UUID playerId) {
		if (playerId != null) frozenPlayers.remove(playerId);
	}

	void clearFrozen() {
		frozenPlayers.clear();
	}

	/** Test hook. Production load is {@link #readPlots} from {@code onEnable}. */
	void installPlots(PlotBounds.ParsedPlots parsed) {
		plots = parsed == null ? PlotBounds.ParsedPlots.off() : parsed;
	}

	void setLockWorldRules(boolean lock) {
		sandboxLockWorldRules = lock;
	}

	// Parse the player-tokens config section (name -> token) into a map. Null section (key absent
	// or empty "{}") or blank-valued entries yield no authorization for that name - so an empty or
	// mistyped section leaves per-player authz OFF rather than silently locking everyone out. #47
	static java.util.Map<String, String> readPlayerTokens(org.bukkit.configuration.ConfigurationSection section) {
		java.util.Map<String, String> tokens = new java.util.HashMap<>();
		if (section == null) return tokens;
		for (String name : section.getKeys(false)) {
			String token = section.getString(name);
			if (token != null && !token.isEmpty()) {
				tokens.put(name, token);
			}
		}
		return tokens;
	}

	public void onEnable() {
		//save a copy of the default config.yml if one is not there
        this.saveDefaultConfig();
        //get host and port from config.yml
		String hostname = this.getConfig().getString("hostname");
		//secure by default: an empty hostname binds to loopback only, not every interface
		if (hostname == null || hostname.isEmpty()) hostname = "localhost";
		int port = this.getConfig().getInt("port");
		getLogger().info("Using host:port - " + hostname + ":" + Integer.toString(port));
		if (hostname.equals("0.0.0.0")) {
			getLogger().warning("The API socket is bound to 0.0.0.0 (all interfaces) and is "
				+ "UNAUTHENTICATED - anyone who can reach port " + port + " can control the world. "
				+ "Only use this on a trusted/firewalled network.");
		}

		//maximum blocks a single getBlocks/setBlocks may span (0 = unlimited)
		maxBlocks = this.getConfig().getInt("max-blocks", 1000000);

		//cumulative blocks all cuboid ops (getBlocks/setBlocks/clone) may touch in one server
		//tick - bounds a flood of near-cap requests that would otherwise stack up (0 = unlimited)
		maxBlocksPerTick = this.getConfig().getLong("max-blocks-per-tick", 10000000L);

		// distinct chunk columns all coordinate ops may touch in one tick (0 = unlimited).
		// Negative is treated as 0 (unlimited), same idiom as max-entities-per-session. #58
		maxChunksPerTick = this.getConfig().getInt("max-chunks-per-tick", 256);
		if (maxChunksPerTick < 0) {
			getLogger().warning("max-chunks-per-tick is negative (" + maxChunksPerTick
				+ "); treating as 0 (unlimited). Use 0 to disable the cap, not a negative.");
			maxChunksPerTick = 0;
		}

		//whether to broadcast a "Welcome <player>" message to everyone on join
		welcomeMessage = this.getConfig().getBoolean("welcome-message", true);

		//whether player.setGameMode / player.give are allowed (disable on shared servers)
		opCommandsEnabled = this.getConfig().getBoolean("enable-op-commands", true);

		//whether reactive event streams (moves/deaths/block breaks+places) broadcast EVERY player's
		//activity to every socket (a tracking feed). Default false: each session sees only its own
		//player's events. Set true for whole-world/region triggers on a trusted single-user server.
		allowGlobalEvents = this.getConfig().getBoolean("allow-global-events", false);

		authToken = this.getConfig().getString("auth-token", "");

		//shared secret for socket classroom.freeze / unfreeze / reset. Empty = those commands Fail. #18
		classroomTeacherToken = this.getConfig().getString("classroom-teacher-token", "");

		//max concurrent socket sessions (0 = unlimited), and max new connections per remote IP per
		//minute (0 = unlimited) - bound resource use from a flood and slow token brute-forcing (#56)
		maxSessions = this.getConfig().getInt("max-sessions", 100);
		maxEntitiesPerSession = this.getConfig().getInt("max-entities-per-session", 1000);
		if (maxEntitiesPerSession < 0) {
			getLogger().warning("max-entities-per-session is negative (" + maxEntitiesPerSession
				+ "); treating as 0 (unlimited). Use 0 to disable the cap, not a negative.");
			maxEntitiesPerSession = 0;
		}
		int maxConnectionsPerMinute = this.getConfig().getInt("max-connections-per-minute", 60);
		connectionRateLimiter = new ConnectionRateLimiter(maxConnectionsPerMinute, 60_000L);

		//per-player bind secrets (player-tokens: name -> token). Empty = per-player authz off.
		//When set, setPlayer is fail-closed: only a listed player with its matching token binds (#47).
		playerTokens.clear();
		playerTokens.putAll(readPlayerTokens(this.getConfig().getConfigurationSection("player-tokens")));

		plots = PlotBounds.readPlots(this.getConfig());
		for (String warning : plots.warnings) {
			getLogger().warning(warning);
		}
		sandboxLockWorldRules = this.getConfig().getBoolean("sandbox-lock-world-rules", true);
		sandboxMaxBlocks = (int) normalizeSandboxCap("sandbox-max-blocks",
			this.getConfig().getInt("sandbox-max-blocks", 0), getLogger()::warning);
		sandboxMaxBlocksPerTick = normalizeSandboxCap("sandbox-max-blocks-per-tick",
			this.getConfig().getLong("sandbox-max-blocks-per-tick", 0L), getLogger()::warning);
		sandboxMaxCommandsPerTick = (int) normalizeSandboxCap("sandbox-max-commands-per-tick",
			this.getConfig().getInt("sandbox-max-commands-per-tick", 0), getLogger()::warning);
		if (plots.enabled && opCommandsEnabled) {
			getLogger().warning("plots is enabled while enable-op-commands is still true. "
				+ "A classroom sandbox should set enable-op-commands: false so a socket cannot "
				+ "self-grant creative mode or items.");
		}

		//get location type (ABSOLUTE or RELATIVE) from config.yml
		String location = this.getConfig().getString("location").toUpperCase();
		try {
			locationType = LocationType.valueOf(location);
		} catch(IllegalArgumentException e) {
			getLogger().warning("warning - location value in config.yml should be ABSOLUTE or RELATIVE - '" + location + "' found");
			locationType = LocationType.valueOf("RELATIVE");
		}
		getLogger().info("Using " + locationType.name() + " locations");
		refreshSandboxOrigin();

		//get hit click type (LEFT, RIGHT or BOTH) from config.yml
		String hitClick = this.getConfig().getString("hitclick").toUpperCase();
		try {
			hitClickType = HitClickType.valueOf(hitClick);
		} catch(IllegalArgumentException e) {
			getLogger().warning("warning - hitclick value in config.yml should be LEFT, RIGHT or BOTH - '" + hitClick + "' found");
			hitClickType = HitClickType.valueOf("RIGHT");
		}
		getLogger().info("Using " + hitClickType.name() + " clicks for hits");

		//setup session list (copy-on-write so async event handlers can iterate it safely)
		sessions = new CopyOnWriteArrayList<RemoteSession>();
		if (getCommand("rj") != null) {
			getCommand("rj").setExecutor(new ClassroomCommands(this));
		} else {
			getLogger().warning("plugin.yml is missing the rj command; /rj freeze and reset are unavailable.");
		}
		
		//create new tcp listener thread
		try {
			if (hostname.equals("0.0.0.0")) {
				serverThread = new ServerListenerThread(this, new InetSocketAddress(port));
			} else {
				serverThread = new ServerListenerThread(this, new InetSocketAddress(hostname, port));
			}
			new Thread(serverThread).start();
			getLogger().info("ThreadListener Started");
		} catch (Exception e) {
			e.printStackTrace();
			getLogger().warning("Failed to start ThreadListener");
			return;
		}
		//register the events
		getServer().getPluginManager().registerEvents(this, this);
		//setup the schedule to called the tick handler
		getServer().getScheduler().scheduleSyncRepeatingTask(this, new TickHandler(), 1, 1);
	}
	
	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		if (!welcomeMessage) return;
		Player p = event.getPlayer();
		Server server = getServer();
		server.broadcast(PlainText.component("Welcome " + PlainText.plain(p.playerListName())));
	}

	@EventHandler(ignoreCancelled=true)
	public void onPlayerInteract(PlayerInteractEvent event) {
		// only react to events which are of the correct type
		switch(hitClickType) {
			case BOTH:
				if ((event.getAction() != Action.RIGHT_CLICK_BLOCK) && (event.getAction() != Action.LEFT_CLICK_BLOCK)) return;
				break;
			case LEFT:
				if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;
				break;
			case RIGHT:
				if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
				break;
		}
		ItemStack currentTool = event.getItem();
		if (currentTool == null || !blockBreakDetectionTools.contains(currentTool.getType())) {
			return;
		}
		for (RemoteSession session: sessions) {
			session.queuePlayerInteractEvent(event);
		}
	}

	@EventHandler(ignoreCancelled=true)
	public void onChatPosted(AsyncChatEvent event) {
		for (RemoteSession session: sessions) {
			session.queueChatPostedEvent(event);
		}
	}
	
	@EventHandler(ignoreCancelled=true)
	public void onProjectileHit(ProjectileHitEvent event) {

		for (RemoteSession session: sessions) {
			session.queueProjectileHitEvent(event);
		}
	}

	@EventHandler(ignoreCancelled=true)
	public void onPlayerMove(PlayerMoveEvent event) {
		// only report when the player crosses into a new block (PlayerMoveEvent fires very often)
		if (event.getTo() == null) return;
		if (event.getFrom().getBlockX() == event.getTo().getBlockX()
				&& event.getFrom().getBlockY() == event.getTo().getBlockY()
				&& event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
			return;
		}
		for (RemoteSession session: sessions) {
			if (allowGlobalEvents || session.isForCurrentPlayer(event.getPlayer())) {
				session.queuePlayerMove(event.getPlayer(), event.getTo());
			}
		}
	}

	/**
	 * Same switch as {@code RemoteSession.tick()}'s first origin latch. The listener subtracts
	 * this block; each session subtracts its own origin. Restart after moving the world spawn.
	 */
	void refreshSandboxOrigin() {
		if (getServer().getWorlds().isEmpty()) return;
		World world = getServer().getWorlds().get(0);
		org.bukkit.Location origin = locationType == LocationType.ABSOLUTE
			? new org.bukkit.Location(world, 0, 0, 0)
			: world.getSpawnLocation();
		sandboxOriginBlockX = origin.getBlockX();
		sandboxOriginBlockY = origin.getBlockY();
		sandboxOriginBlockZ = origin.getBlockZ();
	}

	int sandboxOriginBlockX() { return sandboxOriginBlockX; }
	int sandboxOriginBlockY() { return sandboxOriginBlockY; }
	int sandboxOriginBlockZ() { return sandboxOriginBlockZ; }

	/**
	 * Hand edits. Own plot (including an overlap) and unassigned space are allowed.
	 * A cell outside the actor's plot and inside someone else's is not.
	 */
	boolean denied(Player actor, Block block) {
		if (!isSandboxEnabled() || actor == null || block == null) return false;
		if (getServer().getWorlds().isEmpty()) return false;
		if (!block.getWorld().equals(getServer().getWorlds().get(0))) return false;
		if (actor.hasPermission("raspberryjuice.classroom.teacher")) return false;
		int x = block.getX() - sandboxOriginBlockX;
		int y = block.getY() - sandboxOriginBlockY;
		int z = block.getZ() - sandboxOriginBlockZ;
		String name = PlainText.plain(actor.playerListName());
		PlotBounds own = plotFor(name);
		if (own != null && own.contains(x, y, z)) return false;
		for (PlotBounds plot : plots.byName.values()) {
			if (plot.contains(x, y, z)) return true;
		}
		return false;
	}

	boolean bucketDenied(Player actor, Block clicked, BlockFace face) {
		if (clicked == null || face == null) return false;
		return denied(actor, clicked) || denied(actor, clicked.getRelative(face));
	}

	private boolean placeDenied(BlockPlaceEvent event) {
		if (denied(event.getPlayer(), event.getBlock())) return true;
		if (event instanceof BlockMultiPlaceEvent multi) {
			for (org.bukkit.block.BlockState state : multi.getReplacedBlockStates()) {
				if (state != null && denied(event.getPlayer(), state.getBlock())) return true;
			}
		}
		return false;
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void protectBlockBreak(BlockBreakEvent event) {
		if (denied(event.getPlayer(), event.getBlock())) event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void protectBlockPlace(BlockPlaceEvent event) {
		if (placeDenied(event)) event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void protectBucketEmpty(PlayerBucketEmptyEvent event) {
		if (bucketDenied(event.getPlayer(), event.getBlockClicked(), event.getBlockFace())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
	public void protectBucketFill(PlayerBucketFillEvent event) {
		if (bucketDenied(event.getPlayer(), event.getBlockClicked(), event.getBlockFace())) {
			event.setCancelled(true);
		}
	}

	@EventHandler(ignoreCancelled=true)
	public void onBlockBreak(BlockBreakEvent event) {
		for (RemoteSession session: sessions) {
			if (allowGlobalEvents || session.isForCurrentPlayer(event.getPlayer())) {
				session.queueBlockBreak(event.getPlayer(), event.getBlock());
			}
		}
	}

	@EventHandler(ignoreCancelled=true)
	public void onBlockPlace(BlockPlaceEvent event) {
		for (RemoteSession session: sessions) {
			if (allowGlobalEvents || session.isForCurrentPlayer(event.getPlayer())) {
				session.queueBlockPlace(event.getPlayer(), event.getBlock());
			}
		}
	}

	@EventHandler
	public void onPlayerDeath(PlayerDeathEvent event) {
		for (RemoteSession session: sessions) {
			if (allowGlobalEvents || session.isForCurrentPlayer(event.getEntity())) {
				session.queuePlayerDeath(event.getEntity());
			}
		}
	}

	/** Admission control run on the raw socket BEFORE a RemoteSession (and its two threads) is
	 *  created: refuse the connection if we're at the concurrent-session cap or the remote IP has
	 *  exceeded its per-minute connection rate (#56). Returns true to accept. */
	public boolean admit(java.net.Socket socket) {
		if (!withinSessionCap(sessions.size())) {
			getLogger().warning("Refusing " + socket.getRemoteSocketAddress()
				+ " - at max-sessions (" + maxSessions + ").");
			return false;
		}
		String ip = ipOf(socket.getRemoteSocketAddress());
		if (!connectionRateLimiter.allow(ip, System.currentTimeMillis())) {
			getLogger().warning("Refusing " + socket.getRemoteSocketAddress()
				+ " - connection rate limit exceeded for that IP.");
			return false;
		}
		return true;
	}

	/** True if a new session fits under the concurrent-session cap (0 = unlimited). Pure, for tests. */
	boolean withinSessionCap(int currentSessions) {
		return maxSessions <= 0 || currentSessions < maxSessions;
	}

	/** True if a session with {@code currentOwned} spawned entities may spawn one more
	 *  (0 = unlimited). Pure, for tests. #57 */
	boolean withinEntityCap(int currentOwned) {
		return maxEntitiesPerSession <= 0 || currentOwned < maxEntitiesPerSession;
	}

	/** Visible for tests: override the spawn cap without reloading config. */
	void setMaxEntitiesPerSession(int n) {
		maxEntitiesPerSession = n;
	}

	/** Visible for tests: override the per-tick chunk budget without reloading config. */
	void setMaxChunksPerTick(int n) {
		maxChunksPerTick = n;
	}

	/** Visible for tests: override the classroom cuboid caps without reloading config. */
	void setSandboxMaxBlocks(int n) {
		sandboxMaxBlocks = n;
	}

	void setSandboxMaxBlocksPerTick(long n) {
		sandboxMaxBlocksPerTick = n;
	}

	void setSandboxMaxCommandsPerTick(int n) {
		sandboxMaxCommandsPerTick = n;
	}

	/** Visible for tests: set the socket teacher token without reloading config. */
	void setClassroomTeacherToken(String token) {
		classroomTeacherToken = token;
	}

	/** The IP portion of a socket address for rate-limiting (falls back to the full string). */
	private static String ipOf(java.net.SocketAddress addr) {
		if (addr instanceof java.net.InetSocketAddress isa && isa.getAddress() != null) {
			return isa.getAddress().getHostAddress();
		}
		return String.valueOf(addr);
	}

	/** called when a new session is established. */
	public void handleConnection(RemoteSession newSession) {
		if (checkBanned(newSession)) {
			getLogger().warning("Kicking " + newSession.getSocket().getRemoteSocketAddress() + " because the IP address has been banned.");
			newSession.kick("You've been banned from this server!");
			return;
		}
		//CopyOnWriteArrayList is thread-safe for concurrent add/iterate
		sessions.add(newSession);
	}

	public Player getNamedPlayer(String name) {
		if (name == null) return null;
		for(Player player : Bukkit.getOnlinePlayers()) {
			//match against the plain-text list name (colour codes stripped) so a plain client name matches
			if (name.equals(PlainText.plain(player.playerListName()))) {
				return player;
			}
		}
		return null;
	}

	public Player getHostPlayer() {
		if (hostPlayer != null) return hostPlayer;
		for(Player player : Bukkit.getOnlinePlayers()) {
			return player;
		}
		return null;
	}

	//get entity by id - DONE to be compatible with the pi it should be changed to return an entity not a player...
	public Entity getEntity(int id) {
		for (Player p: getServer().getOnlinePlayers()) {
			if (p.getEntityId() == id) {
				return p;
			}
		}
		// entity ids are unique server-wide, so search every loaded world. This also works with no
		// players online (unlike keying off a "host" player's world) and reaches other worlds.
		for (World w : getServer().getWorlds()) {
			for (Entity e : w.getEntities()) {
				if (e.getEntityId() == id) {
					return e;
				}
			}
		}
		return null;
	}

	public boolean checkBanned(RemoteSession session) {
		Set<String> ipBans = getServer().getIPBans();
		String sessionIp = session.getSocket().getInetAddress().getHostAddress();
		return ipBans.contains(sessionIp);
	}


	public void onDisable() {
		getServer().getScheduler().cancelTasks(this);
		for (RemoteSession session: sessions) {
			try {
				session.close();
			} catch (Exception e) {
				getLogger().warning("Failed to close RemoteSession");
				e.printStackTrace();
			}
		}
		// serverThread can be null if onEnable failed to bind the socket (e.g. port in use)
		if (serverThread != null) {
			serverThread.running = false;
			try {
				serverThread.serverSocket.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		sessions = null;
		serverThread = null;
		getLogger().info("Raspberry Juice Stopped");
	}

	private class TickHandler implements Runnable {
		public void run() {
			//for-each over the copy-on-write snapshot; remove() during iteration is safe
			for (RemoteSession s : sessions) {
				if (s.pendingRemoval) {
					s.close();
					sessions.remove(s);
				} else {
					s.tick();
				}
			}
		}
	}
}
