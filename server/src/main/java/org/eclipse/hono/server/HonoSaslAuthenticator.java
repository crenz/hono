/**
 * Copyright (c) 2016 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial creation
 */
package org.eclipse.hono.server;

import static org.eclipse.hono.authentication.AuthenticationConstants.*;

import java.security.Principal;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.security.cert.X509Certificate;

import org.apache.qpid.proton.engine.Sasl;
import org.apache.qpid.proton.engine.Sasl.SaslOutcome;
import org.apache.qpid.proton.engine.Transport;
import org.eclipse.hono.util.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetSocket;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.sasl.ProtonSaslAuthenticator;
import io.vertx.proton.sasl.impl.ProtonSaslExternalImpl;
import io.vertx.proton.sasl.impl.ProtonSaslPlainImpl;

/**
 * A SASL authenticator that supports the PLAIN and EXTERNAL mechanisms for authenticating clients.
 * <p>
 * On successful authentication a {@code java.security.Principal} reflecting the client's
 * <em>authorization id</em> is attached to the {@code ProtonConnection} under key
 * {@link Constants#KEY_CLIENT_PRINCIPAL}.
 * <p>
 * Verification of credentials provided using PLAIN SASL is delegated to the {@code AuthenticationService}.
 * Client certificate validation is done by Vert.x' {@code NetServer} during the TLS handshake,
 * so this class merely extracts the <em>Common Name</em> (CN) field value from the client certificate's
 * <em>Subject DN</em> and uses it as the authorization id.
 */
public final class HonoSaslAuthenticator implements ProtonSaslAuthenticator {

    private static final      Logger LOG = LoggerFactory.getLogger(HonoSaslAuthenticator.class);
    private static final      Pattern PATTERN_CN = Pattern.compile("^CN=(.+?)(?:,\\s*[A-Z]{1,2}=.+|$)");
    private final Vertx       vertx;
    private Sasl              sasl;
    private boolean           succeeded;
    private ProtonConnection  protonConnection;
    private X509Certificate[] peerCertificateChain;

    /**
     * Creates a new authenticator for a Vertx environment.
     * 
     * @param vertx The Vert.x instance to run on.
     */
    public HonoSaslAuthenticator(final Vertx vertx) {
        this.vertx = Objects.requireNonNull(vertx);
    }

    @Override
    public void init(final NetSocket socket, final ProtonConnection protonConnection, final Transport transport) {
        LOG.debug("initializing SASL authenticator");
        this.protonConnection = protonConnection;
        this.sasl = transport.sasl();
        // TODO determine supported mechanisms dynamically based on registered AuthenticationService implementations
        sasl.server();
        sasl.allowSkip(false);
        sasl.setMechanisms(ProtonSaslPlainImpl.MECH_NAME, ProtonSaslExternalImpl.MECH_NAME);
        if (socket.isSsl()) {
            LOG.debug("client connected using TLS, extracting client certificate chain");
            try {
                peerCertificateChain = socket.peerCertificateChain();
                LOG.debug("client identity is {}", peerCertificateChain[0].getSubjectDN());
            } catch (SSLPeerUnverifiedException e) {
                LOG.debug("could not extract client certificate chain, maybe client auth is not required");
            }
        }
    }

    @Override
    public void process(final Handler<Boolean> completionHandler) {

        String[] remoteMechanisms = sasl.getRemoteMechanisms();

        if (remoteMechanisms.length > 0) {
            String chosenMechanism = remoteMechanisms[0];
            LOG.debug("client wants to use {} SASL mechanism [host: {}, state: {}]",
                    chosenMechanism, sasl.getHostname(), sasl.getState().name());

            if (ProtonSaslPlainImpl.MECH_NAME.equals(chosenMechanism)) {
                evaluatePlainResponse(completionHandler);
            } else if (ProtonSaslExternalImpl.MECH_NAME.equals(chosenMechanism)) {
                evaluateExternalResponse(completionHandler);
            } else {
                LOG.info("client wants to use unsupported {} SASL mechanism [host: {}, state: {}]",
                        chosenMechanism, sasl.getHostname(), sasl.getState().name());
                sasl.done(SaslOutcome.PN_SASL_AUTH);
                completionHandler.handle(true);
            }
        } else {
            LOG.debug("client provided an empty list of SASL mechanisms [hostname: {}, state: {}]",
                    sasl.getHostname(), sasl.getState().name());
            completionHandler.handle(false);
        }
    }

    @Override
    public boolean succeeded() {
        return succeeded;
    }

    private void evaluateExternalResponse(final Handler<Boolean> completionHandler) {

        if (peerCertificateChain == null) {
            LOG.debug("SASL EXTERNAL authentication of client failed, client did not provide certificate chain");
            sasl.done(SaslOutcome.PN_SASL_AUTH);
        } else {
            Principal clientIdentity = peerCertificateChain[0].getSubjectDN();
            String commonName = getCommonName(clientIdentity.getName());
            if (commonName == null) {
                LOG.debug("SASL EXTERNAL authentication of client failed, could not determine authorization ID");
                sasl.done(SaslOutcome.PN_SASL_AUTH);
            } else {
                addPrincipal(commonName);
            }
        }
        completionHandler.handle(true);
    }

    private void evaluatePlainResponse(final Handler<Boolean> completionHandler) {

        byte[] response = new byte[sasl.pending()];
        sasl.recv(response, 0, response.length);

        final JsonObject authenticationRequest = getAuthenticationRequest(ProtonSaslPlainImpl.MECH_NAME, response);
        vertx.eventBus().send(EVENT_BUS_ADDRESS_AUTHENTICATION_IN, authenticationRequest, reply -> {
            if (reply.succeeded()) {
                JsonObject result = (JsonObject) reply.result().body();
                handleAuthenticationResult(result);
            } else {
                LOG.warn("could not process SASL PLAIN response from client", reply.cause());
            }
            completionHandler.handle(true);
        });
    }

    private void handleAuthenticationResult(final JsonObject result) {

        LOG.debug("received result of authentication request: {}", result);
        String error = result.getString(FIELD_ERROR);
        if (error != null) {
            LOG.debug("authentication of client failed", error);
            sasl.done(SaslOutcome.PN_SASL_AUTH);
        } else {
            addPrincipal(result.getString(FIELD_AUTHORIZATION_ID));
        }
    }

    private void addPrincipal(final String authzId) {
        addPrincipal(new Principal() {

            @Override
            public String getName() {
                return authzId;
            }
        });
    }

    private void addPrincipal(final Principal authzId) {

        succeeded = true;
        protonConnection.attachments().set(Constants.KEY_CLIENT_PRINCIPAL, Principal.class, authzId);
        LOG.debug("authentication of client [authorization ID: {}] succeeded", authzId.getName());
        sasl.done(SaslOutcome.PN_SASL_OK);
    }

    private String getCommonName(final String subject) {
        Matcher matcher = PATTERN_CN.matcher(subject);
        if (matcher.matches()) {
            return matcher.group(1); // return CN field value
        } else {
            LOG.debug("could not extract common name from client certificate's subject DN");
            return null;
        }
    }
}
