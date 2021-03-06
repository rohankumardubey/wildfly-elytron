/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.security.sasl.entity;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.wildfly.security.sasl.test.SaslTestUtil.assertMechanisms;
import static org.wildfly.security.sasl.test.SaslTestUtil.obtainSaslClientFactory;
import static org.wildfly.security.sasl.test.SaslTestUtil.obtainSaslServerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.x500.X500Principal;
import javax.security.sasl.Sasl;
import javax.security.sasl.SaslClient;
import javax.security.sasl.SaslClientFactory;
import javax.security.sasl.SaslException;
import javax.security.sasl.SaslServer;
import javax.security.sasl.SaslServerFactory;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.common.iteration.CodePointIterator;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.security.auth.client.AuthenticationContext;
import org.wildfly.security.auth.client.ClientUtils;
import org.wildfly.security.auth.client.MatchRule;
import org.wildfly.security.auth.realm.KeyStoreBackedSecurityRealm;
import org.wildfly.security.credential.X509CertificateChainPrivateCredential;
import org.wildfly.security.sasl.SaslMechanismSelector;
import org.wildfly.security.sasl.test.SaslServerBuilder;
import org.wildfly.security.sasl.util.SaslMechanismInformation;
import org.wildfly.security.x500.cert.BasicConstraintsExtension;
import org.wildfly.security.x500.cert.SelfSignedX509CertificateAndSigningKey;
import org.wildfly.security.x500.cert.X509CertificateBuilder;

import mockit.Mock;
import mockit.MockUp;
import mockit.integration.junit4.JMockit;

/**
 * Client and server side tests for the ISO/IEC 9798-3 authentication SASL mechanism.
 *
 * @author <a href="mailto:fjuma@redhat.com">Farah Juma</a>
 */
//has dependency on x500-cert
@RunWith(JMockit.class)
public class EntityTest {

    private static final String CLIENT_KEYSTORE_ALIAS = "testclient1";
    private static final String KEYSTORE_TYPE = "JKS";
    private static final char[] KEYSTORE_PASSWORD = "password".toCharArray();
    private static final Provider provider = WildFlyElytronSaslEntityProvider.getInstance();
    private KeyStore serverKeyStore = null;
    private KeyStore clientKeyStore = null;
    private KeyStore serverTrustStore = null;
    private KeyStore clientTrustStore = null;

    @BeforeClass
    public static void registerProvider() {
        Security.insertProviderAt(provider, 1);
    }

    @AfterClass
    public static void removeProvider() {
        Security.removeProvider(provider.getName());
    }

    private void createClientKeyStoreServerTrustStore(KeyStore clientKeyStore, KeyStore serverTrustStore) throws Exception {
        // Generate testclient2.example.com self signed certificate
        X500Principal DN = new X500Principal("CN=testclient2.example.com, OU=JBoss, O=Red Hat, L=Raleigh, ST=North Carolina, C=US");
        SelfSignedX509CertificateAndSigningKey selfSignedX509CertificateAndSigningKey = SelfSignedX509CertificateAndSigningKey.builder()
                .setKeyAlgorithmName("DSA")
                .setSignatureAlgorithmName("SHA1withDSA")
                .setDn(DN)
                .setKeySize(1024)
                .build();
        X509Certificate certificate = selfSignedX509CertificateAndSigningKey.getSelfSignedCertificate();
        clientKeyStore.setKeyEntry("dnsincnclient", selfSignedX509CertificateAndSigningKey.getSigningKey(), KEYSTORE_PASSWORD, new X509Certificate[]{certificate});
        serverTrustStore.setCertificateEntry("cn=testclient2.example.com,ou=jboss,o=red hat,l=raleigh,st=north carolina,c=us", certificate);


        // Generate Test Authority self signed certificate
        final X500Principal CA_DN = new X500Principal("CN=Test Authority, OU=JBoss, O=Red Hat, L=Raleigh, ST=North Carolina, C=US");
        selfSignedX509CertificateAndSigningKey = SelfSignedX509CertificateAndSigningKey.builder()
                .setDn(CA_DN)
                .setKeyAlgorithmName("RSA")
                .setSignatureAlgorithmName("SHA256withRSA")
                .addExtension(new BasicConstraintsExtension(false, true, -1))
                .build();
        final X509Certificate caCertificate = selfSignedX509CertificateAndSigningKey.getSelfSignedCertificate();
        final PrivateKey caKey = selfSignedX509CertificateAndSigningKey.getSigningKey();

        // Generate Test Client 1 self signed certificate
        DN = new X500Principal("CN=Test Client 1, OU=JBoss, O=Red Hat, L=Raleigh, ST=North Carolina, C=US");
        selfSignedX509CertificateAndSigningKey = SelfSignedX509CertificateAndSigningKey.builder()
                .setDn(DN)
                .setKeyAlgorithmName("RSA")
                .setSignatureAlgorithmName("SHA256withRSA")
                .addExtension(false, "SubjectAlternativeName", "DNS:testclient1.example.com")
                .build();
        certificate = selfSignedX509CertificateAndSigningKey.getSelfSignedCertificate();
        clientKeyStore.setKeyEntry("testclient1", selfSignedX509CertificateAndSigningKey.getSigningKey(), KEYSTORE_PASSWORD, new X509Certificate[]{certificate});
        serverTrustStore.setCertificateEntry("cn=test client 1,ou=jboss,o=red hat,l=raleigh,st=north carolina,c=us", certificate);


        // Generate Signed Test Client certificate signed by Test Authority
        X500Principal subjectDN = new X500Principal("CN=Signed Test Client, OU=JBoss, O=Red Hat, ST=North Carolina, C=US");

        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        KeyPair generatedKeys = keyPairGenerator.generateKeyPair();
        PrivateKey privateKey = generatedKeys.getPrivate();
        PublicKey publicKey = generatedKeys.getPublic();

        /*
         * The CA certificate is added using the alias of the client it is tested with, this is not really how the trust store should
         * be populated but as the test is also relying on the truststore to back the realm it needs to find an entry for the client
         * and we do not want to add the clients actual certificate as the test is testing CA signed certs.
         */
        serverTrustStore.setCertificateEntry("cn=signed test client,ou=jboss,o=red hat,st=north carolina,c=us", caCertificate);

        certificate = new X509CertificateBuilder()
                .setIssuerDn(CA_DN)
                .setSubjectDn(subjectDN)
                .setSignatureAlgorithmName("SHA256withRSA")
                .setSigningKey(caKey)
                .setPublicKey(publicKey)
                .build();
        clientKeyStore.setKeyEntry("testclientsignedbyca", privateKey, KEYSTORE_PASSWORD, new X509Certificate[]{certificate});
    }

    private void createServerKeyStoreClientTrustStore(KeyStore serverKeyStore,KeyStore clientTrustStore) throws Exception {
        // Generate testserver2.example.com self signed certificate
        X500Principal DN = new X500Principal("CN=testserver2.example.com, OU=JBoss, O=Red Hat, L=Raleigh, ST=North Carolina, C=US");
        SelfSignedX509CertificateAndSigningKey selfSignedX509CertificateAndSigningKey = SelfSignedX509CertificateAndSigningKey.builder()
                .setKeyAlgorithmName("DSA")
                .setSignatureAlgorithmName("SHA1withDSA")
                .setDn(DN)
                .setKeySize(1024)
                .build();
        X509Certificate certificate = selfSignedX509CertificateAndSigningKey.getSelfSignedCertificate();
        serverKeyStore.setKeyEntry("dnsincnserver", selfSignedX509CertificateAndSigningKey.getSigningKey(), KEYSTORE_PASSWORD, new X509Certificate[]{certificate});
        clientTrustStore.setCertificateEntry("dnsincnserver", certificate);


        // Generate Test Server 1 self signed certificate
        DN = new X500Principal("CN=Test Server 1, OU=JBoss, O=Red Hat, L=Raleigh, ST=North Carolina, C=US");
        selfSignedX509CertificateAndSigningKey = SelfSignedX509CertificateAndSigningKey.builder()
                .setDn(DN)
                .setKeyAlgorithmName("RSA")
                .setSignatureAlgorithmName("SHA256withRSA")
                .addExtension(false, "SubjectAlternativeName", "DNS:testserver1.example.com")
                .build();
        certificate = selfSignedX509CertificateAndSigningKey.getSelfSignedCertificate();
        serverKeyStore.setKeyEntry("testserver1", selfSignedX509CertificateAndSigningKey.getSigningKey(), KEYSTORE_PASSWORD, new X509Certificate[]{certificate});
        clientTrustStore.setCertificateEntry("testserver1", certificate);
    }

    @Before
    public void beforeTest() throws Exception {
        clientKeyStore = KeyStore.getInstance("JKS");
        clientKeyStore.load(null, null);
        serverTrustStore = KeyStore.getInstance("JKS");
        serverTrustStore.load(null, null);
        serverKeyStore = KeyStore.getInstance("JKS");
        serverKeyStore.load(null, null);
        clientTrustStore = KeyStore.getInstance("JKS");
        clientTrustStore.load(null, null);
        createClientKeyStoreServerTrustStore(clientKeyStore, serverTrustStore);
        createServerKeyStoreClientTrustStore(serverKeyStore, clientTrustStore);
    }

    @After
    public void afterTest() {
        serverKeyStore = null;
        clientKeyStore = null;
        serverTrustStore = null;
        clientTrustStore = null;
    }

    @Test
    public void testServerAuthIndirect_Server() throws Exception {
        Map<String, Object> props = new HashMap<String, Object>();

        // No properties are set, an appropriate EntitySaslServer should be returned
        SaslServer server = Sasl.createSaslServer(SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC, "TestProtocol", "TestServer", props, null);
        assertEquals(EntitySaslServer.class, server.getClass());
        assertEquals(SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC, server.getMechanismName());

        // If we set SERVER_AUTH to true even though a unilateral mechanism is specified, no server should be returned
        props.put(Sasl.SERVER_AUTH, Boolean.toString(true));
        server = Sasl.createSaslServer(SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC, "TestProtocol", "TestServer", props, null);
        assertNull(server);
    }

    @Test
    public void testServerAuthDirect_Server() {
        SaslServerFactory factory = obtainSaslServerFactory(EntitySaslServerFactory.class);
        assertNotNull("SaslServerFactory not registered", factory);

        String[] mechanisms;
        Map<String, Object> props = new HashMap<String, Object>();

        // No properties set
        mechanisms = factory.getMechanismNames(props);
        assertMechanisms(new String[]{
            SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC, SaslMechanismInformation.Names.IEC_ISO_9798_M_RSA_SHA1_ENC, SaslMechanismInformation.Names.IEC_ISO_9798_U_DSA_SHA1,
            SaslMechanismInformation.Names.IEC_ISO_9798_M_DSA_SHA1, SaslMechanismInformation.Names.IEC_ISO_9798_U_ECDSA_SHA1, SaslMechanismInformation.Names.IEC_ISO_9798_M_ECDSA_SHA1
        }, mechanisms);

        // Request server auth
        props.put(Sasl.SERVER_AUTH, Boolean.toString(true));
        mechanisms = factory.getMechanismNames(props);
        assertMechanisms(new String[]{ SaslMechanismInformation.Names.IEC_ISO_9798_M_RSA_SHA1_ENC, SaslMechanismInformation.Names.IEC_ISO_9798_M_DSA_SHA1, SaslMechanismInformation.Names.IEC_ISO_9798_M_ECDSA_SHA1 }, mechanisms);
    }

    @Test
    public void testServerAuthIndirect_Client() throws Exception {
        Map<String, Object> props = new HashMap<String, Object>();

        // No properties are set, an appropriate EntitySaslClient should be returned
        SaslClient client = Sasl.createSaslClient(new String[]{ SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC }, "TestUser", "TestProtocol", "TestServer", props, null);
        assertEquals(EntitySaslClient.class, client.getClass());
        assertEquals(SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC, client.getMechanismName());

        // If we set SERVER_AUTH to true even though only unilateral mechanisms are specified, no client should be returned
        props.put(Sasl.SERVER_AUTH, Boolean.toString(true));
        client = Sasl.createSaslClient(new String[]{ SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC, SaslMechanismInformation.Names.IEC_ISO_9798_U_DSA_SHA1, SaslMechanismInformation.Names.IEC_ISO_9798_U_ECDSA_SHA1 },
                "TestUser", "TestProtocol", "TestServer", props, null);
        assertNull(client);

        // If we set SERVER_AUTH to true, an appropriate EntitySaslClient should be returned
        props.put(Sasl.SERVER_AUTH, Boolean.toString(true));
        client = Sasl.createSaslClient(new String[]{
                SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC, SaslMechanismInformation.Names.IEC_ISO_9798_U_DSA_SHA1,
                SaslMechanismInformation.Names.IEC_ISO_9798_U_ECDSA_SHA1, SaslMechanismInformation.Names.IEC_ISO_9798_M_RSA_SHA1_ENC, SaslMechanismInformation.Names.IEC_ISO_9798_M_DSA_SHA1, SaslMechanismInformation.Names.IEC_ISO_9798_M_ECDSA_SHA1
            },
                "TestUser", "TestProtocol", "TestServer", props, null);
        assertEquals(EntitySaslClient.class, client.getClass());
        assertEquals(SaslMechanismInformation.Names.IEC_ISO_9798_M_RSA_SHA1_ENC, client.getMechanismName());
    }

    @Test
    public void testServerAuthDirect_Client() {
        SaslClientFactory factory = obtainSaslClientFactory(EntitySaslClientFactory.class);
        assertNotNull("SaslClientFactory not registered", factory);

        String[] mechanisms;
        Map<String, Object> props = new HashMap<String, Object>();

        // No properties set
        mechanisms = factory.getMechanismNames(props);
        assertMechanisms(new String[]{
            SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC, SaslMechanismInformation.Names.IEC_ISO_9798_M_RSA_SHA1_ENC, SaslMechanismInformation.Names.IEC_ISO_9798_U_DSA_SHA1,
            SaslMechanismInformation.Names.IEC_ISO_9798_M_DSA_SHA1, SaslMechanismInformation.Names.IEC_ISO_9798_U_ECDSA_SHA1, SaslMechanismInformation.Names.IEC_ISO_9798_M_ECDSA_SHA1
        }, mechanisms);

        // Request server auth
        props.put(Sasl.SERVER_AUTH, Boolean.toString(true));
        mechanisms = factory.getMechanismNames(props);
        assertMechanisms(new String[]{ SaslMechanismInformation.Names.IEC_ISO_9798_M_RSA_SHA1_ENC, SaslMechanismInformation.Names.IEC_ISO_9798_M_DSA_SHA1, SaslMechanismInformation.Names.IEC_ISO_9798_M_ECDSA_SHA1 }, mechanisms);
    }

    // -- Successful authentication exchanges --

    @Test
    public void testSimpleUnilateralSha1WithRsaAuthentication() throws Exception {
        final SaslClientFactory clientFactory = obtainSaslClientFactory(EntitySaslClientFactory.class);
        assertNotNull(clientFactory);

        final SaslServer saslServer = createSaslServer(SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC, "testserver1.example.com",
                getX509KeyManager(serverKeyStore, KEYSTORE_PASSWORD), serverTrustStore);
        assertNotNull(saslServer);
        assertFalse(saslServer.isComplete());

        final String[] mechanisms = new String[] { SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC };
        CallbackHandler cbh = createClientCallbackHandler(mechanisms, clientKeyStore, CLIENT_KEYSTORE_ALIAS, KEYSTORE_PASSWORD, null);
        final SaslClient saslClient = clientFactory.createSaslClient(mechanisms, null, "test", "testserver1.example.com",
                Collections.<String, Object>emptyMap(), cbh);
        assertNotNull(saslClient);
        assertTrue(saslClient instanceof EntitySaslClient);
        assertFalse(saslClient.hasInitialResponse());
        assertFalse(saslClient.isComplete());

        byte[] message = saslServer.evaluateResponse(new byte[0]);
        assertFalse(saslServer.isComplete());
        assertFalse(saslClient.isComplete());

        message = saslClient.evaluateChallenge(message);
        assertFalse(saslServer.isComplete());
        assertFalse(saslClient.isComplete());

        message = saslServer.evaluateResponse(message);
        assertTrue(saslServer.isComplete());
        assertNull(message);
        assertNull(saslClient.evaluateChallenge(message));
        assertTrue(saslClient.isComplete());
        assertEquals("cn=test client 1,ou=jboss,o=red hat,l=raleigh,st=north carolina,c=us", saslServer.getAuthorizationID());
    }

    @Test
    public void testUnilateralSha1WithRsaAuthenticationWithTrustedAuthorities() throws Exception {
        final SaslClientFactory clientFactory = obtainSaslClientFactory(EntitySaslClientFactory.class);
        assertNotNull(clientFactory);

        final SaslServer saslServer = createSaslServer(SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC, "testserver1.example.com",
                getX509KeyManager(serverKeyStore, KEYSTORE_PASSWORD), serverTrustStore);
        assertNotNull(saslServer);
        assertFalse(saslServer.isComplete());

        final String[] mechanisms = new String[] { SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC };
        CallbackHandler cbh = createClientCallbackHandler(mechanisms, getX509KeyManager(clientKeyStore, KEYSTORE_PASSWORD), null);
        final SaslClient saslClient = clientFactory.createSaslClient(mechanisms, null, "test", "testserver1.example.com",
                Collections.<String, Object>emptyMap(), cbh);
        assertNotNull(saslClient);
        assertTrue(saslClient instanceof EntitySaslClient);
        assertFalse(saslClient.hasInitialResponse());
        assertFalse(saslClient.isComplete());

        byte[] message = saslServer.evaluateResponse(new byte[0]);
        assertFalse(saslServer.isComplete());
        assertFalse(saslClient.isComplete());

        message = saslClient.evaluateChallenge(message);
        assertFalse(saslServer.isComplete());
        assertFalse(saslClient.isComplete());

        message = saslServer.evaluateResponse(message);
        assertTrue(saslServer.isComplete());
        assertNull(message);
        assertNull(saslClient.evaluateChallenge(message));
        assertTrue(saslClient.isComplete());
        assertEquals("cn=signed test client,ou=jboss,o=red hat,st=north carolina,c=us", saslServer.getAuthorizationID());
    }

    @Test
    public void testUnilateralSha1WithRsaAuthenticationWithAuthorizationId() throws Exception {
        final SaslClientFactory clientFactory = obtainSaslClientFactory(EntitySaslClientFactory.class);
        assertNotNull(clientFactory);

        final SaslServer saslServer = createSaslServer(SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC, "testserver1.example.com",
                getX509KeyManager(serverKeyStore, KEYSTORE_PASSWORD), serverTrustStore);

        final String[] mechanisms = new String[] { SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC };
        CallbackHandler cbh = createClientCallbackHandler(mechanisms, clientKeyStore, CLIENT_KEYSTORE_ALIAS, KEYSTORE_PASSWORD, null);
        final SaslClient saslClient = clientFactory.createSaslClient(mechanisms, "cn=test client 1,ou=jboss,o=red hat,l=raleigh,st=north carolina,c=us", "test", "testserver1.example.com",
                Collections.<String, Object>emptyMap(), cbh);
        assertFalse(saslServer.isComplete());
        assertFalse(saslClient.isComplete());

        byte[] message = saslServer.evaluateResponse(new byte[0]);
        assertFalse(saslServer.isComplete());
        assertFalse(saslClient.isComplete());

        message = saslClient.evaluateChallenge(message);
        assertFalse(saslServer.isComplete());
        assertFalse(saslClient.isComplete());

        message = saslServer.evaluateResponse(message);
        assertTrue(saslServer.isComplete());
        assertNull(message);
        assertNull(saslClient.evaluateChallenge(message));
        assertTrue(saslClient.isComplete());
        assertEquals("cn=test client 1,ou=jboss,o=red hat,l=raleigh,st=north carolina,c=us", saslServer.getAuthorizationID());
    }

    @Test
    public void testSimpleMutualSha1WithRsaAuthentication() throws Exception {
        final SaslClientFactory clientFactory = obtainSaslClientFactory(EntitySaslClientFactory.class);
        assertNotNull(clientFactory);

        final SaslServer saslServer = createSaslServer(SaslMechanismInformation.Names.IEC_ISO_9798_M_RSA_SHA1_ENC, "testserver1.example.com",
                getX509KeyManager(serverKeyStore, KEYSTORE_PASSWORD), serverTrustStore);

        final String[] mechanisms = new String[] { SaslMechanismInformation.Names.IEC_ISO_9798_M_RSA_SHA1_ENC };
        CallbackHandler cbh = createClientCallbackHandler(mechanisms, clientKeyStore, CLIENT_KEYSTORE_ALIAS, KEYSTORE_PASSWORD, getX509TrustManager(clientTrustStore));
        final SaslClient saslClient = clientFactory.createSaslClient(mechanisms, null, "test", "testserver1.example.com", Collections.<String, Object>emptyMap(), cbh);
        assertFalse(saslServer.isComplete());
        assertFalse(saslClient.isComplete());

        byte[] message = saslServer.evaluateResponse(new byte[0]);
        assertFalse(saslServer.isComplete());
        assertFalse(saslClient.isComplete());

        message = saslClient.evaluateChallenge(message);
        assertFalse(saslServer.isComplete());
        assertFalse(saslClient.isComplete());

        message = saslServer.evaluateResponse(message);
        assertNotNull(message);
        message = saslClient.evaluateChallenge(message);
        assertNull(message);
        assertTrue(saslClient.isComplete());
        assertTrue(saslServer.isComplete());
        assertEquals("cn=test client 1,ou=jboss,o=red hat,l=raleigh,st=north carolina,c=us", saslServer.getAuthorizationID());
    }

    @Test
    public void testMutualAuthenticationWithDNSInCNField() throws Exception {
        // Although specifying a DNS name using the Common Name field has been deprecated, it is
        // still used in practice (e.g., see http://tools.ietf.org/html/rfc2818). This test makes
        // sure that general name matching during authentication still works in this case.
        final SaslClientFactory clientFactory = obtainSaslClientFactory(EntitySaslClientFactory.class);
        assertNotNull(clientFactory);

        final KeyStore keyStore = serverKeyStore;
        final Certificate[] certificateChain = keyStore.getCertificateChain("dnsInCNServer");
        final SaslServer saslServer = createSaslServer(SaslMechanismInformation.Names.IEC_ISO_9798_M_DSA_SHA1, "testserver2.example.com",
                serverTrustStore, (PrivateKey) keyStore.getKey("dnsInCNServer", KEYSTORE_PASSWORD),
                Arrays.copyOf(certificateChain, certificateChain.length, X509Certificate[].class));

        final String[] mechanisms = new String[] { SaslMechanismInformation.Names.IEC_ISO_9798_M_DSA_SHA1 };
        CallbackHandler cbh = createClientCallbackHandler(mechanisms, clientKeyStore, "dnsInCNClient", KEYSTORE_PASSWORD, getX509TrustManager(clientTrustStore));
        final SaslClient saslClient = clientFactory.createSaslClient(mechanisms, null, "test", "testserver2.example.com",
                Collections.<String, Object>emptyMap(), cbh);
        assertFalse(saslServer.isComplete());
        assertFalse(saslClient.isComplete());

        byte[] message = saslServer.evaluateResponse(new byte[0]);
        assertFalse(saslServer.isComplete());
        assertFalse(saslClient.isComplete());

        message = saslClient.evaluateChallenge(message);
        assertFalse(saslServer.isComplete());
        assertFalse(saslClient.isComplete());

        message = saslServer.evaluateResponse(message);
        assertNotNull(message);

        message = saslClient.evaluateChallenge(message);
        assertNull(message);
        assertTrue(saslClient.isComplete());
        assertTrue(saslServer.isComplete());
        assertEquals("cn=testclient2.example.com,ou=jboss,o=red hat,l=raleigh,st=north carolina,c=us", saslServer.getAuthorizationID());
    }

    // -- Unsuccessful authentication exchanges --

    @Test
    public void testServerNameMismatch() throws Exception {
        final SaslClientFactory clientFactory = obtainSaslClientFactory(EntitySaslClientFactory.class);
        assertNotNull(clientFactory);

        // The server name specified by the client doesn't match the server's actual name
        final SaslServer saslServer = createSaslServer(SaslMechanismInformation.Names.IEC_ISO_9798_M_RSA_SHA1_ENC, "testserver1.example.com",
                getX509KeyManager(serverKeyStore, KEYSTORE_PASSWORD), serverTrustStore);

        final String[] mechanisms = new String[] { SaslMechanismInformation.Names.IEC_ISO_9798_M_RSA_SHA1_ENC };
        CallbackHandler cbh = createClientCallbackHandler(mechanisms, clientKeyStore, CLIENT_KEYSTORE_ALIAS, KEYSTORE_PASSWORD, getX509TrustManager(clientTrustStore));
        final SaslClient saslClient = clientFactory.createSaslClient(mechanisms, null, "test", "anotherserver.example.com",
                Collections.<String, Object>emptyMap(), cbh);

        byte[] message = saslServer.evaluateResponse(new byte[0]);
        try {
            saslClient.evaluateChallenge(message);
            fail("Expected SaslException not thrown");
        } catch (SaslException expected) {
        }
    }

    @Test
    public void testClientNotTrustedByServer() throws Exception {
        final SaslClientFactory clientFactory = obtainSaslClientFactory(EntitySaslClientFactory.class);
        assertNotNull(clientFactory);

        final SaslServer saslServer = createSaslServer(SaslMechanismInformation.Names.IEC_ISO_9798_M_RSA_SHA1_ENC, "testserver1.example.com",
                getX509KeyManager(serverKeyStore, KEYSTORE_PASSWORD), KeyStore.getInstance(KEYSTORE_TYPE));

        final String[] mechanisms = new String[] { SaslMechanismInformation.Names.IEC_ISO_9798_M_RSA_SHA1_ENC };
        CallbackHandler cbh = createClientCallbackHandler(mechanisms, clientKeyStore, CLIENT_KEYSTORE_ALIAS, KEYSTORE_PASSWORD, getX509TrustManager(clientTrustStore));
        final SaslClient saslClient = clientFactory.createSaslClient(mechanisms, null, "test", "testserver1.example.com",
                Collections.<String, Object>emptyMap(), cbh);

        byte[] message = saslServer.evaluateResponse(new byte[0]);
        message = saslClient.evaluateChallenge(message);
        try {
            saslServer.evaluateResponse(message);
            fail("Expected SaslException not thrown");
        } catch (SaslException expected) {
        }
    }

    @Test
    public void testServerNotTrustedByClient() throws Exception {
        final SaslClientFactory clientFactory = obtainSaslClientFactory(EntitySaslClientFactory.class);
        assertNotNull(clientFactory);

        final SaslServer saslServer = createSaslServer(SaslMechanismInformation.Names.IEC_ISO_9798_M_RSA_SHA1_ENC, "testserver1.example.com",
                getX509KeyManager(serverKeyStore, KEYSTORE_PASSWORD), serverTrustStore);

        final String[] mechanisms = new String[] { SaslMechanismInformation.Names.IEC_ISO_9798_M_RSA_SHA1_ENC };
        CallbackHandler cbh = createClientCallbackHandler(mechanisms, clientKeyStore, CLIENT_KEYSTORE_ALIAS, KEYSTORE_PASSWORD, null);
        final SaslClient saslClient = clientFactory.createSaslClient(mechanisms, null, "test", "testserver1.example.com",
                Collections.<String, Object>emptyMap(), cbh);

        byte[] message = saslServer.evaluateResponse(new byte[0]);
        message = saslClient.evaluateChallenge(message);
        message = saslServer.evaluateResponse(message);
        try {
            saslClient.evaluateChallenge(message);
            fail("Expected SaslException not thrown");
        } catch (SaslException expected) {
        }
    }

    @Test
    public void testRfc3163Example() throws Exception {
        // This test uses the example from page 10 in RFC 3163 (https://tools.ietf.org/html/rfc3163#section-5)
        mockRandom(new byte[]{18, 56, -105, 88, 121, -121, 71, -104});

        KeyStore emptyTrustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        emptyTrustStore.load(null, null);
        final SaslServer saslServer = createSaslServer(SaslMechanismInformation.Names.IEC_ISO_9798_U_RSA_SHA1_ENC, "",
                getX509KeyManager(serverKeyStore, KEYSTORE_PASSWORD), emptyTrustStore);
        assertNotNull(saslServer);
        assertFalse(saslServer.isComplete());

        byte[] tokenBA1 = saslServer.evaluateResponse(new byte[0]);
        byte[] expectedTokenBA1 = CodePointIterator.ofString("MAoECBI4l1h5h0eY").base64Decode().drain();
        assertArrayEquals(expectedTokenBA1, tokenBA1);
        assertFalse(saslServer.isComplete());

        byte[] tokenAB = CodePointIterator.ofString("MIIBAgQIIxh5I0h5RYegD4INc2FzbC1yLXVzLmNvbaFPFk1odHRwOi8vY2VydHMtci11cy5jb20vY2VydD9paD1odmNOQVFFRkJRQURnWUVBZ2hBR2hZVFJna0ZqJnNuPUVQOXVFbFkzS0RlZ2pscjCBkzANBgkqhkiG9w0BAQUFAAOBgQCkuC2GgtYcxGG1NEzLA4bh5lqJGOZySACMmc+mDrV7A7KAgbpO2OuZpMCl7zvNt/L3OjQZatiX8d1XbuQ40l+g2TJzJt06o7ogomxdDwqlA/3zp2WMohlI0MotHmfDSWEDZmEYDEA3/eGgkWyi1v1lEVdFuYmrTr8E4wE9hxdQrA==").base64Decode().drain();
        try {
            saslServer.evaluateResponse(tokenAB);
            fail("Expected SaslException not thrown");
        } catch (SaslException expected) {
            // The example specifies the client's certificate using a fake URL (http://certs-r-us.com/cert?ih=hvcNAQEFBQADgYEAghAGhYTRgkFj&sn=EP9uElY3KDegjlr)
            // We do not support certificate URL
            assertTrue(expected.getCause().getMessage().contains("Unexpected ASN.1 tag encountered"));
        }
        assertFalse(saslServer.isComplete());
    }

    private void safeClose(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {}
        }
    }

    private void mockRandom(final byte[] randomStr){
        new MockUp<EntityUtil>(){
            @Mock
            byte[] generateRandomString(int length, Random random){
                return randomStr;
            }
        };
    }

    private KeyStore loadKeyStore(File keyStore) throws IOException, GeneralSecurityException {
        if (keyStore == null) {
            return null;
        }
        KeyStore ks = KeyStore.getInstance(KEYSTORE_TYPE);
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(keyStore);
            ks.load(fis, KEYSTORE_PASSWORD);
        } finally {
            safeClose(fis);
        }
        return ks;
    }

    private SaslServer createSaslServer(final String mechanism, final String serverName, final X509KeyManager keyManager, final File trustStore) throws Exception {
        return createSaslServer(mechanism, serverName, keyManager, loadKeyStore(trustStore));
    }

    private SaslServer createSaslServer(final String mechanism, final String serverName, final X509KeyManager keyManager, final KeyStore trustStore) throws Exception {
        final String realmName = "keyStoreRealm";
        return new SaslServerBuilder(EntitySaslServerFactory.class, mechanism)
                .setProtocol("test")
                .setServerName(serverName)
                .addRealm(realmName, new KeyStoreBackedSecurityRealm(trustStore))
                .setDefaultRealmName(realmName)
                .setKeyManager(keyManager)
                .setTrustManager(getX509TrustManager(trustStore))
                .build();
    }

    private SaslServer createSaslServer(final String mechanism, final String serverName, final KeyStore trustStore, final PrivateKey privateKey,
                                        final X509Certificate... certificateChain) throws Exception {
        final String realmName = "keyStoreRealm";
        final KeyStore ts = trustStore;
        return new SaslServerBuilder(EntitySaslServerFactory.class, mechanism)
                .setProtocol("test")
                .setServerName(serverName)
                .addRealm(realmName, new KeyStoreBackedSecurityRealm(ts))
                .setDefaultRealmName(realmName)
                .setCredential(new X509CertificateChainPrivateCredential(privateKey, certificateChain))
                .setTrustManager(getX509TrustManager(ts))
                .build();
    }

    private CallbackHandler createClientCallbackHandler(final String[] mechanisms, final KeyStore keyStore, final String keyStoreAlias,
                                                        final char[] keyStorePassword, final X509TrustManager trustManager) throws Exception {
        final AuthenticationContext context = AuthenticationContext.empty()
                .with(
                        MatchRule.ALL,
                        AuthenticationConfiguration.empty()
                                .useKeyStoreCredential(keyStore, keyStoreAlias, new KeyStore.PasswordProtection(keyStorePassword))
                                .useTrustManager(trustManager)
                                .setSaslMechanismSelector(SaslMechanismSelector.NONE.addMechanisms(mechanisms)));


        return ClientUtils.getCallbackHandler(new URI("remote://localhost"), context);
    }

    private CallbackHandler createClientCallbackHandler(final String[] mechanisms, final X509KeyManager keyManager,
                                                        final X509TrustManager trustManager) throws Exception {
        final AuthenticationContext context = AuthenticationContext.empty()
                .with(
                        MatchRule.ALL,
                        AuthenticationConfiguration.empty()
                                .useKeyManagerCredential(keyManager)
                                .useTrustManager(trustManager)
                                .setSaslMechanismSelector(SaslMechanismSelector.NONE.addMechanisms(mechanisms)));


        return ClientUtils.getCallbackHandler(new URI("remote://localhost"), context);
    }

    private CallbackHandler createClientCallbackHandler(final String[] mechanisms, final PrivateKey privateKey, final X509Certificate[] certificateChain,
                                                        final X509TrustManager trustManager) throws Exception {
        final AuthenticationContext context = AuthenticationContext.empty()
                .with(
                        MatchRule.ALL,
                        AuthenticationConfiguration.empty()
                                .useCertificateCredential(privateKey, certificateChain)
                                .useTrustManager(trustManager)
                                .setSaslMechanismSelector(SaslMechanismSelector.NONE.addMechanisms(mechanisms)));


        return ClientUtils.getCallbackHandler(new URI("remote://localhost"), context);
    }

    private X509KeyManager getX509KeyManager(final KeyStore keyStore, final char[] keyStorePassword) throws GeneralSecurityException, IOException {
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, keyStorePassword);
        for (KeyManager keyManager : keyManagerFactory.getKeyManagers()) {
            if (keyManager instanceof X509KeyManager) {
                return (X509KeyManager) keyManager;
            }
        }
        return null;
    }

    private X509TrustManager getX509TrustManager(final File trustStore) throws GeneralSecurityException, IOException {
        return getX509TrustManager(loadKeyStore(trustStore));
    }

    private X509TrustManager getX509TrustManager(final KeyStore trustStore) throws GeneralSecurityException, IOException {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        for (TrustManager trustManager : trustManagerFactory.getTrustManagers()) {
            if (trustManager instanceof X509TrustManager) {
                return (X509TrustManager) trustManager;
            }
        }
        return null;
    }
}
