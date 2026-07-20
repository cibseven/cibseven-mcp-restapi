/*
 * Copyright CIB software GmbH and/or licensed to CIB software GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. CIB software licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.cibseven.mcp.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JacksonException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Mints a short-lived CIB seven JWT in exactly the format that engine-rest's
 * {@code JwtTokenAuthenticationProvider} expects, so that engine-rest stays on its
 * existing HMAC/composite filter and the (LDAP) identity provider remains authoritative
 * for group and authorization resolution.
 *
 * <p>Token contract reproduced from
 * {@code org.cibseven.bpm.engine.rest.security.auth.impl.JwtTokenAuthenticationProvider}
 * and {@code JwtUser}:</p>
 * <ul>
 *   <li>HMAC-signed (JWS) with {@code Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret))},
 *       the same {@code cibseven.webclient.authentication.jwtSecret} engine-rest reads;</li>
 *   <li>a claim named {@code "user"} whose value is a JSON <i>string</i>
 *       {@code {"userID":"<id>"}} — note: a serialized string, not a nested object;</li>
 *   <li>verification checks signature and {@code exp} only (no {@code iss}/{@code aud}),
 *       hence the secret is the entire trust boundary — keep it in a secret store.</li>
 * </ul>
 *
 * <p>Because the engine extracts only {@code getUserID()}, the minted token carries
 * nothing else; the very short TTL keeps the impersonation-capable token from being
 * replayable beyond the request it serves.</p>
 */
@Component
@ConditionalOnProperty(name = "cibseven.mcp.engine-rest.auth", havingValue = "minted-jwt")
public class CibSevenJwtMinter {

    private static final Logger logger = LoggerFactory.getLogger(CibSevenJwtMinter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SecretKey key;
    private final Duration ttl;

    public CibSevenJwtMinter(
            @Value("${cibseven.webclient.authentication.jwtSecret}") String jwtSecret,
            @Value("${cibseven.mcp.engine-rest.minted-jwt.ttl-seconds:60}") long ttlSeconds) {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                "cibseven.webclient.authentication.jwtSecret must be set to mint engine-rest tokens");
        }
        // Mirrors JwtTokenAuthenticationProvider.parse(): the property is Base64, decoded
        // to raw key bytes. Same value must be configured on engine-rest.
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(jwtSecret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "cibseven.webclient.authentication.jwtSecret is not valid Base64. "
              + "It must contain the Base64-encoded HMAC key shared with engine-rest.", e);
        }
        try {
            // JJWT selects the HMAC algorithm from the key strength: >=64 raw bytes -> HS512
            // (the CIB seven webclient default), >=48 -> HS384, >=32 -> HS256.
            this.key = Keys.hmacShaKeyFor(keyBytes);
        } catch (WeakKeyException e) {
            throw new IllegalStateException(
                "cibseven.webclient.authentication.jwtSecret decodes to fewer than 32 bytes, "
              + "which is too weak for HMAC JWT signing (RFC 7518). Configure the same "
              + "sufficiently long secret that engine-rest uses.", e);
        }
        this.ttl = Duration.ofSeconds(ttlSeconds);
        logger.debug("CibSevenJwtMinter initialised, ttlSeconds={}", ttlSeconds);
    }

    /**
     * @param userId the resolved CIB seven userId (subject); must be non-blank
     * @return a compact, signed, short-lived JWT ready for the {@code Authorization: Bearer} header
     */
    public String mint(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Refusing to mint a CIB seven JWT for a blank userId");
        }
        String userClaim = serializeUser(userId);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        String token = Jwts.builder()
                .claim("user", userClaim)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        // Never log the token itself — it is a bearer credential for engine-rest.
        logger.debug("Minted engine-rest JWT for userId={}, expiresAt={}", userId, expiresAt);
        return token;
    }

    private String serializeUser(String userId) {
        try {
            // Produces {"userID":"<userId>"} — matches JwtUser's single field and round-trips
            // through its Jackson deserializer without depending on the engine-rest class.
            return MAPPER.writeValueAsString(Map.of("userID", userId));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize CIB seven user claim", e);
        }
    }
}
