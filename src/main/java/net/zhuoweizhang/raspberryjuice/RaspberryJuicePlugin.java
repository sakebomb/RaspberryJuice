package net.zhuoweizhang.raspberryjuice;

import java.net.InetSocketAddress;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
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

	private boolean welcomeMessage;

	private boolean opCommandsEnabled;

	private boolean allowGlobalEvents;

	private String authToken;

	// Per-player bind secrets from config (player-tokens: name -> token). Empty = per-player
	// authorization disabled (setPlayer binds by name, as before). Non-empty = setPlayer is
	// fail-closed: only a listed player, with its matching token, may be bound (#47).
	private final java.util.Map<String, String> playerTokens = new java.util.HashMap<>();

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
	public boolean isOpCommandsEnabled() {
		return opCommandsEnabled;
	}
	public boolean isGlobalEventsAllowed() {
		return allowGlobalEvents;
	}
	public String getAuthToken() {
		return authToken == null ? "" : authToken;
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

		//whether to broadcast a "Welcome <player>" message to everyone on join
		welcomeMessage = this.getConfig().getBoolean("welcome-message", true);

		//whether player.setGameMode / player.give are allowed (disable on shared servers)
		opCommandsEnabled = this.getConfig().getBoolean("enable-op-commands", true);

		//whether reactive event streams (moves/deaths/block breaks+places) broadcast EVERY player's
		//activity to every socket (a tracking feed). Default false: each session sees only its own
		//player's events. Set true for whole-world/region triggers on a trusted single-user server.
		allowGlobalEvents = this.getConfig().getBoolean("allow-global-events", false);

		authToken = this.getConfig().getString("auth-token", "");

		//per-player bind secrets (player-tokens: name -> token). Empty = per-player authz off.
		//When set, setPlayer is fail-closed: only a listed player with its matching token binds (#47).
		playerTokens.clear();
		playerTokens.putAll(readPlayerTokens(this.getConfig().getConfigurationSection("player-tokens")));

		//get location type (ABSOLUTE or RELATIVE) from config.yml
		String location = this.getConfig().getString("location").toUpperCase();
		try {
			locationType = LocationType.valueOf(location);
		} catch(IllegalArgumentException e) {
			getLogger().warning("warning - location value in config.yml should be ABSOLUTE or RELATIVE - '" + location + "' found");
			locationType = LocationType.valueOf("RELATIVE");
		}
		getLogger().info("Using " + locationType.name() + " locations");

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
