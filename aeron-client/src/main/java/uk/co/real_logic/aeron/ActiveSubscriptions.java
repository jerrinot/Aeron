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

import uk.co.real_logic.agrona.collections.Int2ObjectHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Threadsafe for getting to {@link uk.co.real_logic.aeron.Subscription}s by streamId
 */
class ActiveSubscriptions
{
    private final Int2ObjectHashMap<List<Subscription>> subscriptionByStreamIdMap = new Int2ObjectHashMap<>();

    public synchronized void forEach(final int streamId, final Consumer<Subscription> handler)
    {
        final List<Subscription> subscriptions = subscriptionByStreamIdMap.get(streamId);
        if (null != subscriptions)
        {
            subscriptions.forEach(handler);
        }
    }

    public synchronized void add(final Subscription subscription)
    {
        final List<Subscription> subscriptions =
            subscriptionByStreamIdMap.computeIfAbsent(subscription.streamId(), ignore -> new ArrayList<>());

        subscriptions.add(subscription);
    }

    public synchronized void remove(final Subscription subscription)
    {
        final int streamId = subscription.streamId();
        final List<Subscription> subscriptions = subscriptionByStreamIdMap.get(streamId);
        if (subscriptions.remove(subscription) && subscriptions.isEmpty())
        {
            subscriptionByStreamIdMap.remove(streamId);
            if (subscriptionByStreamIdMap.isEmpty())
            {
                subscriptionByStreamIdMap.remove(streamId);
            }
        }
    }
}
