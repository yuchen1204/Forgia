/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.ApiStatus;

/** One-time migration before the upstream config loader can remove unknown Forgia keys. */
@ApiStatus.Internal
public final class ForgiaConfigMigration {
    private ForgiaConfigMigration() {}

    /**
     * Copies only Forgia keys into a missing {@code forgia.toml}. Existing independent files always win.
     * <p>
     * Called for both the instance config directory and the world's serverconfig directory before SERVER
     * configs load, preserving the loader's normal per-world override rules. The legacy files are read only.
     * If upstream already removed all Forgia keys, a saved {@code neoforge-server.forgia.toml} is also recognized.
     * A malformed legacy file fails startup with its path rather than silently disabling the engine.
     */
    public static void migrate(Path directory) {
        final Path target = directory.resolve(ForgiaConfig.FILE_NAME);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        for (final String name : List.of("neoforge-server.toml", "neoforge-server.forgia.toml")) {
            final Path source = directory.resolve(name);
            if (!Files.exists(source)) {
                continue;
            }
            Path temporary = null;
            try {
                final CommentedConfig legacy;
                try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                    legacy = new TomlParser().parse(reader);
                }
                final CommentedConfig migrated = CommentedConfig.inMemory();
                for (final String key : ForgiaConfig.SERVER_SPEC.getValues().valueMap().keySet()) {
                    if (legacy.contains(key)) {
                        migrated.set(key, legacy.getRaw(key));
                    }
                }
                if (migrated.isEmpty()) {
                    continue;
                }
                temporary = Files.createTempFile(directory, "forgia-migration-", ".toml.tmp");
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    new TomlWriter().write(migrated, writer);
                }
                // No REPLACE_EXISTING: a user's independent configuration must never be overwritten.
                Files.move(temporary, target);
                LogUtils.getLogger().info("Migrated Forgia settings from {} to {}; the legacy file was left unchanged", source, target);
                return;
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("Cannot migrate Forgia configuration from " + source + " to " + target, exception);
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException exception) {
                        LogUtils.getLogger().warn("Could not remove Forgia migration temporary file {}", temporary, exception);
                    }
                }
            }
        }
    }
}
