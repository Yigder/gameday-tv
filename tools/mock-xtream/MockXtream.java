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
 * Live channels and replays redirect to public sample streams; movies and episodes to sample MP4s.
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
    static final String BBB_POSTER = "https://upload.wikimedia.org/wikipedia/commons/c/c5/Big_buck_bunny_poster_big.jpg";
    static final String TOS_IMAGE = "https://mango.blender.org/wp-content/uploads/2013/05/01_thom_celia_bridge.jpg";
    static final String PEACH_IMAGE = "https://peach.blender.org/wp-content/uploads/title_anouncement.jpg";
    static final String[][] MOVIES = {
        // id, name, category, video URL, poster, year, rating
        {"201", "Big Buck Bunny", "1", "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s_5MB.mp4", BBB_POSTER, "2008", "7.2"},
        {"202", "Sintel (trailer)", "1", "https://download.blender.org/durian/trailer/sintel_trailer-480p.mp4", PEACH_IMAGE, "2010", "7.4"},
        {"203", "Tears of Steel", "1", "https://test-videos.co.uk/vids/sintel/mp4/h264/720/Sintel_720_10s_5MB.mp4", TOS_IMAGE, "2012", "6.3"},
        {"204", "Jellyfish", "3", "https://test-videos.co.uk/vids/jellyfish/mp4/h264/720/Jellyfish_720_10s_5MB.mp4", PEACH_IMAGE, "2013", "5.9"},
        {"205", "Bunny Returns", "2", "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4", BBB_POSTER, "2014", "6.1"},
        {"206", "Steel Bridge", "3", "https://download.blender.org/durian/trailer/sintel_trailer-480p.mp4", TOS_IMAGE, "2015", "5.5"},
    };    static final String[][] VOD_CATS = {{"1", "Action"}, {"2", "Comedy"}, {"3", "Documentary"}};

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
            } else if (path.startsWith("/addon/")) {
                addon(ex, path.substring("/addon/".length()));
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
            } else if (path.startsWith("/movie/")) {
                String id = path.substring(path.lastIndexOf('/') + 1).replaceAll("\\..*", "");
                for (String[] m : MOVIES) if (m[0].equals(id)) { redirect(ex, m[3]); return; }
                send(ex, 404, "text/plain", "no movie");
            } else if (path.startsWith("/series/")) {
                String id = path.substring(path.lastIndexOf('/') + 1).replaceAll("\\..*", "");
                int i = Math.floorMod(Integer.parseInt(id), MOVIES.length);
                redirect(ex, MOVIES[i][3]);
            } else if (path.startsWith("/timeshift/")) {
                redirect(ex, MOVIES[1][3]);
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
            case "get_vod_categories": return cats(VOD_CATS);
            case "get_series_categories": return "[{\"category_id\":\"1\",\"category_name\":\"Open Movies\"}]";
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
            case "get_vod_streams": {
                StringBuilder sb = new StringBuilder("[");
                for (String[] m : MOVIES) {
                    if (sb.length() > 1) sb.append(',');
                    sb.append("{\"name\":\"").append(m[1]).append("\",\"stream_id\":").append(m[0]).append(",\"stream_icon\":\"").append(m[4])
                        .append("\",\"rating\":\"").append(m[6]).append("\",\"added\":\"").append(now - Integer.parseInt(m[0]) * 3600L)
                        .append("\",\"category_id\":\"").append(m[2]).append("\",\"container_extension\":\"mp4\"}");
                }
                return sb.append(']').toString();
            }
            case "get_vod_info": {
                String id = q.getOrDefault("vod_id", "");
                for (String[] m : MOVIES) if (m[0].equals(id)) {
                    return "{\"info\":{\"name\":\"" + m[1] + "\",\"plot\":\"An open movie from the Blender Foundation and friends, used here as sample content.\",\"cast\":\"Open Movie Project\","
                        + "\"director\":\"Blender Foundation\",\"genre\":\"Animation\",\"releasedate\":\"" + m[5] + "-01-01\",\"duration_secs\":\"596\",\"rating\":\"" + m[6] + "\","
                        + "\"backdrop_path\":[\"" + m[4] + "\"]},\"movie_data\":{\"stream_id\":" + m[0] + ",\"container_extension\":\"mp4\"}}";
                }
                return "{}";
            }
            case "get_series":
                return "[{\"series_id\":301,\"name\":\"Open Movie Shorts\",\"cover\":\"" + BBB_POSTER + "\",\"plot\":\"Short films from the open movie projects, as a show.\","
                    + "\"genre\":\"Animation\",\"releaseDate\":\"2010-01-01\",\"rating\":\"7\",\"last_modified\":\"" + now + "\",\"category_id\":\"1\",\"backdrop_path\":[\"" + TOS_IMAGE + "\"]}]";
            case "get_series_info": {
                StringBuilder eps = new StringBuilder("{");
                for (int s = 1; s <= 2; s++) {
                    if (s > 1) eps.append(',');
                    eps.append('"').append(s).append("\":[");
                    for (int e = 1; e <= 4; e++) {
                        int id = 400 + s * 10 + e;
                        String[] m = MOVIES[Math.floorMod(id, MOVIES.length)];
                        if (e > 1) eps.append(',');
                        eps.append("{\"id\":\"").append(id).append("\",\"episode_num\":").append(e).append(",\"title\":\"").append(m[1])
                            .append("\",\"container_extension\":\"mp4\",\"season\":").append(s).append(",\"info\":{\"plot\":\"Episode ").append(e)
                            .append(" of season ").append(s).append(".\",\"duration_secs\":\"596\",\"movie_image\":\"").append(m[4]).append("\"}}");
                    }
                    eps.append(']');
                }
                eps.append('}');
                return "{\"seasons\":[{\"season_number\":1},{\"season_number\":2}],\"info\":{\"name\":\"Open Movie Shorts\"},\"episodes\":" + eps + "}";
            }
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

    /**
     * A fake Stremio add-on (catalog, details and sources). Its sources also answer for Cinemeta's
     * "tt" titles, so On Demand can be tried end to end with sample videos.
     */
    static void addon(HttpExchange ex, String rest) throws IOException {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        if (rest.equals("manifest.json")) {
            json(ex, "{\"id\":\"dev.gameday.mock\",\"version\":\"1.0.0\",\"name\":\"Mock Streams\",\"description\":\"Sample sources for testing.\","
                + "\"types\":[\"movie\",\"series\"],\"idPrefixes\":[\"tt\",\"mk\"],"
                + "\"resources\":[\"catalog\",{\"name\":\"meta\",\"types\":[\"movie\",\"series\"],\"idPrefixes\":[\"mk\"]},\"stream\",\"subtitles\"],"
                + "\"catalogs\":[{\"type\":\"movie\",\"id\":\"mock\",\"name\":\"Open Movies\",\"extra\":[{\"name\":\"search\"},{\"name\":\"skip\"}]},"
                + "{\"type\":\"series\",\"id\":\"mockshows\",\"name\":\"Open Shorts\"}]}");
            return;
        }
        String[] parts = rest.replace(".json", "").split("/");
        String resource = parts[0];
        String type = parts.length > 1 ? parts[1] : "";
        String id = parts.length > 2 ? URLDecoder.decode(parts[2], StandardCharsets.UTF_8) : "";
        String extra = parts.length > 3 ? URLDecoder.decode(parts[3], StandardCharsets.UTF_8) : "";
        switch (resource) {
            case "catalog": {
                if (extra.contains("skip=")) { json(ex, "{\"metas\":[]}"); return; }
                StringBuilder sb = new StringBuilder("{\"metas\":[");
                if (type.equals("series")) {
                    sb.append("{\"id\":\"mks1\",\"type\":\"series\",\"name\":\"Open Shorts\",\"poster\":\"").append(BBB_POSTER)
                        .append("\",\"background\":\"").append(TOS_IMAGE).append("\",\"releaseInfo\":\"2010\",\"description\":\"Open movies as a show.\"}");
                } else {
                    String q = extra.startsWith("search=") ? extra.substring(7).toLowerCase() : "";
                    boolean first = true;
                    for (String[] m : MOVIES) {
                        if (!q.isEmpty() && !m[1].toLowerCase().contains(q)) continue;
                        if (!first) sb.append(',');
                        first = false;
                        sb.append("{\"id\":\"mk").append(m[0]).append("\",\"type\":\"movie\",\"name\":\"").append(m[1]).append("\",\"poster\":\"").append(m[4])
                            .append("\",\"background\":\"").append(m[4]).append("\",\"releaseInfo\":\"").append(m[5]).append("\",\"imdbRating\":\"").append(m[6])
                            .append("\",\"genres\":[\"Animation\"],\"description\":\"An open movie, used as sample content.\"}");
                    }
                }
                json(ex, sb.append("]}").toString());
                return;
            }
            case "meta": {
                if (id.equals("mks1")) {
                    StringBuilder v = new StringBuilder();
                    for (int s = 1; s <= 2; s++) for (int e = 1; e <= 3; e++) {
                        if (v.length() > 0) v.append(',');
                        v.append("{\"id\":\"mks1:").append(s).append(':').append(e).append("\",\"name\":\"Short ").append(s).append('.').append(e)
                            .append("\",\"season\":").append(s).append(",\"episode\":").append(e).append(",\"released\":\"2010-0").append(s).append("-0").append(e)
                            .append("T00:00:00.000Z\",\"thumbnail\":\"").append(MOVIES[(s * 3 + e) % MOVIES.length][4]).append("\",\"overview\":\"Sample episode.\"}");
                    }
                    json(ex, "{\"meta\":{\"id\":\"mks1\",\"type\":\"series\",\"name\":\"Open Shorts\",\"poster\":\"" + BBB_POSTER + "\",\"background\":\"" + TOS_IMAGE
                        + "\",\"releaseInfo\":\"2010\",\"description\":\"Open movies as a show.\",\"videos\":[" + v + "]}}");
                    return;
                }
                for (String[] m : MOVIES) if (("mk" + m[0]).equals(id)) {
                    json(ex, "{\"meta\":{\"id\":\"" + id + "\",\"type\":\"movie\",\"name\":\"" + m[1] + "\",\"poster\":\"" + m[4] + "\",\"background\":\"" + m[4]
                        + "\",\"releaseInfo\":\"" + m[5] + "\",\"imdbRating\":\"" + m[6] + "\",\"runtime\":\"10 min\",\"genres\":[\"Animation\"],"
                        + "\"cast\":[\"Open Movie Project\"],\"director\":[\"Blender Foundation\"],\"description\":\"An open movie, used as sample content.\"}}");
                    return;
                }
                json(ex, "{\"meta\":null}");
                return;
            }
            case "stream": {
                int pick = Math.floorMod(id.hashCode(), MOVIES.length);
                json(ex, "{\"streams\":["
                    + "{\"name\":\"Mock 1080p\",\"title\":\"Sample.1080p.mp4\\n💾 5 MB\",\"url\":\"" + MOVIES[pick][3] + "\",\"behaviorHints\":{\"bingeGroup\":\"mock-1080\"}},"
                    + "{\"name\":\"Mock 720p\",\"title\":\"Sample.720p.mp4 (needs a header)\",\"url\":\"http://127.0.0.1:8085/addon/hdr/" + pick + "\","
                    + "\"behaviorHints\":{\"proxyHeaders\":{\"request\":{\"X-Mock-Key\":\"ok\"}}}},"
                    + "{\"name\":\"Mock torrent\",\"title\":\"Sample.2160p.mkv 💾 4.2 GB\",\"infoHash\":\"0123456789abcdef0123456789abcdef01234567\",\"fileIdx\":0}"
                    + "]}");
                return;
            }
            case "subtitles": {
                // Two languages: SubRip and WebVTT files, served below.
                json(ex, "{\"subtitles\":[{\"id\":\"mock-en\",\"url\":\"http://127.0.0.1:8085/addon/sub/en.srt\",\"lang\":\"eng\"},"
                    + "{\"id\":\"mock-es\",\"url\":\"http://127.0.0.1:8085/addon/sub/es.vtt\",\"lang\":\"spa\"}]}");
                return;
            }
            case "sub": {
                // A line every 3 seconds for 15 minutes, saying where it should appear.
                boolean vtt = type.endsWith(".vtt");
                String word = type.startsWith("es") ? "Subtítulo de prueba" : "Test subtitle";
                StringBuilder sb = new StringBuilder(vtt ? "WEBVTT\n\n" : "");
                for (int i = 0; i < 300; i++) {
                    int s = i * 3;
                    String a = String.format("%02d:%02d:%02d%s000", s / 3600, s / 60 % 60, s % 60, vtt ? "." : ",");
                    String b = String.format("%02d:%02d:%02d%s500", (s + 2) / 3600, (s + 2) / 60 % 60, (s + 2) % 60, vtt ? "." : ",");
                    if (!vtt) sb.append(i + 1).append('\n');
                    sb.append(a).append(" --> ").append(b).append('\n').append(word).append(" ").append(i + 1)
                        .append("\n<i>at ").append(s / 60).append(':').append(String.format("%02d", s % 60)).append("</i>\n\n");
                }
                send(ex, 200, vtt ? "text/vtt; charset=utf-8" : "application/x-subrip; charset=utf-8", sb.toString());
                return;
            }
            case "hdr": {
                // Only plays when the add-on's header is sent.
                if (!"ok".equals(ex.getRequestHeaders().getFirst("X-Mock-Key"))) { send(ex, 403, "text/plain", "missing header"); return; }
                redirect(ex, MOVIES[Integer.parseInt(type.isEmpty() ? "0" : type) % MOVIES.length][3]);
                return;
            }
            default:
                send(ex, 404, "text/plain", "unknown add-on resource");
        }
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
