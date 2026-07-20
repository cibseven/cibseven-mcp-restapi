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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Base64;

import javax.crypto.SecretKey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import org.junit.jupiter.api.Test;

/**
 * Pins the engine-rest JWT contract (see the CIB7-1592 investigation): HS512 for the
 * CIB seven secret length, numeric iat/exp, and — critically — the {@code user} claim as
 * a JSON <em>string</em> {@code {"userID":"<id>"}}, not a nested object.
 */
class CibSevenJwtMinterTest {

    /** 64 zero bytes, Base64-encoded — long enough that JJWT selects HS512. */
    private static final String SECRET_64_BYTES = Base64.getEncoder().encodeToString(new byte[64]);
    private static final ObjectMapper JSON = new ObjectMapper();

    private Jws<Claims> decode(String token) {
        SecretKey key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(SECRET_64_BYTES));
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
    }

    @Test
    void mintsHs512SignedToken() {
        CibSevenJwtMinter minter = new CibSevenJwtMinter(SECRET_64_BYTES, 60);
        Jws<Claims> jws = decode(minter.mint("OlegSk"));
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("HS512");
    }

    @Test
    void userClaimIsAJsonStringNotANestedObject() throws Exception {
        CibSevenJwtMinter minter = new CibSevenJwtMinter(SECRET_64_BYTES, 60);
        Claims claims = decode(minter.mint("OlegSk")).getPayload();

        Object userClaim = claims.get("user");
        assertThat(userClaim)
                .as("engine-rest deserializes 'user' as a string containing JSON")
                .isInstanceOf(String.class);

        JsonNode parsed = JSON.readTree((String) userClaim);
        assertThat(parsed.isObject()).isTrue();
        assertThat(parsed.path("userID").asText()).isEqualTo("OlegSk");
        assertThat(parsed.size()).isEqualTo(1);
    }

    @Test
    void iatAndExpAreNumericAndTtlApart() {
        long ttlSeconds = 60;
        CibSevenJwtMinter minter = new CibSevenJwtMinter(SECRET_64_BYTES, ttlSeconds);
        Claims claims = decode(minter.mint("OlegSk")).getPayload();

        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
        long iat = claims.getIssuedAt().toInstant().getEpochSecond();
        long exp = claims.getExpiration().toInstant().getEpochSecond();
        assertThat(exp - iat).isEqualTo(ttlSeconds);
    }

    @Test
    void escapesSpecialCharactersInUserId() throws Exception {
        CibSevenJwtMinter minter = new CibSevenJwtMinter(SECRET_64_BYTES, 60);
        String trickyUserId = "we\"ird\\user";
        Claims claims = decode(minter.mint(trickyUserId)).getPayload();

        JsonNode parsed = JSON.readTree(claims.get("user", String.class));
        assertThat(parsed.path("userID").asText()).isEqualTo(trickyUserId);
    }

    @Test
    void refusesBlankUserId() {
        CibSevenJwtMinter minter = new CibSevenJwtMinter(SECRET_64_BYTES, 60);
        assertThatIllegalArgumentException().isThrownBy(() -> minter.mint(" "));
        assertThatIllegalArgumentException().isThrownBy(() -> minter.mint(null));
    }

    @Test
    void rejectsMissingSecret() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new CibSevenJwtMinter(" ", 60))
                .withMessageContaining("jwtSecret");
    }

    @Test
    void rejectsNonBase64Secret() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new CibSevenJwtMinter("not-base64!!!", 60))
                .withMessageContaining("Base64");
    }

    @Test
    void rejectsTooShortSecret() {
        String shortSecret = Base64.getEncoder().encodeToString(new byte[8]);
        assertThatIllegalStateException()
                .isThrownBy(() -> new CibSevenJwtMinter(shortSecret, 60))
                .withMessageContaining("too weak");
    }
}
