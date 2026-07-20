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

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Resolves the validated external identity (Entra access token) to the exact
 * {@code userId} string under which permissions, task assignments and history are
 * stored in the CIB seven database.
 *
 * <p>This is the single point where the deployment-specific identity mapping lives,
 * and the one part that must be verified empirically against the target system:
 * the resolved string has to match {@code ACT_RU_AUTHORIZATION.USER_ID_} (and the
 * attribute the LDAP identity provider exposes as its user id) byte-for-byte,
 * including case.</p>
 *
 * <p>The default {@link ClaimUserIdResolver} reads a single configurable claim. When
 * no Entra claim equals the stored LDAP userId (the common case), provide an
 * alternative implementation that resolves the immutable {@code oid} via Microsoft
 * Graph or a directory lookup, and cache the result.</p>
 */
public interface UserIdResolver {

    /**
     * @param entraToken the validated inbound Entra access token
     * @return the CIB seven userId; never {@code null} or blank — implementations
     *         throw if no stable mapping can be produced rather than minting a token
     *         for an empty subject
     */
    String resolve(Jwt entraToken);
}
