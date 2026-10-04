import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * A tiny fake Xtream Codes panel for developing GameDay TV without a real IPTV login.
 *
 *   java tools/mock-xtream/MockXtream.java            (listens on 127.0.0.1:8085)
 *   adb reverse tcp:8085 tcp:8085                      (lets a TV/phone reach it as 127.0.0.1:8085)
 *
 * In the app: Xtream Codes, server http://127.0.0.1:8085, and the test login below.
 * Live channels and replays redirect to public sample streams.
 */
public class MockXtream {
    // Test-only login for this mock server.
    static final String USER = "gameday";
    static final String PASS = "mock-only-2026";

    static final String[][] CHANNELS = {
        // id, name, category id, archive days
        {"101", "US| ESPN HD", "1", "3"},
        {"102", "US| ESPN2 HD", "1", "0"},
        {"103", "US| FS1 HD", "1", "0"},
        {"104", "US| NFL NETWORK HD", "1", "3"},
        {"105", "US| GOLF CHANNEL HD", "1", "0"},
        {"106", "US| CBS HD", "2", "0"},
        {"107", "US| FOX HD", "2", "0"},
        {"108", "US| NBC HD", "2", "0"},
        {"109", "US| ABC HD", "2", "0"},
        {"110", "US| CNN HD", "3", "0"},
        {"111", "NFL 01: Steelers @ Browns", "4", "0"},
        {"112", "US| TNT HD", "2", "0"},
    };
    static final String[][] LIVE_CATS = {{"1", "USA Sports"}, {"2", "USA Entertainment"}, {"3", "USA News"}, {"4", "NFL Sunday Ticket"}};

    static final String HLS_A = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8";
    static final String HLS_B = "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8";
    /** What catch-up replays play. */
    static final String REPLAY = "https://download.blender.org/durian/trailer/sintel_trailer-480p.mp4";

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8085;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", MockXtream::handle);
        server.start();
        System.out.println("Mock Xtream on http://127.0.0.1:" + port + "  user=" + USER);
    }

    static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        System.out.println(ex.getRequestMethod() + " " + path + " " + q.getOrDefault("action", ""));
        try {
            if (path.equals("/playlist.m3u")) {
                send(ex, 200, "audio/x-mpegurl", playlist());
            } else if (path.equals("/player_api.php")) {
                if (!USER.equals(q.get("username")) || !PASS.equals(q.get("password"))) {
                    json(ex, "{\"user_info\":{\"auth\":0}}");
                    return;
                }
                json(ex, api(q.getOrDefault("action", ""), q));
            } else if (path.startsWith("/live/")) {
                if (path.endsWith(".ts")) { send(ex, 404, "text/plain", "no ts"); return; }
                String id = path.substring(path.lastIndexOf('/') + 1).replaceAll("\\..*", "");
                redirect(ex, Integer.parseInt(id) % 2 == 0 ? HLS_B : HLS_A);
            } else if (path.startsWith("/timeshift/")) {
                redirect(ex, REPLAY);
            } else {
                send(ex, 404, "text/plain", "not found");
            }
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "text/plain", String.valueOf(e));
        }
    }

    static String api(String action, Map<String, String> q) {
        long now = System.currentTimeMillis() / 1000;
        switch (action) {
            case "":
                return "{\"user_info\":{\"auth\":1,\"status\":\"Active\",\"exp_date\":\"" + (now + 90L * 86400) + "\",\"max_connections\":\"3\",\"active_cons\":\"0\"},"
                    + "\"server_info\":{\"timezone\":\"America/New_York\"}}";
            case "get_live_categories": return cats(LIVE_CATS);
            case "get_live_streams": {
                StringBuilder sb = new StringBuilder("[");
                int n = 1;
                for (String[] c : CHANNELS) {
                    if (sb.length() > 1) sb.append(',');
                    sb.append("{\"num\":").append(n++).append(",\"name\":\"").append(c[1]).append("\",\"stream_id\":").append(c[0])
                        .append(",\"stream_icon\":\"\",\"epg_channel_id\":\"ch").append(c[0]).append("\",\"category_id\":\"").append(c[2])
                        .append("\",\"tv_archive\":").append(c[3].equals("0") ? 0 : 1).append(",\"tv_archive_duration\":").append(c[3]).append('}');
                }
                return sb.append(']').toString();
            }
            case "get_short_epg":
            case "get_simple_data_table":
                return epg(q.getOrDefault("stream_id", "0"), now);
            default:
                return "[]";
        }
    }

    /** A second provider: a plain M3U playlist (no login) with a few more channels. */
    static String playlist() {
        String[][] ch = {
            {"UK| SKY SPORTS MAIN EVENT", "UK Sports", "/live/m3u/501.m3u8"},
            {"UK| SKY SPORTS GOLF", "UK Sports", "/live/m3u/502.m3u8"},
            {"CA| TSN 1", "Canada Sports", "/live/m3u/503.m3u8"},
            {"CA| CBC", "Canada", "/live/m3u/504.m3u8"},
        };
        StringBuilder sb = new StringBuilder("#EXTM3U\n");
        for (String[] c : ch) {
            sb.append("#EXTINF:-1 tvg-id=\"\" group-title=\"").append(c[1]).append("\",").append(c[0]).append('\n')
                .append("http://127.0.0.1:8085").append(c[2]).append('\n');
        }
        return sb.toString();
    }


    /** Hour-long programs from 6 hours ago to a day ahead, titled to look like sports TV. */
    static String epg(String streamId, long now) {
        String[] titles = {"SportsCenter", "NFL Live", "College Football: Ohio State at Penn State", "First Take", "Around the Horn",
            "MLB Postseason: Game 3", "Inside the NFL", "Golf Central", "The Evening News", "PGA Tour Highlights"};
        long start = now - now % 3600 - 6 * 3600;
        int seed = Integer.parseInt(streamId.replaceAll("\\D", "0"));
        StringBuilder sb = new StringBuilder("{\"epg_listings\":[");
        for (int i = 0; i < 30; i++) {
            long s = start + i * 3600L;
            String t = titles[Math.floorMod(seed + i, titles.length)];
            if (i > 0) sb.append(',');
            sb.append("{\"title\":\"").append(b64(t)).append("\",\"description\":\"").append(b64("Sample guide entry for " + t + ".")).append("\",")
                .append("\"start_timestamp\":\"").append(s).append("\",\"stop_timestamp\":\"").append(s + 3600).append("\",\"has_archive\":")
                .append(s + 3600 <= now ? 1 : 0).append('}');
        }
        return sb.append("]}").toString();
    }

    static String cats(String[][] cats) {
        StringBuilder sb = new StringBuilder("[");
        for (String[] c : cats) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"category_id\":\"").append(c[0]).append("\",\"category_name\":\"").append(c[1]).append("\"}");
        }
        return sb.append(']').toString();
    }

    static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) out.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    static void json(HttpExchange ex, String body) throws IOException { send(ex, 200, "application/json", body); }

    static void redirect(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().add("Location", to);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
