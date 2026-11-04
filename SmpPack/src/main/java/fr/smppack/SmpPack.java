package fr.smppack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Héberge le resource pack depuis le serveur : un tout petit serveur web (inclus dans Java) sert plugins/SmpPack/pack.zip,
 * et le plugin envoie le pack (avec son SHA-1 calculé tout seul) à chaque joueur qui se connecte.
 */
public final class SmpPack extends JavaPlugin implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private HttpServer http;
    private SharedPort shared;
    private volatile byte[] data;
    private volatile byte[] sha1;
    private volatile String shaHex = "";
    private UUID packId;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        load();
        startHttp();
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        if (http != null) http.stop(0);
        if (shared != null) shared.uninstall();
    }

    private File packFile() {
        return new File(getDataFolder(), "pack.zip");
    }

    /** Lit le zip et calcule son SHA-1 (et un id qui change quand le pack change). */
    private boolean load() {
        File f = packFile();
        if (!f.exists()) {
            data = null;
            getLogger().warning("Pas de pack : mets ton zip dans " + f.getPath());
            return false;
        }
        try {
            byte[] b = Files.readAllBytes(f.toPath());
            byte[] h = MessageDigest.getInstance("SHA-1").digest(b);
            data = b;
            sha1 = h;
            shaHex = HexFormat.of().formatHex(h);
            packId = UUID.nameUUIDFromBytes(h);
            getLogger().info("Pack chargé : " + (b.length / 1024) + " Ko, SHA-1 " + shaHex);
            return true;
        } catch (Exception e) {
            getLogger().warning("Pack illisible : " + e.getMessage());
            return false;
        }
    }

    private void startHttp() {
        int port = getConfig().getInt("port", 0);
        if (port <= 0) {
            if (shared != null) return;
            try {
                shared = new SharedPort(() -> data);
                shared.install();
                getLogger().info("Pack servi sur le port du jeu : " + url());
            } catch (Throwable e) {
                shared = null;
                getLogger().severe("Impossible de passer par le port du jeu (" + e + "). Mets un port libre dans config.yml (port: ...).");
            }
            return;
        }
        try {
            http = HttpServer.create(new InetSocketAddress(port), 0);
            http.createContext("/", this::serve);
            http.setExecutor(Executors.newFixedThreadPool(4));
            http.start();
            getLogger().info("Pack servi sur le port " + port + " : " + url());
        } catch (IOException e) {
            getLogger().severe("Impossible d'ouvrir le port " + port + " (" + e.getMessage() + "). Demande un port en plus à ton hébergeur et mets-le dans config.yml.");
        }
    }

    private void serve(HttpExchange ex) throws IOException {
        try (ex) {
            byte[] b = data;
            if (b == null || !"GET".equalsIgnoreCase(ex.getRequestMethod()) && !"HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "application/zip");
            ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"pack.zip\"");
            if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        }
    }

    private String url() {
        int pub = getConfig().getInt("public-port", 0);
        if (pub <= 0) pub = getConfig().getInt("port", 0);
        if (pub <= 0) pub = Bukkit.getPort();
        // le ?v= change à chaque nouveau pack : le jeu ne reprend pas un vieux pack en cache
        return "http://" + getConfig().getString("public-address", "localhost") + ":" + pub + "/pack.zip?v=" + (shaHex.length() >= 8 ? shaHex.substring(0, 8) : "0");
    }

    private void send(Player p) {
        if (data == null) return;
        p.setResourcePack(packId, url(), sha1, MM.deserialize(getConfig().getString("prompt", "")), getConfig().getBoolean("required", true));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!getConfig().getBoolean("send-on-join", true)) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) send(p);
        }, 20L);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String sub = a.length == 0 ? "info" : a[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                boolean ok = load();
                if (http != null) { http.stop(0); http = null; }
                startHttp();
                s.sendMessage(MM.deserialize(ok ? "<green>Pack rechargé. <gray>SHA-1 " + shaHex + "<newline><gray>/pack-admin renvoyer pour l'envoyer aux joueurs en ligne."
                        : "<red>Pas de pack.zip dans plugins/SmpPack/"));
            }
            case "renvoyer", "resend" -> {
                for (Player p : Bukkit.getOnlinePlayers()) send(p);
                s.sendMessage(MM.deserialize("<green>Pack renvoyé à tout le monde."));
            }
            default -> {
                s.sendMessage(MM.deserialize("<gold>Pack : " + (data == null ? "<red>aucun (plugins/SmpPack/pack.zip)" : "<white>" + (data.length / 1024) + " Ko")));
                s.sendMessage(MM.deserialize("<gold>Lien : <white>" + url()));
                s.sendMessage(MM.deserialize("<gold>SHA-1 : <white>" + shaHex));
                s.sendMessage(MM.deserialize("<gray>Teste le lien dans ton navigateur : il doit télécharger le zip."));
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        return a.length == 1 ? List.of("info", "reload", "renvoyer") : List.of();
    }
}
