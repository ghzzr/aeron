/*
 * Copyright 2018 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster.service;

import io.aeron.Publication;
import io.aeron.cluster.codecs.*;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.CloseHelper;

public class ServiceControlPublisher implements AutoCloseable
{
    private static final int SEND_ATTEMPTS = 3;

    private final BufferClaim bufferClaim = new BufferClaim();
    private final MessageHeaderEncoder messageHeaderEncoder = new MessageHeaderEncoder();
    private final ScheduleTimerRequestEncoder scheduleTimerRequestEncoder = new ScheduleTimerRequestEncoder();
    private final CancelTimerRequestEncoder cancelTimerRequestEncoder = new CancelTimerRequestEncoder();
    private final ServiceActionAckEncoder serviceActionAckEncoder = new ServiceActionAckEncoder();
    private final ConnectLogRequestEncoder connectLogRequestEncoder = new ConnectLogRequestEncoder();
    private final Publication publication;

    public ServiceControlPublisher(final Publication publication)
    {
        this.publication = publication;
    }

    public void close()
    {
        CloseHelper.close(publication);
    }

    public void scheduleTimer(final long correlationId, final long deadlineMs)
    {
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + ScheduleTimerRequestEncoder.BLOCK_LENGTH;

        int attempts = SEND_ATTEMPTS;
        do
        {
            final long result = publication.tryClaim(length, bufferClaim);
            if (result > 0)
            {
                scheduleTimerRequestEncoder
                    .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .correlationId(correlationId)
                    .deadline(deadlineMs);

                bufferClaim.commit();

                return;
            }

            checkResult(result);
        }
        while (--attempts > 0);

        throw new IllegalStateException("Failed to schedule timer");
    }

    public void cancelTimer(final long correlationId)
    {
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + CancelTimerRequestEncoder.BLOCK_LENGTH;

        int attempts = SEND_ATTEMPTS;
        do
        {
            final long result = publication.tryClaim(length, bufferClaim);
            if (result > 0)
            {
                cancelTimerRequestEncoder
                    .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .correlationId(correlationId);

                bufferClaim.commit();

                return;
            }

            checkResult(result);
        }
        while (--attempts > 0);

        throw new IllegalStateException("Failed to cancel timer");
    }

    public void sendAcknowledgment(
        final long logPosition, final long leadershipTermId, final int serviceId, final ClusterAction action)
    {
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + ServiceActionAckEncoder.BLOCK_LENGTH;

        int attempts = SEND_ATTEMPTS;
        do
        {
            final long result = publication.tryClaim(length, bufferClaim);
            if (result > 0)
            {
                serviceActionAckEncoder
                    .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .logPosition(logPosition)
                    .leadershipTermId(leadershipTermId)
                    .serviceId(serviceId)
                    .action(action);

                bufferClaim.commit();

                return;
            }

            checkResult(result);
        }
        while (--attempts > 0);

        throw new IllegalStateException("Failed to send ACK");
    }

    public void connectLog(
        final long leadershipTermId,
        final int commitPositionId,
        final int logSessionId,
        final int logStreamId,
        final String channel)
    {
        final int length = MessageHeaderEncoder.ENCODED_LENGTH + ConnectLogRequestEncoder.BLOCK_LENGTH +
            ConnectLogRequestEncoder.logChannelHeaderLength() + channel.length();

        int attempts = SEND_ATTEMPTS * 2;
        do
        {
            final long result = publication.tryClaim(length, bufferClaim);
            if (result > 0)
            {
                connectLogRequestEncoder
                    .wrapAndApplyHeader(bufferClaim.buffer(), bufferClaim.offset(), messageHeaderEncoder)
                    .leadershipTermId(leadershipTermId)
                    .commitPositionId(commitPositionId)
                    .logSessionId(logSessionId)
                    .logStreamId(logStreamId)
                    .logChannel(channel);

                bufferClaim.commit();

                return;
            }

            checkResult(result);
        }
        while (--attempts > 0);

        throw new IllegalStateException("Failed to send log connect request");
    }

    private static void checkResult(final long result)
    {
        if (result == Publication.NOT_CONNECTED ||
            result == Publication.CLOSED ||
            result == Publication.MAX_POSITION_EXCEEDED)
        {
            throw new IllegalStateException("Unexpected publication state: " + result);
        }
    }
}
