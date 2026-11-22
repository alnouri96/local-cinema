---
trigger: always_on
description: Follow AGENTS.md routing and consult the graphify knowledge graph at graphify-out/ before broad codebase scans.
---

## Agent Navigation & graphify

1. **Always start by reading [`AGENTS.md`](AGENTS.md) and [`graphify-out/GRAPH_REPORT.md`](graphify-out/GRAPH_REPORT.md)** before running broad codebase searches.
2. **Route directly via [`AGENTS.md`](AGENTS.md):** Jump straight to the exact source files listed in the Feature-to-File Routing Table (and see `Phone/AGENTS.md`, `Video/AGENTS.md`, or `G:/MEEDX WEB/AGENTS.md` when scoping within a sub-project).
3. **Use the Graph for Deep Architectural Lookups:** When `graphify-out/graph.json` exists, prefer `graphify query "<question>"`, `graphify path "<A>" "<B>"`, `graphify explain "<concept>"`, or `graphify section "<heading>"` over full-repo grep sweeps.
4. **Keep the Index Fresh:** After modifying code or documentation structure, run `graphify update .` from `G:\Local Cinema` to refresh `graphify-out/` cleanly using `.graphifyignore` exclusions.
