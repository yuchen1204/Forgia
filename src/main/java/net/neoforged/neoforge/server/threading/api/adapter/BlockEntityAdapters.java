/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.api.adapter;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jetbrains.annotations.ApiStatus;

/**
 * Platform-neutral requests for the canonical block-entity phase. None of these values is execution
 * authority. C5 has no generic proof backend: registered targets remain complete serial fences, including
 * requests from dedicated adapters. A future core-audited bridge must independently validate the entire
 * original ticker closure before any request can reach the existing kernel or resource reservation.
 */
@ApiStatus.Experimental
public final class BlockEntityAdapters {
    public static final int PROTOCOL_VERSION = 1;

    private BlockEntityAdapters() {}

    public enum Execution {
        SERIAL, REGION_OWNER, RESOURCE_SCOPED
    }

    public enum Access {
        READ, WRITE
    }

    /** Describes the adapter's claim, never an independently established ownership proof. */
    public enum Ownership {
        INSTANCE_LOCAL, SHARED_READ, UNKNOWN
    }

    public enum Validation {
        CURRENT, INVALID, UNKNOWN
    }

    public enum State {
        ACTIVE, INVALIDATED, UNREGISTERED, CLOSED
    }

    public enum Refusal {
        ADAPTER_SERIAL, CORE_AUDIT_UNAVAILABLE, INVALID_BINDING, INVALIDATED,
        UNKNOWN_RESOURCE, INVALID_RESOURCE, INVALID_DEPENDENCY, UNKNOWN_DEPENDENCY,
        BUDGET_EXCEEDED, CALLBACK_FAILED
    }

    /** Exact registry ids select candidates only. They do not certify classes, functions or callbacks. */
    public record Descriptor(String id, Set<String> types, Set<Execution> executions) {
        public Descriptor {
            id = identifier(id);
            types = Set.copyOf(types);
            types.forEach(BlockEntityAdapters::identifier);
            executions = Set.copyOf(executions);
            if (types.isEmpty() || types.size() > 128 || executions.isEmpty())
                throw new IllegalArgumentException("Invalid block-entity adapter descriptor");
        }
    }

    /** Read-only phase metadata, with no batch, world, scheduler or authority reference. */
    public record Phase(long sequence, String dimension, long gameTime) {
        public Phase {
            dimension = identifier(dimension);
        }
    }

    /**
     * A borrowed view of the exact source bound to this invocation. Available only on the coordinator
     * during inspect; every accessor fails afterwards or from another thread. Read only owned fields and
     * immutable metadata, never query capabilities/providers, consume RNG, load chunks, schedule work or
     * mutate. Do not retain the view, subject or world objects. This contract is not a JVM sandbox.
     */
    public interface Invocation {
        Phase phase();

        long ordinal();

        String type();

        int x();

        int y();

        int z();

        Object subject();
    }

    /** Bounded, read-only revision/lifecycle validation on the server, never a worker callback. */
    @FunctionalInterface
    public interface Validator {
        Validation validate();
    }

    /**
     * An actual backing-root identity, compared by reference without calling equals/hashCode. Ownership
     * and currentness are requests for independent core auditing; CURRENT never certifies a resource.
     * UNKNOWN does not invite a provider lookup. No declarations become reservable core Claims in C5.
     */
    public record Resource(Object identity, Access access, Ownership ownership, Validator current) {
        public Resource {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(access);
            Objects.requireNonNull(ownership);
            Objects.requireNonNull(current);
        }
    }

    /** Canonical topology/configuration/lifecycle dependencies must all still be current. */
    public record Dependency(Validator current) {
        public Dependency {
            Objects.requireNonNull(current);
        }
    }

    public record Request(Execution execution, List<Resource> resources, List<Dependency> dependencies) {
        public Request {
            Objects.requireNonNull(execution);
            resources = List.copyOf(resources);
            dependencies = List.copyOf(dependencies);
        }

        public static Request serial() {
            return new Request(Execution.SERIAL, List.of(), List.of());
        }
    }

    /**
     * Server-session identity; registrations cannot be rebound or resurrected. Mutations require the
     * registration server thread. Invalidation/unregister during a phase disables later callbacks; callback
     * references are retired after that phase closes. Neither operation cancels/replays a started ticker.
     */
    public interface Registration extends AutoCloseable {
        State state();

        void invalidate();

        @Override
        void close();
    }

    public record Status(Descriptor descriptor, State state, Optional<DedicatedBlockEntityAdapter.Audit> audit, long inspections,
            long serialRequests, long failures, Map<Refusal, Long> refusals) {
        public Status {
            refusals = Map.copyOf(refusals);
        }
    }

    /**
     * Declared dependency waves among inspected C5 requests are diagnostic only; omitted native/furnace
     * and unknown tickers make them unsuitable as a Level execution plan. They contain canonical ordinals and no source,
     * resource, validator or execution authority. They never drive the canonical cursor or grant work.
     */
    public record PhaseSummary(Phase phase, List<List<Long>> declaredWaves, long inspections, boolean successful) {
        public PhaseSummary {
            declaredWaves = declaredWaves.stream().map(List::copyOf).toList();
        }
    }

    private static String identifier(String id) {
        Objects.requireNonNull(id);
        if (id.length() > 256 || !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw new IllegalArgumentException("Expected a bounded namespaced identifier");
        return id;
    }
}
