/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.api.adapter;

import java.util.Objects;
import org.jetbrains.annotations.ApiStatus;

/**
 * Extension for a dedicated integration's independently reviewable audit provenance. Resource/topology
 * precision uses the same request and validators as a general adapter. An audit reference, revision,
 * class name, mod id or this interface's implementation never selects an execution backend or proves
 * runtime transformations, side effects, aliases or external writers. No concrete integration is shipped.
 */
@ApiStatus.Experimental
public interface DedicatedBlockEntityAdapter extends BlockEntityAdapter {
    Audit audit();

    record Audit(String reference, String revision) {
        public Audit {
            Objects.requireNonNull(reference);
            Objects.requireNonNull(revision);
            if (reference.isBlank() || reference.length() > 512 || revision.isBlank() || revision.length() > 128)
                throw new IllegalArgumentException("Invalid dedicated adapter audit reference");
        }
    }
}
