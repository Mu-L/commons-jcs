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
package org.apache.commons.jcs.jcache;

import static org.apache.commons.jcs.jcache.Asserts.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.configuration.Configuration;
import javax.cache.spi.CachingProvider;

import org.apache.commons.jcs.engine.control.CompositeCacheManager;
import org.apache.commons.jcs.jcache.proxy.ClassLoaderAwareHandler;

public class JCSCachingManager implements CacheManager
{
    private final CachingProvider provider;
    private final URI uri;
    private final ClassLoader loader;
    private final Properties properties;
    private final ConcurrentMap<String, Cache<?, ?>> caches = new ConcurrentHashMap<String, Cache<?, ?>>();
    private final CompositeCacheManager instance;
    private volatile boolean closed = false;

    public JCSCachingManager(final CachingProvider provider, final URI uri, final ClassLoader loader, final Properties properties)
    {
        this.provider = provider;
        this.uri = uri;
        this.loader = loader;
        this.properties = properties;

        instance = CompositeCacheManager.getUnconfiguredInstance();
        final Properties props = new Properties();
        InputStream inStream = null;
        try
        {
            if (JCSCachingProvider.DEFAULT_URI == uri || uri.toURL().getProtocol().equals("jcs"))
            {
                inStream = loader.getResourceAsStream(uri.getPath());
            }
            else
            {
                inStream = uri.toURL().openStream();
            }
            props.load(inStream);
        }
        catch (final IOException e)
        {
            throw new IllegalArgumentException(e);
        }
        finally
        {
            if (inStream != null)
            {
                try
                {
                    inStream.close();
                }
                catch (final IOException e)
                {
                    // no-op
                }
            }
        }
        if (properties != null)
        {
            props.putAll(properties);
        }
        instance.configure(props);
    }

    private void assertNotClosed()
    {
        if (isClosed())
        {
            throw new IllegalStateException("cache manager closed");
        }
    }

    @Override
    // TODO: use configuration + handle not serializable key/values
    public <K, V, C extends Configuration<K, V>> Cache<K, V> createCache(final String cacheName, final C configuration)
            throws IllegalArgumentException
    {
        assertNotClosed();
        assertNotNull(cacheName, "cacheName");
        final Class<?> keyType = configuration == null ? Object.class : configuration.getKeyType();
        final Class<?> valueType = configuration == null ? Object.class : configuration.getValueType();
        if (!caches.containsKey(cacheName))
        {
            final Cache<K, V> cache = ClassLoaderAwareHandler.newProxy(loader, new JCSCache/*
                                                                                            * <
                                                                                            * K
                                                                                            * ,
                                                                                            * V
                                                                                            * ,
                                                                                            * C
                                                                                            * >
                                                                                            */(loader, this, new JCSConfiguration(
                    configuration, keyType, valueType), instance.getCache(cacheName), instance.getConfigurationProperties()), Cache.class);
            caches.putIfAbsent(cacheName, cache);
        }
        else
        {
            throw new javax.cache.CacheException("cache " + cacheName + " already exists");
        }
        return (Cache<K, V>) getCache(cacheName, keyType, valueType);
    }

    @Override
    public void destroyCache(final String cacheName)
    {
        assertNotClosed();
        assertNotNull(cacheName, "cacheName");
        final Cache<?, ?> cache = caches.remove(cacheName);
        instance.freeCache(cacheName, true);
        if (cache != null && !cache.isClosed())
        {
            cache.clear();
            cache.close();
            instance.freeCache(cacheName, true);
        }
    }

    @Override
    public void enableManagement(final String cacheName, final boolean enabled)
    {
        assertNotClosed();
        assertNotNull(cacheName, "cacheName");
        final JCSCache<?, ?, ?> cache = getJCSCache(cacheName);
        if (cache != null)
        {
            if (enabled)
            {
                cache.enableManagement();
            }
            else
            {
                cache.disableManagement();
            }
        }
    }

    private JCSCache<?, ?, ?> getJCSCache(final String cacheName)
    {
        final Cache<?, ?> cache = caches.get(cacheName);
        return JCSCache.class.cast(ClassLoaderAwareHandler.class.cast(Proxy.getInvocationHandler(cache)).getDelegate());
    }

    @Override
    public void enableStatistics(final String cacheName, final boolean enabled)
    {
        assertNotClosed();
        assertNotNull(cacheName, "cacheName");
        final JCSCache<?, ?, ?> cache = getJCSCache(cacheName);
        if (cache != null)
        {
            if (enabled)
            {
                cache.enableStatistics();
            }
            else
            {
                cache.disableStatistics();
            }
        }
    }

    @Override
    public synchronized void close()
    {
        if (isClosed())
        {
            return;
        }

        assertNotClosed();
        for (final Cache<?, ?> c : caches.values())
        {
            c.close();
        }
        caches.clear();
        closed = true;
        if (JCSCachingProvider.class.isInstance(provider))
        {
            JCSCachingProvider.class.cast(provider).remove(this);
        }
        instance.shutDown();
    }

    @Override
    public <T> T unwrap(final Class<T> clazz)
    {
        if (clazz.isInstance(this))
        {
            return clazz.cast(this);
        }
        throw new IllegalArgumentException(clazz.getName() + " not supported in unwrap");
    }

    @Override
    public boolean isClosed()
    {
        return closed;
    }

    @Override
    public <K, V> Cache<K, V> getCache(final String cacheName)
    {
        assertNotClosed();
        assertNotNull(cacheName, "cacheName");
        return (Cache<K, V>) doGetCache(cacheName, Object.class, Object.class);
    }

    @Override
    public Iterable<String> getCacheNames()
    {
        return new ImmutableIterable<String>(caches.keySet());
    }

    @Override
    public <K, V> Cache<K, V> getCache(final String cacheName, final Class<K> keyType, final Class<V> valueType)
    {
        assertNotClosed();
        assertNotNull(cacheName, "cacheName");
        try
        {
            return doGetCache(cacheName, keyType, valueType);
        }
        catch (final IllegalArgumentException iae)
        {
            throw new ClassCastException(iae.getMessage());
        }
    }

    private <K, V> Cache<K, V> doGetCache(final String cacheName, final Class<K> keyType, final Class<V> valueType)
    {
        final Cache<K, V> cache = (Cache<K, V>) caches.get(cacheName);
        if (cache == null)
        {
            return null;
        }

        final Configuration<K, V> config = cache.getConfiguration(Configuration.class);
        if ((keyType != null && !config.getKeyType().isAssignableFrom(keyType))
                || (valueType != null && !config.getValueType().isAssignableFrom(valueType)))
        {
            throw new IllegalArgumentException("this cache is <" + config.getKeyType().getName() + ", " + config.getValueType().getName()
                    + "> " + " and not <" + keyType.getName() + ", " + valueType.getName() + ">");
        }
        return cache;
    }

    @Override
    public CachingProvider getCachingProvider()
    {
        return provider;
    }

    @Override
    public URI getURI()
    {
        return uri;
    }

    @Override
    public ClassLoader getClassLoader()
    {
        return loader;
    }

    @Override
    public Properties getProperties()
    {
        return properties;
    }
}
