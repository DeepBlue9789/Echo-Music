#!/usr/bin/env python3
"""
Release Sub-version Helper Script for Echo Music Fork.
Calculates the next -d sub-version tag, gathers changelog items, and pushes
the tag to trigger the GitHub Actions Universal Release build.

Usage:
  python .github/scripts/release_subversion.py [--dry-run] [--notes "Custom notes"] [--push]
"""

import argparse
import os
import re
import subprocess
import sys

def run_cmd(cmd, check=True):
    try:
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=check)
        return res.stdout.strip()
    except subprocess.CalledProcessError as e:
        print(f"Error running command {' '.join(cmd)}: {e.stderr.strip()}", file=sys.stderr)
        raise

def get_base_version():
    # Method 1: Check highest official semver git tag (e.g. v1.2.7)
    try:
        tags = run_cmd(["git", "tag", "-l", "v*.*.*"]).splitlines()
        official_tags = [t.strip().lstrip("v") for t in tags if re.match(r"^v\d+\.\d+\.\d+$", t.strip())]
        if official_tags:
            return sorted(official_tags, key=lambda s: [int(x) for x in s.split(".")])[-1]
    except Exception:
        pass

    # Method 2: Check app/build.gradle.kts default version
    gradle_path = os.path.join("app", "build.gradle.kts")
    if os.path.exists(gradle_path):
        with open(gradle_path, "r", encoding="utf-8") as f:
            content = f.read()
        match = re.search(r'versionName[^\n]*?"([0-9]+\.[0-9]+\.[0-9]+)"', content)
        if match:
            return match.group(1)
    return "1.2.7"

def main():
    parser = argparse.ArgumentParser(description="Calculate next -d sub-version tag and trigger release build.")
    parser.add_argument("--dry-run", action="store_true", help="Preview calculated tag and changelog without tagging or pushing.")
    parser.add_argument("--push", action="store_true", help="Push the newly created tag to origin to trigger GitHub Actions release build.")
    parser.add_argument("--notes", type=str, default="", help="Custom changelog note to include in the release.")
    args = parser.parse_args()

    print("=== Echo Music Sub-Version Release Tool ===")
    
    # Fetch tags
    try:
        run_cmd(["git", "fetch", "origin", "--tags"], check=False)
    except Exception:
        pass

    base_version = get_base_version()
    print(f"Base Semver: {base_version}")

    tags_output = run_cmd(["git", "tag", "-l", f"v{base_version}-d*"])
    existing_d_tags = [t.strip() for t in tags_output.splitlines() if t.strip()]

    highest_d = 0
    for t in existing_d_tags:
        m = re.match(rf"^v{re.escape(base_version)}-d(\d+)$", t)
        if m:
            d_num = int(m.group(1))
            if d_num > highest_d:
                highest_d = d_num

    next_d = highest_d + 1
    target_tag = f"v{base_version}-d{next_d}"
    last_tag = f"v{base_version}-d{highest_d}" if highest_d > 0 else f"v{base_version}"

    print(f"Highest Existing Sub-Version: {last_tag}")
    print(f"Target Next Release Tag:      {target_tag}")

    # Gather commits
    try:
        commits_output = run_cmd(["git", "log", f"{last_tag}..HEAD", "--pretty=format:%s"])
        commits = [c.strip() for c in commits_output.splitlines() if c.strip()]
    except Exception:
        commits = []

    filtered = []
    if args.notes:
        filtered.append(args.notes.strip())

    for c in commits:
        low = c.lower()
        if any(k in low for k in ["sync upstream", "merge branch", "merge remote", "[skip ci]"]):
            continue
        if c not in filtered:
            filtered.append(c)

    print("\n--- Planned Changelog ---")
    if filtered:
        for f in filtered:
            print(f" • {f}")
    else:
        print(" • Bug fixes, performance improvements, and fork enhancements.")
    print("-------------------------\n")

    if args.dry_run:
        print(f"[DRY-RUN] Would create and push tag: {target_tag}")
        return

    # Check for uncommitted changes
    status = run_cmd(["git", "status", "--porcelain"])
    if status:
        print("Warning: You have uncommitted local changes.")
        print("Please commit or stash your changes before releasing.")
        sys.exit(1)

    print(f"Creating annotated tag '{target_tag}'...")
    run_cmd(["git", "tag", "-a", target_tag, "-m", f"Echo Music Release {target_tag}"])

    if args.push:
        print(f"Pushing tag '{target_tag}' to origin...")
        run_cmd(["git", "push", "origin", target_tag])
        print(f"\nSuccessfully pushed {target_tag}!")
        print("GitHub Actions is now running 'Build & Publish Release' workflow.")
        print("Release URL will be: https://github.com/DeepBlue9789/Echo-Music/releases/tag/" + target_tag)
    else:
        print(f"\nTag '{target_tag}' created locally.")
        print(f"To push and trigger release, run:\n  git push origin {target_tag}")

if __name__ == "__main__":
    main()
