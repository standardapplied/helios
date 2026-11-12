# Security

The security primitives Helios provides and the boundaries it does not: one secret registry per
session that redacts what tools return, a layered sandbox for model-written code whose
authoritative boundary is the OS around it, confinement of every filesystem path the framework
opens, and an HTTP surface that authenticates every request. Each section says what is enforced and
what is left to the deployer. Back to the [README](../README.md#production-readiness).

- [Secret redaction](#secret-redaction)
- [Sandbox security model](#sandbox-security-model)
- [Least-privilege deployment](#least-privilege-deployment)
- [Workspace confinement](#workspace-confinement)
- [Other boundaries](#other-boundaries)

## Secret redaction

`core.common.SecretRegistry` holds secret values by name; its `redactor()` replaces each with
`<redacted:NAME>`. A session has one (`SessionOptions.withSecretRegistry`, default empty). **A
secret a tool returns never reaches the model or anything the session emits:** the session redacts
every tool result once, before its event, hooks and history, and the error text it reports. Output
cut at a size cap loses any trailing start of a secret. User messages, the system prompt and the
model's output are not redacted; the `withSecretRegistry` Javadoc lists what else is not.
Accepted values: `SecretRegistry` Javadoc.

<!-- snippet: ToolSnippets#secretRegistry -->
```java
var registry = new SecretRegistry();
registry.register("STRIPE_KEY", System.getenv("STRIPE_KEY"));
registry.register("GH_TOKEN", System.getenv("GH_TOKEN"));

var options = SessionOptions.newBuilder().withModel(model).withSecretRegistry(registry).build();
```

A [`CommandGrant`](tools-and-permissions.md#commandgrant-a-cli-without-its-secrets) registers its
environment values in the same registry, so one registry on the session and on its grants keeps a
token out of what the agent reads back.

## Sandbox security model

The `helios-repl` sandbox is defense in depth, not a single boundary:

1. **OS-level sandbox** (the deployer's responsibility): a container, VM or namespace boundary
   around the Helios process. This is the authoritative escape boundary and the only layer that
   isolates the filesystem and the network.
2. **JVM subprocess sandbox** (`JvmSandbox`): the model's code runs in a separate JVM with a cleared
   environment; the parent's `-D` system properties, agents (`-javaagent`, `-agentlib`,
   `-agentpath`) and native-access grants are not passed on, while the classpath and module path
   are; descendants are reaped on close and by the shutdown hook; executes are serialised by a
   `Semaphore(1)`.
3. **Bytecode policy** (`SandboxPolicy` and `PolicyBytecodeVerifier`): every snippet class is
   scanned before it links. Direct calls, field access, class literals, and every reference reachable
   through `invokedynamic` bootstrap arguments, method-handle constants and dynamic constants are
   checked against the same owner and member rules, so `FileReader::new` is denied exactly like
   `new FileReader(...)`. Malformed or cyclic constant pools fail closed.
4. **JShell prelude controls**: typed wrappers around `HostFunction`s, the reserved name skipped by
   the wrapper synthesiser, and a `HostFunctionRegistry` frozen after the sandbox boots.

**What the sandbox subprocess is not.** The child JVM runs as the same user as the Helios process,
in the same mount and network namespaces. Its private working directory scopes relative paths,
nothing more: absolute paths, `/proc`, the host's sockets and every file the Helios user can read
are reachable from a snippet unless a bytecode policy denies the API or the OS boundary hides the
resource. The bytecode verifier is defense in depth against honest mistakes and probing snippets;
it is a static scan of JShell-compiled classes, not a JVM security manager, and it inherits the
JVM's own limits (a static receiver type it cannot widen, JDK internals reached through
`--add-opens`, native code with `--enable-native-access`). Treat layer 1 as mandatory for untrusted
snippets.

The sandbox's RPC to the host runs on a per-session Unix domain socket bound in a private temporary
directory (mode 0700): the host accepts exactly one connection from the subprocess on startup, then
closes the listener and deletes the socket file. A JShell snippet that obtains a raw `PrintStream`
to `FileDescriptor.out` can write whatever it wants to the subprocess's stdout; the host reads
stdout into the execution result's captured output but never parses it as RPC, so forged frames
are inert text. A snippet's stdout and stderr are each cut at 1,000,000 characters per execute,
followed by a `[output truncated: <n> more chars discarded]` line, and an RPC frame over 8,000,000
characters ends the sandbox.

## Least-privilege deployment

`JvmSandbox` launches its child with `ProcessBuilder`, so the child shares whatever confinement the
Helios process itself runs under. The supported least-privilege contract is therefore to run the
process that owns the sandbox (the whole application, or a dedicated executor service that exposes
only the `Sandbox` seam) inside a container that grants nothing it does not need. Rootless Podman,
no image beyond the JDK and your jars:

```bash
podman run --rm \
  --network none \
  --read-only --tmpfs /tmp \
  --cap-drop ALL --security-opt no-new-privileges \
  --pids-limit 256 --memory 1g \
  -v /opt/helios/app:/opt/helios/app:ro \
  -v /srv/tenant-42/workspace:/workspace:ro \
  docker.io/library/eclipse-temurin:25-jdk \
  java --enable-native-access=ALL-UNNAMED -cp '/opt/helios/app/*' your.Executor
```

- Use a full JDK 25 image: the executor and sandbox require `jdk.jshell` and `jdk.compiler`, which
  are absent from the Temurin JRE image.
- `--network none` removes every interface but loopback inside the container; the host's listeners
  and the wider network are unreachable even with a permissive `SandboxPolicy`. Replace it with an
  egress-filtered network only for workloads that need one.
- `--read-only --tmpfs /tmp` gives the sandbox its scratch directory and RPC socket directory on a
  private tmpfs and nothing else writable. Mount the workspace read-only and let host-owned tools
  (`Read`, `Grep` and `Glob` on a `WorkspaceRoot`) mediate access.
- `--cap-drop ALL --security-opt no-new-privileges` blocks privilege escalation inside the
  container; `--pids-limit` and `--memory` bound fork bombs and heap abuse beyond
  `JvmSandboxConfig.maxHeapMb`.

`IsolatedDeploymentTest` in `helios-repl` exercises this recipe with the real `JvmSandbox` under a
permissive policy: a snippet inside the container cannot read a host sentinel file or connect to a
host TCP listener. It runs wherever rootless Podman can start a container and skips with a stated
reason elsewhere, so a green build on an unsupported host is not a claim of enforcement.

## Workspace confinement

`WorkspaceRoot` is the confinement contract for `Read`, `Grep`, `Glob`, `LS` and filesystem memory,
at the workspace or any [curated-corpus](tools-and-permissions.md#a-curated-corpus) root. A
lexical check refuses `..` and absolute paths without dereferencing anything. `root()` is canonical.
`resolveSafe` canonicalises the deepest *existing* prefix, rejecting outside, dangling and looping
links even for a not-yet-existing leaf. Explicitly configured root aliases and requests through
legitimate in-workspace aliases remain supported; returned paths use their canonical spelling.

Strict I/O does not trust that earlier path check. It descends from the filesystem root using
directory descriptors and single-component no-follow `openat` calls, including every ancestor of
the workspace root. An `O_PATH` descriptor pins the leaf for metadata inspection without opening a
FIFO or device for I/O. Only a regular file is reopened through the kernel's `/proc/self/fd`
namespace, while its descriptor remains owned by the stream. Size-limited reads validate that pinned
target and enforce the byte budget during reading, including concurrent growth. `Grep` sniffs and
searches the same bounded content. MIME detection does not invoke platform detectors that might
reopen an unconfined pathname.

Directory creation (`mkdirat`), deletion (`unlinkat`) and directory enumeration also use pinned
directory descriptors. Walkers never follow directory symlinks, and content tools skip non-regular
entries. `LS` can display symlink metadata without following it. A missing workspace root is not
recreated. New files and directories are owner-only (0600/0700, further restricted by the process
umask); existing permissions are preserved. Default output creates or truncates, `CREATE_NEW`
remains exclusive, and `DELETE_ON_CLOSE` is explicitly unsupported in strict mode.

`FileSystemMemoryBackend` is strictly narrower: a `/memories/...` path must canonicalise to exactly
its lexical location under `<root>/.agent/memory`, so any symlink in that path, even one pointing at
another file inside the workspace, is refused for `view`, `create`, `strReplace`, `insert`, `delete`
and `list`. That directory is reserved in every workspace: `Read`, `Grep`, `Glob` and `LS` refuse a
path in it and walkers skip it, so it is reachable only through `MemoryRead` and `MemoryWrite`
(`Execute` is not covered; the sandbox is the boundary there). Memory always uses strict I/O, even
if supplied a trusted-mode workspace. Memory content is strict UTF-8; invalid text is rejected
before creating directories or opening a write target.

Strict mode requires Linux x86-64 or AArch64, the default filesystem and a mounted `/proc/self/fd`.
It uses JDK 25's Foreign Function & Memory API to call libc; no downloaded native library, compiler
or private JDK API is needed. Enable native access for the session module:

```text
java --enable-native-access=com.standardapplied.helios.session --module-path ... --module your.application/your.Main
```

For a classpath application, use `java --enable-native-access=ALL-UNNAMED -cp ... your.Main`.
Maven tests configure these grants automatically. The host grant is not propagated to `JvmSandbox`
child JVMs. Construction fails closed when native access, the filesystem or the platform is
unsupported, including macOS and Windows; there is no automatic weaker fallback.

`new WorkspaceRoot(root, false)` is the explicit, portable **trusted-workspace** opt-out, never a
preset default. It permits symlinks outside the root and does not provide race-resistant ancestor
confinement: a concurrent writer that swaps in a symlink after a path was authorised, or after a
walked entry was checked against the permission policy, can redirect the operation. Use it only
when nothing else writes to the workspace while the agent runs, or when every process that can
write it may also read every target a symlink in it may reach. It does not relax filesystem
memory's requirements.

Descriptor confinement prevents symlink substitution from redirecting an operation. It is not a
filesystem snapshot: an opened inode remains the operation's target after a rename, and another
writer can still edit its contents. Hard links, bind mounts, a hostile kernel or a process that can
modify the host's mount namespace require OS-level isolation. Permission keys assume a
case-sensitive filesystem: on a case-insensitive one (an ext4 casefold directory, vfat, SMB) a path
spelled in another case names the same file under a different key, so path rules there are out of
scope like hard links. These file tools are not a sandbox for arbitrary code execution.

## Other boundaries

- **`CommandGrant`** gives each call a temporary working directory unless `withCwd` sets one, and
  refuses an argument holding a registered secret; see
  [CommandGrant](tools-and-permissions.md#commandgrant-a-cli-without-its-secrets).
- **`OnnxModelDownloader`** refuses absolute paths and traversal in the file names Hugging Face
  supplies, against the local model cache.
- **`PgConfig`** rejects a schema name that does not match Postgres' unquoted-identifier shape,
  so a configuration-driven schema name cannot inject SQL through the table qualifier.
- **The HTTP surface** binds loopback by default and authenticates every request; see
  [Authentication and limits](http.md#authentication-and-limits).
- **Trace tables** store payloads verbatim unless you set a redactor; see
  [What the trace tables hold](observability-and-persistence.md#what-the-trace-tables-hold).
