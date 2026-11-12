/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.Claims;
import net.neoforged.neoforge.server.threading.region.BlockEntityResourceOwnership.SharedReads;

/** Uses the production fresh-publication representation, never reusing a proof from another capture. */
public final class SharedReadOwnershipCostProbe extends OwnershipCostProbe {
    @Override
    List<Claims> capture(Input input) {
        final var shared = SharedReads.capture(input.level, Arrays.asList(input.shared), CURRENT);
        final List<Claims> claims = new ArrayList<>(input.sources.length);
        for (int unit = 0; unit < input.sources.length; ++unit) claims.add(input.localClaims(unit).sharedReads(shared).build());
        return claims;
    }

    @Override
    boolean sameResources(List<Claims> planned, List<Claims> fresh) {
        final var compared = new IdentityHashMap<SharedReads, SharedReads>();
        for (int unit = 0; unit < planned.size(); ++unit) if (!planned.get(unit).sameResources(fresh.get(unit), compared)) return false;
        return true;
    }
}
