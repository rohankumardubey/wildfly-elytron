/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2020 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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

package org.wildfly.security.http.oidc;

import java.security.PublicKey;

/**
 * A hardcoded public key locator.
 *
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 * @author <a href="mailto:fjuma@redhat.com">Farah Juma</a>
 */
class HardcodedPublicKeyLocator implements PublicKeyLocator {

    private PublicKey publicKey;

    public HardcodedPublicKeyLocator(PublicKey publicKey) {
        this.publicKey = publicKey;
    }

    @Override
    public PublicKey getPublicKey(String kid, OidcClientConfiguration oidcClientConfiguration) {
        return publicKey;
    }

    @Override
    public void reset(OidcClientConfiguration oidcClientConfiguration) {
    }

}
