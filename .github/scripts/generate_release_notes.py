import os
import re
import subprocess
import json
import urllib.request

def main():
    tag = os.environ.get("TAG", "").strip()
    if not tag:
        try:
            tag = subprocess.check_output(["git", "describe", "--tags", "--exact-match"], text=True).strip()
        except Exception:
            tag = "v1.2.7-d1"

    os.makedirs("release-apks", exist_ok=True)
    is_fork_subversion = bool(re.search(r"-d\d+", tag))

    if is_fork_subversion:
        print(f"Generating fork sub-version changelog for: {tag}")
        try:
            tags_output = subprocess.check_output(["git", "tag", "--sort=-v:refname"], text=True).splitlines()
            prev_tag = next((t.strip() for t in tags_output if t.strip() != tag and t.strip().startswith("v")), None)
        except Exception:
            prev_tag = None

        if prev_tag:
            git_range = f"{prev_tag}..HEAD"
            log_cmd = ["git", "log", git_range, "--pretty=format:%s"]
        else:
            log_cmd = ["git", "log", "-n", "15", "--pretty=format:%s"]

        try:
            commits = subprocess.check_output(log_cmd, text=True).splitlines()
        except Exception:
            commits = []

        custom_notes = os.environ.get("CUSTOM_CHANGELOG", "").strip()
        if not custom_notes and os.path.exists("release-apks/custom_notes.txt"):
            with open("release-apks/custom_notes.txt", "r", encoding="utf-8") as nf:
                custom_notes = nf.read().strip()

        filtered = []
        if custom_notes:
            for line in custom_notes.splitlines():
                cl = line.strip().lstrip("-* ").strip()
                if cl:
                    filtered.append(cl)

        for c in commits:
            c_clean = c.strip()
            if not c_clean:
                continue
            low = c_clean.lower()
            if any(k in low for k in ["sync upstream", "merge branch", "merge remote", "[skip ci]"]):
                continue
            if c_clean not in filtered:
                filtered.append(c_clean)

        if not filtered:
            filtered = ["Bug fixes, performance improvements, and fork enhancements."]

        body_md = f"## Echo Music Release ({tag})\n\n### Fork Changes:\n"
        for item in filtered:
            body_md += f"- {item}\n"
        body_md += "\n---\n*Echo Music Fork with Listen Together Real-Time Synchronization & 320kbps High-Fidelity Audio.*"

        with open("release_body.md", "w", encoding="utf-8") as f:
            f.write(body_md)

        changelog_data = {
            "description": f"Echo Music {tag} update",
            "changelog": [
                {
                    "title": f"Changes in {tag}",
                    "items": filtered
                }
            ]
        }

        with open(os.path.join("release-apks", "changelog.json"), "w", encoding="utf-8") as f:
            json.dump(changelog_data, f, indent=2)
        print("Generated release_body.md and release-apks/changelog.json successfully.")

    else:
        print(f"Generating upstream release changelog for: {tag}")
        token = os.environ.get("GITHUB_TOKEN", "")
        headers = {"User-Agent": "EchoMusic-CI"}
        if token:
            headers["Authorization"] = f"Bearer {token}"

        req = urllib.request.Request(
            f"https://api.github.com/repos/EchoMusicApp/Echo-Music/releases/tags/{tag}",
            headers=headers
        )

        upstream_body = ""
        changelog_json_downloaded = False

        try:
            with urllib.request.urlopen(req) as resp:
                data = json.loads(resp.read().decode("utf-8"))
                upstream_body = data.get("body", "")
                assets = data.get("assets", [])
                for asset in assets:
                    if asset.get("name") == "changelog.json":
                        dl_url = asset.get("browser_download_url")
                        urllib.request.urlretrieve(dl_url, os.path.join("release-apks", "changelog.json"))
                        changelog_json_downloaded = True
                        break
        except Exception as e:
            print(f"Notice: Could not fetch upstream release notes for {tag}: {e}")

        if not upstream_body:
            upstream_body = f"Official Echo Music Release {tag}"

        body_md = f"{upstream_body}\n\n---\n*Echo Music Fork with Listen Together Real-Time Synchronization & 320kbps High-Fidelity Audio.*"
        with open("release_body.md", "w", encoding="utf-8") as f:
            f.write(body_md)

        if not changelog_json_downloaded:
            items = []
            for line in upstream_body.splitlines():
                line_s = line.strip()
                if line_s.startswith("- ") or line_s.startswith("* "):
                    item_text = line_s[2:].strip()
                    if item_text:
                        items.append(item_text)

            if not items:
                items = [f"Official Echo Music upstream release {tag}."]

            changelog_data = {
                "description": f"Official Echo Music {tag} Release",
                "changelog": [
                    {
                        "title": f"What's New in {tag}",
                        "items": items
                    }
                ]
            }
            with open(os.path.join("release-apks", "changelog.json"), "w", encoding="utf-8") as f:
                json.dump(changelog_data, f, indent=2)
        print("Generated upstream release_body.md and release-apks/changelog.json successfully.")

if __name__ == "__main__":
    main()
