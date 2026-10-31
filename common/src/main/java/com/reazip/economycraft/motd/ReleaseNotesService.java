package com.reazip.economycraft.motd;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.reazip.economycraft.util.EconomyPaths;
import com.reazip.economycraft.util.ChatCompat;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads and displays a compact preview of the latest published GitHub release. */
public final class ReleaseNotesService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final URI LATEST_RELEASE_URI = URI.create(
            "https://api.github.com/repos/TraiNguyenVan/EconomyCraft/releases/latest");
    private static final String RELEASE_PATH_PREFIX = "/TraiNguyenVan/EconomyCraft/releases/tag/";
    private static final Path CACHE_RELATIVE_PATH = Path.of("release-motd-cache.json");
    private static final int MAX_RESPONSE_CHARS = 512_000;
    private static final int MAX_TITLE_CHARS = 100;
    private static final int MAX_CACHED_TITLES = 20;
    private static final Pattern PULL_REQUEST_URL = Pattern.compile(
            "https://github\\.com/TraiNguyenVan/EconomyCraft/pull/[0-9]+(?:\\b|$)");
    private static final Pattern AUTHOR_SUFFIX = Pattern.compile("\\s+by\\s+@[^\\s]+.*$");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("^\\[([^]]+)]\\([^)]*\\)$");
    private static final Pattern MARKDOWN_NOISE = Pattern.compile("[`*_~]");
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ExecutorService FETCH_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "EconomyCraft-release-motd");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicLong SERVER_RUN = new AtomicLong();

    private static volatile ReleaseInfo currentRelease;

    private ReleaseNotesService() {}

    /** Loads the last good response immediately, then fetches the latest release off-thread. */
    public static void start(MinecraftServer server) {
        long serverRun = SERVER_RUN.incrementAndGet();
        Path cachePath = EconomyPaths.configDir(server).resolve(CACHE_RELATIVE_PATH);
        currentRelease = loadCache(cachePath);
        FETCH_EXECUTOR.execute(() -> fetchLatest(cachePath, serverRun));
    }

    /** Builds the cached release preview, applying the configured maximum to the newest titles. */
    public static List<Component> createMessages(int maxChanges) {
        ReleaseInfo release = currentRelease;
        if (release == null) return List.of();

        List<Component> messages = new ArrayList<>();
        messages.add(Component.literal("EconomyCraft v" + release.version)
                .withStyle(style -> style.withColor(ChatFormatting.GOLD)
                        .withBold(true)
                        .withUnderlined(true)
                        .withClickEvent(ChatCompat.openUrlEvent(release.releaseUrl))));
        int count = Math.min(Math.clamp(maxChanges, 0, MAX_CACHED_TITLES), release.pullRequestTitles.size());
        int firstTitle = release.pullRequestTitles.size() - count;
        for (int i = firstTitle; i < release.pullRequestTitles.size(); i++) {
            messages.add(Component.literal("• " + release.pullRequestTitles.get(i)).withStyle(ChatFormatting.GRAY));
        }
        return List.copyOf(messages);
    }

    /** Sends the cached release preview to a player using the configured title limit. */
    public static void sendTo(ServerPlayer player, int maxChanges) {
        if (player == null) return;
        createMessages(maxChanges).forEach(player::sendSystemMessage);
    }

    private static void fetchLatest(Path cachePath, long serverRun) {
        try {
            HttpRequest request = HttpRequest.newBuilder(LATEST_RELEASE_URI)
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "EconomyCraft")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                LOGGER.warn("[EconomyCraft] GitHub release lookup returned HTTP {}", response.statusCode());
                return;
            }
            if (response.body() == null || response.body().length() > MAX_RESPONSE_CHARS) {
                LOGGER.warn("[EconomyCraft] GitHub release response was empty or too large");
                return;
            }

            ReleaseInfo fetched = parseRelease(response.body());
            if (fetched == null) {
                LOGGER.warn("[EconomyCraft] GitHub latest release response did not contain valid release notes");
                return;
            }

            if (SERVER_RUN.get() != serverRun) return;
            currentRelease = fetched;
            saveCache(cachePath, fetched);
            LOGGER.info("[EconomyCraft] Loaded login MOTD notes for release {}", fetched.version);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.debug("[EconomyCraft] GitHub release lookup was interrupted");
        } catch (Exception e) {
            LOGGER.warn("[EconomyCraft] Could not fetch the latest GitHub release; using the saved MOTD cache if available: {}",
                    e.getMessage());
        }
    }

    private static ReleaseInfo parseRelease(String responseBody) {
        try {
            JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
            if (json.has("draft") && json.get("draft").getAsBoolean()) return null;
            if (json.has("prerelease") && json.get("prerelease").getAsBoolean()) return null;

            String tagName = stringField(json, "tag_name");
            String releaseUrl = safeReleaseUrl(stringField(json, "html_url"));
            if (tagName == null || !tagName.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")
                    || releaseUrl == null) return null;

            String body = stringField(json, "body");
            return new ReleaseInfo(tagName.startsWith("v") ? tagName.substring(1) : tagName,
                    releaseUrl, extractPullRequestTitles(body));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String stringField(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) return null;
        String value = object.get(field).getAsString().strip();
        return value.isEmpty() ? null : value;
    }

    private static String safeReleaseUrl(String candidate) {
        if (candidate == null) return null;
        try {
            URI uri = URI.create(candidate);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getUserInfo() != null || uri.getPath() == null
                    || !uri.getPath().startsWith(RELEASE_PATH_PREFIX)) return null;
            return uri.toASCIIString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> extractPullRequestTitles(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<String> titles = new ArrayList<>(MAX_CACHED_TITLES);
        boolean hasSections = body.lines().anyMatch(line -> line.startsWith("## "));
        boolean inChanges = !hasSections;
        for (String rawLine : body.split("\\R")) {
            String line = rawLine.strip();
            if (line.startsWith("## ")) {
                inChanges = line.toLowerCase(Locale.ROOT).contains("what's changed")
                        || line.toLowerCase(Locale.ROOT).contains("what’s changed");
                continue;
            }
            if (!inChanges || !(line.startsWith("* ") || line.startsWith("- "))
                    || !PULL_REQUEST_URL.matcher(line).find()) continue;

            String title = line.substring(2).strip();
            title = AUTHOR_SUFFIX.matcher(title).replaceFirst("").strip();
            Matcher markdownLink = MARKDOWN_LINK.matcher(title);
            if (markdownLink.matches()) title = markdownLink.group(1).strip();
            title = MARKDOWN_NOISE.matcher(title).replaceAll("").strip();
            title = title.replaceAll("\\s+", " ");
            if (title.isEmpty()) continue;
            String normalizedTitle = title.toLowerCase(Locale.ROOT);
            if (normalizedTitle.startsWith("chore(release):") || normalizedTitle.startsWith("release:")) continue;
            if (title.length() > MAX_TITLE_CHARS) title = title.substring(0, MAX_TITLE_CHARS - 1).stripTrailing() + "…";
            titles.add(title);
        }
        int firstVisibleTitle = Math.max(0, titles.size() - MAX_CACHED_TITLES);
        return List.copyOf(titles.subList(firstVisibleTitle, titles.size()));
    }

    private static ReleaseInfo loadCache(Path path) {
        if (!Files.isRegularFile(path)) return null;
        try {
            ReleaseInfo cached = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), ReleaseInfo.class);
            if (cached == null || cached.version == null || !cached.version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?")
                    || safeReleaseUrl(cached.releaseUrl) == null) return null;
            List<String> titles = cached.pullRequestTitles == null ? List.of()
                    : cached.pullRequestTitles.stream().filter(title -> title != null && !title.isBlank())
                    .map(title -> title.length() > MAX_TITLE_CHARS
                            ? title.substring(0, MAX_TITLE_CHARS - 1).stripTrailing() + "…" : title.strip())
                    .toList();
            if (titles.size() > MAX_CACHED_TITLES) {
                titles = List.copyOf(titles.subList(titles.size() - MAX_CACHED_TITLES, titles.size()));
            }
            return new ReleaseInfo(cached.version, cached.releaseUrl, titles);
        } catch (Exception e) {
            LOGGER.warn("[EconomyCraft] Could not read the saved release notes cache: {}", e.getMessage());
            return null;
        }
    }

    private static void saveCache(Path path, ReleaseInfo release) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, GSON.toJson(release), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The cache is optional. A failed cleanup must not affect server startup or status pings.
            }
            LOGGER.warn("[EconomyCraft] Could not persist the latest release notes cache: {}", e.getMessage());
        }
    }

    private static final class ReleaseInfo {
        private String version;
        private String releaseUrl;
        private List<String> pullRequestTitles;

        @SuppressWarnings("unused")
        private ReleaseInfo() {}

        private ReleaseInfo(String version, String releaseUrl, List<String> pullRequestTitles) {
            this.version = version;
            this.releaseUrl = releaseUrl;
            this.pullRequestTitles = pullRequestTitles;
        }
    }
}
