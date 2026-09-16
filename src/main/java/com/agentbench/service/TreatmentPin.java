package com.agentbench.service;

import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/** The treatment under test, as one digest of THIS build.
 *
 *  Port of the Python worker's "confirm the treatment hasn't changed since the job was enqueued"
 *  guard. There the treatment is the on-disk runner/ and oracle/ trees, which can be edited in
 *  place mid-experiment; here runner and oracle are compiled into the same artifact, so a single
 *  digest covers both — jobs.pinned_runner_sha and jobs.pinned_oracle_sha (and the experiment's
 *  two columns) therefore carry the SAME value, the build digest. The risk that remains is real:
 *  the service is rebuilt and redeployed while an experiment's jobs are still queued, and the
 *  remaining arms silently run a different treatment than the ones already scored.
 *
 *  The digest is the SHA-256 of the running jar's bytes when packaged, or of the compiled class
 *  tree (every file, its path included, in sorted order) when running exploded. Computed once at
 *  startup: the bytecode a live JVM runs cannot change under it. */
@Component
public class TreatmentPin {

    private final String current;

    public TreatmentPin() { this.current = compute(); }

    /** the digest every job/experiment enqueued by this build is pinned to */
    public String current() { return current; }

    private static String compute() {
        try {
            File source = new ApplicationHome(TreatmentPin.class).getSource();
            if (source == null) return "unpinned";
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            Path root = source.toPath();
            if (Files.isDirectory(root)) {
                try (Stream<Path> files = Files.walk(root)) {
                    List<Path> sorted = files.filter(Files::isRegularFile).sorted().toList();
                    for (Path p : sorted) {
                        sha.update(root.relativize(p).toString().getBytes());
                        sha.update((byte) 0);
                        sha.update(Files.readAllBytes(p));
                    }
                }
            } else {
                sha.update(Files.readAllBytes(root));
            }
            return HexFormat.of().formatHex(sha.digest()).substring(0, 16);
        } catch (Exception e) {
            return "unpinned";   // an unreadable code source must not stop the service from queueing work
        }
    }
}
