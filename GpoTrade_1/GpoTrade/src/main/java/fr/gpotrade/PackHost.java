package fr.gpotrade;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Distribue le resource pack (embarqué dans le jar) :
 * soit via un lien fourni dans la config, soit en l'hébergeant lui-même sur un petit serveur HTTP.
 * Retient aussi quels joueurs l'ont bien chargé, pour leur afficher le joli fond.
 */
final class PackHost {

    private static final String RESOURCE = "GpoTrade-ResourcePack.zip";

    private final TradePlugin plugin;
    private final UUID packId = UUID.nameUUIDFromBytes("gpotrade-pack".getBytes(StandardCharsets.UTF_8));
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private byte[] zip;
    private String sha1;
    private volatile String url;
    private HttpServer server;
    private ExecutorService executor;

    PackHost(TradePlugin plugin) {
        this.plugin = plugin;
    }

    private ConfigurationSection cfg() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("resource-pack");
        return s != null ? s : plugin.getConfig().createSection("resource-pack");
    }

    void start() {
        try (InputStream in = plugin.getResource(RESOURCE)) {
            if (in == null) {
                plugin.getLogger().warning("Resource pack introuvable dans le jar : fond graphique désactivé.");
                return;
            }
            zip = in.readAllBytes();
            sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(zip));
            // copie dans plugins/GpoTrade/ pour ceux qui veulent l'héberger ou l'installer à la main
            Files.write(new File(plugin.getDataFolder(), RESOURCE).toPath(), zip);
        } catch (Exception e) {
            plugin.getLogger().warning("Lecture du resource pack impossible : " + e.getMessage());
            return;
        }
        if (!cfg().getBoolean("enabled", true)) return;

        String configured = cfg().getString("url", "").trim();
        if (!configured.isEmpty()) {
            url = configured;
            plugin.getLogger().info("Resource pack distribué via : " + url);
            return;
        }
        if (!cfg().getBoolean("self-host", true)) return;

        int port = cfg().getInt("port", 8163);
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/gpotrade.zip", ex -> {
                ex.getResponseHeaders().add("Content-Type", "application/zip");
                ex.sendResponseHeaders(200, zip.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(zip);
                }
            });
            executor = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "GpoTrade-PackHost");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(executor);
            server.start();
        } catch (IOException e) {
            plugin.getLogger().warning("Impossible d'ouvrir le port " + port + " pour le resource pack ("
                    + e.getMessage() + "). Change resource-pack.port, ou mets un lien dans resource-pack.url.");
            return;
        }

        String address = cfg().getString("public-address", "").trim();
        if (address.isEmpty()) address = Bukkit.getIp();
        if (address != null && !address.isEmpty() && !address.startsWith("0.")) {
            setUrl(address, port);
        } else {
            detectPublicIp(port);
        }
    }

    private void setUrl(String address, int port) {
        url = "http://" + address + ":" + port + "/gpotrade.zip?v=" + sha1.substring(0, 8);
        plugin.getLogger().info("Resource pack hébergé sur " + url
                + " (si les joueurs ne le reçoivent pas, vérifie que le port " + port + " est ouvert).");
    }

    private void detectPublicIp(int port) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.ipify.org"))
                        .timeout(Duration.ofSeconds(5)).build();
                String ip = client.send(req, HttpResponse.BodyHandlers.ofString()).body().trim();
                if (!ip.isEmpty()) setUrl(ip, port);
            } catch (Exception e) {
                plugin.getLogger().warning("IP publique introuvable : renseigne resource-pack.public-address dans la config.");
            } 
        });
    }

    void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
        loaded.clear();
    }

    void send(Player p) {
        String u = url;
        if (u == null || sha1 == null) return;
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(packId, URI.create(u), sha1);
        p.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .required(cfg().getBoolean("required", false))
                .prompt(Component.text("Fond graphique de la fenêtre d'échange", NamedTextColor.GOLD))
                .replace(false)
                .build());
    }

    /** Vrai si ce joueur doit voir la fenêtre avec le fond graphique. */
    boolean fancy(Player p) {
        return cfg().getBoolean("force-fancy-title", false) || loaded.contains(p.getUniqueId());
    }

    void markLoaded(Player p, boolean ok) {
        if (ok) loaded.add(p.getUniqueId());
        else loaded.remove(p.getUniqueId());
    }

    void forget(Player p) {
        loaded.remove(p.getUniqueId());
    }
}
