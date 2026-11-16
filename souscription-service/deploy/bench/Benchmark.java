import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Banc de mesure NFG-007 a NFG-013 de souscription-service (plan 40, W8-T05).
 *
 * <p>Lance l'artefact package ({@code target/quarkus-app/quarkus-run.jar}) en N repliques sur un
 * PostgreSQL reel, derriere un repartiteur round-robin sans affinite tenu par le banc (role de
 * souscription-gateway / du Service Kubernetes), avec les bouchons des deux contrats consommes.
 * Chaque valeur du rapport est mesuree ici : aucune n'est recopiee d'une cible.
 *
 * <p>Lancement : {@code deploy/bench/run-benchmark.sh} (construit le classpath : pilote PostgreSQL
 * et {@code target/test-classes} pour l'arithmetique de projection du bouchon de contrat).
 * Identifiants de base : {@code BENCH_DB_ADMIN_URL}, {@code BENCH_DB_USER},
 * {@code BENCH_DB_PASSWORD} (environnement) ; aucun identifiant n'est code en dur.
 *
 * <p>Sorties : {@code target/benchmark/results.json}, {@code target/benchmark/results.md}, les
 * journaux et les captures {@code /q/metrics} de chaque replique.
 */
public final class Benchmark {

    // ---- parametres (surchargeables par l'environnement) ----------------------------------
    static final int REPLICAS = intEnv("BENCH_REPLICAS", 4);
    /** Plafond de concurrence emis par souscription-gateway (cloison du contrat : 30 appels). */
    static final int VIRTUAL_USERS = intEnv("BENCH_VUS", 30);
    static final int LOAD_SECONDS = intEnv("BENCH_LOAD_SECONDS", 90);
    static final int WARMUP_SECONDS = intEnv("BENCH_WARMUP_SECONDS", 15);
    /** Enveloppe memoire emulee du conteneur (equivalent de la limite cgroup). */
    static final int MEMORY_LIMIT_MB = intEnv("BENCH_MEMORY_LIMIT_MB", 512);
    /** Retention comprimee : rend observable en minutes l'etat stationnaire de la purge PT20M. */
    /**
     * Debit nominal (requetes/s, toutes repliques) : plafond que souscription-gateway peut emettre
     * au SLA du contrat entrant, cloison de 30 appels / p50 de 40 ms = 750 requetes/s.
     */
    static final double NOMINAL_RPS = Double.parseDouble(env("BENCH_NOMINAL_RPS", "750"));
    static final boolean NMT = "1".equals(env("BENCH_NMT", "0"));
    /** Collecteur retenu par l'ergonomie JVM dans l'enveloppe emulee (cf. Cluster.start). */
    static final String GC_FLAG = env("BENCH_GC_FLAG",
            MEMORY_LIMIT_MB < 1792 ? "-XX:+UseSerialGC" : "-XX:+UseG1GC");
    /**
     * BENCH_RUNTIME=docker : chaque replique est un conteneur de l'image Jib sous une vraie limite
     * cgroup ({@code --memory}) ; la memoire est alors le working set du conteneur (docker stats).
     */
    static final boolean CONTAINERS = "docker".equals(env("BENCH_RUNTIME", "process"));
    static final String IMAGE = env("BENCH_IMAGE", "horizon/souscription-service:1.0.0-SNAPSHOT");
    /** Nom sous lequel un conteneur joint l'hote (bouchons de contrat, PostgreSQL). */
    static final String HOST_ALIAS = env("BENCH_HOST_ALIAS", "host.docker.internal");
    static final String DRAFT_RETENTION = env("BENCH_DRAFT_RETENTION", "PT30S");
    static final String DRAFT_PURGE_CRON = env("BENCH_DRAFT_PURGE_CRON", "*/5 * * * * ?");
    static final String DATABASE = "souscription_bench";

    static final Path ROOT = Paths.get(System.getProperty("user.dir"));
    static final Path OUT = ROOT.resolve("target").resolve("benchmark");
    static final Path JAR = ROOT.resolve("target").resolve("quarkus-app").resolve("quarkus-run.jar");

    static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2))
            .executor(Executors.newCachedThreadPool())
            .build();

    static final Map<String, Object> RESULTS = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        if (!Files.isRegularFile(JAR)) {
            throw new IllegalStateException("artefact introuvable : " + JAR
                    + " (executer d'abord mvn -f souscription-service/pom.xml package)");
        }
        Files.createDirectories(OUT);
        RESULTS.put("startedAt", Instant.now().toString());
        RESULTS.put("environment", environment());
        try (Stubs stubs = new Stubs(); Cluster cluster = new Cluster(stubs)) {
            recreateDatabase();
            RESULTS.put("maxConnections", queryLong("SHOW max_connections"));

            startup(cluster);
            load(cluster);
            rollingRestart(cluster);
            chaos(cluster, stubs);
        } finally {
            dropDatabase();
            RESULTS.put("finishedAt", Instant.now().toString());
            Files.writeString(OUT.resolve("results.json"), Json.write(RESULTS));
            System.out.println(Json.write(RESULTS));
        }
    }

    // =====================================================================================
    // NFG-007 — demarrage
    // =====================================================================================

    static void startup(Cluster cluster) throws Exception {
        List<Map<String, Object>> starts = new ArrayList<>();
        // Replique 1 : joue l'etape de pipeline Flyway (migrate-at-start=true) ; mesure separee.
        Replica first = cluster.start("r1", true);
        starts.add(first.startup);
        // Repliques 2..N : configuration de production (migrate-at-start=false), schema present.
        for (int i = 2; i <= REPLICAS; i++) {
            starts.add(cluster.start("r" + i, false).startup);
        }
        cluster.rotation.addAll(cluster.replicas);
        RESULTS.put("nfg007_startup", starts);
    }

    // =====================================================================================
    // NFG-008 / 009 / 010 / 011 / 013 — charge round-robin sur N repliques
    // =====================================================================================

    static void load(Cluster cluster) throws Exception {
        // Chauffe au debit nominal : la compilation JIT est faite avant la premiere fenetre mesuree.
        LoadRun warmup = new LoadRun(cluster, VIRTUAL_USERS, NOMINAL_RPS);
        warmup.run(Duration.ofSeconds(WARMUP_SECONDS), null);
        // Le nominal precede le stress : la RSS d'une JVM ne redescend pas apres un pic, l'ordre
        // inverse mesurerait le stress deux fois.
        RESULTS.put("load_nominal", phase(cluster, "nominal", NOMINAL_RPS));
        RESULTS.put("load_stress", phase(cluster, "stress", 0));
        // Apres l'arret du trafic : une retention plus un cycle de purge, rien ne doit rester.
        Thread.sleep(Duration.parse(DRAFT_RETENTION).toMillis() + 10_000);
        RESULTS.put("draftsAfterDrain", queryLong("SELECT count(*) FROM subscriptions.subscription_draft"));
    }

    /** Une fenetre de charge mesuree ; rps = 0 : boucle fermee sans temps de reflexion. */
    static Map<String, Object> phase(Cluster cluster, String label, double rps) throws Exception {
        long draftsBefore = queryLong("SELECT count(*) FROM subscriptions.subscription_draft");
        long subscriptionsBefore = queryLong("SELECT count(*) FROM subscriptions.subscriptions");
        String before = label + "-before";
        String after = label + "-after";
        cluster.scrapeAll(before);
        Sampler sampler = new Sampler(cluster);
        LoadRun run = new LoadRun(cluster, VIRTUAL_USERS, rps);
        run.run(Duration.ofSeconds(LOAD_SECONDS), sampler);
        Map<String, Object> nmt = NMT && !CONTAINERS ? cluster.nativeMemoryCommittedMb() : Map.of();
        cluster.scrapeAll(after);

        Map<String, Object> load = new LinkedHashMap<>();
        load.put("replicas", cluster.rotation.size());
        load.put("virtualUsers", VIRTUAL_USERS);
        load.put("targetRps", rps == 0 ? "unpaced" : rps);
        load.put("durationSeconds", LOAD_SECONDS);
        load.putAll(run.summary());
        load.put("serverSideHttpServerRequests", cluster.timerWindow(before, after,
                "http_server_requests_seconds", "/v1"));
        load.put("restClientHttpClientRequests", cluster.timerWindow(before, after,
                "http_client_requests_seconds", "/v1"));
        load.put("faultToleranceCounters", cluster.counterWindow(before, after, "ft_"));
        load.put("acquisitionTimeoutsInLogs", cluster.grepLogs("Acquisition timeout"));

        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("memoryLimitMb", MEMORY_LIMIT_MB);
        memory.put("gc", CONTAINERS ? "JVM ergonomics in container" : GC_FLAG);
        memory.put("source", CONTAINERS
                ? "docker stats MemUsage (cgroup v2 memory.current - inactive_file = working set)"
                : "ps -o rss");
        memory.put("rssMbMaxPerReplica", sampler.rssMaxMb());
        memory.put("rssMbAvgPerReplica", sampler.rssAvgMb());
        memory.put("nmtCommittedMbAtEndOfLoadPerReplica", nmt);
        memory.put("jvmMemoryCommittedMbPerReplica", cluster.committedMb(after));
        load.put("memory", memory);

        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("maxConnections", RESULTS.get("maxConnections"));
        pool.put("replicas", cluster.rotation.size());
        pool.put("poolMaxSizePerReplica", 10);
        pool.put("pgStatActivityBenchDbMax", sampler.pgMax.get());
        pool.put("pgStatActivityAllDbMax", sampler.pgAllMax.get());
        pool.put("agroalActiveMaxPerReplica", sampler.agroalActiveMax);
        pool.put("agroalAwaitingMaxPerReplica", sampler.agroalAwaitingMax);
        pool.put("agroalMaxUsedPerReplica", cluster.metricPerReplica(after, "agroal_max_used_count"));
        pool.put("agroalBlockingTimeMaxMsPerReplica", cluster.metricPerReplica(after,
                "agroal_blocking_time_max_milliseconds"));
        load.put("pool", pool);

        Map<String, Object> stateless = new LinkedHashMap<>();
        stateless.put("draftRetention", DRAFT_RETENTION);
        stateless.put("draftPurgeCron", DRAFT_PURGE_CRON);
        stateless.put("draftsBefore", draftsBefore);
        stateless.put("draftsSamples", sampler.draftSeries);
        stateless.put("draftsSteadyState", sampler.steadyState(Duration.parse(DRAFT_RETENTION)
                .toSeconds() + 10));
        stateless.put("subscriptionsCreated",
                queryLong("SELECT count(*) FROM subscriptions.subscriptions") - subscriptionsBefore);
        stateless.put("journeysMinDistinctReplicas", run.minDistinctReplicas.get());
        stateless.put("journeysAllRequestsOnOneReplica", run.singleReplicaJourneys.get());
        stateless.put("journeysSpanningSeveralReplicas",
                run.journeysOk.get() - run.singleReplicaJourneys.get());
        stateless.put("setCookieResponses", run.setCookies.get());
        load.put("statelessness", stateless);
        return load;
    }

    // =====================================================================================
    // NFG-012 — redemarrage progressif sous charge
    // =====================================================================================

    static void rollingRestart(Cluster cluster) throws Exception {
        LoadRun run = new LoadRun(cluster, VIRTUAL_USERS);
        AtomicBoolean done = new AtomicBoolean();
        List<Map<String, Object>> steps = new CopyOnWriteArrayList<>();
        Thread restarter = new Thread(() -> {
            try {
                Thread.sleep(5_000);
                List<Replica> old = new ArrayList<>(cluster.rotation);
                int generation = 0;
                for (Replica victim : old) {
                    generation++;
                    Map<String, Object> step = new LinkedHashMap<>();
                    // maxSurge=1 / maxUnavailable=0 : la nouvelle replique est prete avant tout retrait.
                    Replica surge = cluster.start(victim.name + "-g" + generation, false);
                    cluster.rotation.add(surge);
                    step.put("new", surge.name);
                    step.put("newReadyMillis", surge.startup.get("readyMillis"));
                    // Retrait des Endpoints puis preStop sleep 5 puis SIGTERM (deploy/deployment.yaml).
                    cluster.rotation.remove(victim);
                    Thread.sleep(5_000);
                    long t0 = System.nanoTime();
                    victim.process.destroy();
                    boolean exited = victim.process.waitFor(45, TimeUnit.SECONDS);
                    step.put("old", victim.name);
                    step.put("sigtermToExitMillis", (System.nanoTime() - t0) / 1_000_000);
                    step.put("exitedWithinGracePeriod", exited);
                    step.put("exitCode", exited ? victim.process.exitValue() : null);
                    steps.add(step);
                }
            } catch (Exception e) {
                steps.add(Map.of("error", String.valueOf(e)));
            } finally {
                done.set(true);
            }
        });
        restarter.start();
        run.runUntil(done, null);
        restarter.join();
        cluster.replicas.removeIf(r -> !r.process.isAlive());

        Map<String, Object> restart = new LinkedHashMap<>();
        restart.put("shutdownTimeout", "30S");
        restart.put("preStopSleepSeconds", 5);
        restart.put("steps", steps);
        restart.putAll(run.summary());
        RESULTS.put("nfg012_rolling_restart", restart);
    }

    // =====================================================================================
    // Rejeu du test de chaos (plan 30) sur la configuration finale
    // =====================================================================================

    static void chaos(Cluster cluster, Stubs stubs) throws Exception {
        Replica target = cluster.rotation.get(0);
        List<Map<String, Object>> cases = new ArrayList<>();
        cases.add(chaosCase(target, "1-simulation-arretee", stubs::stopSimulation,
                stubs::startSimulation, 503));
        cases.add(chaosCase(target, "2-catalogue-arrete", stubs::stopCatalogue,
                stubs::startCatalogue, 503));
        cases.add(chaosCase(target, "3-latence-2500ms", () -> stubs.simulationLatency = 2500,
                () -> stubs.simulationLatency = 0, 201));
        cases.add(chaosCase(target, "4-latence-3500ms", () -> stubs.simulationLatency = 3500,
                () -> stubs.simulationLatency = 0, 503));
        RESULTS.put("chaos_replay", cases);
    }

    static Map<String, Object> chaosCase(Replica replica, String name, Runnable fault,
                                         Runnable restore, int expected) throws Exception {
        Client client = new Client(() -> replica);
        Journey journey = new Journey(client);
        journey.prepareForConfirmation();
        long before = queryLong("SELECT count(*) FROM subscriptions.subscriptions");
        fault.run();
        long t0 = System.nanoTime();
        HttpResponse<String> response = journey.confirm();
        long millis = (System.nanoTime() - t0) / 1_000_000;
        int live = client.get("/q/health/live").statusCode();
        long after = queryLong("SELECT count(*) FROM subscriptions.subscriptions");
        restore.run();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case", name);
        result.put("expectedStatus", expected);
        result.put("status", response.statusCode());
        result.put("problemType", field(response.body(), "type"));
        result.put("durationMillis", millis);
        result.put("liveness", live);
        result.put("subscriptionRowsDelta", after - before);
        if (response.statusCode() == 503) {
            // Rejouable a l'identique : meme brouillon, meme cle, apres retour de la dependance.
            HttpResponse<String> replay = journey.confirm();
            result.put("replayAfterRestoreStatus", replay.statusCode());
            result.put("subscriptionRowsDeltaAfterReplay",
                    queryLong("SELECT count(*) FROM subscriptions.subscriptions") - before);
        }
        return result;
    }

    // =====================================================================================
    // Charge
    // =====================================================================================

    static final String[] OPERATIONS = {"startDraft", "getDraft", "putSubscriber",
            "putSimulation", "confirm", "getSubscription"};

    static final class LoadRun {
        final Cluster cluster;
        final int users;
        final Map<String, ConcurrentLinkedQueue<Long>> latencies = new ConcurrentHashMap<>();
        final Map<String, AtomicLong> errors = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<String> errorSamples = new ConcurrentLinkedQueue<>();
        final AtomicLong requests = new AtomicLong();
        final AtomicLong journeysOk = new AtomicLong();
        final AtomicLong journeysKo = new AtomicLong();
        final AtomicInteger minDistinctReplicas = new AtomicInteger(Integer.MAX_VALUE);
        final AtomicLong singleReplicaJourneys = new AtomicLong();
        final AtomicLong setCookies = new AtomicLong();
        long elapsedMillis;

        final double rps;
        final AtomicLong nextSlot = new AtomicLong(System.nanoTime());

        LoadRun(Cluster cluster, int users) {
            this(cluster, users, 0);
        }

        LoadRun(Cluster cluster, int users, double rps) {
            this.cluster = cluster;
            this.users = users;
            this.rps = rps;
            for (String op : OPERATIONS) {
                latencies.put(op, new ConcurrentLinkedQueue<>());
                errors.put(op, new AtomicLong());
            }
        }

        /** Modele ouvert : une requete par creneau de 1/rps seconde, tous utilisateurs confondus. */
        void pace() {
            if (rps <= 0) {
                return;
            }
            long interval = (long) (1_000_000_000L / rps);
            long now = System.nanoTime();
            long slot = nextSlot.updateAndGet(previous -> Math.max(previous + interval, now));
            if (slot > now) {
                java.util.concurrent.locks.LockSupport.parkNanos(slot - now);
            }
        }

        void run(Duration duration, Sampler sampler) throws InterruptedException {
            long deadline = System.nanoTime() + duration.toNanos();
            AtomicBoolean stop = new AtomicBoolean();
            Thread timer = new Thread(() -> {
                while (System.nanoTime() < deadline) {
                    sleep(100);
                }
                stop.set(true);
            });
            timer.start();
            runUntil(stop, sampler);
            timer.join();
        }

        void runUntil(AtomicBoolean stop, Sampler sampler) throws InterruptedException {
            ExecutorService pool = Executors.newFixedThreadPool(users);
            if (sampler != null) {
                sampler.start();
            }
            long t0 = System.nanoTime();
            for (int i = 0; i < users; i++) {
                pool.submit(() -> {
                    while (!stop.get()) {
                        journey();
                    }
                });
            }
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.MINUTES);
            elapsedMillis = (System.nanoTime() - t0) / 1_000_000;
            if (sampler != null) {
                sampler.stop();
            }
        }

        void journey() {
            Client client = new Client(cluster::next);
            Journey journey = new Journey(client);
            boolean ok = journey.step(this, "startDraft", 201, journey::start)
                    && journey.step(this, "getDraft", 200, journey::read)
                    && journey.step(this, "putSubscriber", 200, journey::subscriber)
                    && journey.step(this, "putSimulation", 200, journey::simulation)
                    && journey.step(this, "getDraft", 200, journey::readKey)
                    && journey.step(this, "confirm", 201, journey::confirm)
                    && journey.step(this, "getSubscription", 200, journey::subscription);
            if (ok) {
                journeysOk.incrementAndGet();
                int distinct = client.replicasSeen.size();
                minDistinctReplicas.accumulateAndGet(distinct, Math::min);
                if (distinct == 1) {
                    singleReplicaJourneys.incrementAndGet();
                }
            } else {
                journeysKo.incrementAndGet();
            }
        }

        Map<String, Object> summary() {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("elapsedMillis", elapsedMillis);
            summary.put("requests", requests.get());
            summary.put("throughputRps", round(requests.get() * 1000.0 / Math.max(1, elapsedMillis)));
            summary.put("journeysCompleted", journeysOk.get());
            summary.put("journeysFailed", journeysKo.get());
            long totalErrors = errors.values().stream().mapToLong(AtomicLong::get).sum();
            summary.put("clientErrors", totalErrors);
            summary.put("clientErrorsByOperation", counts(errors));
            summary.put("errorSamples", new ArrayList<>(errorSamples).subList(0,
                    Math.min(10, errorSamples.size())));
            Map<String, Object> perOp = new LinkedHashMap<>();
            List<Long> all = new ArrayList<>();
            for (String op : OPERATIONS) {
                List<Long> values = new ArrayList<>(latencies.get(op));
                all.addAll(values);
                perOp.put(op, percentiles(values));
            }
            summary.put("clientLatencyMs", perOp);
            summary.put("clientLatencyMsAllOperations", percentiles(all));
            return summary;
        }
    }

    /** Un parcours complet ; chaque requete est routee independamment (round-robin). */
    static final class Journey {
        final Client client;
        final String email = "banc." + UUID.randomUUID() + "@example.fr";
        String draftId;
        String key;
        String reference;

        Journey(Client client) {
            this.client = client;
        }

        boolean step(LoadRun run, String op, int expected, Supplier<HttpResponse<String>> call) {
            run.pace();
            long t0 = System.nanoTime();
            HttpResponse<String> response;
            try {
                response = call.get();
            } catch (RuntimeException e) {
                run.errors.get(op).incrementAndGet();
                run.errorSamples.add(op + " exception " + e);
                return false;
            }
            run.latencies.get(op).add(System.nanoTime() - t0);
            run.requests.incrementAndGet();
            if (response.headers().firstValue("Set-Cookie").isPresent()) {
                run.setCookies.incrementAndGet();
            }
            if (response.statusCode() != expected) {
                run.errors.get(op).incrementAndGet();
                String body = response.body();
                run.errorSamples.add(op + " " + response.statusCode() + " "
                        + body.substring(0, Math.min(200, body.length())));
                return false;
            }
            return true;
        }

        HttpResponse<String> start() {
            HttpResponse<String> r = client.send("POST", "/v1/drafts",
                    "{\"productCode\":\"EQUILIBRE\"}");
            draftId = field(r.body(), "draftId");
            return r;
        }

        HttpResponse<String> read() {
            return client.get("/v1/drafts/" + draftId);
        }

        HttpResponse<String> readKey() {
            HttpResponse<String> r = read();
            key = field(r.body(), "confirmationKey");
            return r;
        }

        HttpResponse<String> subscriber() {
            return client.send("PUT", "/v1/drafts/" + draftId + "/subscriber",
                    "{\"firstName\":\"Camille\",\"lastName\":\"Durand\",\"email\":\"" + email
                            + "\",\"birthDate\":\"1985-04-12\"}");
        }

        HttpResponse<String> simulation() {
            return client.send("PUT", "/v1/drafts/" + draftId + "/simulation",
                    "{\"initialAmount\":\"1000.00\",\"monthlyAmount\":\"100.00\",\"durationYears\":5}");
        }

        HttpResponse<String> confirm() {
            HttpResponse<String> r = client.send("POST", "/v1/drafts/" + draftId + "/confirmation",
                    "{\"confirmationKey\":\"" + key + "\"}");
            if (r.statusCode() == 201 || r.statusCode() == 200) {
                reference = field(r.body(), "reference");
            }
            return r;
        }

        HttpResponse<String> subscription() {
            return client.get("/v1/subscriptions/" + reference);
        }

        void prepareForConfirmation() {
            expect(201, start());
            expect(200, subscriber());
            expect(200, simulation());
            expect(200, readKey());
        }

        static void expect(int status, HttpResponse<String> response) {
            if (response.statusCode() != status) {
                throw new IllegalStateException("attendu " + status + ", recu "
                        + response.statusCode() + " : " + response.body());
            }
        }
    }

    /** Client HTTP portant la paire double-submit relayee par la passerelle. */
    static final class Client {
        final Supplier<Replica> router;
        final String csrf = "jeton-banc-" + UUID.randomUUID();
        final java.util.Set<String> replicasSeen = new java.util.HashSet<>();

        Client(Supplier<Replica> router) {
            this.router = router;
        }

        HttpResponse<String> get(String path) {
            return send("GET", path, null);
        }

        HttpResponse<String> send(String method, String path, String body) {
            Replica replica = router.get();
            replicasSeen.add(replica.name);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(replica.base() + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("X-CSRF-Token", csrf)
                    .header("Cookie", "csrf-token=" + csrf);
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body));
            }
            try {
                return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new IllegalStateException(replica.name + " " + method + " " + path + " : " + e, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    // =====================================================================================
    // Echantillonnage : RSS, pg_stat_activity, agroal, subscription_draft
    // =====================================================================================

    static final class Sampler {
        final Cluster cluster;
        final AtomicBoolean running = new AtomicBoolean();
        final Map<String, List<Long>> rss = new ConcurrentHashMap<>();
        final AtomicLong pgMax = new AtomicLong();
        final AtomicLong pgAllMax = new AtomicLong();
        final Map<String, Double> agroalActiveMax = new ConcurrentHashMap<>();
        final Map<String, Double> agroalAwaitingMax = new ConcurrentHashMap<>();
        final List<Map<String, Object>> draftSeries = new CopyOnWriteArrayList<>();
        Thread thread;
        Thread memoryThread;
        long t0;

        Sampler(Cluster cluster) {
            this.cluster = cluster;
        }

        void start() {
            running.set(true);
            t0 = System.nanoTime();
            if (CONTAINERS) {
                // docker stats --no-stream bloque ~1-2 s : fil dedie, pour ne pas ralentir les autres echantillons.
                memoryThread = new Thread(() -> {
                    while (running.get()) {
                        containerMemoryKb(cluster.rotation).forEach((name, kb) ->
                                rss.computeIfAbsent(name, k -> new CopyOnWriteArrayList<>()).add(kb));
                    }
                });
                memoryThread.start();
            }
            thread = new Thread(() -> {
                int tick = 0;
                while (running.get()) {
                    for (Replica replica : cluster.rotation) {
                        long kb = CONTAINERS ? -1 : rssKb(replica.process.pid());
                        if (kb > 0) {
                            rss.computeIfAbsent(replica.name, k -> new CopyOnWriteArrayList<>()).add(kb);
                        }
                        if (tick % 2 == 0) {
                            String metrics = replica.metrics();
                            agroalActiveMax.merge(replica.name, metric(metrics, "agroal_active_count"), Math::max);
                            agroalAwaitingMax.merge(replica.name, metric(metrics, "agroal_awaiting_count"), Math::max);
                        }
                    }
                    try {
                        pgMax.accumulateAndGet(queryLong("SELECT count(*) FROM pg_stat_activity"
                                + " WHERE datname = '" + DATABASE + "'"), Math::max);
                        pgAllMax.accumulateAndGet(queryLong("SELECT count(*) FROM pg_stat_activity"
                                + " WHERE backend_type = 'client backend'"), Math::max);
                        if (tick % 5 == 0) {
                            Map<String, Object> point = new LinkedHashMap<>();
                            point.put("t", (System.nanoTime() - t0) / 1_000_000_000);
                            point.put("rows", queryLong(
                                    "SELECT count(*) FROM subscriptions.subscription_draft"));
                            draftSeries.add(point);
                        }
                    } catch (RuntimeException ignored) {
                        // un echantillon manque ne fausse pas le maximum
                    }
                    tick++;
                    sleep(1000);
                }
            });
            thread.start();
        }

        void stop() throws InterruptedException {
            running.set(false);
            thread.join();
            if (memoryThread != null) {
                memoryThread.join();
            }
        }

        Map<String, Object> rssMaxMb() {
            Map<String, Object> out = new TreeMap<>();
            rss.forEach((k, v) -> out.put(k, round(Collections.max(v) / 1024.0)));
            return out;
        }

        Map<String, Object> rssAvgMb() {
            Map<String, Object> out = new TreeMap<>();
            rss.forEach((k, v) -> out.put(k, round(v.stream().mapToLong(Long::longValue)
                    .average().orElse(0) / 1024.0)));
            return out;
        }

        /** Lignes de subscription_draft une fois la retention ecoulee (regime stationnaire). */
        Map<String, Object> steadyState(long afterSeconds) {
            List<Long> rows = new ArrayList<>();
            for (Map<String, Object> point : draftSeries) {
                if ((Long) point.get("t") >= afterSeconds) {
                    rows.add((Long) point.get("rows"));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("fromSecond", afterSeconds);
            out.put("samples", rows.size());
            if (!rows.isEmpty()) {
                long min = Collections.min(rows);
                long max = Collections.max(rows);
                double avg = rows.stream().mapToLong(Long::longValue).average().orElse(0);
                out.put("min", min);
                out.put("max", max);
                out.put("mean", round(avg));
                out.put("spreadPercentOfMean", round((max - min) * 100.0 / Math.max(1, avg)));
                // Pente : premiere moitie vs seconde moitie du regime stationnaire.
                int half = rows.size() / 2;
                double first = rows.subList(0, Math.max(1, half)).stream().mapToLong(Long::longValue).average().orElse(0);
                double second = rows.subList(half, rows.size()).stream().mapToLong(Long::longValue).average().orElse(0);
                out.put("driftPercentSecondHalfVsFirstHalf", round((second - first) * 100.0 / Math.max(1, first)));
            }
            return out;
        }
    }

    // =====================================================================================
    // Repliques
    // =====================================================================================

    static final class Replica {
        final String name;
        final int port;
        final Process process;
        final Path log;
        final Map<String, Object> startup = new LinkedHashMap<>();
        final Map<String, String> scrapes = new ConcurrentHashMap<>();

        Replica(String name, int port, Process process, Path log) {
            this.name = name;
            this.port = port;
            this.process = process;
            this.log = log;
        }

        String base() {
            return "http://localhost:" + port;
        }

        int probe(String path) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(base() + path))
                        .timeout(Duration.ofSeconds(2)).GET().build();
                return HTTP.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (IOException e) {
                return -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }

        String metrics() {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(base() + "/q/metrics"))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
            } catch (IOException e) {
                return "";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
        }

        String logText() {
            try {
                return Files.readString(log, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        }
    }

    static final class Cluster implements AutoCloseable {
        final Stubs stubs;
        final List<Replica> replicas = new CopyOnWriteArrayList<>();
        /** Endpoints du Service : les repliques prets et non en cours de retrait. */
        final List<Replica> rotation = new CopyOnWriteArrayList<>();
        final AtomicLong cursor = new AtomicLong();

        Cluster(Stubs stubs) {
            this.stubs = stubs;
        }

        Replica next() {
            List<Replica> snapshot = rotation;
            return snapshot.get((int) Math.floorMod(cursor.getAndIncrement(), (long) snapshot.size()));
        }

        Replica start(String name, boolean migrate) throws Exception {
            int port = freePort();
            List<String> command = new ArrayList<>();
            command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
            // Arguments JVM de l'image (quarkus.jib.jvm-arguments) ; MaxRAM emule la limite cgroup.
            command.add("-XX:MaxRAM=" + MEMORY_LIMIT_MB + "m");
            command.add("-XX:MaxRAMPercentage=75");
            // Ergonomie JVM d'un conteneur : sous 1792 MB de memoire visible, la JVM n'est pas
            // « server-class » et retient SerialGC. -XX:MaxRAM ne change pas cette detection (la
            // memoire physique de l'hote reste visible) : le choix est donc reproduit explicitement.
            command.add(GC_FLAG);
            // Empreinte telle que la JVM la compte (jcmd VM.native_memory), independante de la
            // comptabilite RSS de l'OS hote. Desactive par defaut : NMT ajoute sa propre empreinte
            // a la RSS mesuree. BENCH_NMT=1 pour la decomposition.
            if (NMT) {
                command.add("-XX:NativeMemoryTracking=summary");
            }
            command.add("-Dfile.encoding=UTF-8");
            command.add("-Duser.timezone=Europe/Paris");
            Map<String, String> props = new LinkedHashMap<>();
            props.put("quarkus.profile", "test");
            props.put("quarkus.http.port", String.valueOf(port));
            props.put("quarkus.datasource.active", "true");
            props.put("quarkus.hibernate-orm.active", "true");
            props.put("quarkus.datasource.jdbc.url", databaseUrl(DATABASE));
            props.put("quarkus.datasource.username", dbUser());
            props.put("quarkus.datasource.password", dbPassword());
            props.put("quarkus.flyway.migrate-at-start", String.valueOf(migrate));
            props.put("quarkus.rest-client.catalogue.url", stubs.catalogueBase());
            props.put("quarkus.rest-client.simulation.url", stubs.simulationBase());
            props.put("quarkus.scheduler.enabled", "true");
            props.put("horizon.draft.retention", DRAFT_RETENTION);
            props.put("horizon.draft.purge.cron", DRAFT_PURGE_CRON);
            props.put("quarkus.log.category.\"fr.demo.souscription\".level", "INFO");
            Path log = OUT.resolve(name + ".log");
            ProcessBuilder builder;
            if (CONTAINERS) {
                builder = new ProcessBuilder(dockerRun(name, port, migrate));
                // Identifiants transmis par l'environnement (docker run -e NOM sans valeur) :
                // jamais sur la ligne de commande ni dans les journaux.
                builder.environment().put("QUARKUS_DATASOURCE_USERNAME", dbUser());
                builder.environment().put("QUARKUS_DATASOURCE_PASSWORD", dbPassword());
            } else {
                props.forEach((k, v) -> command.add("-D" + k + "=" + v));
                command.add("-jar");
                command.add(JAR.toString());
                builder = new ProcessBuilder(command);
            }
            long t0 = System.nanoTime();
            Process process = builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
            Replica replica = new Replica(name, port, process, log);
            replicas.add(replica);
            long started = -1;
            long ready = -1;
            long deadline = t0 + TimeUnit.SECONDS.toNanos(90);
            while (System.nanoTime() < deadline && process.isAlive() && ready < 0) {
                if (started < 0 && replica.probe("/q/health/started") == 200) {
                    started = (System.nanoTime() - t0) / 1_000_000;
                }
                if (started >= 0 && replica.probe("/q/health/ready") == 200) {
                    ready = (System.nanoTime() - t0) / 1_000_000;
                }
                if (ready < 0) {
                    sleep(25);
                }
            }
            if (ready < 0) {
                throw new IllegalStateException(name + " pas pret :\n" + replica.logText());
            }
            String text = replica.logText();
            replica.startup.put("replica", name);
            replica.startup.put("flywayMigrateAtStart", migrate);
            replica.startup.put("quarkusStartedInMillis", startedIn(text));
            replica.startup.put("processSpawnToStartedProbeMillis", started);
            replica.startup.put("readyMillis", ready);
            replica.startup.put("flywayLine", firstMatch(text, "(Successfully applied [^\\n]*|Schema [^\\n]* is up to date[^\\n]*|Current version of schema[^\\n]*)"));
            return replica;
        }

        /**
         * Replique conteneurisee : image Jib, arguments JVM de l'image (MaxRAMPercentage=75 sur la
         * limite cgroup), limite memoire de deploy/deployment.yaml, sans swap. docker run reste
         * attache : SIGTERM sur le client est relaye au conteneur (--sig-proxy), comme le kubelet.
         */
        List<String> dockerRun(String name, int port, boolean migrate) {
            String container = containerName(name);
            removeContainer(container);
            List<String> command = new ArrayList<>(List.of("docker", "run", "--rm", "--name", container,
                    "--memory", MEMORY_LIMIT_MB + "m", "--memory-swap", MEMORY_LIMIT_MB + "m",
                    "-p", "127.0.0.1:" + port + ":8080",
                    "--add-host", HOST_ALIAS + ":host-gateway"));
            Map<String, String> env = new LinkedHashMap<>();
            env.put("QUARKUS_PROFILE", "test");
            env.put("QUARKUS_HTTP_PORT", "8080");
            env.put("QUARKUS_DATASOURCE_ACTIVE", "true");
            env.put("QUARKUS_HIBERNATE_ORM_ACTIVE", "true");
            env.put("QUARKUS_DATASOURCE_JDBC_URL", toContainer(databaseUrl(DATABASE)));
            env.put("QUARKUS_FLYWAY_MIGRATE_AT_START", String.valueOf(migrate));
            env.put("QUARKUS_SCHEDULER_ENABLED", "true");
            env.put("QUARKUS_REST_CLIENT_CATALOGUE_URL", toContainer(stubs.catalogueBase()));
            env.put("QUARKUS_REST_CLIENT_SIMULATION_URL", toContainer(stubs.simulationBase()));
            env.put("HORIZON_DRAFT_RETENTION", DRAFT_RETENTION);
            env.put("HORIZON_DRAFT_PURGE_CRON", DRAFT_PURGE_CRON);
            // = quarkus.log.category."fr.demo.souscription".level (cle entre guillemets -> __).
            env.put("QUARKUS_LOG_CATEGORY__FR_DEMO_SOUSCRIPTION__LEVEL", "INFO");
            env.forEach((k, v) -> command.addAll(List.of("-e", k + "=" + v)));
            command.addAll(List.of("-e", "QUARKUS_DATASOURCE_USERNAME", "-e", "QUARKUS_DATASOURCE_PASSWORD"));
            command.add(IMAGE);
            return command;
        }

        /** URL de l'hote vue depuis un conteneur (PostgreSQL, bouchons de contrat). */
        static String toContainer(String url) {
            return url.replace("//localhost:", "//" + HOST_ALIAS + ":")
                    .replace("//127.0.0.1:", "//" + HOST_ALIAS + ":");
        }

        /** Total « committed » de Native Memory Tracking, tas et hors tas compris. */
        Map<String, Object> nativeMemoryCommittedMb() {
            Map<String, Object> out = new TreeMap<>();
            Path jcmd = Paths.get(System.getProperty("java.home"), "bin", "jcmd");
            Pattern total = Pattern.compile("Total: reserved=(\\d+)KB, committed=(\\d+)KB");
            for (Replica replica : rotation) {
                try {
                    Process p = new ProcessBuilder(jcmd.toString(), String.valueOf(replica.process.pid()),
                            "VM.native_memory", "summary").redirectErrorStream(true).start();
                    String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    p.waitFor(30, TimeUnit.SECONDS);
                    Matcher m = total.matcher(text);
                    out.put(replica.name, m.find() ? round(Long.parseLong(m.group(2)) / 1024.0) : null);
                } catch (IOException e) {
                    out.put(replica.name, null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return out;
        }

        Map<String, Object> committedMb(String label) {
            Map<String, Object> out = new TreeMap<>();
            for (Replica replica : rotation) {
                String text = replica.scrapes.getOrDefault(label, "");
                Map<String, Object> areas = new LinkedHashMap<>();
                areas.put("heap", round(metricWhere(text, "jvm_memory_committed_bytes", "area=\"heap\"") / 1048576));
                areas.put("nonheap", round(metricWhere(text, "jvm_memory_committed_bytes", "area=\"nonheap\"") / 1048576));
                areas.put("direct", round(metricWhere(text, "jvm_buffer_memory_used_bytes", "id=\"direct\"") / 1048576));
                out.put(replica.name, areas);
            }
            return out;
        }

        void scrapeAll(String label) throws IOException {
            for (Replica replica : rotation) {
                String metrics = replica.metrics();
                replica.scrapes.put(label, metrics);
                Files.writeString(OUT.resolve(replica.name + "-" + label + ".prom"), metrics);
            }
        }

        Map<String, Object> metricPerReplica(String label, String name) {
            Map<String, Object> out = new TreeMap<>();
            for (Replica replica : rotation) {
                out.put(replica.name, metric(replica.scrapes.getOrDefault(label, ""), name));
            }
            return out;
        }

        /** Timer Micrometer sur la fenetre de charge (difference de deux captures), toutes repliques. */
        Map<String, Object> timerWindow(String before, String after, String base, String uriPrefix) {
            Map<String, double[]> agg = new TreeMap<>();
            Map<String, Double> max = new TreeMap<>();
            Pattern line = Pattern.compile("^" + Pattern.quote(base)
                    + "_(count|sum|max)\\{(.*)}\\s+([0-9.eE+-]+)$", Pattern.MULTILINE);
            for (Replica replica : rotation) {
                for (int pass = 0; pass < 2; pass++) {
                    String text = replica.scrapes.getOrDefault(pass == 0 ? before : after, "");
                    Matcher m = line.matcher(text);
                    while (m.find()) {
                        String labels = m.group(2);
                        String uri = label(labels, "uri");
                        if (uri == null || !uri.startsWith(uriPrefix)) {
                            continue;
                        }
                        String key = label(labels, "method") + " " + uri + " " + label(labels, "status");
                        double value = Double.parseDouble(m.group(3));
                        double sign = pass == 0 ? -1 : 1;
                        double[] cs = agg.computeIfAbsent(key, k -> new double[2]);
                        switch (m.group(1)) {
                            case "count" -> cs[0] += sign * value;
                            case "sum" -> cs[1] += sign * value;
                            default -> {
                                if (pass == 1) {
                                    max.merge(key, value, Math::max);
                                }
                            }
                        }
                    }
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            agg.forEach((key, cs) -> {
                if (cs[0] <= 0) {
                    return;
                }
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("count", (long) cs[0]);
                v.put("meanMs", round(cs[1] * 1000 / cs[0]));
                v.put("maxMsLastWindow", max.containsKey(key) ? round(max.get(key) * 1000) : null);
                out.put(key, v);
            });
            return out;
        }

        /** Variation des compteurs de tolerance aux pannes (ft_*_total) sur la fenetre, toutes repliques. */
        Map<String, Object> counterWindow(String before, String after, String prefix) {
            Map<String, Double> agg = new TreeMap<>();
            Pattern line = Pattern.compile("^(" + Pattern.quote(prefix) + "[a-zA-Z_]*_total)\\{(.*)}\\s+([0-9.eE+-]+)$",
                    Pattern.MULTILINE);
            for (Replica replica : rotation) {
                for (int pass = 0; pass < 2; pass++) {
                    Matcher m = line.matcher(replica.scrapes.getOrDefault(pass == 0 ? before : after, ""));
                    while (m.find()) {
                        String labels = m.group(2).replaceAll("fr\\.demo\\.souscription\\.integration\\.", "")
                                .replace("$$CDIWrapper", "");
                        agg.merge(m.group(1) + "{" + labels + "}",
                                (pass == 0 ? -1 : 1) * Double.parseDouble(m.group(3)), Double::sum);
                    }
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            agg.forEach((k, v) -> out.put(k, (long) Math.round(v)));
            return out;
        }

        long grepLogs(String needle) {
            long n = 0;
            for (Replica replica : replicas) {
                String text = replica.logText();
                int i = 0;
                while ((i = text.indexOf(needle, i)) >= 0) {
                    n++;
                    i += needle.length();
                }
            }
            return n;
        }

        @Override
        public void close() {
            for (Replica replica : replicas) {
                replica.process.destroy();
            }
            for (Replica replica : replicas) {
                try {
                    if (!replica.process.waitFor(40, TimeUnit.SECONDS)) {
                        replica.process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (CONTAINERS) {
                    removeContainer(containerName(replica.name));
                }
            }
        }
    }

    static String containerName(String replica) {
        return "souscription-bench-" + replica;
    }

    static void removeContainer(String container) {
        try {
            new ProcessBuilder("docker", "rm", "-f", container).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(30, TimeUnit.SECONDS);
        } catch (IOException e) {
            // best effort
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Working set de chaque conteneur en KB : {@code docker stats} rapporte memory.current moins
     * inactive_file (cgroup v2), soit container_memory_working_set_bytes du kubelet.
     */
    static Map<String, Long> containerMemoryKb(List<Replica> replicas) {
        Map<String, String> byContainer = new LinkedHashMap<>();
        for (Replica replica : replicas) {
            byContainer.put(containerName(replica.name), replica.name);
        }
        Map<String, Long> out = new LinkedHashMap<>();
        if (byContainer.isEmpty()) {
            sleep(1000);
            return out;
        }
        List<String> command = new ArrayList<>(List.of("docker", "stats", "--no-stream", "--format",
                "{{.Name}}\t{{.MemUsage}}"));
        command.addAll(byContainer.keySet());
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(30, TimeUnit.SECONDS);
            Pattern line = Pattern.compile("^(\\S+)\\t([0-9.]+)(B|KiB|MiB|GiB)\\s*/", Pattern.MULTILINE);
            Matcher m = line.matcher(text);
            while (m.find()) {
                String replica = byContainer.get(m.group(1));
                if (replica == null) {
                    continue;
                }
                double value = Double.parseDouble(m.group(2));
                double kb = switch (m.group(3)) {
                    case "B" -> value / 1024;
                    case "KiB" -> value;
                    case "MiB" -> value * 1024;
                    default -> value * 1024 * 1024;
                };
                if (kb > 0) {
                    out.put(replica, Math.round(kb));
                }
            }
        } catch (IOException e) {
            sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    // =====================================================================================
    // Bouchons des deux contrats consommes
    // =====================================================================================

    static final class Stubs implements AutoCloseable {
        static final Map<String, String> PRODUCTS = Map.of(
                "PRUDENT", product("PRUDENT", "Horizon Prudent", "Prudent", "0.020000"),
                "EQUILIBRE", product("EQUILIBRE", "Horizon \u00c9quilibre", "\u00c9quilibr\u00e9", "0.040000"),
                "DYNAMIQUE", product("DYNAMIQUE", "Horizon Dynamique", "Dynamique", "0.060000"));

        final int cataloguePort = freePort();
        final int simulationPort = freePort();
        volatile HttpServer catalogue;
        volatile HttpServer simulation;
        volatile long simulationLatency;

        Stubs() {
            startCatalogue();
            startSimulation();
        }

        String catalogueBase() {
            return "http://localhost:" + cataloguePort;
        }

        String simulationBase() {
            return "http://localhost:" + simulationPort;
        }

        void startCatalogue() {
            catalogue = server(cataloguePort);
            catalogue.createContext("/v1/products", exchange -> {
                String code = exchange.getRequestURI().getPath().replaceFirst("^/v1/products/?", "");
                String body = PRODUCTS.get(code);
                if (body == null) {
                    respond(exchange, 404, "{\"type\":\"urn:horizon:not-found\",\"title\":\"horizon.request.not-found\",\"status\":404}");
                } else {
                    respond(exchange, 200, body);
                }
            });
            catalogue.start();
        }

        void startSimulation() {
            simulation = server(simulationPort);
            simulation.createContext("/v1/simulations", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (simulationLatency > 0) {
                    sleep(simulationLatency);
                }
                String initial = field(body, "initialAmount");
                String monthly = field(body, "monthlyAmount");
                String rate = field(body, "annualRate");
                Matcher years = Pattern.compile("\"durationYears\"\\s*:\\s*(\\d+)").matcher(body);
                if (initial == null || monthly == null || rate == null || !years.find()) {
                    respond(exchange, 400, "{\"type\":\"urn:horizon:validation-failed\",\"title\":\"horizon.request.invalid\",\"status\":400}");
                    return;
                }
                int duration = Integer.parseInt(years.group(1));
                String contributed = new BigDecimal(initial)
                        .add(new BigDecimal(monthly).multiply(BigDecimal.valueOf(duration * 12L)))
                        .setScale(2, RoundingMode.HALF_UP).toPlainString();
                String capital = fr.demo.souscription.integration.SimulationStubServer
                        .expectedFinalCapital(initial, monthly, duration, rate);
                respond(exchange, 200, String.format(Locale.ROOT,
                        "{\"initialAmount\":\"%s\",\"monthlyAmount\":\"%s\",\"durationYears\":%d,"
                                + "\"contributed\":\"%s\",\"finalCapital\":\"%s\"}",
                        new BigDecimal(initial).setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        new BigDecimal(monthly).setScale(2, RoundingMode.HALF_UP).toPlainString(),
                        duration, contributed, capital));
            });
            simulation.start();
        }

        void stopCatalogue() {
            catalogue.stop(0);
        }

        void stopSimulation() {
            simulation.stop(0);
        }

        static HttpServer server(int port) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 256);
                server.setExecutor(Executors.newCachedThreadPool());
                return server;
            } catch (IOException e) {
                throw new IllegalStateException("bouchon indisponible sur " + port, e);
            }
        }

        static String product(String code, String name, String profile, String rate) {
            return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"profile\":\"" + profile
                    + "\",\"description\":\"Produit " + code + "\",\"annualRate\":\"" + rate + "\"}";
        }

        static void respond(HttpExchange exchange, int status, String body) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().add("Content-Type",
                        status == 200 ? "application/json" : "application/problem+json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException abandoned) {
                // appelant parti (timeout)
            }
        }

        @Override
        public void close() {
            catalogue.stop(0);
            simulation.stop(0);
        }
    }

    // =====================================================================================
    // Base de donnees
    // =====================================================================================

    static String adminUrl() {
        return env("BENCH_DB_ADMIN_URL", "jdbc:postgresql://localhost:5432/postgres");
    }

    static String dbUser() {
        return env("BENCH_DB_USER", System.getProperty("user.name"));
    }

    static String dbPassword() {
        return env("BENCH_DB_PASSWORD", "");
    }

    static String databaseUrl(String database) {
        String admin = adminUrl();
        int query = admin.indexOf('?');
        String base = query < 0 ? admin : admin.substring(0, query);
        String suffix = query < 0 ? "" : admin.substring(query);
        return base.substring(0, base.lastIndexOf('/') + 1) + database + suffix;
    }

    static void recreateDatabase() throws SQLException {
        try (Connection c = DriverManager.getConnection(adminUrl(), dbUser(), dbPassword());
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + DATABASE);
        }
    }

    static void dropDatabase() {
        try (Connection c = DriverManager.getConnection(adminUrl(), dbUser(), dbPassword());
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        } catch (SQLException ignored) {
            // best effort
        }
    }

    /** Requetes fixes du banc uniquement (aucune entree externe). */
    static long queryLong(String sql) {
        String url = sql.contains("subscriptions.") ? databaseUrl(DATABASE) : adminUrl();
        try (Connection c = DriverManager.getConnection(url, dbUser(), dbPassword());
             Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return Long.parseLong(r.getString(1));
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    // =====================================================================================
    // Utilitaires
    // =====================================================================================

    static Map<String, Object> environment() {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        env.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        env.put("replicas", REPLICAS);
        env.put("virtualUsers", VIRTUAL_USERS);
        env.put("runtime", CONTAINERS ? "docker " + IMAGE : "process");
        env.put(CONTAINERS ? "memoryLimitMbCgroup" : "memoryLimitMbEmulated", MEMORY_LIMIT_MB);
        env.put("gc", CONTAINERS ? "JVM ergonomics in container" : GC_FLAG);
        env.put("nominalRps", NOMINAL_RPS);
        env.put("nativeMemoryTracking", NMT);
        env.put("artifact", ROOT.relativize(JAR).toString());
        return env;
    }

    static Map<String, Object> percentiles(List<Long> nanos) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", nanos.size());
        if (nanos.isEmpty()) {
            return out;
        }
        List<Long> sorted = new ArrayList<>(nanos);
        Collections.sort(sorted);
        out.put("p50", ms(at(sorted, 0.50)));
        out.put("p95", ms(at(sorted, 0.95)));
        out.put("p99", ms(at(sorted, 0.99)));
        out.put("max", ms(sorted.get(sorted.size() - 1)));
        return out;
    }

    static long at(List<Long> sorted, double q) {
        int index = (int) Math.ceil(q * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    static double ms(long nanos) {
        return round(nanos / 1_000_000.0);
    }

    static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static Map<String, Object> counts(Map<String, AtomicLong> counters) {
        Map<String, Object> out = new LinkedHashMap<>();
        counters.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    static long rssKb(long pid) {
        try {
            Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid)).start();
            try (InputStream in = ps.getInputStream()) {
                String out = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
                ps.waitFor();
                return out.isEmpty() ? -1 : Long.parseLong(out);
            }
        } catch (IOException | NumberFormatException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    /** Somme des series d'un metre Prometheus (toutes etiquettes confondues). */
    static double metric(String text, String name) {
        Matcher m = Pattern.compile("^" + Pattern.quote(name) + "(\\{.*})?\\s+([0-9.eE+-]+)$",
                Pattern.MULTILINE).matcher(text);
        double sum = 0;
        while (m.find()) {
            sum += Double.parseDouble(m.group(2));
        }
        return sum;
    }

    static double metricWhere(String text, String name, String labelFilter) {
        Matcher m = Pattern.compile("^" + Pattern.quote(name) + "\\{(.*)}\\s+([0-9.eE+-]+)$",
                Pattern.MULTILINE).matcher(text);
        double sum = 0;
        while (m.find()) {
            if (m.group(1).contains(labelFilter)) {
                sum += Double.parseDouble(m.group(2));
            }
        }
        return sum;
    }

    static String label(String labels, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(labels);
        return m.find() ? m.group(1) : null;
    }

    static String field(String json, String name) {
        if (json == null) {
            return null;
        }
        Matcher m = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static Long startedIn(String log) {
        Matcher m = Pattern.compile("started in ([0-9]+(?:\\.[0-9]+)?)s").matcher(log);
        return m.find() ? new BigDecimal(m.group(1)).movePointRight(3).longValue() : null;
    }

    static String firstMatch(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    static int intEnv(String name, int fallback) {
        return Integer.parseInt(env(name, String.valueOf(fallback)));
    }

    /** Serialisation JSON minimale des resultats (Map / List / nombres / chaines). */
    static final class Json {
        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            write(out, value, 0);
            return out.append('\n').toString();
        }

        static void write(StringBuilder out, Object value, int indent) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof Map<?, ?> map) {
                out.append("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    out.append(first ? "\n" : ",\n").append("  ".repeat(indent + 1));
                    string(out, String.valueOf(e.getKey()));
                    out.append(": ");
                    write(out, e.getValue(), indent + 1);
                    first = false;
                }
                out.append(first ? "}" : "\n" + "  ".repeat(indent) + "}");
            } else if (value instanceof List<?> list) {
                out.append("[");
                boolean first = true;
                for (Object item : list) {
                    out.append(first ? "\n" : ",\n").append("  ".repeat(indent + 1));
                    write(out, item, indent + 1);
                    first = false;
                }
                out.append(first ? "]" : "\n" + "  ".repeat(indent) + "]");
            } else if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else {
                string(out, String.valueOf(value));
            }
        }

        static void string(StringBuilder out, String s) {
            out.append('"');
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format("\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }
    }
}
