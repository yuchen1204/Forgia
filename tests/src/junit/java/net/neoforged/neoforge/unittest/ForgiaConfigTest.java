/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.unittest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.common.ForgiaConfig;
import net.neoforged.neoforge.common.ForgiaConfigMigration;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForgiaConfigTest {
    @TempDir
    Path directory;

    @Test
    void serverConfigIsRegisteredSeparatelyWithDefaultOffSwitches() {
        final ModConfig independent = ModConfigs.getFileMap().get("forgia.toml");
        final ModConfig upstream = ModConfigs.getFileMap().get("neoforge-server.toml");
        assertNotNull(independent);
        assertNotNull(upstream);
        assertSame(ForgiaConfig.SERVER_SPEC, independent.getSpec());
        assertEquals(ModConfig.Type.SERVER, independent.getType());
        assertFalse(((ModConfigSpec) upstream.getSpec()).getValues().contains("enableRegionThreadingBridge"));
        assertTrue(((ModConfigSpec) upstream.getSpec()).getValues().contains("removeErroringEntities"));
        for (final var entry : ForgiaConfig.SERVER_SPEC.getValues().valueMap().entrySet()) {
            if (entry.getKey().startsWith("enable")) {
                assertEquals(false, ((ModConfigSpec.ConfigValue<?>) entry.getValue()).getDefault(), entry.getKey());
            }
        }
    }

    @Test
    void migratesOnlyForgiaKeysAndDoesNotRewriteLegacyFile() throws IOException {
        final String legacy = """
                # Preserve upstream settings and comments until its own loader processes them.
                removeErroringEntities = true
                permissionHandler = "example:handler"
                enableRegionThreadingBridge = true
                enableRegionThreadingEntityTicks = true
                regionThreadingWorkerThreads = 9
                regionThreadingFixedRegionChunks = 8
                regionThreadingMode = "FIXED_3X3"
                """;
        Files.writeString(this.directory.resolve("neoforge-server.toml"), legacy);
        ForgiaConfigMigration.migrate(this.directory);
        final CommentedConfig migrated = read(this.directory.resolve("forgia.toml"));
        assertEquals(5, migrated.size());
        assertEquals(true, migrated.get("enableRegionThreadingBridge"));
        assertEquals(9, (int) migrated.get("regionThreadingWorkerThreads"));
        assertEquals(8, (int) migrated.get("regionThreadingFixedRegionChunks"));
        assertFalse(migrated.contains("removeErroringEntities"));
        assertFalse(migrated.contains("permissionHandler"));
        assertEquals(legacy, Files.readString(this.directory.resolve("neoforge-server.toml")));
    }

    @Test
    void existingIndependentConfigWinsEvenOverMalformedLegacyFile() throws IOException {
        final String independent = "enableRegionThreadingBridge = false\nregionThreadingWorkerThreads = 3\n";
        Files.writeString(this.directory.resolve("forgia.toml"), independent);
        Files.writeString(this.directory.resolve("neoforge-server.toml"), "broken = [");
        ForgiaConfigMigration.migrate(this.directory);
        assertEquals(independent, Files.readString(this.directory.resolve("forgia.toml")));
    }

    @Test
    void savedSideFileCanRecoverSettingsAfterUpstreamRemovedTheKeys() throws IOException {
        Files.writeString(this.directory.resolve("neoforge-server.toml"), "removeErroringEntities = false\n");
        Files.writeString(this.directory.resolve("neoforge-server.forgia.toml"), "enableRegionThreadingBridge = true\nregionThreadingWorkerThreads = 9\n");
        ForgiaConfigMigration.migrate(this.directory);
        assertEquals(true, read(this.directory.resolve("forgia.toml")).get("enableRegionThreadingBridge"));
        assertEquals(9, (int) read(this.directory.resolve("forgia.toml")).get("regionThreadingWorkerThreads"));
    }

    @Test
    void activeLegacySettingsTakePriorityOverASavedSideFile() throws IOException {
        Files.writeString(this.directory.resolve("neoforge-server.toml"), "enableRegionThreadingBridge = false\n");
        Files.writeString(this.directory.resolve("neoforge-server.forgia.toml"), "enableRegionThreadingBridge = true\n");
        ForgiaConfigMigration.migrate(this.directory);
        assertEquals(false, read(this.directory.resolve("forgia.toml")).get("enableRegionThreadingBridge"));
    }

    @Test
    void switchingBackToUpstreamAndThenForgiaDoesNotResetIndependentSettings() throws IOException {
        Files.writeString(this.directory.resolve("neoforge-server.toml"), "enableRegionThreadingBridge = true\n");
        ForgiaConfigMigration.migrate(this.directory);
        final String migrated = Files.readString(this.directory.resolve("forgia.toml"));
        // Simulate upstream correcting its own file after a core switch.
        Files.writeString(this.directory.resolve("neoforge-server.toml"), "removeErroringEntities = false\n");
        ForgiaConfigMigration.migrate(this.directory);
        assertEquals(migrated, Files.readString(this.directory.resolve("forgia.toml")));
        assertEquals(true, read(this.directory.resolve("forgia.toml")).get("enableRegionThreadingBridge"));
    }

    @Test
    void baseAndWorldOverrideAreMigratedIndependently() throws IOException {
        final Path world = Files.createDirectory(this.directory.resolve("serverconfig"));
        Files.writeString(this.directory.resolve("neoforge-server.toml"), "enableRegionThreadingBridge = true\n");
        Files.writeString(world.resolve("neoforge-server.toml"), "enableRegionThreadingBridge = false\n");
        ForgiaConfigMigration.migrate(this.directory);
        ForgiaConfigMigration.migrate(world);
        assertEquals(true, read(this.directory.resolve("forgia.toml")).get("enableRegionThreadingBridge"));
        assertEquals(false, read(world.resolve("forgia.toml")).get("enableRegionThreadingBridge"));
    }

    @Test
    void noLegacyForgiaKeysLeavesCreationOfDefaultOffFileToTheLoader() throws IOException {
        ForgiaConfigMigration.migrate(this.directory);
        Files.writeString(this.directory.resolve("neoforge-server.toml"), "removeErroringEntities = false\n");
        ForgiaConfigMigration.migrate(this.directory);
        assertFalse(Files.exists(this.directory.resolve("forgia.toml")));
        assertFalse(ForgiaConfig.SERVER.enableRegionThreadingBridge.getDefault());
    }

    @Test
    void malformedLegacyConfigFailsExplicitlyWithoutCreatingAPartialTarget() throws IOException {
        Files.writeString(this.directory.resolve("neoforge-server.toml"), "enableRegionThreadingBridge = [");
        final IllegalStateException failure = assertThrows(IllegalStateException.class, () -> ForgiaConfigMigration.migrate(this.directory));
        assertTrue(failure.getMessage().contains("neoforge-server.toml"));
        assertFalse(Files.exists(this.directory.resolve("forgia.toml")));
    }

    private static CommentedConfig read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return new TomlParser().parse(reader);
        }
    }
}
