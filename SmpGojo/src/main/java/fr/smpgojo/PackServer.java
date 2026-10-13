package fr.smpgojo;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.concurrent.Executors;

/**
 * Petit serveur web intégré : sert le pack de sons sur un port libre de l'hébergeur
 * (http://adresse-du-serveur:port/SmpGojo-sons.zip), pour ne dépendre d'aucun site externe.
 */
final class PackServer {

    private final SmpGojo pl;
    private HttpServer http;
    private byte[] zip;
    private String sha1 = "";
    private int port;

    PackServer(SmpGojo pl) {
        this.pl = pl;
    }

    boolean start(int port) {
        this.port = port;
        try (InputStream in = pl.getResource("pack/SmpGojo-sons.zip")) {
            if (in == null) {
                pl.getLogger().warning("Pack de sons introuvable dans le plugin.");
                return false;
            }
            zip = in.readAllBytes();
            byte[] h = MessageDigest.getInstance("SHA-1").digest(zip);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            sha1 = sb.toString();
            http = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
            http.createContext("/", ex -> {
                byte[] body = zip;
                ex.getResponseHeaders().add("Content-Type", "application/zip");
                ex.getResponseHeaders().add("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
            });
            http.setExecutor(Executors.newFixedThreadPool(2));
            http.start();
            pl.getLogger().info("Pack de sons servi sur le port " + port + " (SHA-1 " + sha1 + ")");
            return true;
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            pl.getLogger().warning("Impossible d'ouvrir le port " + port + " pour le pack de sons : " + e.getMessage());
            http = null;
            return false;
        }
    }

    boolean running() {
        return http != null;
    }

    String sha1() {
        return sha1;
    }

    /** L'adresse à donner au joueur : celle qu'il a tapée pour se connecter (ou celle de la config). */
    String urlFor(org.bukkit.entity.Player p) {
        String host = pl.getConfig().getString("resource-pack.host", "");
        if (host == null || host.isBlank()) {
            InetSocketAddress v = p.getVirtualHost();
            host = v != null ? v.getHostString() : null;
        }
        if (host == null || host.isBlank()) host = org.bukkit.Bukkit.getIp();
        if (host == null || host.isBlank()) return null;
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return "http://" + host + ":" + port + "/SmpGojo-sons.zip";
    }

    void stop() {
        if (http != null) http.stop(0);
        http = null;
    }
}
