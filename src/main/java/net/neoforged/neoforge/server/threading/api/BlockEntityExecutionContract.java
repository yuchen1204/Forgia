/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.api;

import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable descriptions shared by the native-adapter and protected furnace execution bridges.
 * These values grant no execution rights. Only the internal engine may bind an original invocation,
 * refresh its proof and issue a thread-bound permit under the existing region exclusion.
 * <p>
 * This first contract adds no adapter registration protocol or worker/commit callbacks. Existing
 * {@link BlockEntityTickAdapter} registration and callback timing remain unchanged. Resource descriptions
 * name existing audited paths, not general capability, inventory or multi-region access permissions.
 */
@ApiStatus.Experimental
public final class BlockEntityExecutionContract {
    private BlockEntityExecutionContract() {}

    /** A complete region owner and a subordinate work scope are different authorities. */
    public enum Mode {
        REGION_OWNER, RESOURCE_SCOPED
    }

    public enum Resources {
        UNPROVEN, REGION_LOCAL, FURNACE_LOCAL, FURNACE_CLAIMS
    }

    /** Candidate means more core validation is required; it never means permission to run. */
    public enum Outcome {
        CANDIDATE, SERIAL_FENCE
    }

    public record AdapterDescriptor(ResourceLocation id, Set<ResourceLocation> types, Mode mode) {
        public AdapterDescriptor {
            Objects.requireNonNull(id);
            types = Set.copyOf(types);
            Objects.requireNonNull(mode);
            if (types.isEmpty() || types.size() > 128) throw new IllegalArgumentException("Invalid execution descriptor types");
        }
    }

    public record Inspection(@Nullable AdapterDescriptor descriptor, Resources resources, Outcome outcome) {
        public Inspection {
            Objects.requireNonNull(resources);
            Objects.requireNonNull(outcome);
            if (outcome == Outcome.CANDIDATE && (descriptor == null || resources == Resources.UNPROVEN))
                throw new IllegalArgumentException("An unproven invocation is a serial fence");
            if (descriptor != null && resources != Resources.UNPROVEN
                    && (descriptor.mode() == Mode.REGION_OWNER) != (resources == Resources.REGION_LOCAL))
                throw new IllegalArgumentException("Resource description does not match execution mode");
        }

        public static Inspection serialFence() {
            return new Inspection(null, Resources.UNPROVEN, Outcome.SERIAL_FENCE);
        }
    }

    /** The engine also checks reference identity: an equal copy is not this invocation's inspection. */
    public record Refresh(Inspection inspection, Outcome outcome) {
        public Refresh {
            Objects.requireNonNull(inspection);
            Objects.requireNonNull(outcome);
            if (outcome == Outcome.CANDIDATE && inspection.outcome() != Outcome.CANDIDATE)
                throw new IllegalArgumentException("A refresh cannot widen a serial fence");
        }
    }
}
