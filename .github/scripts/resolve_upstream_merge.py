#!/usr/bin/env python3
"""
Automated conflict resolution script for upstream release merges in Echo Music fork.
Harmonizes fork enhancements (Listen Together, JioSaavn, dynamic sub-versioning, etc.)
with upstream changes when merging official release tags.
"""

import os
import re
import subprocess
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

def run_git(args, check=True):
    res = subprocess.run(["git"] + args, cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if check and res.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {res.stderr.strip()}")
    return res.stdout.strip()

def get_conflicted_files():
    output = run_git(["diff", "--name-only", "--diff-filter=U"], check=False)
    if not output:
        return []
    return [line.strip() for line in output.splitlines() if line.strip()]

def resolve_gradle_properties(filepath):
    """
    Harmonizes gradle.properties:
    Always preserves fork JVM args (-Xmx6g, -Xmx8g) which are required for large builds.
    """
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    if "<<<<<<<" not in content:
        return False

    pattern = re.compile(r'<<<<<<<[^\n]*\n(.*?)=======\s*(?:org\.gradle\.jvmargs|kotlin\.daemon\.jvmargs)[^\n]*\n.*?>======[^\n]*\n?', re.DOTALL)
    # Match any conflict block in gradle.properties and keep HEAD
    simple_pattern = re.compile(r'<<<<<<<[^\n]*\n(.*?)=======\s*.*?>======[^\n]*', re.DOTALL)
    
    # Generic replacement: keep HEAD lines for gradle.properties
    def keep_head(m):
        return m.group(1).rstrip()

    new_content = re.sub(r'<<<<<<<[^\n]*\n(.*?)=======\s*.*?>>>>>>>[^\n]*', keep_head, content, flags=re.DOTALL)
    if new_content != content:
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(new_content)
        return True
    return False

def resolve_build_gradle_kts(filepath):
    """
    Harmonizes app/build.gradle.kts version block:
    Extracts upstream's new versionCode and versionName, and plugs them into the
    fork's dynamic environment-variable / sub-versioning parser block.
    """
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    if "<<<<<<<" not in content:
        return False

    pattern = re.compile(
        r'<<<<<<<[^\n]*\n(.*?)=======\s*versionCode\s*=\s*(\d+)\s*versionName\s*=\s*"([^"]+)"\s*>>>>>>>[^\n]*',
        re.DOTALL
    )

    match = pattern.search(content)
    if match:
        upstream_code = match.group(2)
        upstream_name = match.group(3)
        print(f"  [build.gradle.kts] Extracted upstream versionCode={upstream_code}, versionName={upstream_name}")

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

        content = content[:match.start()] + replacement + content[match.end():]
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(content)
        return True

    return False

def resolve_universal_imports(content):
    """
    Universal resolver for conflict blocks that consist entirely of import statements.
    Takes union of imports from HEAD and UPSTREAM, deduplicates, and sorts them.
    """
    conflict_pattern = re.compile(r'<<<<<<<[^\n]*\n(.*?)=======\s*(.*?)>>>>>>>[^\n]*', re.DOTALL)

    def import_replacer(match):
        head_part = match.group(1).strip()
        upstream_part = match.group(2).strip()

        head_lines = [l.strip() for l in head_part.splitlines() if l.strip()]
        upstream_lines = [l.strip() for l in upstream_part.splitlines() if l.strip()]

        # Check if both sides only contain import statements
        all_lines = head_lines + upstream_lines
        if all(l.startswith("import ") for l in all_lines):
            combined = sorted(list(set(all_lines)))
            return "\n".join(combined)

        # Return original if not purely imports
        return match.group(0)

    new_content = conflict_pattern.sub(import_replacer, content)
    return new_content

def resolve_whitespace_only_conflicts(content):
    """
    Universal resolver for conflict blocks where both sides are identical ignoring whitespace.
    Picks upstream's formatting convention.
    """
    conflict_pattern = re.compile(r'<<<<<<<[^\n]*\n(.*?)=======\s*(.*?)>>>>>>>[^\n]*', re.DOTALL)

    def ws_replacer(match):
        head_part = match.group(1)
        upstream_part = match.group(2)

        # Remove all whitespace and compare
        norm_head = re.sub(r'\s+', '', head_part)
        norm_upstream = re.sub(r'\s+', '', upstream_part)

        if norm_head == norm_upstream:
            return upstream_part.strip('\n')

        return match.group(0)

    new_content = conflict_pattern.sub(ws_replacer, content)
    return new_content

def resolve_kotlin_file(filepath):
    """
    Applies universal semantic resolvers (imports union, whitespace normalization,
    and common additive patterns) to any Kotlin file.
    """
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    if "<<<<<<<" not in content:
        return False

    orig = content
    content = resolve_universal_imports(content)
    content = resolve_whitespace_only_conflicts(content)

    # Clean empty-line conflict leftovers
    empty_conflict = re.compile(r'<<<<<<<[^\n]*\n\s*=======\s*>>>>>>>[^\n]*', re.DOTALL)
    content = empty_conflict.sub('', content)

    if content != orig:
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(content)
        # Check if all conflicts in this file were resolved
        return "<<<<<<<" not in content

    return False

def main():
    print("=== Auto-Resolving Upstream Merge Conflicts ===")
    conflicts = get_conflicted_files()
    if not conflicts:
        print("No conflicted files detected.")
        return 0

    print(f"Conflicted files ({len(conflicts)}):")
    for c in conflicts:
        print(f" - {c}")

    for rel_path in conflicts:
        abs_path = os.path.join(REPO_ROOT, rel_path)
        if not os.path.exists(abs_path):
            continue

        resolved = False
        if rel_path.endswith("app/build.gradle.kts"):
            resolved = resolve_build_gradle_kts(abs_path)
        elif rel_path.endswith("gradle.properties"):
            resolved = resolve_gradle_properties(abs_path)
        elif rel_path.startswith(".github/workflows/"):
            run_git(["checkout", "HEAD", "--", rel_path], check=False)
            resolved = True
        elif rel_path.endswith(".kt"):
            resolved = resolve_kotlin_file(abs_path)

        if resolved or ("<<<<<<<" not in open(abs_path, "r", encoding="utf-8", errors="ignore").read()):
            run_git(["add", rel_path])
            print(f"  [OK] Successfully resolved {rel_path}")

    # Check remaining conflicts
    remaining = get_conflicted_files()
    if remaining:
        print(f"\n::warning::Manual resolution needed for {len(remaining)} file(s):")
        for r in remaining:
            print(f"  - {r}")
        return 1

    print("\nAll merge conflicts resolved successfully!")
    return 0

if __name__ == "__main__":
    sys.exit(main())
