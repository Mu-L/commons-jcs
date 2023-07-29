package org.apache.commons.jcs3.auxiliary.remote.server;

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

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

import org.apache.commons.jcs3.auxiliary.AuxiliaryCache;
import org.apache.commons.jcs3.auxiliary.MockCacheEventLogger;
import org.apache.commons.jcs3.auxiliary.remote.MockRemoteCacheListener;
import org.apache.commons.jcs3.auxiliary.remote.RemoteCacheAttributes;
import org.apache.commons.jcs3.auxiliary.remote.RemoteCacheFactory;
import org.apache.commons.jcs3.auxiliary.remote.RemoteCacheManager;
import org.apache.commons.jcs3.engine.CacheElement;
import org.apache.commons.jcs3.engine.CacheStatus;
import org.apache.commons.jcs3.engine.behavior.ICacheElement;
import org.apache.commons.jcs3.engine.control.MockCompositeCacheManager;
import org.apache.commons.jcs3.engine.control.MockElementSerializer;
import org.apache.commons.jcs3.utils.net.HostNameUtil;
import org.apache.commons.jcs3.utils.timing.SleepUtil;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * These tests startup the remote server and make requests to it.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class BasicRemoteCacheClientServerUnitTest extends Assert
{
    private static final int LOCAL_PORT = 12020;

   /**
     * Server instance to use in the tests.
     */
    private static RemoteCacheServer<String, String> server;

    /**
     * Factory instance to use in the tests.
     */
    private static RemoteCacheFactory factory;

    /**
     * the remote server port
     */
    private static int remotePort;

    /**
     * Starts the server. This is not in a setup, since the server is slow to kill right now.
     */
    @BeforeClass
    public static void setup()
    {
        // Add some debug to try and find out why test fails on Jenkins/Continuum
        try {
            final InetAddress lh = InetAddress.getByName("localhost");
            System.out.println("localhost="+lh);
            final InetAddress ina=InetAddress.getLocalHost();
            System.out.println("InetAddress.getLocalHost()="+ina);
            // Iterate all NICs (network interface cards)...
            final Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if ( ifaces != null ) {
            while ( ifaces.hasMoreElements() )
                {
                    final NetworkInterface iface = ifaces.nextElement();
                    // Iterate all IP addresses assigned to each card...
                    for ( final Enumeration<InetAddress> inetAddrs = iface.getInetAddresses(); inetAddrs.hasMoreElements(); )
                    {
                        final InetAddress inetAddr = inetAddrs.nextElement();
                        final boolean loopbackAddress = inetAddr.isLoopbackAddress();
                        final boolean siteLocalAddress = inetAddr.isSiteLocalAddress();
                        System.out.println("Found: "+ inetAddr +
                                " isLoopback: " + loopbackAddress +
                                " isSiteLocal: " + siteLocalAddress +
                                ((!loopbackAddress && siteLocalAddress) ? " *" : ""));
                    }
                }
            }
        } catch (final Exception e) {
            e.printStackTrace();
        }
        // end of debug
        final String configFile = "TestRemoteCacheClientServer.ccf";
        server = RemoteCacheServerStartupUtil.startServerUsingProperties(configFile);
        factory = new RemoteCacheFactory();
        factory.initialize();
        remotePort = server.remoteCacheServerAttributes.getRemoteLocation().getPort();
    }

    @AfterClass
    public static void stop() throws IOException
    {
        if (server != null) { // in case setup failed, no point throwing NPE as well
            server.shutdown("localhost", remotePort);
        }
    }

    /**
     * Verify that we can start the remote cache server. Send an item to the remote. Verify that the
     * remote put count goes up. If we go through JCS, the manager will be shared and we will get
     * into an endless loop. We will use a mock cache manager instead.
     * <p>
     * The remote server uses the real JCS. We can verify that items are added to JCS behind the
     * server by calling get. We cannot access it directly via JCS since it is serialized.
     * <p>
     * This test uses a mock injected client to test a normal server.
     * <p>
     *
     * @throws Exception
     */
    @Test
    public void test1SinglePut()
            throws Exception
            {
        // SETUP
        final MockCompositeCacheManager compositeCacheManager = new MockCompositeCacheManager();

        final RemoteCacheAttributes attributes = new RemoteCacheAttributes();
        attributes.setRemoteLocation("localhost", remotePort);
        attributes.setLocalPort(LOCAL_PORT);
        attributes.setCacheName("testSinglePut");

        final RemoteCacheManager remoteCacheManager = factory.getManager(attributes, compositeCacheManager, new MockCacheEventLogger(), new MockElementSerializer());
        final AuxiliaryCache<String, String> cache = remoteCacheManager.getCache(attributes);

        // DO WORK
        final int numPutsPrior = server.getPutCount();
        final ICacheElement<String, String> element = new CacheElement<>(cache.getCacheName(), "key", "value");
        cache.update(element);
        SleepUtil.sleepAtLeast(200);

        // VERIFY
        try
        {
            assertEquals("Cache is alive", CacheStatus.ALIVE, cache.getStatus());
            assertEquals("Wrong number of puts", 1, server.getPutCount() - numPutsPrior);
        }
        catch (final junit.framework.AssertionFailedError e)
        {
            System.out.println(cache.getStats());
            System.out.println(server.getStats());
            throw e;
        }

        // DO WORK
        final ICacheElement<String, String> result = cache.get("key");

        // VERIFY
        assertEquals("Wrong element.", element.getVal(), result.getVal());
            }

    /**
     * Verify that we can remove an item via the remote server.
     * <p>
     *
     * @throws Exception
     */
    @Test
    public void test2PutRemove()
            throws Exception
            {
        // SETUP
        final MockCompositeCacheManager compositeCacheManager = new MockCompositeCacheManager();

        final RemoteCacheAttributes attributes = new RemoteCacheAttributes();
        attributes.setRemoteLocation("localhost", remotePort);
        attributes.setLocalPort(LOCAL_PORT);
        attributes.setCacheName("testPutRemove");

        final MockCacheEventLogger cacheEventLogger = new MockCacheEventLogger();

        final RemoteCacheManager remoteCacheManager = factory.getManager(attributes, compositeCacheManager, cacheEventLogger, null);
        final AuxiliaryCache<String, String> cache = remoteCacheManager.getCache(attributes);

        // DO WORK
        final int numPutsPrior = server.getPutCount();
        final ICacheElement<String, String> element = new CacheElement<>(cache.getCacheName(), "key", "value");
        cache.update(element);
        SleepUtil.sleepAtLeast(50);

        // VERIFY
        try
        {
            assertEquals("Cache is alive", CacheStatus.ALIVE, cache.getStatus());
            assertEquals("Wrong number of puts", 1, server.getPutCount() - numPutsPrior);
        }
        catch (final junit.framework.AssertionFailedError e)
        {
            System.out.println(cache.getStats());
            System.out.println(server.getStats());
            throw e;
        }

        // DO WORK
        final ICacheElement<String, String> result = cache.get("key");

        // VERIFY
        assertEquals("Wrong element.", element.getVal(), result.getVal());

        // DO WORK
        cache.remove("key");
        SleepUtil.sleepAtLeast(200);
        final ICacheElement<String, String> resultAfterRemote = cache.get("key");

        // VERIFY
        assertNull("Element resultAfterRemote should be null.", resultAfterRemote);
            }

    /**
     * Register a listener with the server. Send an update. Verify that the listener received it.
     *
     * @throws Exception
     */
    @Test
    public void test3PutAndListen()
            throws Exception
            {
        // SETUP
        final MockCompositeCacheManager compositeCacheManager = new MockCompositeCacheManager();

        final RemoteCacheAttributes attributes = new RemoteCacheAttributes();
        attributes.setRemoteLocation("localhost", remotePort);
        attributes.setLocalPort(LOCAL_PORT);
        attributes.setCacheName("testPutAndListen");

        final RemoteCacheManager remoteCacheManager = factory.getManager(attributes, compositeCacheManager, new MockCacheEventLogger(), new MockElementSerializer());
        final AuxiliaryCache<String, String> cache = remoteCacheManager.getCache(attributes);

        final MockRemoteCacheListener<String, String> listener = new MockRemoteCacheListener<>();
        server.addCacheListener(cache.getCacheName(), listener);

        // DO WORK
        final int numPutsPrior = server.getPutCount();
        final ICacheElement<String, String> element = new CacheElement<>(cache.getCacheName(), "key", "value");
        cache.update(element);
        SleepUtil.sleepAtLeast(50);

        // VERIFY
        try
        {
            assertEquals("Cache is alive", CacheStatus.ALIVE, cache.getStatus());
            assertEquals("Wrong number of puts", 1, server.getPutCount() - numPutsPrior);
            assertEquals("Wrong number of puts to listener.", 1, listener.putCount);
        }
        catch (final junit.framework.AssertionFailedError e)
        {
            System.out.println(cache.getStats());
            System.out.println(server.getStats());
            throw e;
        }
        finally
        {
            // remove from all regions.
            server.removeCacheListener(listener);
        }
    }

    /**
     * Register a listener with the server. Send an update. Verify that the listener received it.
     *
     * @throws Exception
     */
    @Test
    public void test4PutaMultipleAndListen()
            throws Exception
    {
        // SETUP
        final MockCompositeCacheManager compositeCacheManager = new MockCompositeCacheManager();

        final RemoteCacheAttributes attributes = new RemoteCacheAttributes();
        attributes.setRemoteLocation("localhost", remotePort);
        attributes.setLocalPort(LOCAL_PORT);
        attributes.setCacheName("testPutaMultipleAndListen");

        final RemoteCacheManager remoteCacheManager = factory.getManager(attributes, compositeCacheManager, new MockCacheEventLogger(), new MockElementSerializer());
        final AuxiliaryCache<String, String> cache = remoteCacheManager.getCache(attributes);

        final MockRemoteCacheListener<String, String> listener = new MockRemoteCacheListener<>();
        server.addCacheListener(cache.getCacheName(), listener);

        // DO WORK
        final int numPutsPrior = server.getPutCount();
        final int numToPut = 100;
        for (int i = 0; i < numToPut; i++)
        {
            final ICacheElement<String, String> element = new CacheElement<>(cache.getCacheName(), "key" + 1, "value" + i);
            cache.update(element);
        }
        SleepUtil.sleepAtLeast(500);

        // VERIFY
        try
        {
            assertEquals("Cache is alive", CacheStatus.ALIVE, cache.getStatus());
            assertEquals("Wrong number of puts", numToPut, server.getPutCount() - numPutsPrior);
            assertEquals("Wrong number of puts to listener.", numToPut, listener.putCount);
        }
        catch (final junit.framework.AssertionFailedError e)
        {
            System.out.println(cache.getStats());
            System.out.println(server.getStats());
            throw e;
        }
    }

    @Test
    public void testLocalHost() throws Exception
    {
        final InetAddress byName = InetAddress.getByName("localhost");
        assertTrue("Expected localhost (" + byName.getHostAddress() + ") to be a loopback address", byName.isLoopbackAddress());
        final InetAddress localHost = HostNameUtil.getLocalHostLANAddress();
        assertTrue("Expected getLocalHostLANAddress() (" + localHost + ") to return a site local address", localHost.isSiteLocalAddress());
    }
}
