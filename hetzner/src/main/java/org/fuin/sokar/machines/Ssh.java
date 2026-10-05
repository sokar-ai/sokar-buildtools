package org.fuin.sokar.machines;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import net.schmizz.keepalive.KeepAliveProvider;
import net.schmizz.sshj.DefaultConfig;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.jspecify.annotations.Nullable;

/**
 * A connection to a machine.
 * <p>
 * <strong>Host keys are not verified, deliberately.</strong> These machines are created and
 * destroyed per run - a rented server booted from a snapshot has a key nobody has seen before, and
 * pinning one would mean refusing to talk to the machine we were just given. What is protected
 * here is not the channel; it is what runs at the far end of it.
 * <p>
 * <strong>One connection, many channels.</strong> A connection per command meant one TCP handshake
 * and one key exchange each - around eighty in two minutes - and CI's sshd answered "Connection
 * refused" to two of them 145 seconds in, with nothing wrong on either side: a client that opens
 * and drops connections that fast looks like something worth refusing. Measured by counting
 * {@code Accepted publickey} in the machine's journal: about 80 connections and 15.7s before, 2 and
 * 8.5s after. Callers share one of these and open channels on it.
 * <p>
 * <strong>One connection needs a heartbeat.</strong> The connection that lives for a whole leg
 * spends minutes producing nothing while an image builds, and something on the path between a
 * GitHub runner and a rented machine drops a TCP flow that idle. Both tier 1 legs of run
 * 93676645170 lost it the same way: last output at 08:57:51 as tier 1 began, five minutes of
 * silence, then {@code Connection reset} - no test had failed, and the suite's own banner never
 * arrived because everything buffered went with the connection. sshj sends nothing of its own
 * unless it is asked to, so this is the cost of holding one connection rather than eighty, and it
 * is paid here.
 * <p>
 * HEARTBEAT rather than KEEP_ALIVE: it sends SSH_MSG_IGNORE and expects no answer, so it cannot
 * itself end the connection. KEEP_ALIVE disconnects after five unanswered requests, and a small
 * machine with native-image on every core is exactly where five answers might not come in time -
 * turning a working build into a lost one for the sake of noticing a dead peer sooner.
 */
public final class Ssh implements AutoCloseable {

    /** How long a probe may hang before the machine counts as unreachable. */
    private static final int PROBE_TIMEOUT = 4000;

    /** Seconds between heartbeats. Below every idle timeout that has been seen to cut this. */
    private static final int HEARTBEAT_INTERVAL = 30;

    private final String host;

    private final String user;

    private final Credential credential;

    private SSHClient client;

    private Ssh(String host, String user, Credential credential) throws IOException {
        this.host = host;
        this.user = user;
        this.credential = credential;
        this.client = connected();
    }

    /**
     * Opens a connection.
     *
     * @param host Where to connect.
     * @param user Who to connect as.
     * @param credential The key to authenticate with.
     * @return The open connection.
     * @throws IOException If the machine cannot be reached or the key is refused.
     */
    public static Ssh to(String host, String user, Credential credential) throws IOException {
        return new Ssh(host, user, credential);
    }

    /**
     * Tells whether a machine answers and accepts the key, without holding a connection.
     * <p>
     * Authenticating rather than only connecting: sshd accepts a TCP connection well before it
     * will run anything, so a probe that stops at "something is listening" reports ready for a
     * machine that is still booting.
     *
     * @param host Where to connect.
     * @param user Who to connect as.
     * @param credential The key to authenticate with.
     * @return {@code true} if it is up and the key works.
     */
    public static boolean reachable(String host, String user, Credential credential) {
        final SSHClient probe = new SSHClient();
        probe.addHostKeyVerifier(new PromiscuousVerifier());
        probe.setConnectTimeout(PROBE_TIMEOUT);
        probe.setTimeout(PROBE_TIMEOUT);
        try {
            probe.connect(host);
            authenticate(probe, user, credential);
            return true;
        } catch (IOException | RuntimeException ex) {
            // Down, or not up yet. Both are "no" here and the caller knows which it is waiting for.
            return false;
        } finally {
            try {
                probe.close();
            } catch (IOException ex) {
                // Nothing to do about a probe that will not close.
            }
        }
    }

    private SSHClient connected() throws IOException {
        final DefaultConfig config = new DefaultConfig();
        config.setKeepAliveProvider(KeepAliveProvider.HEARTBEAT);
        final SSHClient fresh = new SSHClient(config);
        fresh.addHostKeyVerifier(new PromiscuousVerifier());
        fresh.connect(host);
        authenticate(fresh, user, credential);
        // After connecting, because the keepalive belongs to the connection rather than the client.
        // Started by hand, which sshj's own example omits: setting the interval only sets a field
        // and flips isEnabled() to true - the thread stays in state NEW, and nothing in connect,
        // authenticate or startSession ever starts it. Measured, after a first version of this
        // that set the interval and sent nothing: with the interval set and the thread unstarted,
        // a connection idle for 60s sent exactly as many segments as one with no keepalive at all.
        final net.schmizz.keepalive.KeepAlive heartbeat = fresh.getConnection().getKeepAlive();
        heartbeat.setKeepAliveInterval(HEARTBEAT_INTERVAL);
        heartbeat.start();
        return fresh;
    }

    private static void authenticate(SSHClient client, String user, Credential credential)
            throws IOException {
        client.authPublickey(user, client.loadKeys(credential.material(), null, null));
    }

    /**
     * Runs a command with no terminal, the way a script would.
     *
     * @param command What to run.
     * @return What it wrote and what it exited with.
     * @throws IOException If the command cannot be run.
     */
    public Output run(String command) throws IOException {
        return run(command, null);
    }

    /**
     * Runs a command with no terminal and feeds it standard input.
     * <p>
     * <strong>This is how a secret reaches a machine.</strong> A command line is readable by every
     * process there and lands in shell history; standard input is neither.
     *
     * @param command What to run.
     * @param stdin What to feed it, or {@code null} for nothing.
     * @return What it wrote and what it exited with.
     * @throws IOException If the command cannot be run.
     */
    public Output run(String command, @Nullable String stdin) throws IOException {
        try (var session = client.startSession()) {
            final var exec = session.exec(command);
            if (stdin != null) {
                try (var in = exec.getOutputStream()) {
                    in.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            }
            final String out = new String(exec.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            final String err = new String(exec.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            exec.join();
            final Integer status = exec.getExitStatus();
            return new Output(out, err, status == null ? -1 : status);
        }
    }

    /**
     * Copies a local file to the machine.
     * <p>
     * Over the connection that is already open, rather than a second way in: one authentication,
     * one host to be reachable, and nothing that needs {@code scp} to exist on either side.
     *
     * @param local What to send.
     * @param remote Where to put it, an absolute path.
     * @throws IOException If it cannot be sent.
     */
    public void upload(java.nio.file.Path local, String remote) throws IOException {
        client.newSCPFileTransfer().upload(local.toString(), remote);
    }

    /**
     * Copies a file from the machine.
     *
     * @param remote What to fetch, an absolute path.
     * @param local Where to put it.
     * @throws IOException If it cannot be fetched.
     */
    public void download(String remote, java.nio.file.Path local) throws IOException {
        client.newSCPFileTransfer().download(remote, local.toString());
    }

    /**
     * Returns the underlying client, for a caller that needs a channel of its own.
     *
     * @return The client.
     */
    public SSHClient client() {
        return client;
    }

    /** Drops the session, for a restart that is about to take it away anyway. */
    public void disconnect() {
        try {
            client.disconnect();
        } catch (IOException ex) {
            // Already gone, which is what was wanted.
        }
    }

    /**
     * Opens a new session after a restart.
     *
     * @throws IOException If the machine cannot be reached.
     */
    public void reconnect() throws IOException {
        client = connected();
    }

    @Override
    public void close() throws IOException {
        client.disconnect();
    }

    /**
     * What a command wrote and what it exited with.
     *
     * @param out Standard output.
     * @param err Standard error.
     * @param status Exit status, or {@code -1} when the far end reported none.
     */
    public record Output(String out, String err, int status) {

        /**
         * Returns everything it wrote, whichever stream it used.
         *
         * @return Both streams, standard output first.
         */
        public String all() {
            return out + err;
        }
    }
}
