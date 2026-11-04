package btools.router;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Puente sencillo hacia BRouter (motor de rutas sin internet). Vive en el paquete btools.router para leer los giros. */
public class OpenCarRouter {
    public static class Hint {
        public double lat, lon;
        public int cmd, exit, idx;
        public double distFromStart;   // metros desde el inicio de la ruta
        public String name = "";
        public String text = "";
    }

    public static class Result {
        public String error;
        public double[] lats, lons;
        public double[] cum;           // metros acumulados por punto
        public int distance, seconds;
        public List<Hint> hints = new ArrayList<>();
    }

    private static OsmNodeNamed node(String name, double lat, double lon) {
        OsmNodeNamed n = new OsmNodeNamed();
        n.name = name;
        n.ilon = (int) ((lon + 180.) * 1000000. + 0.5);
        n.ilat = (int) ((lat + 90.) * 1000000. + 0.5);
        return n;
    }

    private static String nameFrom(String kv) {
        if (kv == null) return "";
        int i = kv.indexOf("name=");
        if (i < 0) return "";
        int s = i + 5, e = kv.length();
        int j = kv.indexOf(" ", s);
        // los valores con espacios vienen con '_' o separados por ' '; BRouter guarda name=Av_Busch
        if (j > 0) e = j;
        return kv.substring(s, e).replace('_', ' ');
    }

    public static Result route(File segDir, String profilePath, double fLat, double fLon, double tLat, double tLon, long timeoutMs) {
        Result r = new Result();
        try {
            RoutingContext rc = new RoutingContext();
            rc.localFunction = profilePath;
            rc.turnInstructionMode = 2;
            rc.turnInstructionRoundabouts = true;
            rc.processUnusedTags = true;
            List<OsmNodeNamed> wp = new ArrayList<>();
            wp.add(node("from", fLat, fLon));
            wp.add(node("to", tLat, tLon));
            RoutingEngine re = new RoutingEngine(null, null, segDir, wp, rc);
            re.quite = true;
            re.doRun(timeoutMs);
            if (re.getErrorMessage() != null) { r.error = re.getErrorMessage(); return r; }
            OsmTrack t = re.getFoundTrack();
            if (t == null) { r.error = "sin ruta"; return r; }
            if (t.voiceHints == null || t.voiceHints.list == null || t.voiceHints.list.isEmpty()) {
                try { t.processVoiceHints(rc); } catch (Throwable e) { }
            }
            int n = t.nodes.size();
            r.lats = new double[n]; r.lons = new double[n]; r.cum = new double[n];
            double acc = 0;
            for (int i = 0; i < n; i++) {
                OsmPathElement p = t.nodes.get(i);
                r.lats[i] = p.getILat() / 1000000. - 90.;
                r.lons[i] = p.getILon() / 1000000. - 180.;
                if (i > 0) acc += p.calcDistance(t.nodes.get(i - 1));
                r.cum[i] = acc;
            }
            r.distance = t.distance > 0 ? t.distance : (int) acc;
            try { r.seconds = t.getTotalSeconds(); } catch (Throwable e) { r.seconds = 0; }
            if (r.seconds <= 0) r.seconds = (int) (r.distance / 9.0);
            if (t.voiceHints != null && t.voiceHints.list != null) {
                for (VoiceHint v : t.voiceHints.list) {
                    Hint h = new Hint();
                    h.idx = Math.max(0, Math.min(n - 1, v.indexInTrack));
                    h.lat = r.lats[h.idx];
                    h.lon = r.lons[h.idx];
                    h.cmd = v.cmd;
                    h.exit = v.roundaboutExit;
                    h.distFromStart = r.cum[h.idx];
                    h.name = v.goodWay != null ? nameFrom(v.goodWay.wayKeyValues) : "";
                    h.text = v.getCommandString();
                    r.hints.add(h);
                }
            }
            return r;
        } catch (Throwable e) {
            r.error = e.toString();
            return r;
        }
    }
}
