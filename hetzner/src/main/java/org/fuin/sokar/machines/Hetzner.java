package org.fuin.sokar.machines;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Renting test machines, and making sure none is left behind.
 * <p>
 * <strong>The one thing worth reading first: a server that is not destroyed costs 81 EUR a
 * month</strong>, against 3 cents for the fifteen minutes it is meant to live. So destruction is
 * structural rather than a step at the end - {@link #acquire} hands back a {@link Lease}
 * that deletes, and {@link #sweep} exists because a process killed between two statements cannot
 * clean up after itself.
 * <p>
 * Everything created here is labelled {@code sokar=ci} so the sweep can find it without a list of
 * names to keep in step with reality.
 */
public final class Hetzner implements Machines {

    /** Applied to everything created here, and how the sweep finds it again. */
    static final String LABEL_SELECTOR = "sokar=ci";

    /** Identifies the run that created a server, so a run can delete its own and only its own. */
    static final String RUN_LABEL = "run";

    /**
     * Where a CI server may be created.
     * <p>
     * A zone rather than a location on purpose: a single location runs out - fsn1 offered zero
     * server types on 2026-09-06 while nbg1 and hel1 offered eighteen - and a hard-coded one
     * fails with "unsupported location for server type", which reads like a wrong type or a bad
     * token rather than a full datacentre.
     */
    private static final String NETWORK_ZONE = "eu-central";

    /**
     * How long to keep asking when the project is at its server limit, and how often.
     * <p>
     * Four repositories rent machines now and nothing coordinates them, so overlapping runs
     * collide. The API says {@code resource_limit_exceeded} and the run dies twenty minutes in,
     * having already built everything.
     */
    private static final Duration LIMIT_WAIT = Duration.ofSeconds(30);

    /** How many times to wait out a full project before giving up. */
    private static final int LIMIT_ATTEMPTS = 20;

    /** How long a create or delete may take before something is wrong with it. */
    private static final Duration ACTION_PATIENCE = Duration.ofMinutes(5);

    private final Api api;

    private final String runId;

    /** How long to wait between attempts at a full project. Shortened by tests, never in use. */
    private final Duration limitWait;

    private Hetzner(Api api, String runId, Duration limitWait) {
        this.api = api;
        this.runId = runId;
        this.limitWait = limitWait;
    }

    /**
     * Opens the API with a token.
     *
     * @param token The API token, from the environment and never from an argument.
     * @param runId Identifies this run, so its servers can be told from another run's.
     * @return The API.
     */
    public static Hetzner with(String token, String runId) {
        return new Hetzner(new Api(token), runId, LIMIT_WAIT);
    }

    /**
     * Points at another API root, for a test that stands one up.
     *
     * @param base The API root, without a trailing slash.
     * @param runId Identifies this run.
     * @return The API.
     */
    static Hetzner against(String base, String runId) {
        return against(base, runId, LIMIT_WAIT);
    }

    /**
     * Points at another API root and does not really wait, for a test of the waiting.
     *
     * @param base The API root, without a trailing slash.
     * @param runId Identifies this run.
     * @param limitWait How long to wait between attempts.
     * @return The API.
     */
    static Hetzner against(String base, String runId, Duration limitWait) {
        return new Hetzner(new Api("test-token", base), runId, limitWait);
    }

    /**
     * Creates a server and hands back something that deletes it.
     * <p>
     * The delete is in the {@code close}, so a caller using try-with-resources cannot forget it
     * and cannot skip it by failing early. That is the whole reason this returns a resource rather
     * than a server.
     *
     * @param spec What to create.
     * @return The rented machine, which must be closed.
     * @throws IOException If it cannot be created.
     */
    @Override
    public Lease acquire(Spec spec) throws IOException {
        final Map<String, Object> snapshot = newestSnapshot(spec.os());
        return acquire(spec, Values.id(snapshot.get("id")),
                Values.text(snapshot, "description"));
    }

    /**
     * Returns what identifies this run.
     * <p>
     * Used in a server's name as well as its labels: a name built from a timestamp collides when
     * two runs start in the same second, which is exactly what two pushes landing together
     * produce - and the API refuses the second with "server name is already used", failing a leg
     * for a reason that has nothing to do with the change under test.
     *
     * @return The run id.
     */
    public String runId() {
        return runId;
    }

    /**
     * Creates a machine from a stock image rather than from one of ours.
     * <p>
     * What the snapshot provisioner starts from: there is no snapshot yet when one is being made.
     *
     * @param spec What to create.
     * @param image A stock image name, such as {@code ubuntu-26.04}.
     * @return The machine, which must be closed.
     * @throws IOException If it cannot be created.
     */
    public Lease acquireFromStock(Spec spec, String image) throws IOException {
        return acquire(spec, image, image);
    }

    private Lease acquire(Spec spec, Object image, String describedAs) throws IOException {
        final List<Placement> candidates = placements(spec.serverTypes());
        final long key = keyMatching(spec.credential());

        Map<String, Object> created = null;
        Placement placement = null;
        final List<String> refused = new ArrayList<>();
        for (final Placement candidate : candidates) {
            System.out.println("creating " + spec.name() + ": " + candidate.type() + ", "
                    + describedAs + ", " + candidate.location());
            try {
                created = createWhenThereIsRoom(spec, image, candidate, key);
                placement = candidate;
                break;
            } catch (Api.ApiException ex) {
                // A type this image cannot go on is the same answer as a type nobody has: move
                // to the next one named rather than ending the run. Measured against a snapshot
                // taken from a 160 GB machine - every cheaper type answered "image disk is
                // bigger than server type disk", which is a property of the pair and not of the
                // project's stock.
                if (!refusesTheType(ex)) {
                    throw ex;
                }
                System.out.println("  " + candidate.type() + " will not take this image: "
                        + ex.getMessage());
                refused.add(candidate.type());
            }
        }
        if (created == null) {
            throw new IOException("none of " + spec.serverTypes() + " could host the '"
                    + spec.os() + "' snapshot" + (refused.isEmpty() ? ""
                            : "; refused by " + refused)
                    + ". A snapshot can only be restored onto a disk at least as big as the one it"
                    + " was taken from, so cheaper types need a snapshot built on a smaller disk.");
        }
        final Map<String, Object> server = Values.object(created, "server");
        final long id = Values.id(server.get("id"));
        // The server bills from the moment the API answered. Until the Lease that deletes it exists, a failure
        // here is the only thing that knows its id, so it deletes it before giving up.
        final String address;
        try {
            await(Values.object(created, "action"));
            address = Values.text(Values.object(Values.object(server, "public_net"), "ipv4"), "ip");
            if (address.isBlank()) {
                throw new IOException("the API named no address for " + spec.name() + " (server " + id + ")");
            }
        } catch (IOException | RuntimeException ex) {
            System.err.println("deleting " + spec.name() + " (server " + id + "), which never became usable");
            try {
                delete(id);
            } catch (IOException | RuntimeException deleteFailed) {
                System.err.println("COULD NOT DELETE " + spec.name() + " (server " + id + "): "
                        + deleteFailed.getMessage() + " - delete it by hand, it is billing");
                ex.addSuppressed(deleteFailed);
            }
            throw ex;
        }
        System.out.println("created  " + spec.name() + " at " + address);
        return new Lease(id, spec.name(), address, spec, () -> destroy(spec, id, address));
    }

    /**
     * Tells whether the API refused the type rather than the request.
     * <p>
     * Narrow on purpose: everything else - a bad token, a full project, an image that does not
     * exist - is a mistake that trying a different machine cannot fix, and quietly working
     * through a list of types would turn one clear error into several confusing ones.
     *
     * @param refusal What the API said.
     * @return Whether another type is worth trying.
     */
    private static boolean refusesTheType(Api.ApiException refusal) {
        return String.valueOf(refusal.getMessage()).contains("image disk is bigger than server");
    }

    /**
     * Destroys a rented server, or says loudly that it could not.
     *
     * @param spec What was asked for, which says whether to keep it.
     * @param id The server.
     * @param address Where it is, for a message somebody has to act on.
     * @throws IOException If it could not be deleted.
     */
    private void destroy(Spec spec, long id, String address) throws IOException {
        if (spec.keep()) {
            System.out.println("KEEPING " + spec.name() + " at " + address
                    + " - it is costing money until it is swept or deleted by hand.");
            return;
        }
        System.out.println("deleting " + spec.name());
        try {
            delete(id);
            System.out.println("deleted  " + spec.name());
        } catch (IOException ex) {
            System.err.println("COULD NOT DELETE " + spec.name() + ": " + ex.getMessage());
            System.err.println("  delete it by hand, it is billing: " + address);
            throw ex;
        }
    }

    private Map<String, Object> createWhenThereIsRoom(Spec spec, Object image,
            Placement placement, long key) throws IOException {
        final Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("sokar", "ci");
        labels.put(RUN_LABEL, runId);
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", spec.name());
        body.put("server_type", placement.type());
        body.put("image", image);
        body.put("location", placement.location());
        body.put("ssh_keys", List.of(key));
        body.put("labels", labels);
        body.put("start_after_create", Boolean.TRUE);

        for (int attempt = 1; attempt <= LIMIT_ATTEMPTS; attempt++) {
            try {
                return api.post("/servers", body);
            } catch (Api.ApiException ex) {
                // Only that one error is retried. Anything else - a bad image, a full datacentre,
                // a rejected token - is a mistake that waiting cannot fix, and burning ten minutes
                // before reporting it would be worse than failing now.
                if (!"resource_limit_exceeded".equals(ex.code())) {
                    throw ex;
                }
                if (attempt == LIMIT_ATTEMPTS) {
                    throw new IOException("the project was still at its server limit after "
                            + LIMIT_ATTEMPTS * limitWait.toSeconds() / 60 + " minutes. Another "
                            + "run is holding machines, or something leaked one: check with the "
                            + "sweep.", ex);
                }
                System.out.println("  at the project's server limit, waiting "
                        + limitWait.toSeconds() + "s (" + attempt + "/" + LIMIT_ATTEMPTS + ")");
                sleep(limitWait);
            }
        }
        throw new IOException("unreachable");
    }

    /**
     * Waits for an action to finish, because "created" is not "ready".
     *
     * @param action The action the API returned.
     * @throws IOException If it failed, or took longer than anything reasonable.
     */
    private void await(Map<String, Object> action) throws IOException {
        if (action.isEmpty()) {
            return;
        }
        final long id = Values.id(action.get("id"));
        final Instant deadline = Instant.now().plus(ACTION_PATIENCE);
        String status = Values.text(action, "status");
        while ("running".equals(status)) {
            if (Instant.now().isAfter(deadline)) {
                throw new IOException("action " + id + " was still running after "
                        + ACTION_PATIENCE.toMinutes() + " minutes");
            }
            sleep(Duration.ofSeconds(2));
            status = Values.text(Values.object(api.get("/actions/" + id), "action"), "status");
        }
        if (!"success".equals(status)) {
            throw new IOException("action " + id + " ended as '" + status + "'");
        }
    }

    /**
     * Returns the most recent snapshot for one operating system.
     * <p>
     * By label rather than by id, so a workflow does not carry a number that goes stale the next
     * time a snapshot is rebuilt. Newest wins, because rebuilding is how an image is updated.
     *
     * @param os The {@code os} label a snapshot was built with, such as {@code fedora}.
     * @return The snapshot.
     * @throws IOException If there is none, saying which were built.
     */
    Map<String, Object> newestSnapshot(String os) throws IOException {
        final List<Map<String, Object>> available = new ArrayList<>();
        for (final Map<String, Object> image : api.all(
                "/images?type=snapshot&label_selector=" + LABEL_SELECTOR + ",os=" + os, "images")) {
            if ("available".equals(Values.text(image, "status"))) {
                available.add(image);
            }
        }
        if (available.isEmpty()) {
            final TreeSet<String> built = new TreeSet<>();
            for (final Map<String, Object> image : api.all(
                    "/images?type=snapshot&label_selector=" + LABEL_SELECTOR, "images")) {
                built.add(Values.labels(image).getOrDefault("os", "?"));
            }
            throw new IOException("No snapshot for '" + os + "'. Built: "
                    + (built.isEmpty() ? "none" : built)
                    + ". Build one with the snapshot provisioner before running a leg.");
        }
        return available.stream().max(Comparator.comparing(Values::created)).orElseThrow();
    }

    /**
     * Picks the first wanted type that a location in the zone can actually serve right now.
     * <p>
     * <strong>Ordered, so cheaper can be preferred without risking a run.</strong> A workflow
     * names the types it wants in the order it wants them and gets the first that is available;
     * naming one type instead means a run dies when that type is sold out.
     * <p>
     * <strong>Availability, not adequacy.</strong> This moves on when a type is not
     * <em>offered</em>, never when it turns out too small for the work - a two-core machine that
     * exists will be used and the build will fail on it. The order is a price preference among
     * machines that can be created, and the smallest entry has to be one the job can actually run
     * on.
     * <p>
     * Asked rather than assumed: availability is per location and changes - fsn1 offered zero
     * server types on 2026-09-06 while nbg1 and hel1 offered eighteen - and Hetzner reports a
     * location that cannot serve a type the same way it reports a nonsense one.
     * <p>
     * <strong>Asked of the server type, where the API keeps it now.</strong> Each type lists its locations with
     * whether it is {@code available} there; the zone of a location comes from {@code /locations}. The
     * {@code /datacenters} endpoint this read before answers 410 since Hetzner removed it on 2026-10-01, which
     * stopped every leg before it had a machine.
     *
     * @param types Types to try, in order.
     * @return The type to create and where.
     * @throws IOException If nothing in the zone offers any of them, saying what was asked.
     */
    List<Placement> placements(List<String> types) throws IOException {
        final Map<String, String> zones = new java.util.LinkedHashMap<>();
        for (final Map<String, Object> location : api.all("/locations", "locations")) {
            zones.put(Values.text(location, "name"), Values.text(location, "network_zone"));
        }
        final List<Placement> found = new ArrayList<>();
        final List<String> tried = new ArrayList<>();
        for (final String type : types) {
            final List<Map<String, Object>> known = api.all("/server_types?name=" + type,
                    "server_types");
            if (known.isEmpty()) {
                tried.add(type + "=no such type");
                continue;
            }
            final List<String> where = new ArrayList<>();
            for (final Map<String, Object> location : Values.objects(known.getFirst(), "locations")) {
                final String here = Values.text(location, "name");
                if (!NETWORK_ZONE.equals(zones.get(here))) {
                    continue;
                }
                if (Boolean.TRUE.equals(location.get("available"))) {
                    // The rate, because the reason for an ordered list is money and a run that
                    // does not say what it chose to spend cannot be checked against the bill.
                    System.out.println("location " + here + " has " + type
                            + hourly(known.getFirst(), here));
                    found.add(new Placement(type, here));
                    break;
                }
                where.add(here);
            }
            if (where.size() > 0 && found.stream().noneMatch(each -> each.type().equals(type))) {
                tried.add(type + "=not offered in " + String.join("/", where));
            }
        }
        if (found.isEmpty()) {
            throw new IOException("no location in " + NETWORK_ZONE + " currently offers any of "
                    + types + ": " + String.join("; ", tried));
        }
        return found;
    }

    /**
     * Returns what a type costs per hour where it will be created.
     *
     * @param type The server type as the API describes it.
     * @param location Where it will be created.
     * @return Something to append to a line, or an empty string when the API gave no price.
     */
    private static String hourly(Map<String, Object> type, String location) {
        for (final Map<String, Object> price : Values.objects(type, "prices")) {
            if (location.equals(Values.text(price, "location"))) {
                final String gross = Values.text(Values.object(price, "price_hourly"), "gross");
                if (!gross.isBlank()) {
                    // Trimmed: the API answers with eight decimal places, which is noise in a log.
                    return " at " + trimmed(gross) + " EUR/h";
                }
            }
        }
        return "";
    }

    /**
     * Returns a price with as many decimals as anybody reads.
     *
     * @param price What the API said.
     * @return The same number, to four decimals.
     */
    static String trimmed(String price) {
        try {
            return new java.math.BigDecimal(price)
                    .setScale(4, java.math.RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException ex) {
            return price;
        }
    }

    /**
     * What will be created and where.
     *
     * @param type The Hetzner server type.
     * @param location Where it can be created.
     */
    record Placement(String type, String location) {
    }

    /**
     * Returns the project's key that matches the private key in hand.
     * <p>
     * Matched by fingerprint rather than by name. Naming it means keeping two things in step - the
     * secret holding the private half and the key registered in the project - and when they drift
     * the server is created with a public key nobody holds, which shows up as a connection refused
     * twenty lines later. The fingerprint cannot drift.
     *
     * @param credential The private key that will be used to connect.
     * @return The id of the matching key in the project.
     * @throws IOException If none matches, listing what the project holds.
     */
    long keyMatching(Credential credential) throws IOException {
        final String wanted = Fingerprint.md5(credential);
        final List<Map<String, Object>> available = api.all("/ssh_keys", "ssh_keys");
        final List<String> held = new ArrayList<>();
        for (final Map<String, Object> key : available) {
            if (wanted.equals(Values.text(key, "fingerprint"))) {
                System.out.println("ssh key '" + Values.text(key, "name")
                        + "' matches the private key in hand");
                return Values.id(key.get("id"));
            }
            held.add(Values.text(key, "name") + "=" + Values.text(key, "fingerprint"));
        }
        throw new IOException("No key in the project matches the private key (" + wanted + ")."
                + " In the project: " + (held.isEmpty() ? "none" : held)
                + ". Add its public half to the project.");
    }

    /**
     * Stops a server, so an image of it is not a picture of a half-written disk.
     *
     * @param id The server.
     * @throws IOException If the API refuses.
     */
    public void shutdown(long id) throws IOException {
        await(Values.object(api.post("/servers/" + id + "/actions/shutdown", Map.of()), "action"));
    }

    /**
     * Takes a snapshot of a stopped server.
     *
     * @param id The server.
     * @param description What to call it.
     * @param os The {@code os} label, which is how a leg finds it again.
     * @return The new image's id.
     * @throws IOException If the API refuses.
     */
    public long snapshot(long id, String description, String os) throws IOException {
        final Map<String, Object> answer = api.post("/servers/" + id + "/actions/create_image",
                Map.of("description", description, "type", "snapshot",
                        "labels", Map.of("sokar", "ci", "os", os)));
        await(Values.object(answer, "action"));
        return Values.id(Values.object(answer, "image").get("id"));
    }

    /**
     * Deletes one server.
     *
     * @param id Its id.
     * @throws IOException If the API refuses.
     */
    void delete(long id) throws IOException {
        await(Values.object(api.delete("/servers/" + id), "action"));
    }

    /**
     * Returns every server this labelling knows about.
     *
     * @return What the project is holding.
     * @throws IOException If the API refuses.
     */
    public List<Server> servers() throws IOException {
        final List<Server> found = new ArrayList<>();
        for (final Map<String, Object> body : api.all("/servers?label_selector=" + LABEL_SELECTOR,
                "servers")) {
            found.add(new Server(Values.id(body.get("id")), Values.text(body, "name"),
                    Values.labels(body).getOrDefault(RUN_LABEL, ""), Values.created(body),
                    Values.text(Values.object(Values.object(body, "public_net"), "ipv4"), "ip")));
        }
        return found;
    }

    /**
     * Deletes the servers this run created, and only those.
     * <p>
     * Deleting by age instead would catch another run's server whenever that run is slower than
     * the window, and the symptom - ssh dying part way through a build - is close to undebuggable.
     *
     * @return How many were deleted.
     * @throws IOException If the API refuses.
     */
    public int deleteMine() throws IOException {
        return deleteRun(runId);
    }

    /**
     * Deletes what one named run created.
     * <p>
     * <strong>Named rather than implied, because a run id is not always reproducible.</strong> In
     * CI it comes from {@code GITHUB_RUN_ID} and the leg, so every step of one leg derives the
     * same one and {@link #deleteMine()} is enough. Locally it is a timestamp, and a second
     * process cannot derive what a first one used - so a lease writes its id down and a sweep is
     * told it. Without this, that line in the file would be something nothing could read.
     *
     * @param run The id to match, as {@code lease --write} recorded it.
     * @return How many were deleted.
     * @throws IOException If the provider refuses.
     */
    public int deleteRun(String run) throws IOException {
        int deleted = 0;
        for (final Server server : servers()) {
            if (server.run().equals(run)) {
                System.out.println("deleting " + server.name());
                delete(server.id());
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * Deletes servers older than a window, whoever created them.
     * <p>
     * This is the net under everything else: a process killed between two statements cannot clean
     * up after itself, and what it leaves behind bills until somebody notices.
     *
     * @param olderThan How old a server must be to count as left behind.
     * @param dryRun Whether to only say what would go.
     * @return How many were deleted, or would have been.
     * @throws IOException If the API refuses.
     */
    public int sweep(Duration olderThan, boolean dryRun) throws IOException {
        final Instant cutoff = Instant.now().minus(olderThan);
        int swept = 0;
        for (final Server server : servers()) {
            if (server.created().isAfter(cutoff)) {
                // Said out loud, because "0 to delete" otherwise covers two different worlds: a
                // project holding three machines that are all in use, and a project holding
                // none. Only one of those means the sweep is looking at what it thinks it is.
                System.out.println("keeping " + server.name() + ", created " + server.created());
                continue;
            }
            System.out.println((dryRun ? "WOULD DELETE " : "deleting ") + server.name()
                    + ", created " + server.created() + " (run " + server.run() + ")");
            if (!dryRun) {
                delete(server.id());
            }
            swept++;
        }
        if (swept == 0) {
            System.out.println("nothing to sweep");
        }
        return swept;
    }

    private static void sleep(Duration duration) throws IOException {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting", ex);
        }
    }

    @Override
    public void close() {
        api.close();
    }

    /**
     * A server the project is holding.
     *
     * @param id Its id.
     * @param name Its name.
     * @param run The run that created it, or an empty string.
     * @param created When it was created.
     * @param address Its address.
     */
    public record Server(long id, String name, String run, Instant created, String address) {
    }
}
