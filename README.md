# CIB seven REST API MCP Server

`cibseven-mcp-restapi` is a Spring Boot auto-configured library that turns any
**OpenAPI**-described REST API into **MCP tools**: it parses the OpenAPI document,
registers one MCP tool per operation, and executes tool calls against the target API.
Its primary use is exposing the [CIB seven](https://cibseven.org) engine REST API to
LLMs, but the mapping itself is generic — point `cibseven.openapi.url` at any OpenAPI
document.

The library supports two security setups:

- **Engine REST security only**: the MCP endpoint itself is not protected. The caller's
  `Authorization` header (e.g. a CIB seven JWT) is relayed to engine-rest, which
  validates it with its own authentication provider.
- **OAuth2-protected MCP endpoint**: the MCP endpoint is an OAuth2 resource server,
  including the RFC 9728 discovery metadata MCP clients (e.g. the claude.ai connector)
  need. The validated caller is then either forwarded to an OAuth2-protected engine-rest
  (`passthrough`) or translated into a short-lived CIB seven JWT (`minted-jwt`), so the
  engine's existing (LDAP) authorizations apply per user.

Full documentation: [REST API MCP Plugin](https://docs.cibseven.org/) in the CIB seven
user guide.

> Looking for a ready-to-run server instead of a library? See
> [cibseven-mcp-server](https://github.com/cibseven/cibseven-mcp-server) — a minimal
> Spring Boot application (plus Docker image and Helm chart) hosting this library.

## Requirements

- Java 17+
- Spring Boot 4.1.x (Spring AI MCP server 2.1.x, MCP SDK 2.0.0)

## Installation

```xml
<dependency>
  <groupId>org.cibseven.mcp</groupId>
  <artifactId>cibseven-mcp-restapi</artifactId>
  <version>${mcp-restapi.version}</version>
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

## Security

A call crosses two hops, each authenticated separately:

- **Inbound** (MCP client → MCP server): who is calling the MCP endpoint?
- **Outbound** (MCP server → engine-rest): which CIB seven user does engine-rest run the
  call as, so that the engine's identity provider (e.g. LDAP) resolves the right groups
  and authorizations?

Which setup is active depends on whether Spring Security is on the classpath of the host
application:

| Setup | Spring Security | Inbound | Outbound | MCP clients |
| --- | --- | --- | --- | --- |
| [Engine REST security only](#engine-rest-security-only) | Not on the classpath | Not protected | Caller's `Authorization` header relayed unchanged | Clients supporting custom headers, e.g. VS Code |
| [OAuth2-protected MCP endpoint](#oauth2-protected-mcp-endpoint) | On the classpath, `issuer-uri` required | OAuth2 resource server | `passthrough` or `minted-jwt` | All, incl. the claude.ai connector |

### Engine REST security only

Without Spring Security on the classpath the MCP endpoint is **not protected**. The
`Authorization` header of each incoming MCP request is relayed unchanged and unvalidated
to engine-rest, whatever its scheme. engine-rest alone decides whether the credentials
are valid and which user the call runs as.

A typical combination is engine-rest with the Composite authentication provider, e.g.
in a CIB seven Run distribution:

```yaml
camunda:
  bpm:
    run:
      auth:
        enabled: true
        authentication: composite   # CIB seven JWT, with HTTP Basic as fallback
```

> **Unprotected engine-rest.** With the `pseudo` provider the `Authorization` header is
> ignored and every call is accepted without authentication.

> **Unprotected MCP endpoint.** Anyone who can reach the MCP endpoint can list the
> tools, and every tool call is only as secure as engine-rest's own authentication. Only
> expose the endpoint in trusted networks.

Clients that let you set headers directly (VS Code, MCP Inspector) can send a static
bearer token — with the `composite` provider a CIB seven JWT signed with the engine's
`cibseven.webclient.authentication.jwtSecret`:

```json
"cibseven-mcp": {
  "url": "http://localhost:8080/mcp",
  "type": "http",
  "headers": { "Authorization": "Bearer <CIB seven JWT>" }
}
```

The claude.ai connector cannot do this (its UI only takes client id/secret) — use the
OAuth2-protected setup below.

Only `passthrough` (the default of `cibseven.mcp.engine-rest.auth`) is supported here;
`minted-jwt` makes the application fail to start, because minting a token requires a
validated caller identity.

### OAuth2-protected MCP endpoint

Add `spring-boot-starter-oauth2-resource-server` and configure
`spring.security.oauth2.resourceserver.jwt.issuer-uri`. The library then turns the MCP
endpoint into an **OAuth2 resource server**:

- Incoming JWT bearer tokens are validated against the authorization server.
- The standard discovery metadata is served unauthenticated:
  `/.well-known/oauth-authorization-server` and, per
  [RFC 9728](https://www.rfc-editor.org/rfc/rfc9728), the Protected Resource Metadata
  under `/.well-known/oauth-protected-resource{mcp-endpoint}`.

> **`issuer-uri` is required.** If Spring Security is on the classpath but `issuer-uri`
> is not set, the application fails to start (this prevents Spring Boot's default HTTP
> Basic login with a generated password). To leave the endpoint unprotected, remove
> Spring Security from the classpath instead.

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

#### Outgoing engine-rest authentication

Once the MCP server has validated the caller, it still has to tell engine-rest *who* the
call is for. This is handled by the pluggable `EngineRestAuthProvider` strategy (package
`org.cibseven.mcp.auth`), selected per deployment:

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

Deployment requirements for this mode (engine-rest side):

- **Activate OAuth2** in engine-rest; in a Spring Boot application add
  `cibseven-bpm-spring-boot-starter-security`, which already brings Spring Security.
- **Map the user id**: the engine takes the CIB seven user id from the claim configured
  in `spring.security.oauth2.resourceserver.jwt.principal-claim-name`. It must match the
  user id stored in the engine exactly; if no claim matches, use `minted-jwt`.
- **Disable the read-only OAuth2 identity provider** so group/authorization resolution
  stays with the (LDAP) identity provider:
  `camunda.bpm.oauth2.identity-provider.enabled=false`.
- **Validate the audience (`aud`)**, not only `iss` + signature (recommended). Without
  `aud` validation engine-rest would accept *any* validly signed token from the tenant,
  including tokens minted for unrelated applications.

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
  webclient:
    authentication:
      jwtSecret: ${CIBSEVEN_JWT_SECRET}
```

The CIB seven userId is produced by a `UserIdResolver`:

- **`claim`** (default) — reads `user-id-claim` (default `preferred_username`) directly.
  Correct **only** if that claim equals the stored LDAP userId byte- and case-exactly.
  Verify with `SELECT DISTINCT user_id_ FROM act_ru_authorization` before relying on it.
- **`graph`** — resolves the immutable Entra `oid` to the on-premises `sAMAccountName`
  via Microsoft Graph (cached, 8 h). Use this when no Entra claim matches the stored
  userId 1:1 (the common LDAP-prod case). Requires a `graph` OAuth2 **client
  registration** (`client_credentials`, Application permission `User.Read.All`,
  admin-consented) and `spring-boot-starter-oauth2-client` on the classpath; the
  required `OAuth2AuthorizedClientManager` is provided automatically when
  `resolver=graph`:

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
fallback. For other content the part is named after the field without an extension,
which downstream tools (Cockpit, Modeler) may not be able to open — MCP clients should
always send a `filename` with the correct extension (e.g. `invoice.bpmn`). See
[SKILL.md](SKILL.md) for the client-side guidance, which can be installed as a skill in
MCP clients that support skills.

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
