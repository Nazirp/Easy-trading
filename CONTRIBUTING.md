# How we work on this repo

Team 9 "Amigos" — Easy Trading. Short on purpose: a document nobody reads is
useless. If something here turns out to be wrong for us, change it.

## Who owns what

| Area | Path | Owner |
|---|---|---|
| Database schema + functions | `db/` | Glenn |
| Backend (REST, business logic, API clients, persistence) | `backend/src/main/java/` | Nazir |
| Frontend (HTML/CSS/JS) | `backend/src/main/resources/static/` | Isna |
| Interface contracts | `backend/CONTRACTS.md` | Nazir |

Ownership means "ask before making big changes here", not "nobody else may
touch it". Small fixes anywhere are fine.

Note that Isna's frontend files live *inside* the backend module — the backend
serves them (see CONTRACTS.md §0 for why). So we all work in one repo, not
three.

## The daily rhythm

```bash
git pull                                  # ALWAYS, before you start
# ...do your work...
git status                                # see what changed
git add .
git commit -m "SCRUM-46 compute SMA crossover signal"
git push
```

**Pull before you start.** This one habit prevents most conflicts. If you
haven't pulled in two days, pull before writing a single line.

**If `git push` is rejected**, someone pushed while you were working. That's
git protecting their work, not an error. Fix: `git pull`, resolve anything it
flags, `git push` again.

## Branches

**One branch per feature**, named `yourname/what-it-does`:

```bash
git switch -c isna/watchlist-page
```

Merge into `main` when it actually works:

```bash
git switch main
git pull
git merge isna/watchlist-page
git push
```

Why branches when our files barely overlap? Because **`main` must always run.**
We demo from it, and half-finished work committed straight to `main` breaks
that for everyone. A branch keeps your in-progress mess out of the way until
it's ready.

Small fixes (a typo, a one-line change) can go straight to `main`. Use
judgement.

## Commit messages

Start with the Jira key, then say what the commit does:

```
SCRUM-46 compute SMA crossover signal
SCRUM-22 add watchlist table and repository
SCRUM-44 add .gitignore
```

The Jira key makes the commit show up on the ticket once the repo is linked —
free traceability, and the tutor can see it.

**Commit small and often.** Ten small commits are easier to understand and to
revert than one giant "did stuff". A commit should be one coherent change you
could describe in a sentence.

## Merge conflicts

Normal, not a disaster. They happen when two people changed the *same lines* of
the same file. Git marks the spot:

```
<<<<<<< HEAD
your version
=======
their version
>>>>>>> their-branch
```

Edit the file so it says what it should say, delete all three marker lines,
then `git add` that file and `git commit`. If you're unsure which version is
right, ask the person who wrote the other one — don't guess.

## Never commit

- **API keys or passwords.** `application.yml` reads them from environment
  variables (`TWELVEDATA_API_KEY`, `FINNHUB_API_KEY`) — keep it that way. Git
  history is permanent: deleting a key in a later commit does **not** remove it
  from history. If a key ever does get committed, say so immediately and
  regenerate it; don't quietly delete it.
- **`target/`** — Maven build output, regenerated every build.
- **IDE folders** — `.idea/`, `*.iml`, `.vscode/`.
- **Backup/scratch folders** — e.g. `static_old`. Delete them or keep them
  outside the repo.

The `.gitignore` covers most of this, but check `git status` before committing —
if something in that list appears, it means `.gitignore` needs a line added.

## Before you push

- Does it still build? `mvn spring-boot:run` starts without errors.
- Does the app still do what it did before your change?
- Any leftover debug output or commented-out experiments to remove?

This is a three-person course project, not a bank — we're not running CI. That
makes it your responsibility not to push something broken to `main`.

## Things that aren't in this repo

- **Tickets and planning** live in Jira (project `SCRUM`).
- **Use cases, architecture diagrams and contracts** live in Confluence.
- `backend/CONTRACTS.md` is a mirror of the Confluence contracts page. The
  repo file is what the code is written against; if they disagree, the repo
  wins and the Confluence page needs re-syncing.
