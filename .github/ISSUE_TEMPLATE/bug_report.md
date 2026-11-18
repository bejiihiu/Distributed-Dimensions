---
name: Bug report
about: Create a report to help us improve
title: ''
labels: bug
assignees: ''

---

**Describe the bug**
A clear and concise description of what the bug is.

**To Reproduce**
Steps to reproduce the behavior:
1. Go to '...'
2. Run '...'
3. See error

**Expected behavior**
A clear and concise description of what you expected to happen.

**Environment:**
- Java version: [e.g. temurin 25]
- Platform and version: [e.g. Paper 26.3 backend / Folia 26.3 backend / Velocity 4.2.0 proxy]
- Plugin version: [e.g. 1.0.0]
- Topology: [which backend runs which dimension, e.g. nether=A, end=B]

**Diagnostics**
- If the problem is on the proxy: output of `/ddproxy status` (config health, channel registration, dimensions, in-flight operations)
- If the problem is on a backend: the `health` log lines from startup (e.g. configuration health / degraded-mode reasons)
- Relevant console output around the failure

**Additional context**
```
Add any other context about the problem here.
```
