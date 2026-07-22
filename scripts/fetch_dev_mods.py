"""Drop the compileOnly integration mods into run/mods for a dev session.

Jade is a compileOnly dependency on purpose: adding it runtimeOnly would double-load
against a real install and trip NeoForge's duplicate-modid check. That keeps the
published jar honest, but it also means `runClient` has no Jade to test against
unless it is placed by hand - which is what this does, from the copy Gradle already
downloaded.

    python scripts/fetch_dev_mods.py

JEI is not handled here; it is a runtimeOnly dependency and Gradle puts it on the
dev classpath itself.
"""

import shutil
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
RUN_MODS = REPO / "run" / "mods"
CACHE = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"

# group directory under the Gradle cache -> filename to write into run/mods
WANTED = {
    "maven.modrinth/jade": "jade.jar",
}


def main() -> None:
    RUN_MODS.mkdir(parents=True, exist_ok=True)
    for group, name in WANTED.items():
        source = next((CACHE / group).rglob("*.jar"), None)
        if source is None:
            print(f"not in the Gradle cache yet: {group} - run a build first")
            continue
        shutil.copy2(source, RUN_MODS / name)
        print(f"{name} <- {source.name}")


if __name__ == "__main__":
    main()
