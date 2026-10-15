#!/usr/bin/env python3
"""
Universal automated conflict resolution engine for upstream release merges
in the Echo Music fork.

Handles:
  1. Import-only conflicts  → union + deduplicate + sort
  2. Whitespace-only conflicts → adopt upstream formatting
  3. build.gradle.kts version blocks → extract upstream versions, inject fork sub-versioning
  4. gradle.properties → keep fork JVM args
  5. Workflow files (.github/workflows/) → keep fork HEAD
  6. settings.gradle.kts → union all include() statements
  7. libs.versions.toml → union all catalog entries
  8. modify/delete conflicts → keep fork files, accept upstream deletions of non-fork files
  9. Fork-protected files → --ours with surgical upstream adoption
 10. Unrecognized .kt files → --theirs (accept upstream)
"""

import os
import re
import subprocess
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# Files that contain fork-specific modifications and should prefer HEAD (ours) on conflict
FORK_PROTECTED_FILES = {
    "app/src/main/kotlin/com/music/echo/playback/MusicService.kt",
    "app/src/main/kotlin/com/music/echo/ui/screens/settings/PlayerSettings.kt",
    "app/src/main/kotlin/com/music/echo/ui/component/Lyrics.kt",
    "app/src/main/kotlin/com/music/echo/di/AppModule.kt",
    "app/src/main/kotlin/com/music/echo/playback/PlayerConnection.kt",
    "app/src/main/kotlin/com/music/echo/MainActivity.kt",
    "core/src/main/kotlin/echo/music/iad1tya/constants/PreferenceKeys.kt",
    "app/src/main/kotlin/com/music/echo/echomusic/updater/echomusicupdater.kt",
}

# Files/directories that are fork-only and must never be deleted by upstream
FORK_ONLY_PATHS = {
    "jiosaavn/",
    "app/src/main/kotlin/com/music/echo/listentogether/",
    "app/src/main/kotlin/com/music/echo/p2p/",
    "app/src/main/kotlin/com/music/echo/ui/component/FloatingChatBubble.kt",
    ".github/workflows/sync-upstream.yml",
    ".github/workflows/build-release.yml",
    ".github/workflows/fork-release.yml",
    ".github/scripts/",
}


def run_git(args, check=True, cwd=None):
    """Execute a git command and return stdout."""
    res = subprocess.run(
        ["git"] + args,
        cwd=cwd or REPO_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    if check and res.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {res.stderr.strip()}")
    return res.stdout.strip()


def get_conflicted_files():
    """Return list of files with unresolved merge conflicts."""
    output = run_git(["diff", "--name-only", "--diff-filter=U"], check=False)
    if not output:
        return []
    return [line.strip() for line in output.splitlines() if line.strip()]


def get_deleted_by_theirs():
    """Return list of files deleted by upstream (modify/delete: deleted in MERGE_HEAD)."""
    # These show up as 'deleted by them' in git status during merge
    status = run_git(["status", "--porcelain", "-u"], check=False)
    deleted = []
    for line in status.splitlines():
        # 'DU' = deleted by us (we deleted, they modified) — accept theirs
        # 'UD' = deleted by them (they deleted, we modified) — check if fork file
        stripped = line.strip()
        if stripped.startswith("UD ") or stripped.startswith("UA "):
            filepath = stripped[3:].strip()
            deleted.append(filepath)
    return deleted


def is_fork_protected(rel_path):
    """Check if a file path is a fork-protected file or inside a fork-only directory."""
    if rel_path in FORK_PROTECTED_FILES:
        return True
    for prefix in FORK_ONLY_PATHS:
        if rel_path.startswith(prefix):
            return True
    return False


# ──────────────────────────────────────────────────────────────────────────────
# Per-file-type resolvers
# ──────────────────────────────────────────────────────────────────────────────

def resolve_gradle_properties(filepath):
    """Keep fork JVM args in gradle.properties (always prefer HEAD)."""
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    if "<<<<<<" not in content:
        return True  # no conflicts

    # Generic: keep HEAD lines for all conflict blocks
    def keep_head(m):
        return m.group(1).rstrip()

    new_content = re.sub(
        r'<<<<<<<[^\n]*\n(.*?)=======\s*.*?>>>>>>>[^\n]*',
        keep_head, content, flags=re.DOTALL
    )
    if new_content != content:
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(new_content)
    return "<<<<<<" not in new_content


def resolve_build_gradle_kts(filepath):
    """
    Extract upstream versionCode/versionName from conflict and inject into
    the fork's dynamic sub-versioning parser block.
    """
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    if "<<<<<<" not in content:
        return True

    # Try to extract upstream version from conflict markers
    pattern = re.compile(
        r'<<<<<<<[^\n]*\n(.*?)=======\s*(.*?)>>>>>>>[^\n]*',
        re.DOTALL
    )

    upstream_code = None
    upstream_name = None

    for match in pattern.finditer(content):
        upstream_part = match.group(2)
        code_match = re.search(r'versionCode\s*=\s*(\d+)', upstream_part)
        name_match = re.search(r'versionName\s*=\s*"([^"]+)"', upstream_part)
        if code_match and not upstream_code:
            upstream_code = code_match.group(1)
        if name_match and not upstream_name:
            upstream_name = name_match.group(1)

    if upstream_code and upstream_name:
        print(f"  [build.gradle.kts] Extracted upstream versionCode={upstream_code}, versionName={upstream_name}")

        # Build the fork's dynamic versioning block
        replacement = f'''        val envVersionName = System.getenv("APP_VERSION_NAME") ?: project.findProperty("versionName")?.toString() ?: "{upstream_name}"
        val defaultOrParsedCode = run {{
            val clean = envVersionName.removePrefix("v").trim()
            val base = clean.split("-").first()
            val parts = base.split(".").mapNotNull {{ it.toIntOrNull() }}
            val d = Regex("""-d(\\d+)""", RegexOption.IGNORE_CASE).find(clean)?.groupValues?.get(1)?.toIntOrNull()
            if (parts.size >= 3 && d != null) {{
                parts[0] * 1000000 + parts[1] * 10000 + parts[2] * 100 + d
            }} else null
        }} ?: {upstream_code}
        val envVersionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull()
            ?: project.findProperty("versionCode")?.toString()?.toIntOrNull()
            ?: defaultOrParsedCode
        versionCode = envVersionCode
        versionName = envVersionName'''

        # Replace the version conflict block
        version_conflict = re.compile(
            r'<<<<<<<[^\n]*\n(?:.*?versionCode.*?|.*?versionName.*?|.*?envVersionName.*?|.*?APP_VERSION.*?)>>>>>>>[^\n]*',
            re.DOTALL
        )
        match = version_conflict.search(content)
        if match:
            content = content[:match.start()] + replacement + content[match.end():]

    # For any remaining conflicts in build.gradle.kts, try universal resolvers
    content = resolve_universal_imports(content)
    content = resolve_whitespace_only_conflicts(content)

    # If still conflicted, try keeping HEAD for remaining blocks (fork features)
    remaining_conflicts = re.compile(r'<<<<<<<[^\n]*\n(.*?)=======\s*(.*?)>>>>>>>[^\n]*', re.DOTALL)

    def prefer_head_with_upstream_additions(m):
        head_part = m.group(1).strip()
        upstream_part = m.group(2).strip()

        head_lines = set(l.strip() for l in head_part.splitlines() if l.strip())
        upstream_lines = [l.strip() for l in upstream_part.splitlines() if l.strip()]

        # Add upstream-only lines that HEAD doesn't have (new dependencies, etc.)
        combined = head_part.rstrip()
        for ul in upstream_lines:
            if ul not in head_lines:
                combined += "\n" + ul
        return combined

    new_content = remaining_conflicts.sub(prefer_head_with_upstream_additions, content)

    with open(filepath, "w", encoding="utf-8") as f:
        f.write(new_content)

    return "<<<<<<" not in new_content


def resolve_settings_gradle(filepath):
    """
    Union all include() statements from both HEAD and upstream in settings.gradle.kts.
    Preserves fork modules (e.g. :jiosaavn) and adds new upstream modules.
    """
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    if "<<<<<<" not in content:
        return True

    conflict_pattern = re.compile(r'<<<<<<<[^\n]*\n(.*?)=======\s*(.*?)>>>>>>>[^\n]*', re.DOTALL)

    def union_includes(m):
        head_part = m.group(1)
        upstream_part = m.group(2)

        head_lines = [l.rstrip() for l in head_part.splitlines() if l.strip()]
        upstream_lines = [l.rstrip() for l in upstream_part.splitlines() if l.strip()]

        # Check if all lines are include() statements
        all_lines = head_lines + upstream_lines
        if all(re.match(r'\s*include\s*\(', l.strip()) for l in all_lines if l.strip()):
            # Union and sort
            seen = set()
            combined = []
            for l in head_lines + upstream_lines:
                normalized = l.strip()
                if normalized not in seen:
                    seen.add(normalized)
                    combined.append(l)
            return "\n".join(combined)

        # For non-include conflicts, union lines
        seen = set()
        combined = []
        for l in head_lines + upstream_lines:
            normalized = l.strip()
            if normalized and normalized not in seen:
                seen.add(normalized)
                combined.append(l)
        return "\n".join(combined)

    new_content = conflict_pattern.sub(union_includes, content)

    with open(filepath, "w", encoding="utf-8") as f:
        f.write(new_content)

    return "<<<<<<" not in new_content


def resolve_libs_versions_toml(filepath):
    """
    Union all version catalog entries from HEAD and upstream in libs.versions.toml.
    HEAD entries take precedence for shared keys; upstream-only entries are added.
    """
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    if "<<<<<<" not in content:
        return True

    conflict_pattern = re.compile(r'<<<<<<<[^\n]*\n(.*?)=======\s*(.*?)>>>>>>>[^\n]*', re.DOTALL)

    def union_toml_entries(m):
        head_part = m.group(1)
        upstream_part = m.group(2)

        head_lines = [l.rstrip() for l in head_part.splitlines()]
        upstream_lines = [l.rstrip() for l in upstream_part.splitlines()]

        # Extract key-value pairs
        head_keys = {}
        for l in head_lines:
            kv = re.match(r'^(\s*[\w-]+)\s*=\s*(.*)', l)
            if kv:
                head_keys[kv.group(1).strip()] = l

        upstream_keys = {}
        for l in upstream_lines:
            kv = re.match(r'^(\s*[\w-]+)\s*=\s*(.*)', l)
            if kv:
                upstream_keys[kv.group(1).strip()] = l

        # Start with all HEAD lines, then add upstream-only entries
        combined = list(head_lines)
        for key, line in upstream_keys.items():
            if key not in head_keys:
                combined.append(line)

        # Filter empty lines at boundaries
        result = "\n".join(l for l in combined if l is not None)
        return result.strip()

    new_content = conflict_pattern.sub(union_toml_entries, content)

    with open(filepath, "w", encoding="utf-8") as f:
        f.write(new_content)

    return "<<<<<<" not in new_content


def resolve_universal_imports(content):
    """
    Universal resolver for conflict blocks consisting entirely of import statements.
    Takes union, deduplicates, and sorts alphabetically.
    """
    conflict_pattern = re.compile(r'<<<<<<<[^\n]*\n(.*?)=======\s*(.*?)>>>>>>>[^\n]*', re.DOTALL)

    def import_replacer(match):
        head_part = match.group(1).strip()
        upstream_part = match.group(2).strip()

        head_lines = [l.strip() for l in head_part.splitlines() if l.strip()]
        upstream_lines = [l.strip() for l in upstream_part.splitlines() if l.strip()]

        all_lines = head_lines + upstream_lines
        if all(l.startswith("import ") for l in all_lines):
            combined = sorted(set(all_lines))
            return "\n".join(combined)
        return match.group(0)

    return conflict_pattern.sub(import_replacer, content)


def resolve_whitespace_only_conflicts(content):
    """
    Resolve conflicts where both sides are identical ignoring whitespace.
    Adopts upstream's formatting convention.
    """
    conflict_pattern = re.compile(r'<<<<<<<[^\n]*\n(.*?)=======\s*(.*?)>>>>>>>[^\n]*', re.DOTALL)

    def ws_replacer(match):
        head_part = match.group(1)
        upstream_part = match.group(2)
        norm_head = re.sub(r'\s+', '', head_part)
        norm_upstream = re.sub(r'\s+', '', upstream_part)
        if norm_head == norm_upstream:
            return upstream_part.strip('\n')
        return match.group(0)

    return conflict_pattern.sub(ws_replacer, content)


def resolve_fork_protected_kt(filepath, rel_path):
    """
    For fork-protected Kotlin files: apply universal resolvers first,
    then for remaining conflicts, keep HEAD (fork features) but merge
    any purely additive upstream blocks.
    """
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    if "<<<<<<" not in content:
        return True

    orig = content

    # Phase 1: Universal resolvers
    content = resolve_universal_imports(content)
    content = resolve_whitespace_only_conflicts(content)

    # Phase 2: For remaining conflicts, prefer HEAD (fork code)
    conflict_pattern = re.compile(r'<<<<<<<[^\n]*\n(.*?)=======\s*(.*?)>>>>>>>[^\n]*', re.DOTALL)

    def prefer_head(m):
        head_part = m.group(1)
        upstream_part = m.group(2)

        head_text = head_part.rstrip('\n')
        upstream_text = upstream_part.strip('\n')

        # If HEAD is empty, accept upstream (they added something new)
        if not head_text.strip():
            return upstream_text

        # If upstream is empty, keep HEAD (they deleted something we need)
        if not upstream_text.strip():
            return head_text

        # Default: keep HEAD (our fork features)
        return head_text

    content = conflict_pattern.sub(prefer_head, content)

    # Clean up empty conflict leftovers
    empty_conflict = re.compile(r'<<<<<<<[^\n]*\n\s*=======\s*>>>>>>>[^\n]*', re.DOTALL)
    content = empty_conflict.sub('', content)

    if content != orig:
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(content)

    return "<<<<<<" not in content


def resolve_generic_kt(filepath):
    """
    For non-fork Kotlin files: apply universal resolvers, then prefer upstream.
    """
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    if "<<<<<<" not in content:
        return True

    orig = content

    # Phase 1: Universal resolvers
    content = resolve_universal_imports(content)
    content = resolve_whitespace_only_conflicts(content)

    # Phase 2: For remaining, prefer upstream (theirs)
    conflict_pattern = re.compile(r'<<<<<<<[^\n]*\n(.*?)=======\s*(.*?)>>>>>>>[^\n]*', re.DOTALL)

    def prefer_upstream(m):
        upstream_part = m.group(2)
        return upstream_part.strip('\n')

    content = conflict_pattern.sub(prefer_upstream, content)

    # Clean up
    empty_conflict = re.compile(r'<<<<<<<[^\n]*\n\s*=======\s*>>>>>>>[^\n]*', re.DOTALL)
    content = empty_conflict.sub('', content)

    if content != orig:
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(content)

    return "<<<<<<" not in content


def resolve_generic_file(filepath, prefer_ours=True):
    """
    Generic conflict resolver for non-code files.
    Attempts universal resolvers, then falls back to ours/theirs preference.
    """
    with open(filepath, "r", encoding="utf-8", errors="ignore") as f:
        content = f.read()

    if "<<<<<<" not in content:
        return True

    content = resolve_whitespace_only_conflicts(content)

    conflict_pattern = re.compile(r'<<<<<<<[^\n]*\n(.*?)=======\s*(.*?)>>>>>>>[^\n]*', re.DOTALL)

    def resolver(m):
        if prefer_ours:
            part = m.group(1).rstrip('\n')
        else:
            part = m.group(2).strip('\n')
        return part if part.strip() else ""

    content = conflict_pattern.sub(resolver, content)

    with open(filepath, "w", encoding="utf-8") as f:
        f.write(content)

    return "<<<<<<" not in content


# ──────────────────────────────────────────────────────────────────────────────
# Modify/Delete conflict handlers
# ──────────────────────────────────────────────────────────────────────────────

def handle_modify_delete_conflicts():
    """
    Handle modify/delete conflicts:
    - Fork-protected files: keep our version (git checkout --ours)
    - Non-fork files: accept upstream deletion (git rm)
    """
    status = run_git(["status", "--porcelain", "-u"], check=False)
    resolved = []

    for line in status.splitlines():
        stripped = line.strip()

        # UD = upstream deleted, we modified
        if stripped.startswith("UD "):
            filepath = stripped[3:].strip()
            if is_fork_protected(filepath):
                print(f"  [modify/delete] Keeping fork file: {filepath}")
                run_git(["checkout", "--ours", "--", filepath], check=False)
                run_git(["add", filepath], check=False)
            else:
                print(f"  [modify/delete] Accepting upstream deletion: {filepath}")
                run_git(["rm", "--cached", filepath], check=False)
            resolved.append(filepath)

        # DU = we deleted, upstream modified
        elif stripped.startswith("DU "):
            filepath = stripped[3:].strip()
            if is_fork_protected(filepath):
                print(f"  [modify/delete] Fork deleted but protected, keeping deletion: {filepath}")
                run_git(["rm", "--cached", filepath], check=False)
            else:
                print(f"  [modify/delete] Accepting upstream modification: {filepath}")
                run_git(["checkout", "--theirs", "--", filepath], check=False)
                run_git(["add", filepath], check=False)
            resolved.append(filepath)

        # AA = both added (new file on both sides)
        elif stripped.startswith("AA "):
            filepath = stripped[3:].strip()
            if is_fork_protected(filepath):
                print(f"  [both-added] Keeping fork version: {filepath}")
                run_git(["checkout", "--ours", "--", filepath], check=False)
            else:
                print(f"  [both-added] Accepting upstream version: {filepath}")
                run_git(["checkout", "--theirs", "--", filepath], check=False)
            run_git(["add", filepath], check=False)
            resolved.append(filepath)

    return resolved


# ──────────────────────────────────────────────────────────────────────────────
# Main orchestrator
# ──────────────────────────────────────────────────────────────────────────────

def main():
    print("=" * 60)
    print("=== Universal Upstream Merge Conflict Resolver ===")
    print("=" * 60)

    # Phase 0: Handle modify/delete conflicts first
    print("\n--- Phase 0: Modify/Delete Conflicts ---")
    md_resolved = handle_modify_delete_conflicts()
    if md_resolved:
        for f in md_resolved:
            print(f"  [OK] {f}")
    else:
        print("  No modify/delete conflicts found.")

    # Phase 1: Content conflicts
    conflicts = get_conflicted_files()
    if not conflicts:
        print("\nNo remaining conflicted files detected.")
        print("All merge conflicts resolved successfully!")
        return 0

    print(f"\n--- Phase 1: Content Conflicts ({len(conflicts)} files) ---")
    for c in conflicts:
        print(f"  - {c}")

    resolved_count = 0
    failed = []

    for rel_path in conflicts:
        abs_path = os.path.join(REPO_ROOT, rel_path)
        if not os.path.exists(abs_path):
            # File was deleted, skip
            continue

        resolved = False
        strategy = "unknown"

        try:
            # Route to appropriate resolver
            if rel_path.startswith(".github/workflows/"):
                # Always keep fork workflows
                run_git(["checkout", "HEAD", "--", rel_path], check=False)
                resolved = True
                strategy = "keep-fork-workflows"

            elif rel_path == "app/build.gradle.kts" or rel_path.endswith("/build.gradle.kts"):
                if rel_path == "app/build.gradle.kts":
                    resolved = resolve_build_gradle_kts(abs_path)
                    strategy = "build-gradle-versioning"
                else:
                    resolved = resolve_generic_file(abs_path, prefer_ours=False)
                    strategy = "generic-build-gradle"

            elif rel_path == "gradle.properties":
                resolved = resolve_gradle_properties(abs_path)
                strategy = "gradle-properties"

            elif rel_path == "settings.gradle.kts":
                resolved = resolve_settings_gradle(abs_path)
                strategy = "settings-gradle-union"

            elif rel_path == "gradle/libs.versions.toml":
                resolved = resolve_libs_versions_toml(abs_path)
                strategy = "libs-versions-union"

            elif rel_path.endswith(".kt"):
                if is_fork_protected(rel_path):
                    resolved = resolve_fork_protected_kt(abs_path, rel_path)
                    strategy = "fork-protected-kt"
                else:
                    resolved = resolve_generic_kt(abs_path)
                    strategy = "generic-kt-upstream"

            elif rel_path.endswith(".xml") or rel_path.endswith(".json"):
                prefer = is_fork_protected(rel_path)
                resolved = resolve_generic_file(abs_path, prefer_ours=prefer)
                strategy = f"generic-{'ours' if prefer else 'theirs'}"

            else:
                # Unknown file type — prefer upstream for non-fork files
                prefer = is_fork_protected(rel_path)
                resolved = resolve_generic_file(abs_path, prefer_ours=prefer)
                strategy = f"fallback-{'ours' if prefer else 'theirs'}"

        except Exception as e:
            print(f"  [ERROR] Exception resolving {rel_path}: {e}")
            resolved = False

        # Verify resolution
        if resolved or ("<<<<<<" not in open(abs_path, "r", encoding="utf-8", errors="ignore").read()):
            run_git(["add", rel_path])
            print(f"  [OK] {rel_path} ({strategy})")
            resolved_count += 1
        else:
            # Last resort: for fork-protected files, force --ours
            if is_fork_protected(rel_path):
                print(f"  [FORCE-OURS] {rel_path} — forcing fork version")
                run_git(["checkout", "--ours", "--", rel_path], check=False)
                run_git(["add", rel_path])
                resolved_count += 1
            else:
                # For non-fork files, force --theirs
                print(f"  [FORCE-THEIRS] {rel_path} — forcing upstream version")
                run_git(["checkout", "--theirs", "--", rel_path], check=False)
                run_git(["add", rel_path])
                resolved_count += 1

    # Final check
    remaining = get_conflicted_files()
    if remaining:
        print(f"\n::warning::Manual resolution needed for {len(remaining)} file(s):")
        for r in remaining:
            print(f"  - {r}")
        return 1

    total = len(md_resolved) + resolved_count
    print(f"\n{'=' * 60}")
    print(f"All merge conflicts resolved successfully! ({total} files)")
    print(f"{'=' * 60}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
