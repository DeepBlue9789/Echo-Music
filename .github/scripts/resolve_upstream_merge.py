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

def resolve_music_service_kt(filepath):
    """
    Harmonizes MusicService.kt:
    - Retains dataStore-backed Cronet toggle (with fallback to Chunked/OkHttp).
    - Retains 8-second grace delay in preloadJob.
    """
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    if "<<<<<<<" not in content:
        return False

    changed = False

    # Conflict 1: useCronet
    cronet_pattern = re.compile(
        r'<<<<<<<[^\n]*\n\s*val useCronet = runBlocking \{ dataStore\.get\(echo\.music\.iad1tya\.constants\.EnableCronetKey, true\) \}\s*=======\s*val useCronet = [^\n]+\s*>>>>>>>[^\n]*',
        re.DOTALL
    )
    if cronet_pattern.search(content):
        content = cronet_pattern.sub(
            '        val useCronet = runBlocking { dataStore.get(echo.music.iad1tya.constants.EnableCronetKey, true) }',
            content
        )
        changed = True

    # Conflict 2: preloadJob launch / delay
    preload_pattern = re.compile(
        r'<<<<<<<[^\n]*\n\s*preloadJob = scope\.launch\(kotlinx\.coroutines\.Dispatchers\.IO\) \{\s*=======\s*preloadJob =\s*scope\.launch\(kotlinx\.coroutines\.Dispatchers\.IO\) \{\s*kotlinx\.coroutines\.delay\((\d+L)\)[^\n]*\s*>>>>>>>[^\n]*',
        re.DOTALL
    )
    m = preload_pattern.search(content)
    if m:
        delay_val = m.group(1)
        content = preload_pattern.sub(
            f'        preloadJob = scope.launch(kotlinx.coroutines.Dispatchers.IO) {{\n            kotlinx.coroutines.delay({delay_val}) // grace period before prefetching',
            content
        )
        changed = True

    if changed:
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(content)
        return True
    return False

def resolve_lyrics_kt(filepath):
    """
    Harmonizes Lyrics.kt:
    - Retains showRomanizedLyrics preference check over upstream's hardcoded 'true' or 'false'.
    - Cleans up formatting/whitespace conflict markers.
    """
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    if "<<<<<<<" not in content:
        return False

    changed = False

    # Romanized lyrics check
    romanized_pattern = re.compile(
        r'<<<<<<<[^\n]*\n\s*if \(showRomanizedLyrics\) \{\s*=======\s*if \((?:true|false)\) \{\s*>>>>>>>[^\n]*',
        re.DOTALL
    )
    if romanized_pattern.search(content):
        content = romanized_pattern.sub('                        if (showRomanizedLyrics) {', content)
        changed = True

    # Active translations block
    translations_pattern = re.compile(
        r'<<<<<<<[^\n]*\n\s*if \(hasActiveTranslations &&\s*=======\s*if \(\s*hasActiveTranslations &&\s*>>>>>>>[^\n]*',
        re.DOTALL
    )
    if translations_pattern.search(content):
        content = translations_pattern.sub('                        if (hasActiveTranslations &&', content)
        changed = True

    # Standalone empty line conflicts
    empty_conflict = re.compile(
        r'<<<<<<<[^\n]*\n\s*=======\s*>>>>>>>[^\n]*',
        re.DOTALL
    )
    if empty_conflict.search(content):
        content = empty_conflict.sub('', content)
        changed = True

    if changed:
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(content)
        return True
    return False

def resolve_listen_together_screen_kt(filepath):
    """
    Harmonizes ListenTogetherScreen.kt:
    Adopts upstream's updated invite link domain if conflicted.
    """
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    if "<<<<<<<" not in content:
        return False

    link_pattern = re.compile(
        r'<<<<<<<[^\n]*\n\s*val inviteLink = remember\(roomCode\) \{\s*"[^"]+"\s*=======\s*val inviteLink =\s*remember\(roomCode\) \{\s*"([^"]+)"\s*>>>>>>>[^\n]*\s*\}',
        re.DOTALL
    )
    m = link_pattern.search(content)
    if m:
        new_link = m.group(1)
        replacement = f'''                val inviteLink = remember(roomCode) {{
                    "{new_link}"
                }}'''
        content = link_pattern.sub(replacement, content)
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(content)
        return True
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
        elif rel_path.endswith("MusicService.kt"):
            resolved = resolve_music_service_kt(abs_path)
        elif rel_path.endswith("Lyrics.kt"):
            resolved = resolve_lyrics_kt(abs_path)
        elif rel_path.endswith("ListenTogetherScreen.kt"):
            resolved = resolve_listen_together_screen_kt(abs_path)
        elif rel_path.startswith(".github/workflows/"):
            run_git(["checkout", "HEAD", "--", rel_path], check=False)
            resolved = True

        if resolved:
            run_git(["add", rel_path])
            print(f"  [OK] Successfully resolved {rel_path}")

    # Check remaining conflicts
    remaining = get_conflicted_files()
    if remaining:
        print(f"\n::error::Unresolved merge conflicts remain in {len(remaining)} file(s):")
        for r in remaining:
            print(f"  - {r}")
        return 1

    print("\nAll known merge conflicts resolved successfully!")
    return 0

if __name__ == "__main__":
    sys.exit(main())
