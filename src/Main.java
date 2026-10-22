import java.awt.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// ======================= KONFIGURATION =======================

/** Wie oft geprüft wird. Bitte nicht unter ~2 Minuten gehen, sonst blockt FUTBIN dich. */
static final Duration INTERVAL = Duration.ofMinutes(5);

/**
 * Push aufs Handy über https://ntfy.sh (kostenlos, kein Account).
 * Das Topic steht NICHT im Code (sonst könnte es jeder lesen, wenn das Projekt auf GitHub liegt), sondern
 * - lokal in der Datei "ntfy-topic.txt" im Projektordner (ist in .gitignore), oder
 * - auf dem Server in der Umgebungsvariable NTFY_TOPIC (bei GitHub Actions als Secret).
 * Wer das Topic kennt, kann deine Benachrichtigungen mitlesen – also schwer zu erraten wählen.
 */
static final String NTFY_TOPIC = loadNtfyTopic();

/** Hier merkt sich der Bot, für welche Spieler er schon benachrichtigt hat (überlebt Neustarts). */
static final Path STATE_FILE = Path.of("state.txt");

/** RUN_ONCE=true -> nur ein Durchlauf und dann beenden (für GitHub Actions, das den Bot regelmäßig neu startet). */
static final boolean RUN_ONCE = Boolean.parseBoolean(System.getenv("RUN_ONCE"));

enum Platform { PS, PC }

/** UNTER = melden, wenn Preis <= Zielpreis (Kaufen). UEBER = melden, wenn Preis >= Zielpreis (Verkaufen). */
enum Direction { UNTER, UEBER }

record Watch(String name, String url, Platform platform, long targetPrice, Direction direction) {}

/** Hier deine Spieler eintragen: URL einfach aus dem Browser von der FUTBIN-Spielerseite kopieren. */
static final List<Watch> WATCHES = List.of(
        new Watch("Paz", "https://www.futbin.com/27/player/23115/nicolas-paz-martinez", Platform.PS, 180_000, Direction.UNTER)
        // , new Watch("Name", "https://www.futbin.com/27/player/.../...", Platform.PC, 50_000, Direction.UEBER)
);

// ==============================================================

static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

/** Spieler, für die schon benachrichtigt wurde – damit du nicht alle 5 Minuten dieselbe Meldung bekommst. */
static final Set<String> alreadyNotified = new HashSet<>();

static TrayIcon trayIcon;

void main(String[] args) throws Exception {
    if (args.length > 0 && args[0].equals("test")) {
        notify("FUTBIN-Preisbot", "Test: Benachrichtigungen funktionieren!", "https://www.futbin.com");
        System.exit(0);
    }

    setupTray();
    loadState();
    IO.println("FUTBIN-Preisbot gestartet. Prüfe " + WATCHES.size() + " Spieler"
            + (RUN_ONCE ? " (einmalig)." : " alle " + INTERVAL.toMinutes() + " Minuten.")
            + (NTFY_TOPIC.isBlank() ? " Handy-Push: AUS" : " Handy-Push: AN"));

    while (true) {
        for (Watch watch : WATCHES) {
            check(watch);
            Thread.sleep(3_000); // kleine Pause zwischen Anfragen
        }
        saveState();
        if (RUN_ONCE) System.exit(0);
        Thread.sleep(INTERVAL);
    }
}

static String key(Watch w) {
    return w.url() + "|" + w.platform() + "|" + w.targetPrice() + "|" + w.direction();
}

static String loadNtfyTopic() {
    String env = System.getenv("NTFY_TOPIC");
    if (env != null && !env.isBlank()) return env.trim();
    try {
        return Files.readString(Path.of("ntfy-topic.txt")).trim();
    } catch (Exception e) {
        return "";
    }
}

void loadState() {
    try {
        if (Files.exists(STATE_FILE)) alreadyNotified.addAll(Files.readAllLines(STATE_FILE));
    } catch (Exception e) {
        IO.println("Konnte " + STATE_FILE + " nicht lesen: " + e.getMessage());
    }
}

void saveState() {
    try {
        Files.write(STATE_FILE, alreadyNotified);
    } catch (Exception e) {
        IO.println("Konnte " + STATE_FILE + " nicht speichern: " + e.getMessage());
    }
}

void check(Watch watch) {
    String now = LocalTime.now().format(TIME);
    try {
        long price = fetchPrice(watch.url(), watch.platform());
        if (price <= 0) {
            IO.println("[" + now + "] " + watch.name() + ": aktuell kein Preis auf " + watch.platform());
            return;
        }

        boolean hit = switch (watch.direction()) {
            case UNTER -> price <= watch.targetPrice();
            case UEBER -> price >= watch.targetPrice();
        };
        IO.println("[" + now + "] " + watch.name() + " (" + watch.platform() + "): " + fmt(price)
                + " | Ziel " + (watch.direction() == Direction.UNTER ? "<= " : ">= ") + fmt(watch.targetPrice())
                + (hit ? "  -> ZIEL ERREICHT" : ""));

        if (hit && alreadyNotified.add(key(watch))) {
            notify("FUTBIN: " + watch.name(),
                    watch.name() + " steht bei " + fmt(price) + " Coins (" + watch.platform() + ")", watch.url());
        } else if (!hit) {
            alreadyNotified.remove(key(watch)); // Preis wieder raus aus dem Zielbereich -> nächstes Mal wieder melden
        }
    } catch (Exception e) {
        IO.println("[" + now + "] Fehler bei " + watch.name() + ": " + e.getMessage());
    }
}

/** Lädt die Spielerseite und liest den niedrigsten BIN-Preis für die gewünschte Plattform aus. */
long fetchPrice(String url, Platform platform) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36")
            .header("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
        throw new IllegalStateException("HTTP " + response.statusCode());
    }

    // <div class="price-box platform-ps-only price-box-original-player" ...> ... <div class="price inline-with-icon lowest-price-1">7,300,000<img ...
    Pattern pattern = Pattern.compile(
            "platform-" + platform.name().toLowerCase() + "-only price-box-original-player[\\s\\S]*?lowest-price-1\">\\s*([\\d.,]+)");
    Matcher m = pattern.matcher(response.body());
    if (!m.find()) {
        throw new IllegalStateException("Preis nicht gefunden – hat FUTBIN das Seitenlayout geändert?");
    }
    return Long.parseLong(m.group(1).replaceAll("[.,]", ""));
}

void setupTray() {
    if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) return; // z. B. auf einem Server ohne Bildschirm
    try {
        Image image = Toolkit.getDefaultToolkit().createImage(new byte[0]);
        trayIcon = new TrayIcon(image, "FUTBIN-Preisbot");
        trayIcon.setImageAutoSize(true);
        SystemTray.getSystemTray().add(trayIcon);
    } catch (AWTException e) {
        IO.println("Windows-Benachrichtigungen nicht verfügbar: " + e.getMessage());
    }
}

void notify(String title, String message, String url) {
    IO.println(">>> BENACHRICHTIGUNG: " + message);
    if (trayIcon != null) {
        Toolkit.getDefaultToolkit().beep();
        trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
    }
    if (!NTFY_TOPIC.isBlank()) {
        try {
            String json = "{\"topic\":" + jsonString(NTFY_TOPIC) + ",\"title\":" + jsonString(title)
                    + ",\"message\":" + jsonString(message) + ",\"click\":" + jsonString(url) + ",\"tags\":[\"soccer\"]}";
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://ntfy.sh/"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            int status = HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != 200) IO.println("ntfy-Push fehlgeschlagen: HTTP " + status);
        } catch (Exception e) {
            IO.println("ntfy-Push fehlgeschlagen: " + e.getMessage());
        }
    }
}

static String jsonString(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
}

static String fmt(long coins) {
    return String.format("%,d", coins).replace(',', '.');
}
