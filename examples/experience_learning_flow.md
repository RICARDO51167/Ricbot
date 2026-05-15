# Experience Learning Flow

Minimal sequence for turning one finished task into governed experience.

```text
/summary
/summary --write-note
/experience extract
/experience list
/experience show exp_...
/experience verify exp_...
/context --detail --sources
/experience promote exp_...
```

Expected behavior:

- `/summary` renders the current task summary without writing notes.
- `/summary --write-note` writes `notes/tasks/<timestamp>-<slug>.md` and updates `notes/index.json`.
- `/experience extract` writes candidate entries to `experience/candidates.jsonl`.
- Candidate entries do not enter `/context`.
- `/experience verify <id>` writes the reviewed entry to `experience/verified.jsonl`.
- Verified entries can appear under `verified_experience` in `/context`.
- `/experience promote <id>` is explicit and writes a project playbook note through `NoteService`.

Useful verification snippets:

```text
experience extracted:
status: CANDIDATE

experience verified
status: VERIFIED

verified_experience
experience/verified.jsonl:exp_...
```
