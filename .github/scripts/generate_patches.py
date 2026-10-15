#!/usr/bin/env python3
"""
Fork Patch Generator for Echo Music.

Extracts all fork-specific modifications into discrete, categorized patch files.
These patches can be re-applied on top of any future upstream release using
git apply --3way --ignore-whitespace.

Usage:
  python .github/scripts/generate_patches.py [--base TAG] [--output-dir DIR]

The patches are organized by feature:
  01-fork-workflows.patch         — CI/CD workflows and scripts
  02-fork-build-config.patch      — build.gradle.kts versioning, gradle.properties, lint
  03-jiosaavn-module.patch        — Entire :jiosaavn module + settings.gradle.kts include
  04-jiosaavn-integration.patch   — JioSaavn integration in MusicService, AppModule, PlayerSettings
  05-listen-together-module.patch — All listentogether/ and p2p/ files (new files)
  06-listen-together-integration.patch — LT integration points in shared files
  07-lyrics-romanization.patch    — Lyrics.kt romanization fix
  08-updater-fork.patch           — In-app updater fork endpoints
  09-fork-preferences.patch       — Fork-specific preference keys
"""

import argparse
import os
import subprocess
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# Patch definitions: (name, description, file globs/paths)
PATCH_DEFINITIONS = [
    (
        "01-fork-workflows",
        "Fork CI/CD workflows and automation scripts",
        [
            ".github/workflows/sync-upstream.yml",
            ".github/workflows/build-release.yml",
            ".github/workflows/fork-release.yml",
            ".github/workflows/codeql.yml",
            ".github/scripts/",
        ]
    ),
    (
        "02-fork-build-config",
        "Fork build configuration (versioning, JVM args, lint rules)",
        [
            "app/build.gradle.kts",
            "gradle.properties",
            "app/lint.xml",
        ]
    ),
    (
        "03-jiosaavn-module",
        "JioSaavn 320kbps audio module (entire :jiosaavn directory)",
        [
            "jiosaavn/",
            "settings.gradle.kts",
        ]
    ),
    (
        "04-jiosaavn-integration",
        "JioSaavn integration in shared upstream files",
        [
            "app/src/main/kotlin/com/music/echo/playback/MusicService.kt",
            "app/src/main/kotlin/com/music/echo/di/AppModule.kt",
            "app/src/main/kotlin/com/music/echo/ui/screens/settings/PlayerSettings.kt",
            "app/src/main/kotlin/com/music/echo/utils/NetworkUtils.kt",
            "app/src/main/kotlin/com/music/echo/playback/DownloadUtil.kt",
        ]
    ),
    (
        "05-listen-together-module",
        "Listen Together P2P engine (fork-only new files)",
        [
            "app/src/main/kotlin/com/music/echo/listentogether/",
            "app/src/main/kotlin/com/music/echo/p2p/",
            "app/src/main/kotlin/com/music/echo/ui/component/FloatingChatBubble.kt",
            "app/src/main/kotlin/com/music/echo/ui/screens/settings/integrations/",
        ]
    ),
    (
        "06-listen-together-integration",
        "Listen Together integration points in shared upstream files",
        [
            "app/src/main/kotlin/com/music/echo/MainActivity.kt",
            "app/src/main/kotlin/com/music/echo/playback/PlayerConnection.kt",
        ]
    ),
    (
        "07-lyrics-romanization",
        "Lyrics romanization and translation rendering fix",
        [
            "app/src/main/kotlin/com/music/echo/ui/component/Lyrics.kt",
        ]
    ),
    (
        "08-updater-fork",
        "In-app updater fork repository endpoints",
        [
            "app/src/main/kotlin/com/music/echo/echomusic/updater/",
        ]
    ),
    (
        "09-fork-preferences",
        "Fork-specific preference keys and constants",
        [
            "core/src/main/kotlin/echo/music/iad1tya/constants/PreferenceKeys.kt",
        ]
    ),
]


def run_cmd(cmd, check=True, cwd=None):
    res = subprocess.run(
        cmd, cwd=cwd or REPO_ROOT,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8", errors="replace"
    )
    if check and res.returncode != 0:
        raise RuntimeError(f"Command failed: {' '.join(cmd)}\n{res.stderr.strip()}")
    return res.stdout or ""


def find_merge_base(tag):
    """Find the upstream commit that was merged into the fork at the given tag."""
    # First try: direct tag
    try:
        run_cmd(["git", "rev-parse", tag], check=True)
    except RuntimeError:
        print(f"Warning: Tag {tag} not found. Trying upstream/{tag}...")
        tag = f"upstream/{tag}"

    # Find the merge base between HEAD and the upstream tag
    base = run_cmd(["git", "merge-base", "HEAD", tag]).strip()
    return base


def get_fork_diff_for_paths(base_ref, paths):
    """Get the diff between base_ref and HEAD for specific paths."""
    cmd = ["git", "diff", base_ref, "HEAD", "--"]
    for p in paths:
        full = os.path.join(REPO_ROOT, p)
        if os.path.isdir(full):
            cmd.append(p)
        elif os.path.isfile(full):
            cmd.append(p)
        else:
            # May be a glob or may not exist yet in base
            cmd.append(p)

    diff = run_cmd(cmd, check=False)
    return diff.strip() if diff else ""


def get_new_files_for_paths(base_ref, paths):
    """Get files that exist in HEAD but not in base_ref (fork-only new files)."""
    new_files = []
    for p in paths:
        full = os.path.join(REPO_ROOT, p)
        if os.path.isdir(full):
            # Check each file in the directory
            for root, dirs, files in os.walk(full):
                for f in files:
                    file_path = os.path.join(root, f)
                    rel_path = os.path.relpath(file_path, REPO_ROOT).replace("\\", "/")
                    # Check if file exists in base
                    result = subprocess.run(
                        ["git", "cat-file", "-e", f"{base_ref}:{rel_path}"],
                        cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE
                    )
                    if result.returncode != 0:
                        new_files.append(rel_path)
        elif os.path.isfile(full):
            rel_path = p.replace("\\", "/")
            result = subprocess.run(
                ["git", "cat-file", "-e", f"{base_ref}:{rel_path}"],
                cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE
            )
            if result.returncode != 0:
                new_files.append(rel_path)
    return new_files


def main():
    parser = argparse.ArgumentParser(description="Generate fork patches from current state.")
    parser.add_argument("--base", type=str, default=None,
                        help="Base upstream tag to diff against (default: auto-detect latest merged upstream tag)")
    parser.add_argument("--output-dir", type=str, default=".github/patches",
                        help="Directory to write patch files (default: .github/patches)")
    parser.add_argument("--dry-run", action="store_true",
                        help="Preview what patches would be generated without writing files")
    args = parser.parse_args()

    # Auto-detect base tag if not specified
    if args.base:
        base_tag = args.base
    else:
        # Find the highest upstream version tag
        tags_output = run_cmd(["git", "tag", "-l", "v*.*.*"]).strip()
        if tags_output:
            official_tags = []
            for t in tags_output.splitlines():
                t = t.strip()
                # Only official semver tags (no -d suffix)
                import re
                if re.match(r'^v\d+\.\d+(\.\d+)?$', t):
                    official_tags.append(t)
            if official_tags:
                # Sort by version
                base_tag = sorted(official_tags, key=lambda s: [int(x) for x in s.lstrip('v').split('.')])[-1]
            else:
                base_tag = "v1.4.1"
        else:
            base_tag = "v1.4.1"

    print(f"=== Fork Patch Generator ===")
    print(f"Base tag: {base_tag}")

    # Find the actual merge base commit
    try:
        merge_base = find_merge_base(base_tag)
        print(f"Merge base: {merge_base[:12]}")
    except Exception as e:
        print(f"Error finding merge base: {e}")
        print("Using base tag directly...")
        merge_base = base_tag

    output_dir = os.path.join(REPO_ROOT, args.output_dir)
    if not args.dry_run:
        os.makedirs(output_dir, exist_ok=True)

    generated = 0
    skipped = 0

    for patch_name, description, paths in PATCH_DEFINITIONS:
        diff = get_fork_diff_for_paths(merge_base, paths)

        if not diff:
            print(f"  [SKIP] {patch_name}.patch — no changes")
            skipped += 1
            continue

        # Count hunks for statistics
        hunk_count = diff.count("\n@@")
        line_count = len(diff.splitlines())

        if args.dry_run:
            print(f"  [WOULD CREATE] {patch_name}.patch — {hunk_count} hunks, {line_count} lines")
            print(f"    Description: {description}")
        else:
            patch_path = os.path.join(output_dir, f"{patch_name}.patch")
            header = f"# {description}\n# Generated from diff: {merge_base[:12]}..HEAD\n# Paths: {', '.join(paths)}\n\n"
            with open(patch_path, "w", encoding="utf-8", newline="\n") as f:
                f.write(diff)
            print(f"  [OK] {patch_name}.patch — {hunk_count} hunks, {line_count} lines")

        generated += 1

    print(f"\nSummary: {generated} patches generated, {skipped} skipped (no changes)")

    if not args.dry_run and generated > 0:
        # Write a manifest file
        manifest_path = os.path.join(output_dir, "MANIFEST.md")
        with open(manifest_path, "w", encoding="utf-8") as f:
            f.write(f"# Fork Patch Manifest\n\n")
            f.write(f"**Base**: `{base_tag}` (`{merge_base[:12]}`)\n\n")
            f.write(f"**Generated**: {generated} patches\n\n")
            f.write(f"## Patches\n\n")
            f.write(f"| Order | Patch | Description |\n")
            f.write(f"|:---:|:---|:---|\n")
            for patch_name, description, paths in PATCH_DEFINITIONS:
                patch_file = os.path.join(output_dir, f"{patch_name}.patch")
                exists = "✅" if os.path.exists(patch_file) else "⬜"
                f.write(f"| {exists} | `{patch_name}.patch` | {description} |\n")
            f.write(f"\n## Apply Order\n\n")
            f.write(f"```bash\n")
            f.write(f"for patch in .github/patches/*.patch; do\n")
            f.write(f"  git apply --3way --ignore-whitespace \"$patch\"\n")
            f.write(f"done\n")
            f.write(f"```\n")
        print(f"\nManifest written to: {manifest_path}")


if __name__ == "__main__":
    main()
