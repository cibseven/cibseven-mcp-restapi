# CIB seven REST API MCP Server

`cibseven-mcp-restapi` is a Spring Boot auto-configured library that turns any
**OpenAPI**-described REST API into **MCP tools**: it parses the OpenAPI document,
registers one MCP tool per operation, and executes tool calls against the target API.
Its primary use is exposing the [CIB seven](https://cibseven.org) engine REST API to
LLMs, but the mapping itself is generic — point `cibseven.openapi.url` at any OpenAPI
document.

The library also ships everything needed to run this securely:

- **Inbound**: OAuth2 resource-server protection of the MCP endpoint, including the
  RFC 9728 discovery metadata MCP clients (e.g. the claude.ai connector) need.
- **Outbound**: a pluggable authentication strategy towards the target engine-rest —
  forward the caller's token (`passthrough`) or translate the caller's identity into a
  short-lived CIB seven JWT (`minted-jwt`), so the engine's existing (LDAP)
  authorizations apply per user.

> Looking for a ready-to-run server instead of a library? See
> [cibseven-mcp-server](https://github.com/cibseven/cibseven-mcp-server) — a minimal
> Spring Boot application (plus Docker image and Helm chart) hosting this library.

## Requirements

- Java 17+
- Spring Boot 4.0.x (Spring AI MCP server 2.0.0, MCP SDK 2.0.0)

## Installation

```xml
<dependency>
  <groupId>org.cibseven.mcp</groupId>
  <artifactId>cibseven-mcp-restapi</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Minimal configuration in the host application:

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STATELESS
        name: cibseven-mcp-server
        version: 1.0.0
        type: SYNC
        instructions: "CIB seven MCP server exposing the Engine REST API as MCP tools."
        streamable-http:
          mcp-endpoint: /mcp

cibseven:
  mcp:
    restapi-mcp: true          # activates this library's auto-configuration
  webclient:
    engineRest:
      url: http://localhost:8080
```

## Configuration reference

| Property | Default | Description |
| --- | --- | --- |
| `cibseven.mcp.restapi-mcp` | – | Set `true` to activate the library. |
| `cibseven.openapi.url` | CIB seven 2.2 [openapi.json](https://docs.cibseven.org/rest/cibseven/2.2/swagger/openapi.json) | OpenAPI document to expose as MCP tools (HTTP(S) URL or file path). |
| `cibseven.webclient.engineRest.url` | `http://localhost:8080` | Base URL of the API the tools call. |
| `cibseven.webclient.engineRest.path` | `/engine-rest` | Path appended to the base URL. |
| `spring.ai.mcp.server.streamable-http.mcp-endpoint` | – | MCP endpoint path (e.g. `/mcp`). Required. |

## Securing the MCP endpoint (inbound)

As soon as `spring.security.oauth2.resourceserver.jwt.issuer-uri` is configured (and
Spring Security is on the classpath), the library turns the MCP endpoint into an
**OAuth2 resource server**:

- Incoming JWT bearer tokens are validated against the authorization server.
- The standard discovery metadata is served unauthenticated:
  `/.well-known/oauth-authorization-server` and, per
  [RFC 9728](https://www.rfc-editor.org/rfc/rfc9728), the Protected Resource Metadata
  under `/.well-known/oauth-protected-resource{mcp-endpoint}`.
- Each MCP session is bound to the user that created it.

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://login.microsoftonline.com/<tenant>/v2.0

cibseven:
  mcp:
    oauth2:
      scopes-supported: "openid offline_access api://<entra-app-id>/access_as_user"  # optional
```

| Property | Required | Description |
| --- | --- | --- |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | Yes | Authorization server issuer. Its presence activates the OAuth2 configuration. |
| `cibseven.mcp.oauth2.scopes-supported` | No | Space- or comma-separated scopes advertised in the Protected Resource Metadata (`scopes_supported`). |

**`issuer-uri` vs `jwk-set-uri`:** use `issuer-uri`. `jwk-set-uri` only tells Spring
where to fetch signing keys, while `issuer-uri` also enables the issuer-metadata
discovery this library relies on to build the OAuth2 discovery documents for MCP
clients.

**`scopes-supported`:** some MCP clients have no scope input of their own (e.g. the
**claude.ai** connector) and rely on the Protected Resource Metadata to know which scope
to request. Without it they send an `/authorize` request with no scope, which some
authorization servers reject before login (Microsoft Entra ID:
`AADSTS900144: The request body must contain the following parameter: 'scope'`).

**Static bearer alternative:** for clients that let you set headers directly (VS Code,
MCP Inspector), a servlet-filter/JWT setup also works:

```json
"cibseven-mcp": {
  "url": "http://localhost:8080/mcp",
  "type": "http",
  "headers": { "Authorization": "Bearer ..." }
}
```

The claude.ai connector cannot do this (its UI only takes client id/secret) — only the
OAuth2 chain works there.

## Outgoing engine-rest authentication (outbound)

The section above secures the **inbound** hop (MCP client → MCP server). Authentication
of the **outbound** hop (MCP server → CIB seven engine-rest) is a separate concern: once
the MCP server has validated the caller, it still has to tell engine-rest *who* the call
is for, so that the engine's existing (LDAP) identity provider resolves the right groups
and authorizations. This is handled by the pluggable `EngineRestAuthProvider` strategy
(package `org.cibseven.mcp.auth`), selected per deployment:

| Property | Default | Description |
| --- | --- | --- |
| `cibseven.mcp.engine-rest.auth` | `passthrough` | `passthrough` or `minted-jwt` (see below). |
| `cibseven.mcp.engine-rest.minted-jwt.resolver` | `claim` | `claim`, `graph` or `static`. Only used in `minted-jwt` mode. |
| `cibseven.mcp.engine-rest.minted-jwt.user-id-claim` | `preferred_username` | Token claim read by the `claim` resolver. |
| `cibseven.mcp.engine-rest.minted-jwt.static.user-id` | – | Fixed userId used by the `static` resolver (dev/test only). |
| `cibseven.mcp.engine-rest.minted-jwt.ttl-seconds` | `60` | Lifetime of the minted CIB seven JWT. |
| `cibseven.webclient.authentication.jwtSecret` | – | Base64-encoded HMAC secret shared with engine-rest (required in `minted-jwt` mode). |

### `passthrough` (default)

The validated inbound bearer token is forwarded to engine-rest unchanged. Use this when
**engine-rest is itself an OAuth2 resource server** against the same issuer.

Deployment requirements for this mode (engine-rest side, *not* the MCP server's
responsibility but required for it to be safe):

- **Validate the audience (`aud`)**, not only `iss` + signature. Without `aud`
  validation engine-rest would accept *any* validly signed token from the tenant,
  including tokens minted for unrelated applications.
- **Disable the read-only OAuth2 identity provider** so group/authorization resolution
  stays with the (LDAP) identity provider:
  `camunda.bpm.oauth2.identity-provider.enabled=false`.

### `minted-jwt`

The MCP server translates the validated external identity into a freshly minted,
short-lived **CIB seven HMAC JWT**, signed with the shared
`cibseven.webclient.authentication.jwtSecret` — exactly the token format engine-rest's
`JwtTokenAuthenticationProvider` already accepts from the LDAP-bound webclient.
engine-rest needs **no** OAuth2 configuration, never sees the external token (so the
audience concern disappears), and the (LDAP) identity provider stays authoritative for
groups and authorizations.

> engine-rest must run an authentication provider that actually reads the
> `Authorization` header — e.g. `camunda.bpm.run.auth.authentication: composite` in a
> CIB seven Run distribution. The default `pseudo` provider ignores it entirely and
> every call fails with 401.

```yaml
cibseven:
  mcp:
    engine-rest:
      auth: minted-jwt
      minted-jwt:
        resolver: graph        # or: claim | static
        ttl-seconds: 60
```

The CIB seven userId is produced by a `UserIdResolver`:

- **`claim`** (default) — reads `user-id-claim` (default `preferred_username`) directly.
  Correct **only** if that claim equals the stored LDAP userId byte- and case-exactly.
  Verify with `SELECT DISTINCT user_id_ FROM act_ru_authorization` before relying on it.
- **`graph`** — resolves the immutable Entra `oid` to the on-premises `sAMAccountName`
  via Microsoft Graph (cached, 8 h). Use this when no Entra claim matches the stored
  userId 1:1 (the common LDAP-prod case). Requires a `graph` OAuth2 **client
  registration** (`client_credentials`, Application permission `User.Read.All`,
  admin-consented); the required `OAuth2AuthorizedClientManager` is provided
  automatically when `resolver=graph`:

  ```yaml
  spring:
    security:
      oauth2:
        client:
          registration:
            graph:
              client-id: ${GRAPH_CLIENT_ID}
              client-secret: ${GRAPH_CLIENT_SECRET}
              authorization-grant-type: client_credentials
              scope: https://graph.microsoft.com/.default
              provider: entra
          provider:
            entra:
              token-uri: https://login.microsoftonline.com/<tenant>/oauth2/v2.0/token
  ```

- **`static`** — ignores the caller's identity and always uses one configured userId
  (`minted-jwt.static.user-id`). **Development and testing only**: every caller is
  impersonated as that user. It is the easiest local on-ramp for `minted-jwt` mode
  (no Graph registration, no claim mapping).

A host application can always provide its own `UserIdResolver` bean, which overrides the
built-in ones.

> **Security note.** In `minted-jwt` mode the (internet-reachable) MCP server holds the
> shared secret and can therefore mint a token for *any* userId, including
> administrators — impersonation capability concentrated in one component. Keep the
> secret in a real secret store, never in the image or repository; plan rotation; keep
> the TTL short (60 s default); and isolate engine-rest at the network level so only the
> MCP server and the webapp can reach it.

> **Reachability note.** Claude.ai connects to the MCP server from Anthropic's cloud
> over the public internet, so the MCP endpoint must be publicly reachable (lock inbound
> down to Anthropic's IP ranges); engine-rest itself stays internal.

## Multipart operations (file uploads)

Operations with `multipart/form-data` bodies (e.g. deployment creation) accept a `data`
(or `content`) argument for the file content and an optional `filename` argument that
names the uploaded part — even though `filename` is not declared in the OpenAPI schema.
Without it, the extension is guessed from the content (BPMN/DMN/CMMN namespaces) as a
fallback. See [SKILL.md](SKILL.md) for the client-side guidance.

## Known limitations

- The OpenAPI document is fetched **eagerly at startup**; if `cibseven.openapi.url` is
  unreachable, the application fails to start (deliberate: a tool-less MCP server would
  otherwise look healthy).
- Tool **output schemas** are not yet exposed (input schemas only).
- Only local `#/components/schemas/...` references are supported; external `$ref`s are
  rejected.

## Building from source

```bash
mvn verify        # compiles and runs the test suite
```

## Debugging

Use the [MCP Inspector](https://github.com/modelcontextprotocol/inspector)
(`npx @modelcontextprotocol/inspector`, on Windows `npx.cmd`), or an MCP client such as
VS Code:

```json
{
  "servers": {
    "cibseven-mcp-restapi": {
      "url": "http://localhost:8080/mcp",
      "type": "http"
    }
  },
  "inputs": []
}
```

## License

Apache License 2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).
