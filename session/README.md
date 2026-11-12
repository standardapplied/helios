# helios-session

The session SDK: `AgentSession`, a long-lived, streamable, steerable conversation that runs the
agent loop on virtual threads, with `SessionPresets`, hooks, permissions, the workspace file tools,
memory, execution providers, context compaction, session stores, subagents and skills. Installation
and a first session are in the [README](../README.md#quick-start); the rest is in the guides:

- [Sessions](../docs/sessions.md): runs, limits, events, stores, compaction, failures, lifecycle.
- [Tools, permissions and hooks](../docs/tools-and-permissions.md).
- [Subagents and skills](../docs/subagents-and-skills.md).
- [Cost and budgets](../docs/providers.md#cost-and-budgets).

Each exported package's Javadoc covers its subsystem: `com.standardapplied.helios.session`,
`...session.ask`, `...session.execution`, `...session.files`, `...session.hooks`,
`...session.memory`, `...session.permissions`, `...session.store` and `...session.tools`.
[`examples/session-demo`](../examples/session-demo) runs a full session against Gemini on a real
workspace.
