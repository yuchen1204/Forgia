/*
 * Copyright (c) Forgia contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat;


/**
 * Detects whether the current thread is one of Forgia's region workers.
 * <p>
 * The compatibility mod deliberately does not link against Forgia: a server that runs the plain NeoForge core
 * must be able to load this jar and simply never match, so the check is the worker thread's name plus an
 * optional system property override. Forgia names its pool threads {@code NeoForge Region Worker #n}; an
 * operator can force the serialized path with {@code -Dforgia.compat.alwaysSerialize=true} to test a pack on a
 * core that renames its threads.
 */
public final class RegionThreadsCompat {
    private static final String WORKER_THREAD_PREFIX = "NeoForge Region Worker";
    private static final boolean ALWAYS_SERIALIZE = Boolean.getBoolean("forgia.compat.alwaysSerialize");

    private RegionThreadsCompat() {}

    /** {@return true when the current thread may run a region tick in parallel with other workers} */
    public static boolean isRegionThread() {
        if (ALWAYS_SERIALIZE) {
            return true;
        }
        final Thread thread = Thread.currentThread();
        return thread.getName().startsWith(WORKER_THREAD_PREFIX);
    }

    /** {@return the worker thread's name, or {@code null} outside a worker} */
    public static String regionThreadName() {
        return isRegionThread() ? Thread.currentThread().getName() : null;
    }
}
