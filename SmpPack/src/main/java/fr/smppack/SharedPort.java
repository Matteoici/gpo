package fr.smppack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.kyori.adventure.key.Key;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * Fait passer le téléchargement du pack par le port du jeu : chaque nouvelle connexion est regardée,
 * si elle commence par « GET » ou « HEAD » c'est un navigateur / le jeu qui télécharge le pack (on répond en HTTP et on ferme),
 * sinon c'est un joueur Minecraft normal et on ne touche à rien.
 */
final class SharedPort {

    private static final Key KEY = Key.key("smppack", "http");
    private static final String NAME = "smppack_http";
    private static final byte[][] METHODS = {"GET ".getBytes(StandardCharsets.US_ASCII), "HEAD ".getBytes(StandardCharsets.US_ASCII)};

    private final Supplier<byte[]> pack;
    private Class<?> holder;

    SharedPort(Supplier<byte[]> pack) {
        this.pack = pack;
    }

    /** Branche l'écoute sur les connexions du serveur (API interne de Paper). */
    void install() throws Exception {
        holder = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
        Class<?> listener = Class.forName("io.papermc.paper.network.ChannelInitializeListener");
        Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{listener}, (o, m, args) -> {
            switch (m.getName()) {
                case "afterInitChannel" -> {
                    Channel ch = (Channel) args[0];
                    if (ch.pipeline().get(NAME) == null) ch.pipeline().addFirst(NAME, new Sniffer());
                    return null;
                }
                case "hashCode" -> { return System.identityHashCode(o); }
                case "equals" -> { return o == args[0]; }
                case "toString" -> { return "SmpPack http"; }
                default -> { return null; }
            }
        });
        Method add = holder.getMethod("addListener", Key.class, listener);
        add.invoke(null, KEY, proxy);
    }

    void uninstall() {
        if (holder == null) return;
        try {
            holder.getMethod("removeListener", Key.class).invoke(null, KEY);
        } catch (Exception ignored) {
        }
    }

    private final class Sniffer extends ChannelInboundHandlerAdapter {
        private ByteBuf buf;
        private boolean http;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf in)) {
                ctx.fireChannelRead(msg);
                return;
            }
            if (buf == null) buf = ctx.alloc().buffer();
            buf.writeBytes(in);
            in.release();
            if (!http) {
                int r = match();
                if (r < 0) { pass(ctx); return; }      // un joueur Minecraft : on s'efface
                if (r == 0) return;                     // pas encore assez d'octets pour savoir
                http = true;
                if (ctx.pipeline().get("timeout") != null) ctx.pipeline().remove("timeout");
            }
            // on attend la fin des en-têtes HTTP (ou 8 Ko max)
            String req = buf.toString(StandardCharsets.US_ASCII);
            if (!req.contains("\r\n\r\n") && buf.readableBytes() < 8192) return;
            respond(ctx, req);
        }

        /** 1 = début de requête HTTP, -1 = autre chose, 0 = on ne sait pas encore */
        private int match() {
            int n = buf.readableBytes();
            boolean maybe = false;
            for (byte[] m : METHODS) {
                boolean ok = true;
                for (int i = 0; i < Math.min(n, m.length); i++) {
                    if (buf.getByte(buf.readerIndex() + i) != m[i]) { ok = false; break; }
                }
                if (ok && n >= m.length) return 1;
                if (ok) maybe = true;
            }
            return maybe ? 0 : -1;
        }

        private void pass(ChannelHandlerContext ctx) {
            ByteBuf b = buf;
            buf = null;
            ctx.pipeline().remove(this);
            ctx.fireChannelRead(b);
        }

        private void respond(ChannelHandlerContext ctx, String req) {
            buf.release();
            buf = null;
            String line = req.split("\r\n", 2)[0];
            String[] parts = line.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            boolean head = line.startsWith("HEAD");
            byte[] data = pack.get();
            if (data == null || !path.startsWith("/pack")) {
                byte[] body = "Rien ici : le pack est sur /pack.zip".getBytes(StandardCharsets.UTF_8);
                String h = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                ctx.writeAndFlush(Unpooled.wrappedBuffer(h.getBytes(StandardCharsets.US_ASCII), body)).addListener(ChannelFutureListener.CLOSE);
                return;
            }
            String h = "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Disposition: attachment; filename=\"pack.zip\"\r\n"
                    + "Content-Length: " + data.length + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
            ByteBuf out = head ? Unpooled.wrappedBuffer(h.getBytes(StandardCharsets.US_ASCII))
                    : Unpooled.wrappedBuffer(h.getBytes(StandardCharsets.US_ASCII), data);
            ctx.writeAndFlush(out).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            if (buf != null) { buf.release(); buf = null; }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (buf != null) { buf.release(); buf = null; }
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (http) ctx.close();
            else ctx.fireExceptionCaught(cause);
        }
    }
}
