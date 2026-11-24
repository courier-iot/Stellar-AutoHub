# Stellar AutoHub

Upload a zip, files or a folder from your phone straight into a GitHub repo, watch the Actions build, read the errors, and install the APK.

## First-time setup

1. **Upload this project** to a new repo (e.g. `Stellar-AutoHub`) on `main`. Include the `.github` folder.
2. **Signing secrets (optional for the very first build):**
   - If you already have a keystore (e.g. the one Stellar Simulcast uses), add the same 4 secrets here:
     `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
   - If you don't, the first build is signed with a temporary debug key. Install it, create a key in
     **AutoHub → 🔒 Keys**, apply it to this repo in **Secrets**, bump the version and build again.
     Uninstall the debug build once, then install the signed one.
3. **GitHub sign-in:** github.com → Settings → Developer settings → OAuth Apps → New OAuth App
   (name: Stellar AutoHub, homepage + callback: `https://github.com`), tick **Enable Device Flow**,
   copy the **Client ID** into AutoHub. You can paste a personal access token instead
   (classic: `repo` + `workflow`).

## How uploads work

- Commits use the Git Data API: one commit per upload, with no 100-file limit. Hidden folders like `.github` are included.
- The preview shows **New / Changed / Delete / Identical / Skipped** before anything is sent. Identical files are never re-sent.
- A full-project zip or folder can **mirror** the repo, removing old files. You pick which ones, and `README*` / `LICENSE*` are protected by default. Loose files only **patch**.
- A wrapping top folder (`MyApp/…`) is stripped automatically.
- Keystores, `build/`, `.gradle/`, `local.properties`, `__MACOSX` and `.DS_Store` are never uploaded.
- You can share a zip from any app to AutoHub. It guesses the repo from the app's `applicationId`.

## Builds

- Builds are watched in the background, with a notification on pass or fail.
- On failure you get the failed step, annotations, highlighted error lines, **Copy for Claude**, **Share log**, and Re-run failed / all.
- On success it offers **Install** when the app isn't on the phone or is older. If no release was published, it can install the APK from the build artifact.

## Secrets

The secrets screen reads the workflows, lists every `secrets.NAME` they use, and marks each one set or missing. **Apply signing key** fills the signing secrets automatically by matching their names. Values are encrypted on the phone (libsodium sealed box) before upload.

## Security & upkeep (v1.1.0)

- **App lock:** fingerprint or screen lock on open, after 0, 1, 5 or 15 minutes away. Sensitive actions ask again: key passwords and files, secrets, mirror deletes, undo, cleanup, backups.
- **Secret scanner:** commits are blocked until each possible token, private key or config password is either left out or marked fine.
- **Screenshot block** on the Keys, Secrets and Backup screens.
- **APK signature check** before installing, plus the name of the vault key that signed it.
- **Token health:** warns about revoked or expiring tokens and a missing `workflow` scope.
- **Undo upload:** a new commit that restores the previous files. History is never rewritten.
- **Encrypted backup** (`.sahb`, AES-256-GCM + PBKDF2) of repos, history, keys and settings.
- **Auto version bump** when the version already has a release.
- **GitHub storage cleanup:** old runs, artifacts and releases.
- **Workflow health:** finds outdated actions and deprecation warnings, with one-tap fixes through the normal preview.
- **Crash log** stored on the phone only, **offline queue**, **automatic cache cleanup**, and an **R8-shrunk APK**.

## Developer tools (v2.0.0)

- **Files:** browse any branch, view images, save files to the phone, create and delete files.
- **Editor:**
  - Syntax highlighting for Kotlin, Java, XML, Gradle, YAML, JSON, Markdown, properties and shell.
  - Line numbers, find & replace, undo/redo, tabs, word wrap, go to line, Markdown preview.
  - Edits are saved on the phone as **drafts** until you commit.
- **Changes:** every draft goes into one commit, reviewed through the normal preview. It warns you if a file changed on GitHub meanwhile.
- **Pre-commit checks:** unbalanced brackets (Kotlin/Java/Gradle), invalid YAML, JSON or XML.
- **Diffs** in the preview, in Changes, and for every commit.
- **Edit before upload:** tap a file in the preview.
- **History:** commits with diffs, "Use this version" and "Undo in this file".
- **Search** across the repo, including your drafts.
- **Branches and pull requests:** create and switch branches, open a PR, see its checks, then squash & merge.
- **Build now**, **Cancel** a running build, **tap an error** to jump to that line, and **Copy for Claude** (report + code around each error + file list).
- **New app from template**, **share/save the repo as a zip**, **build stats**, **mute per repo**, and a **home-screen widget**.
- **Display:** text size, high contrast, pure black, and the accent color.
- **Expiring OAuth tokens renew automatically** (sign in once with the code after updating).

## Windows & other projects (v2.2.0)

- AutoHub detects the project kind (Android, Electron, Node, .NET, Python, C/C++) and adapts:
  - **Ignore rules per kind.** Electron keeps `build/` (icons) and skips `node_modules/`, `dist/` and `out/`. .NET skips `bin/` and `obj/`.
  - **Release files card** for non-Android repos: send or save the `.exe` or zip from the latest release.
  - **Tappable errors** from Windows runners (`D:\a\…`), TypeScript (`file.ts(12,5)`) and Node stack traces.
  - **JavaScript/TypeScript** highlighting and bracket checks (strings, `template ${}` and regex literals included).
  - **Version bump** works with `package.json` too.
- **New app wizard → "What goes in it?":** Android starter, Windows starter (Electron), your own zip or folder (uploaded as the first commit), or an empty repo.
- **Electron workflow:** builds on `windows-latest` with electron-builder (or Electron Forge) and releases `vX.Y.Z` from `package.json` on `main`, with an installer and a portable `.exe`.
- **Workflow health** can add the standard build workflow to repos that have none.
