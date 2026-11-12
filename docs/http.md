# HTTP surface

`helios-runtime` serves sessions over REST and Server-Sent Events on Helidon SE, so clients in any
language can create a session, send it messages, stream its events, answer its questions and read
each run's result. `RuntimeServer` binds loopback by default and authenticates every request; each
session answers only the principal that created it. This guide lists the routes, the question
API, authentication and the request limits. The [README](../README.md#the-http-surface) shows a
local server to start from. Back to the [README](../README.md).

- [Routes](#routes)
- [Questions and approvals](#questions-and-approvals)
- [Authentication and limits](#authentication-and-limits)

## Routes

`RuntimeServer` mounts the routes under `/v1` (`withRoutePrefix` changes it). An options factory
builds each session's `SessionOptions` from a `SessionRequest`, whose `sessionId()` the options
must use and whose `owner()` is the authenticated `Principal`.

| Route | Does |
|---|---|
| `POST /sessions` | creates a session; `201` with `{"sessionId", "eventsUrl"}` |
| `POST /sessions/{id}/messages` | body `{"text": "..."}`; sends it, steering the run in progress or starting the next; `202` |
| `POST /sessions/{id}/interrupt` | cancels the run in progress; `202` |
| `GET /sessions/{id}/events` | an SSE stream of the session's `QueryEvent`s, every run, each named by its type |
| `GET /sessions/{id}/result?timeout=<s>` | long poll for the latest run's terminal: `200` with `{type, runId, result}`, or `204` when the timeout (default 60 s, clamped to 0..300) passes first |
| `GET /sessions/{id}/questions` | the questions still pending |
| `POST /sessions/{id}/questions/{questionId}` | answers one; `202` |
| `DELETE /sessions/{id}` | closes and unregisters the session; `204` |

A client that falls behind on the event stream reads `event: EventsDropped` where its stream missed
events, and replaces the turn's partial text with the run's result; see
[Events](sessions.md#events).

## Questions and approvals

A session that asks the user, through a permission rule that says "ask" or the model's
`AskUserQuestion`, waits until the question is answered. A client that missed the `QuestionAsked`
event lists the questions still pending:

```http
GET /v1/sessions/sess-1/questions

200 OK
[{"questionId": "perm-0193…", "header": "Permission",
  "question": "Allow Write(./notes.md)?\n\nArguments: {\"path\":\"./notes.md\"}",
  "options": [{"label": "Allow", "description": "ask rule Write(**) matched Write(./notes.md)"},
              {"label": "Deny", "description": "Refuse this tool call"}],
  "multiSelect": false}]
```

and answers one by its id; `customText` is optional:

```http
POST /v1/sessions/sess-1/questions/perm-0193…
{"selectedLabels": ["Allow"], "customText": ""}

202 Accepted
```

An id starting `perm-` asks to approve a tool call; one starting `q-` is the model's own question.
Tell them apart by the prefix, never the header: the model chooses its own question's header and
labels. The answer names no tool or arguments, so it approves exactly the call the question was
asked about. A body that is not a valid response is `400`, a question not pending (unknown or
already answered) `404`, a closed session `409`; `401`, `413`, `429` and `503` as for
`POST .../messages`.

## Authentication and limits

`RuntimeServer.Builder` binds **`127.0.0.1` by default**. Every route runs one `Authenticator`
before it reads a body or looks up a session, and a session answers only the `Principal` that
created it: anyone else gets the same `404` as for an unknown id. A missing or invalid credential is
`401` with the authenticator's `challenge()` in `WWW-Authenticate` (an authenticator that throws,
`503`), and expiry or revocation closes open SSE streams without another event; pending result
polls answer `401`. A non-loopback host needs an authenticator; `withoutAuthentication()` serves a
loopback bind only. Details: `AgentHttpService` and `Authenticator` Javadoc.

Behind an authenticating reverse proxy, trust the identity it asserts only on its connections.
`TrustedProxyAuthenticator` reads the `X-Authenticated-Tenant` and `X-Authenticated-Subject`
headers, and only from the proxy addresses you name:

<!-- snippet: HttpSnippets#trustedProxy -->
```java
var server =
    RuntimeServer.builder()
        .withRegistry(SessionRegistry.inMemory())
        .withOptionsFactory(
            request ->
                SessionOptions.newBuilder()
                    .withModel(model)
                    .withSessionId(request.sessionId())
                    .withSystemPrompt(promptFor(request.owner().tenant()))
                    .build())
        .withHost("0.0.0.0")
        .withAuthenticator(
            TrustedProxyAuthenticator.newBuilder()
                .withTrustedProxy(InetAddress.getByName("10.0.0.5"))
                .build())
        .build();
```

The proxy must strip client-supplied copies of both headers. Any other verifier (a JWT library,
mutual TLS) is an `Authenticator`.

Every request is admitted against finite bounds before any work: a body is at most 256 KiB
(`413`), a principal has at most 32 requests in flight, open streams and polls included (`429`),
and a server at most 512 (`503`). A principal holds at most 16 sessions and a registry 256
(`429` / `503`), the owner's longest-idle session evicted first; an idle session closes after 15
minutes, checked every 30 s. `RuntimeServer.Builder.withRequestLimits(RequestLimits)` sets the
body and per-principal bounds and `SessionRegistry.newBuilder()` the session caps and retention;
the server-wide 512 is fixed. A
refusal answers without reading the body and closes the connection. `RuntimeServer` sets two
Helidon options so that no body byte is ever parsed as a request (a request-smuggling guard), and a
`WebServer` of your own that mounts `AgentHttpService` must set both:

<!-- snippet: HttpSnippets#ownWebServer -->
```java
var builder =
    WebServer.builder()
        .maxPayloadSize(limits.maxRequestBytes())
        .addProtocol(Http1Config.builder().continueImmediately(true).build());
```
