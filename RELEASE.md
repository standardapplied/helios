# Releasing to Maven Central

This project publishes to Maven Central via the [Central Publishing Portal](https://central.sonatype.com). Releases are automated through GitHub Actions — no developer needs GPG keys or Central Portal credentials locally.

## CI Workflows

| Workflow | Trigger | What it does |
|----------|---------|--------------|
| **CI** | Push to `main`, pull requests | Build, test, coverage, quality gate |
| **Release** | Push to `release/**`, manual dispatch | Build, test, sign, deploy to Central Portal |

## Published Modules

| Artifact | Description |
|----------|-------------|
| `com.standardapplied:helios-core` | Zero-dependency primitives: model, tool, fault tolerance, schema, trace, durability and cost interfaces |
| `com.standardapplied:helios-session` | Session SDK: `AgentSession`, presets, hooks, permissions, workspace file tools, agent loop |
| `com.standardapplied:helios-runtime` | Helidon SE HTTP/SSE surface for sessions |
| `com.standardapplied:helios-gemini` | Google Gemini provider via the Interactions API |
| `com.standardapplied:helios-anthropic` | Anthropic Claude provider via the Messages API |
| `com.standardapplied:helios-openai` | OpenAI provider via the Responses API |
| `com.standardapplied:helios-onnx` | Local ONNX Runtime embedding models |
| `com.standardapplied:helios-persistence` | PostgreSQL persistence for traces, prompts and durable runs via Helidon DbClient |
| `com.standardapplied:helios-repl` | Sandboxed JShell execution substrate |
| `com.standardapplied:helios-testing` | `ScriptedModel` test double for deterministic evals |

## One-Time Setup

### 1. Central Portal Account

1. Create an account at [central.sonatype.com](https://central.sonatype.com)
2. Go to **Account → Generate User Token** to get your `username` and `password` token pair

### 2. Namespace

The `com.standardapplied` namespace is already verified on the Central Portal (**Namespaces** shows
it as **Verified**). Nothing to set up; the publishing account only needs access to it.

### 3. GPG Key

Generate a project-level signing key for a release identity the organisation controls
(`<release-name>`, `<release-email>`). This key is used exclusively by CI — no developer needs it
locally.

```bash
# Generate a passphrase-protected key with 2-year expiry
gpg --batch --gen-key <<EOF
Key-Type: RSA
Key-Length: 4096
Name-Real: <release-name>
Name-Email: <release-email>
Expire-Date: 2y
Passphrase: <a-strong-passphrase>
EOF

# Find the key ID
gpg --list-keys --keyid-format short

# Publish to a keyserver (Maven Central verifies signatures against these)
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# Export the private key for GitHub Actions
gpg --export-secret-keys --armor <release-email>
```

Copy the full armored output (including `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `-----END PGP PRIVATE KEY BLOCK-----`).

### 4. GitHub Secrets

Go to **Settings → Secrets and variables → Actions** in the GitHub repository and add:

| Secret | Value |
|--------|-------|
| `GPG_PRIVATE_KEY` | The armored private key from step 3 |
| `GPG_PASSPHRASE` | The passphrase used when generating the key |
| `MAVEN_CENTRAL_USERNAME` | Central Portal token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal token password |

No `~/.m2/settings.xml` changes needed. The CI workflow generates credentials at build time via `setup-java`.

## Development Workflow

The `main` branch always carries a `-SNAPSHOT` version (e.g., `3.0.0-SNAPSHOT`).

```bash
# Build and install locally for testing
mvn clean install

# Run tests with coverage
mvn verify
```

Snapshots are not published to Maven Central. Use `mvn install` for local testing.

## Release Process

### 1. Verify main is clean

```bash
git checkout main
git pull
mvn clean verify
```

All tests must pass.

### 2. Create a release branch

```bash
git checkout -b release/3.0.0
```

### 3. Set the release version

```bash
mvn versions:set -DnewVersion=3.0.0
mvn versions:commit
git add pom.xml */pom.xml
git commit -m "Release 3.0.0"
```

### 4. Push — CI handles the rest

```bash
git push origin release/3.0.0
```

The **Release** workflow will automatically:
- Build and run all tests
- Generate sources and javadoc JARs
- Sign all artifacts with the project GPG key
- Upload the bundle to the Central Portal

### 5. Publish the deployment

1. Go to [central.sonatype.com](https://central.sonatype.com) → **Deployments**
2. Find your deployment (status: **Validated**)
3. Click **Publish**
4. Wait for sync to Maven Central (usually under 30 minutes)

Verify the artifacts are live:

```
https://repo1.maven.org/maven2/com/standardapplied/helios-core/3.0.0/
https://repo1.maven.org/maven2/com/standardapplied/helios-session/3.0.0/
https://repo1.maven.org/maven2/com/standardapplied/helios-runtime/3.0.0/
https://repo1.maven.org/maven2/com/standardapplied/helios-gemini/3.0.0/
https://repo1.maven.org/maven2/com/standardapplied/helios-anthropic/3.0.0/
https://repo1.maven.org/maven2/com/standardapplied/helios-openai/3.0.0/
https://repo1.maven.org/maven2/com/standardapplied/helios-onnx/3.0.0/
https://repo1.maven.org/maven2/com/standardapplied/helios-persistence/3.0.0/
https://repo1.maven.org/maven2/com/standardapplied/helios-repl/3.0.0/
https://repo1.maven.org/maven2/com/standardapplied/helios-testing/3.0.0/
```

To skip this manual step, add `<autoPublish>true</autoPublish>` to the `central-publishing-maven-plugin` configuration in `pom.xml`.

### 6. Tag and merge

```bash
git tag v3.0.0
git push origin v3.0.0
```

Merge the release branch back to main via pull request.

### 7. Bump main to next snapshot

```bash
git checkout main
git pull
mvn versions:set -DnewVersion=3.1.0-SNAPSHOT
mvn versions:commit
git add pom.xml */pom.xml
git commit -m "Bump to 3.1.0-SNAPSHOT"
git push
```

## Quick Reference

| Step | Command |
|------|---------|
| Local build | `mvn clean install` |
| Run tests | `mvn verify` |
| Set version | `mvn versions:set -DnewVersion=X.Y.Z` |
| Format code | `mvn spotless:apply` |

## Troubleshooting

**CI deploy fails with GPG error**: Verify the `GPG_PRIVATE_KEY` secret contains the full armored key including header/footer lines.

**Central Portal rejects deployment**: Check that `pom.xml` has all required metadata — `name`, `description`, `url`, `licenses`, `developers`, `scm`. All are already configured.

**Central Portal rejects the namespace**: Confirm the publishing account can see `com.standardapplied` as **Verified** under **Namespaces**.

**Manual deploy (without CI)**: Add Central Portal credentials to `~/.m2/settings.xml` under server id `central`, then run `mvn clean deploy -Prelease -pl core,session,runtime,gemini,anthropic,openai,onnx,persistence,repl,testing`.
