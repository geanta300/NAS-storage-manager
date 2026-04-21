# Manual GitHub Publishing (No Local Push)

This project can be shared publicly **without configuring a remote** and **without running `git push`**.

## 1) Initialize local git only

```powershell
git init
git add .
git commit -m "Initial public release"
```

Do **not** run `git remote add` unless you explicitly want it.

## 2) Create an empty GitHub repository in browser

- Go to GitHub → New repository
- Create it with **no README / no .gitignore / no license** (already present locally)

## 3) Upload manually in browser

- Open the new repo page
- Use **Add file → Upload files**
- Drag files/folders from this project (respecting `.gitignore` exclusions)
- Commit directly from the web UI

This avoids any local remote configuration and avoids accidental pushes to wrong destinations.

## 4) Verify after upload

- Confirm that no local/private files are present (`local.properties`, `.idea`, `.gradle*`, build folders, logs)
- Confirm `README.md` and `LICENSE` are visible
- Confirm no vendor-heavy folders were uploaded unintentionally (`third_party/`)
