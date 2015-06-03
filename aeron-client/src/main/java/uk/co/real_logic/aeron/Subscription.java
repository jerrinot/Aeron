/*
 * Copyright 2014 - 2015 Real Logic Ltd.
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
package uk.co.real_logic.aeron;

import uk.co.real_logic.aeron.common.concurrent.logbuffer.DataHandler;
import uk.co.real_logic.aeron.common.concurrent.logbuffer.TermReader;
import uk.co.real_logic.agrona.concurrent.status.Position;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

/**
 * Aeron Subscriber API for receiving messages from publishers on a given channel and streamId pair.
 * Subscribers are created via an {@link Aeron} object, and received messages are delivered
 * to the {@link DataHandler} provided at creation time.
 * <p>
 * By default fragmented messages are not reassembled before delivery. If an application must
 * receive whole messages, whether or not they were fragmented, then the Subscriber
 * should be created with a {@link FragmentAssemblyAdapter} or a custom implementation.
 * <p>
 * It is an applications responsibility to {@link #poll} the Subscriber for new messages.
 * <p>
 * Subscriptions are not threadsafe and should not be shared between subscribers.
 * @see Aeron#addSubscription(String, int, DataHandler)
 * @see FragmentAssemblyAdapter
 */
public class Subscription implements AutoCloseable
{
    private static final int TRUE = 1;
    private static final int FALSE = 0;

    private static final Connection[] EMPTY_ARRAY = new Connection[0];
    private static final AtomicIntegerFieldUpdater<Subscription> IS_CLOSED_UPDATER =
        AtomicIntegerFieldUpdater.newUpdater(Subscription.class, "isClosed");

    private final long registrationId;
    private final int streamId;
    private int roundRobinIndex = 0;
    private volatile int isClosed = FALSE;

    private final String channel;
    private final ClientConductor clientConductor;
    private volatile Connection[] connections = EMPTY_ARRAY;
    private final DataHandler dataHandler;

    Subscription(
        final ClientConductor conductor,
        final DataHandler dataHandler,
        final String channel,
        final int streamId,
        final long registrationId)
    {
        this.clientConductor = conductor;
        this.dataHandler = dataHandler;
        this.channel = channel;
        this.streamId = streamId;
        this.registrationId = registrationId;
    }

    /**
     * Media address for delivery to the channel.
     *
     * @return Media address for delivery to the channel.
     */
    public String channel()
    {
        return channel;
    }

    /**
     * Stream identity for scoping within the channel media address.
     *
     * @return Stream identity for scoping within the channel media address.
     */
    public int streamId()
    {
        return streamId;
    }

    /**
     * Read waiting data and deliver to {@link uk.co.real_logic.aeron.common.concurrent.logbuffer.DataHandler}s.
     *
     * Each fragment read will be a whole message if it is under MTU length. If larger than MTU side then it will come
     * as a series of fragments ordered withing a session.
     *
     * @param fragmentCountLimit number of message fragments to limit for a single poll operation.
     * @return the number of fragments received
     * @throws IllegalStateException if the subscription is closed.
     */
    public int poll(final int fragmentCountLimit)
    {
        ensureOpen();

        final Connection[] connections = this.connections;
        final int length = connections.length;
        int fragmentRead = 0;

        if (length > 0)
        {
            int startingIndex = roundRobinIndex++;
            if (startingIndex >= length)
            {
                roundRobinIndex = startingIndex = 0;
            }

            int i = startingIndex;

            final DataHandler dataHandler = this.dataHandler;
            do
            {
                fragmentRead += connections[i].poll(dataHandler, fragmentCountLimit);

                if (++i == length)
                {
                    i = 0;
                }
            }
            while (i != startingIndex);
        }

        return fragmentRead;
    }

    /**
     * Release the Subscription so that associated buffers can be released.
     */
    public void close()
    {
        if (IS_CLOSED_UPDATER.compareAndSet(this, FALSE, TRUE))
        {
            synchronized (clientConductor)
            {
                for (final Connection connection : connections)
                {
                    clientConductor.lingerResource(connection);
                }
                connections = EMPTY_ARRAY;

                clientConductor.releaseSubscription(this);
            }
        }
    }

    long registrationId()
    {
        return registrationId;
    }

    void onConnectionReady(
        final int sessionId,
        final long initialPosition,
        final long correlationId,
        final TermReader[] termReaders,
        final Position position,
        final LogBuffers logBuffers)
    {
        final Connection[] oldArray = connections;
        final int oldLength = oldArray.length;
        final Connection[] newArray = new Connection[oldLength + 1];

        System.arraycopy(oldArray, 0, newArray, 0, oldLength);
        newArray[oldLength] = new Connection(termReaders, sessionId, initialPosition, correlationId, position, logBuffers);

        connections = newArray;
    }

    boolean isConnected(final int sessionId)
    {
        boolean isConnected = false;

        for (final Connection connection : connections)
        {
            if (sessionId == connection.sessionId())
            {
                isConnected = true;
                break;
            }
        }

        return isConnected;
    }

    boolean removeConnection(final long correlationId)
    {
        final Connection[] oldArray = connections;
        final int oldLength = oldArray.length;
        Connection removedConnection = null;
        int index = -1;

        for (int i = 0; i < oldLength; i++)
        {
            if (oldArray[i].correlationId() == correlationId)
            {
                index = i;
                removedConnection = oldArray[i];
            }
        }

        if (null != removedConnection)
        {
            final int newSize = oldLength - 1;
            final Connection[] newArray = new Connection[newSize];
            System.arraycopy(oldArray, 0, newArray, 0, index);
            System.arraycopy(oldArray, index + 1, newArray, index, newSize - index);
            connections = newArray;

            clientConductor.lingerResource(removedConnection);

            return true;
        }

        return false;
    }

    boolean hasNoConnections()
    {
        return connections.length == 0;
    }

    private void ensureOpen()
    {
        if (TRUE == isClosed)
        {
            throw new IllegalStateException(String.format(
                "Subscription is closed: channel=%s streamId=%d registrationId=%d", channel, streamId, registrationId));
        }
    }
}
