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

package org.eclipse.hono.client.impl;

import java.util.Objects;

import org.eclipse.hono.client.MessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonSender;

/**
 * A Vertx-Proton based client for uploading telemtry data to a Hono server.
 */
public class TelemetrySenderImpl extends AbstractSender {

    private static final String     TELEMETRY_ENDPOINT_NAME  = "telemetry/";
    private static final Logger     LOG = LoggerFactory.getLogger(TelemetrySenderImpl.class);

    private TelemetrySenderImpl(final String tenantId, final Context context, final ProtonSender sender) {
        super(tenantId, context);
        this.sender = sender;
    }

    /**
     * Gets the AMQP <em>target</em> address to use for uploading data to Hono's telemetry endpoint.
     * 
     * @param tenantId The tenant to upload data for.
     * @param deviceId The device to upload data for. If {@code null}, the target address can be used
     *                 to upload data for arbitrary devices belonging to the tenant.
     * @return The target address.
     * @throws NullPointerException if tenant is {@code null}.
     */
    public static String getTargetAddress(final String tenantId, final String deviceId) {
        StringBuilder targetAddress = new StringBuilder(TELEMETRY_ENDPOINT_NAME).append(Objects.requireNonNull(tenantId));
        if (deviceId != null && deviceId.length() > 0) {
            targetAddress.append("/").append(deviceId);
        }
        return targetAddress.toString();
    }

    @Override
    protected String getTo(final String deviceId) {
        return getTargetAddress(tenantId, deviceId);
    }

    public static void create(
            final Context context,
            final ProtonConnection con,
            final String tenantId,
            final String deviceId,
            final Handler<AsyncResult<MessageSender>> creationHandler) {

        Objects.requireNonNull(context);
        Objects.requireNonNull(con);
        Objects.requireNonNull(tenantId);
        createSender(context, con, tenantId, deviceId).setHandler(created -> {
            if (created.succeeded()) {
                creationHandler.handle(Future.succeededFuture(
                        new TelemetrySenderImpl(tenantId, context, created.result())));
            } else {
                creationHandler.handle(Future.failedFuture(created.cause()));
            }
        });
    }

    private static Future<ProtonSender> createSender(
            final Context ctx,
            final ProtonConnection con,
            final String tenantId,
            final String deviceId) {

        final Future<ProtonSender> result = Future.future();
        final String targetAddress = getTargetAddress(tenantId, deviceId);

        ctx.runOnContext(create -> {
            final ProtonSender sender = con.createSender(targetAddress);
            sender.setQoS(ProtonQoS.AT_MOST_ONCE);
            sender.openHandler(senderOpen -> {
                if (senderOpen.succeeded()) {
                    LOG.debug("telemetry sender for [{}] open", senderOpen.result().getRemoteTarget());
                    result.complete(senderOpen.result());
                } else {
                    LOG.debug("telemetry sender open for [{}] failed: {}", targetAddress, senderOpen.cause().getMessage());
                    result.fail(senderOpen.cause());
                }
            }).closeHandler(senderClosed -> {
                if (senderClosed.succeeded()) {
                    LOG.debug("telemetry sender for [{}] closed", senderClosed.result().getRemoteTarget());
                } else {
                    LOG.debug("telemetry closed due to {}", senderClosed.cause().getMessage());
                }
            }).open();
        });

        return result;
    }
}
