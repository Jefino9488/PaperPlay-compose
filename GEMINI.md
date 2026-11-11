# Workspace Rules

## Commit Policy

- Commit every completed logical change cleanly with a clear, descriptive commit message; do not leave finished work uncommitted.
- Keep commits focused and atomic (one logical feature, refactor, or fix per commit) rather than mixing unrelated edits.
- Run the relevant compile checks and unit tests for modified modules before committing.
- Never commit secrets, signing keys, or machine-local files (such as `local.properties` or `key.properties`).

## Reference Codebase

- Treat `/home/jefino9488/Downloads/PaperPlay/` (the original Flutter app) as strictly **read-only** reference material; never modify, write to, or delete files inside it.
