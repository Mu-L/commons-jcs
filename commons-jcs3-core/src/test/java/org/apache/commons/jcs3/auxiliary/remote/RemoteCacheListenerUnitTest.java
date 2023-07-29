package org.apache.commons.jcs3.auxiliary.remote;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import junit.framework.TestCase;

import org.apache.commons.jcs3.engine.control.MockCompositeCacheManager;
import org.apache.commons.jcs3.auxiliary.remote.behavior.IRemoteCacheAttributes;
import org.apache.commons.jcs3.engine.CacheElementSerialized;
import org.apache.commons.jcs3.engine.ElementAttributes;
import org.apache.commons.jcs3.engine.behavior.ICache;
import org.apache.commons.jcs3.engine.behavior.ICacheElement;
import org.apache.commons.jcs3.engine.behavior.ICacheElementSerialized;
import org.apache.commons.jcs3.engine.behavior.ICompositeCacheManager;
import org.apache.commons.jcs3.engine.behavior.IElementAttributes;
import org.apache.commons.jcs3.engine.behavior.IElementSerializer;
import org.apache.commons.jcs3.utils.serialization.StandardSerializer;

/**
 * Tests for the remote cache listener.
 */
public class RemoteCacheListenerUnitTest
    extends TestCase
{
    /**
     * Create a RemoteCacheListener with a mock cache manager.  Set remove on put to false.
     * Create a serialized element.  Call put on the listener.
     * Verify that the deserialized element is in the cache.
     * <p>
     * @throws Exception
     */
    public void testUpdate_PutOnPut()
        throws Exception
    {
        // SETUP
        final IRemoteCacheAttributes irca = new RemoteCacheAttributes();
        irca.setRemoveUponRemotePut( false );
        final ICompositeCacheManager cacheMgr = new MockCompositeCacheManager();
        final RemoteCacheListener<String, String> listener = new RemoteCacheListener<>( irca, cacheMgr, new StandardSerializer() );

        final String cacheName = "testName";
        final String key = "key";
        final String value = "value fdsadf dsafdsa fdsaf dsafdsaf dsafdsaf dsaf dsaf dsaf dsafa dsaf dsaf dsafdsaf";
        final IElementAttributes attr = new ElementAttributes();
        attr.setMaxLife(34);

        final IElementSerializer elementSerializer = new StandardSerializer();

        final ICacheElementSerialized<String, String> element =
            new CacheElementSerialized<>( cacheName, key, elementSerializer
            .serialize( value ), attr );

        // DO WORK
        listener.handlePut( element );

        // VERIFY
        final ICache<String, String> cache = cacheMgr.getCache( cacheName );
        final ICacheElement<String, String> after = cache.get( key );

        assertNotNull( "Should have a deserialized object.", after );
        assertEquals( "Values should be the same.", value, after.getVal() );
        assertEquals( "Attributes should be the same.", attr.getMaxLife(), after
            .getElementAttributes().getMaxLife() );
        assertEquals( "Keys should be the same.", key, after.getKey() );
        assertEquals( "Cache name should be the same.", cacheName, after.getCacheName() );
    }

    /**
     * Create a RemoteCacheListener with a mock cache manager.  Set remove on put to true.
     * Create a serialized element.  Call put on the listener.
     * Verify that the deserialized element is not in the cache.
     * <p>
     * @throws Exception
     */
    public void testUpdate_RemoveOnPut()
        throws Exception
    {
        // SETUP
        final IRemoteCacheAttributes irca = new RemoteCacheAttributes();
        irca.setRemoveUponRemotePut( true );
        final ICompositeCacheManager cacheMgr = new MockCompositeCacheManager();
        final RemoteCacheListener<String, String> listener = new RemoteCacheListener<>( irca, cacheMgr, new StandardSerializer() );

        final String cacheName = "testName";
        final String key = "key";
        final String value = "value fdsadf dsafdsa fdsaf dsafdsaf dsafdsaf dsaf dsaf dsaf dsafa dsaf dsaf dsafdsaf";
        final IElementAttributes attr = new ElementAttributes();
        attr.setMaxLife(34);

        final IElementSerializer elementSerializer = new StandardSerializer();

        final ICacheElementSerialized<String, String> element =
            new CacheElementSerialized<>( cacheName, key, elementSerializer
            .serialize( value ), attr );

        // DO WORK
        listener.handlePut( element );

        // VERIFY
        final ICache<String, String> cache = cacheMgr.getCache( cacheName );
        final ICacheElement<String, String> after = cache.get( key );

        assertNull( "Should not have a deserialized object since remove on put is true.", after );
    }
}
