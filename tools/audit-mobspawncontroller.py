#!/usr/bin/env python3
"""Read-only, offline inventory for the pinned MobSpawnController 1.2.3 adapter.

Uses javap (no mod initialization) and raw JSON inventory. This does not certify
parallel safety or reproduce the mod's complete configuration decoding semantics.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tomllib
import zipfile


MANAGER = "com.mobspawncontroller.command.MobSpawnManager"
EXPECTED_METHODS = {
    "isSpawnAllowed": "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/entity/MobSpawnType;)Z",
    "isNaturalSpawnAllowed": "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/entity/MobSpawnType;)Z",
    "matchesAnyStructure": "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/util/List;)Z",
    "getAllRules": "()Ljava/util/Map;",
    "getAllNaturalSpawnSettings": "()Ljava/util/Map;",
    "applyAttributeOverrides": "(Lnet/minecraft/world/entity/LivingEntity;)V",
}


def inspect_jar(jar, javap):
    with zipfile.ZipFile(jar) as archive:
        metadata = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode())
    mod = next(item for item in metadata["mods"] if item["modId"] == "mobspawncontroller")
    result = subprocess.run(
        [javap, "-p", "-s", "-classpath", str(jar), MANAGER],
        capture_output=True, text=True, check=True,
    )
    signatures = {}
    method = None
    for line in result.stdout.splitlines():
        match = re.search(r"\b(\w+)\([^;]*\);$", line.strip())
        if match:
            method = match.group(1)
        elif method and line.strip().startswith("descriptor:"):
            signatures.setdefault(method, []).append(line.split("descriptor:", 1)[1].strip())
            method = None
    checks = {name: descriptor in signatures.get(name, [])
              for name, descriptor in EXPECTED_METHODS.items()}
    return {
        "mod_id": mod["modId"],
        "version": str(mod["version"]),
        "license": metadata.get("license"),
        "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "manager_abi_checks": checks,
        "pinned_version_and_abi_match": str(mod["version"]) == "1.2.3" and all(checks.values()),
    }


def inspect_rules(path):
    data = json.loads(path.read_text())
    if not isinstance(data, dict):
        raise ValueError("Expected rules.json root object")
    sections = []
    for mob_id, rule in data.items():
        if not isinstance(rule, dict):
            continue
        # The new key takes precedence even if malformed, just as presence of
        # vanilla_spawn suppresses loading spawn_restrictions in the inspected jar.
        section = rule.get("vanilla_spawn") if "vanilla_spawn" in rule else rule.get("spawn_restrictions")
        if isinstance(section, dict):
            sections.append((mob_id, section))
    return {
        "source": "local raw JSON inventory; not live server state or normalized runtime rules",
        "raw_mob_entries": len(data),
        "raw_natural_sections": len(sections),
        "sections_with_structure_selectors": sum(bool(s.get("structures") or s.get("excluded_structures")) for _, s in sections),
        "sections_with_empty_structure_selectors": sum(not (s.get("structures") or s.get("excluded_structures")) for _, s in sections),
        "sections_with_nearby_limits": sum(s.get("max_nearby") is not None for _, s in sections),
        "sections_with_season_filters": sum(bool(s.get("seasons") or s.get("excluded_seasons")) for _, s in sections),
        "nearby_limit_samples": [
            {"entity": mob_id, "max_nearby": section["max_nearby"], "radius": section.get("nearby_radius", 16)}
            for mob_id, section in sections if section.get("max_nearby") is not None
        ][:6],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--rules", required=True, type=Path)
    parser.add_argument("--javap", default=shutil.which("javap"))
    args = parser.parse_args()
    if not args.javap:
        parser.error("javap is required; provide --javap /path/to/jdk/bin/javap")
    report = {
        "artifact": inspect_jar(args.jar, args.javap),
        "configuration": inspect_rules(args.rules),
        "parallel_activation_certified": False,
        "limits": "ABI/version checks and configuration inventory only; no listener takeover or concurrency proof",
    }
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0 if report["artifact"]["pinned_version_and_abi_match"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
