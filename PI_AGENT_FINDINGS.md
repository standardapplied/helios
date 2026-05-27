# Pi Agent SDK — Findings & Inspiration for Helios

Analysis of [earendil-works/pi](https://github.com/earendil-works/pi) agent core
(`packages/agent/`), conducted 2026-05-26. Each item has a status; update as work lands.

## Items to Adopt

### 1. `transformContext` — pre-LLM message pipeline

**Status: WON'T DO — hooks cover this**

Pi has a two-step pipeline before each LLM call:
`transformContext(AgentMessage[]) -> AgentMessage[]` then
`convertToLlm(AgentMessage[]) -> Message[]`. The first step operates on
the app-level message type (pruning, injecting external context, compaction)
*before* the LLM conversion.

Our `PreModelTurnHook` with `MutateInput` on the `"history"` key does something
similar, but it's awkward — the hook receives a `Map<String, Object>` and must
cast to `List<Message>`. A first-class `ContextTransformer` functional interface
chained before the model call would be cleaner and more discoverable.

**Design direction:** `@FunctionalInterface ContextTransformer` in `session`,
wired through `SessionOptions.Builder.withContextTransformer(...)`. Invoked by
`TurnRunner` before the model call, after `PreModelTurnHook` but before history
snapshot. The compactor remains separate (fires at the 0.95 watermark); this is
a per-turn pre-processing step.

### 2. Follow-up queue — separate from steering

**Status: WON'T DO — PreStopHook + Inject covers this**

Pi distinguishes *steering* (injected after current turn, before next LLM call)
from *follow-up* (injected only when the agent would otherwise stop). Our
`SteeringQueue` handles both but doesn't distinguish priority. Two queues with
clear drain semantics would let presets queue post-completion work without
interfering with mid-turn corrections.

### 3. Conversation branching + branch summarization

**Status: DEFERRED (Phase 7+)**

Pi's session is a *tree*, not a list. `moveTo(entryId)` navigates to a prior
branch point, summarizing the abandoned branch via LLM. Powerful for exploratory
agents. Our session is linear today; track for the durable session phase.

### 4. `shouldStopAfterTurn` callback

**Status: WON'T DO — SessionLimits (wall-clock, budget, turns) + PreStopHook cover this**

Pi checks a predicate after each turn, before polling queues. Our `PreStopHook`
fires only on `STOP` finish reason. A callback after *every* turn would let
deployers implement "stop after N tokens" or "stop if user disconnected."

### 5. Per-tool `executionMode` override

**Status: TODO**

Pi allows individual tools to declare `executionMode: "sequential" | "parallel"`.
Our `ToolDispatch` runs serially. When parallel dispatch lands, per-tool opt-out
for non-idempotent tools would be valuable.

### 6. `prepareNextTurn` — hot-swap model/thinking mid-run

**Status: TODO**

Pi's callback can swap model, thinking level, or context between turns. Our
`SessionOptions` is immutable for the run. Enables adaptive strategies like
"Haiku for tool turns, Opus for reasoning." Needs careful state management design.

## Already Better in Helios

- **Fault tolerance**: FaultTolerance + TransientStreamException + StreamRetryPolicy
- **Type safety**: Sealed interfaces with compile-time exhaustiveness
- **Security**: REPL three-tier enforcement (L1 bytecode, L2 policy, L3 modules)
- **Observability**: Trace / Span / SpanListener structured telemetry
- **Cost tracking**: CostCalculator + CostEstimate + budget limits
- **Hook composability**: Prioritized HookRegistry vs. single-callback

## Explicitly Not Copying

- **Declaration merging** for custom messages — TypeScript-specific
- **Mutable public state** — Pi's `agent.state.tools = [...]` is unsafe for concurrency
- **Single-callback hooks** — Our prioritized registry is more composable
