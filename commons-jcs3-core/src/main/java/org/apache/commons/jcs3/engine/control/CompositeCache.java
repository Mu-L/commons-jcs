package org.apache.commons.jcs3.engine.control;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.jcs3.access.exception.CacheException;
import org.apache.commons.jcs3.access.exception.ObjectNotFoundException;
import org.apache.commons.jcs3.auxiliary.AuxiliaryCache;
import org.apache.commons.jcs3.engine.CacheStatus;
import org.apache.commons.jcs3.engine.behavior.ICache;
import org.apache.commons.jcs3.engine.behavior.ICacheElement;
import org.apache.commons.jcs3.engine.behavior.ICompositeCacheAttributes;
import org.apache.commons.jcs3.engine.behavior.ICompositeCacheAttributes.DiskUsagePattern;
import org.apache.commons.jcs3.engine.behavior.IElementAttributes;
import org.apache.commons.jcs3.engine.behavior.IRequireScheduler;
import org.apache.commons.jcs3.engine.control.event.ElementEvent;
import org.apache.commons.jcs3.engine.control.event.behavior.ElementEventType;
import org.apache.commons.jcs3.engine.control.event.behavior.IElementEvent;
import org.apache.commons.jcs3.engine.control.event.behavior.IElementEventHandler;
import org.apache.commons.jcs3.engine.control.event.behavior.IElementEventQueue;
import org.apache.commons.jcs3.engine.control.group.GroupId;
import org.apache.commons.jcs3.engine.match.KeyMatcherPatternImpl;
import org.apache.commons.jcs3.engine.match.behavior.IKeyMatcher;
import org.apache.commons.jcs3.engine.memory.behavior.IMemoryCache;
import org.apache.commons.jcs3.engine.memory.lru.LRUMemoryCache;
import org.apache.commons.jcs3.engine.memory.shrinking.ShrinkerThread;
import org.apache.commons.jcs3.engine.stats.CacheStats;
import org.apache.commons.jcs3.engine.stats.StatElement;
import org.apache.commons.jcs3.engine.stats.behavior.ICacheStats;
import org.apache.commons.jcs3.engine.stats.behavior.IStats;
import org.apache.commons.jcs3.log.Log;

/**
 * This is the primary hub for a single cache/region. It controls the flow of items through the
 * cache. The auxiliary and memory caches are plugged in here.
 * <p>
 * This is the core of a JCS region. Hence, this simple class is the core of JCS.
 */
public class CompositeCache<K, V>
    implements ICache<K, V>, IRequireScheduler
{
    /** Log instance */
    private static final Log log = Log.getLog(CompositeCache.class);

    /**
     * EventQueue for handling element events. Lazy initialized. One for each region. To be more efficient, the manager
     * should pass a shared queue in.
     */
    private IElementEventQueue elementEventQ;

    /** Auxiliary caches. */
    private CopyOnWriteArrayList<AuxiliaryCache<K, V>> auxCaches = new CopyOnWriteArrayList<>();

    /** Is this alive? */
    private final AtomicBoolean alive;

    /** Region Elemental Attributes, default. */
    private IElementAttributes attr;

    /** Cache Attributes, for hub and memory auxiliary. */
    private ICompositeCacheAttributes cacheAttr;

    /** How many times update was called. */
    private final AtomicLong updateCount;

    /** How many times remove was called. */
    private final AtomicLong removeCount;

    /** Memory cache hit count */
    private final AtomicLong hitCountRam;

    /** Auxiliary cache hit count (number of times found in ANY auxiliary) */
    private final AtomicLong hitCountAux;

    /** Count of misses where element was not found. */
    private final AtomicLong missCountNotFound;

    /** Count of misses where element was expired. */
    private final AtomicLong missCountExpired;

    /** Cache manager. */
    private CompositeCacheManager cacheManager;

    /**
     * The cache hub can only have one memory cache. This could be made more flexible in the future,
     * but they are tied closely together. More than one doesn't make much sense.
     */
    private IMemoryCache<K, V> memCache;

    /** Key matcher used by the getMatching API */
    private IKeyMatcher<K> keyMatcher = new KeyMatcherPatternImpl<>();

    private ScheduledFuture<?> future;

    /**
     * Constructor for the Cache object
     *
     * @param cattr The cache attribute
     * @param attr The default element attributes
     */
    public CompositeCache(final ICompositeCacheAttributes cattr, final IElementAttributes attr)
    {
        this.attr = attr;
        this.cacheAttr = cattr;
        this.alive = new AtomicBoolean(true);
        this.updateCount = new AtomicLong();
        this.removeCount = new AtomicLong();
        this.hitCountRam = new AtomicLong();
        this.hitCountAux = new AtomicLong();
        this.missCountNotFound = new AtomicLong();
        this.missCountExpired = new AtomicLong();

        createMemoryCache(cattr);

        log.info("Constructed cache with name [{0}] and cache attributes {1}",
                cacheAttr.getCacheName(), cattr);
    }

    /**
     * Copies the item to memory if the memory size is greater than 0. Only spool if the memory
     * cache size is greater than 0, else the item will immediately get put into purgatory.
     *
     * @param element
     * @throws IOException
     */
    private void copyAuxiliaryRetrievedItemToMemory(final ICacheElement<K, V> element)
        throws IOException
    {
        if (memCache.getCacheAttributes().getMaxObjects() > 0)
        {
            memCache.update(element);
        }
        else
        {
            log.debug("Skipping memory update since no items are allowed in memory");
        }
    }

    /**
     * Create the MemoryCache based on the config parameters.
     * TODO: consider making this an auxiliary, despite its close tie to the CacheHub.
     * TODO: might want to create a memory cache config file separate from that of the hub -- ICompositeCacheAttributes
     *
     * @param cattr
     */
    private void createMemoryCache(final ICompositeCacheAttributes cattr)
    {
        if (memCache == null)
        {
            try
            {
                final Class<?> c = Class.forName(cattr.getMemoryCacheName());
                @SuppressWarnings("unchecked") // Need cast
                final
                IMemoryCache<K, V> newInstance =
                    (IMemoryCache<K, V>) c.getDeclaredConstructor().newInstance();
                memCache = newInstance;
                memCache.initialize(this);
            }
            catch (final Exception e)
            {
                log.warn("Failed to init mem cache, using: LRUMemoryCache", e);

                this.memCache = new LRUMemoryCache<>();
                this.memCache.initialize(this);
            }
        }
        else
        {
            log.warn("Refusing to create memory cache -- already exists.");
        }
    }

    /**
     * Flushes all cache items from memory to auxiliary caches and close the auxiliary caches.
     */
    @Override
    public void dispose()
    {
        dispose(false);
    }

    /**
     * Invoked only by CacheManager. This method disposes of the auxiliaries one by one. For the
     * disk cache, the items in memory are freed, meaning that they will be sent through the
     * overflow channel to disk. After the auxiliaries are disposed, the memory cache is disposed.
     *
     * @param fromRemote
     */
    public void dispose(final boolean fromRemote)
    {
         // If already disposed, return immediately
        if (!alive.compareAndSet(true, false))
        {
            return;
        }

        log.info("In DISPOSE, [{0}] fromRemote [{1}]",
                this.cacheAttr::getCacheName, () -> fromRemote);

        // Remove us from the cache managers list
        // This will call us back but exit immediately
        if (cacheManager != null)
        {
            cacheManager.freeCache(getCacheName(), fromRemote);
        }

        // Try to stop shrinker thread
        if (future != null)
        {
            future.cancel(true);
        }

        // Now, shut down the event queue
        if (elementEventQ != null)
        {
            elementEventQ.dispose();
            elementEventQ = null;
        }

        // Dispose of each auxiliary cache, Remote auxiliaries will be
        // skipped if 'fromRemote' is true.
        for (final ICache<K, V> aux : auxCaches)
        {
            try
            {
                // Skip this auxiliary if:
                // - The auxiliary is null
                // - The auxiliary is not alive
                // - The auxiliary is remote and the invocation was remote
                if (aux == null || aux.getStatus() != CacheStatus.ALIVE
                    || fromRemote && aux.getCacheType() == CacheType.REMOTE_CACHE)
                {
                    log.info("In DISPOSE, [{0}] SKIPPING auxiliary [{1}] fromRemote [{2}]",
                            this.cacheAttr::getCacheName,
                            () -> aux == null ? "null" : aux.getCacheName(),
                            () -> fromRemote);
                    continue;
                }

                log.info("In DISPOSE, [{0}] auxiliary [{1}]",
                        this.cacheAttr::getCacheName, aux::getCacheName);

                // IT USED TO BE THE CASE THAT (If the auxiliary is not a lateral, or the cache
                // attributes
                // have 'getUseLateral' set, all the elements currently in
                // memory are written to the lateral before disposing)
                // I changed this. It was excessive. Only the disk cache needs the items, since only
                // the disk cache is in a situation to not get items on a put.
                if (aux.getCacheType() == CacheType.DISK_CACHE)
                {
                    final int numToFree = memCache.getSize();
                    memCache.freeElements(numToFree);

                    log.info("In DISPOSE, [{0}] put {1} into auxiliary [{2}]",
                            this.cacheAttr::getCacheName, () -> numToFree,
                            aux::getCacheName);
                }

                // Dispose of the auxiliary
                aux.dispose();
            }
            catch (final IOException ex)
            {
                log.error("Failure disposing of aux.", ex);
            }
        }

        log.info("In DISPOSE, [{0}] disposing of memory cache.",
                this.cacheAttr::getCacheName);
        try
        {
            memCache.dispose();
        }
        catch (final IOException ex)
        {
            log.error("Failure disposing of memCache", ex);
        }
    }

    protected void doExpires(final ICacheElement<K, V> element)
    {
        missCountExpired.incrementAndGet();
        remove(element.getKey());
    }

    /**
     * Gets an item from the cache.
     *
     * @param key
     * @return element from the cache, or null if not present
     * @see org.apache.commons.jcs3.engine.behavior.ICache#get(Object)
     */
    @Override
    public ICacheElement<K, V> get(final K key)
    {
        return get(key, false);
    }

    /**
     * Look in memory, then disk, remote, or laterally for this item. The order is dependent on the
     * order in the cache.ccf file.
     * <p>
     * Do not try to go remote or laterally for this get if it is localOnly. Otherwise try to go
     * remote or lateral if such an auxiliary is configured for this region.
     *
     * @param key
     * @param localOnly
     * @return ICacheElement
     */
    protected ICacheElement<K, V> get(final K key, final boolean localOnly)
    {
        ICacheElement<K, V> element = null;

        boolean found = false;

        log.debug("get: key = {0}, localOnly = {1}", key, localOnly);

        try
        {
            // First look in memory cache
            element = memCache.get(key);

            if (element != null)
            {
                // Found in memory cache
                if (isExpired(element))
                {
                    log.debug("{0} - Memory cache hit, but element expired",
                            () -> cacheAttr.getCacheName());

                    doExpires(element);
                    element = null;
                }
                else
                {
                    log.debug("{0} - Memory cache hit", () -> cacheAttr.getCacheName());

                    // Update counters
                    hitCountRam.incrementAndGet();
                }

                found = true;
            }
            else
            {
                // Item not found in memory. If local invocation look in aux
                // caches, even if not local look in disk auxiliaries
                for (final AuxiliaryCache<K, V> aux : auxCaches)
                {
                    final CacheType cacheType = aux.getCacheType();

                    if (!localOnly || cacheType == CacheType.DISK_CACHE)
                    {
                        log.debug("Attempting to get from aux [{0}] which is of type: {1}",
                                aux::getCacheName, () -> cacheType);

                        try
                        {
                            element = aux.get(key);
                        }
                        catch (final IOException e)
                        {
                            log.error("Error getting from aux", e);
                        }
                    }

                    log.debug("Got CacheElement: {0}", element);

                    // Item found in one of the auxiliary caches.
                    if (element != null)
                    {
                        if (isExpired(element))
                        {
                            log.debug("{0} - Aux cache[{1}] hit, but element expired.",
                                    () -> cacheAttr.getCacheName(), aux::getCacheName);

                            // This will tell the remotes to remove the item
                            // based on the element's expiration policy. The elements attributes
                            // associated with the item when it created govern its behavior
                            // everywhere.
                            doExpires(element);
                            element = null;
                        }
                        else
                        {
                            log.debug("{0} - Aux cache[{1}] hit.",
                                    () -> cacheAttr.getCacheName(), aux::getCacheName);

                            // Update counters
                            hitCountAux.incrementAndGet();
                            copyAuxiliaryRetrievedItemToMemory(element);
                        }

                        found = true;

                        break;
                    }
                }
            }
        }
        catch (final IOException e)
        {
            log.error("Problem encountered getting element.", e);
        }

        if (!found)
        {
            missCountNotFound.incrementAndGet();

            log.debug("{0} - Miss", () -> cacheAttr.getCacheName());
        }

        if (element != null)
        {
            element.getElementAttributes().setLastAccessTimeNow();
        }

        return element;
    }

    /**
     * Gets the list of auxiliary caches for this region.
     *
     * @return a list of auxiliary caches, may be empty, never null
     * @since 3.1
     */
    public List<AuxiliaryCache<K, V>> getAuxCacheList()
    {
        return this.auxCaches;
    }

    /**
     * Gets the ICompositeCacheAttributes attribute of the Cache object.
     *
     * @return The ICompositeCacheAttributes value
     */
    public ICompositeCacheAttributes getCacheAttributes()
    {
        return this.cacheAttr;
    }

    /**
     * Gets the cacheName attribute of the Cache object. This is also known as the region name.
     *
     * @return The cacheName value
     */
    @Override
    public String getCacheName()
    {
        return cacheAttr.getCacheName();
    }

    /**
     * Gets the cacheType attribute of the Cache object.
     *
     * @return The cacheType value
     */
    @Override
    public CacheType getCacheType()
    {
        return CacheType.CACHE_HUB;
    }

    /**
     * Gets the default element attribute of the Cache object This returns a copy. It does not
     * return a reference to the attributes.
     *
     * @return The attributes value
     */
    public IElementAttributes getElementAttributes()
    {
        if (attr != null)
        {
            return attr.clone();
        }
        return null;
    }

    /**
     * Gets the elementAttributes attribute of the Cache object.
     *
     * @param key
     * @return The elementAttributes value
     * @throws CacheException
     * @throws IOException
     */
    public IElementAttributes getElementAttributes(final K key)
        throws CacheException, IOException
    {
        final ICacheElement<K, V> ce = get(key);
        if (ce == null)
        {
            throw new ObjectNotFoundException("key " + key + " is not found");
        }
        return ce.getElementAttributes();
    }

    /**
     * Number of times a requested item was found in and auxiliary cache.
     * @return number of auxiliary hits.
     */
    public long getHitCountAux()
    {
        return hitCountAux.get();
    }

    /**
     * Number of times a requested item was found in the memory cache.
     *
     * @return number of hits in memory
     */
    public long getHitCountRam()
    {
        return hitCountRam.get();
    }

    /**
     * Returns the key matcher used by get matching.
     *
     * @return keyMatcher
     */
    public IKeyMatcher<K> getKeyMatcher()
    {
        return this.keyMatcher;
    }

    /**
     * Gets a set of the keys for all elements in the cache
     *
     * @return A set of the key type
     */
    public Set<K> getKeySet()
    {
        return getKeySet(false);
    }

    /**
     * Gets a set of the keys for all elements in the cache
     *
     * @param localOnly true if only memory keys are requested
     * @return A set of the key type
     */
    public Set<K> getKeySet(final boolean localOnly)
    {
        return Stream.concat(memCache.getKeySet().stream(), auxCaches.stream()
            .filter(aux -> !localOnly || aux.getCacheType() == CacheType.DISK_CACHE)
            .flatMap(aux -> {
                try
                {
                    return aux.getKeySet().stream();
                }
                catch (final IOException e)
                {
                    return Stream.of();
                }
            }))
            .collect(Collectors.toSet());
    }

    /**
     * Build a map of all the matching elements in all of the auxiliaries and memory.
     *
     * @param pattern
     * @return a map of K key to ICacheElement&lt;K, V&gt; element, or an empty map if there is no
     *         data in cache for any matching keys
     */
    @Override
    public Map<K, ICacheElement<K, V>> getMatching(final String pattern)
    {
        return getMatching(pattern, false);
    }

    /**
     * Build a map of all the matching elements in all of the auxiliaries and memory. Items in
     * memory will replace from the auxiliaries in the returned map. The auxiliaries are accessed in
     * opposite order. It's assumed that those closer to home are better.
     * <p>
     * Do not try to go remote or laterally for this get if it is localOnly. Otherwise try to go
     * remote or lateral if such an auxiliary is configured for this region.
     *
     * @param pattern
     * @param localOnly
     * @return a map of K key to ICacheElement&lt;K, V&gt; element, or an empty map if there is no
     *         data in cache for any matching keys
     */
    protected Map<K, ICacheElement<K, V>> getMatching(final String pattern, final boolean localOnly)
    {
        log.debug("get: pattern [{0}], localOnly = {1}", pattern, localOnly);

        try
        {
            return Stream.concat(
                    getMatchingFromMemory(pattern).entrySet().stream(),
                    getMatchingFromAuxiliaryCaches(pattern, localOnly).entrySet().stream())
                    .collect(Collectors.toMap(
                            Entry::getKey,
                            Entry::getValue,
                            // Prefer memory entries
                            (mem, aux) -> mem));
        }
        catch (final IOException e)
        {
            log.error("Problem encountered getting elements.", e);
        }

        return new HashMap<>();
    }

    /**
     * If local invocation look in aux caches, even if not local look in disk auxiliaries.
     * <p>
     * Moves in reverse order of definition. This will allow you to override those that are from the
     * remote with those on disk.
     *
     * @param pattern
     * @param localOnly
     * @return a map of K key to ICacheElement&lt;K, V&gt; element, or an empty map if there is no
     *         data in cache for any matching keys
     * @throws IOException
     */
    private Map<K, ICacheElement<K, V>> getMatchingFromAuxiliaryCaches(final String pattern, final boolean localOnly)
        throws IOException
    {
        final Map<K, ICacheElement<K, V>> elements = new HashMap<>();

        for (final ListIterator<AuxiliaryCache<K, V>> i = auxCaches.listIterator(auxCaches.size()); i.hasPrevious();)
        {
            final AuxiliaryCache<K, V> aux = i.previous();

            final Map<K, ICacheElement<K, V>> elementsFromAuxiliary =
                new HashMap<>();

            final CacheType cacheType = aux.getCacheType();

            if (!localOnly || cacheType == CacheType.DISK_CACHE)
            {
                log.debug("Attempting to get from aux [{0}] which is of type: {1}",
                        aux::getCacheName, () -> cacheType);

                try
                {
                    elementsFromAuxiliary.putAll(aux.getMatching(pattern));
                }
                catch (final IOException e)
                {
                    log.error("Error getting from aux", e);
                }

                log.debug("Got CacheElements: {0}", elementsFromAuxiliary);

                processRetrievedElements(aux, elementsFromAuxiliary);
                elements.putAll(elementsFromAuxiliary);
            }
        }

        return elements;
    }

    /**
     * Gets the key array from the memcache. Builds a set of matches. Calls getMultiple with the
     * set. Returns a map: key -&gt; result.
     *
     * @param pattern
     * @return a map of K key to ICacheElement&lt;K, V&gt; element, or an empty map if there is no
     *         data in cache for any matching keys
     * @throws IOException
     */
    protected Map<K, ICacheElement<K, V>> getMatchingFromMemory(final String pattern)
        throws IOException
    {
        // find matches in key array
        // this avoids locking the memory cache, but it uses more memory
        final Set<K> keyArray = memCache.getKeySet();
        final Set<K> matchingKeys = getKeyMatcher().getMatchingKeysFromArray(pattern, keyArray);

        // call get multiple
        return getMultipleFromMemory(matchingKeys);
    }

    /**
     * Access to the memory cache for instrumentation.
     *
     * @return the MemoryCache implementation
     */
    public IMemoryCache<K, V> getMemoryCache()
    {
        return memCache;
    }

    /**
     * Number of times a requested element was found but was expired.
     * @return number of found but expired gets.
     */
    public long getMissCountExpired()
    {
        return missCountExpired.get();
    }

    /**
     * Number of times a requested element was not found.
     * @return number of misses.
     */
    public long getMissCountNotFound()
    {
        return missCountNotFound.get();
    }

    /**
     * Gets multiple items from the cache based on the given set of keys.
     *
     * @param keys
     * @return a map of K key to ICacheElement&lt;K, V&gt; element, or an empty map if there is no
     *         data in cache for any of these keys
     */
    @Override
    public Map<K, ICacheElement<K, V>> getMultiple(final Set<K> keys)
    {
        return getMultiple(keys, false);
    }

    /**
     * Look in memory, then disk, remote, or laterally for these items. The order is dependent on
     * the order in the cache.ccf file. Keep looking in each cache location until either the element
     * is found, or the method runs out of places to look.
     * <p>
     * Do not try to go remote or laterally for this get if it is localOnly. Otherwise try to go
     * remote or lateral if such an auxiliary is configured for this region.
     *
     * @param keys
     * @param localOnly
     * @return ICacheElement
     */
    protected Map<K, ICacheElement<K, V>> getMultiple(final Set<K> keys, final boolean localOnly)
    {
        final Map<K, ICacheElement<K, V>> elements = new HashMap<>();

        log.debug("get: key = {0}, localOnly = {1}", keys, localOnly);

        try
        {
            // First look in memory cache
            elements.putAll(getMultipleFromMemory(keys));

            // If fewer than all items were found in memory, then keep looking.
            if (elements.size() != keys.size())
            {
                final Set<K> remainingKeys = pruneKeysFound(keys, elements);
                elements.putAll(getMultipleFromAuxiliaryCaches(remainingKeys, localOnly));
            }
        }
        catch (final IOException e)
        {
            log.error("Problem encountered getting elements.", e);
        }

        // if we didn't find all the elements, increment the miss count by the number of elements not found
        if (elements.size() != keys.size())
        {
            missCountNotFound.addAndGet(keys.size() - elements.size());

            log.debug("{0} - {1} Misses", () -> cacheAttr.getCacheName(),
                    () -> keys.size() - elements.size());
        }

        return elements;
    }

    /**
     * If local invocation look in aux caches, even if not local look in disk auxiliaries.
     *
     * @param keys
     * @param localOnly
     * @return the elements found in the auxiliary caches
     * @throws IOException
     */
    private Map<K, ICacheElement<K, V>> getMultipleFromAuxiliaryCaches(final Set<K> keys, final boolean localOnly)
        throws IOException
    {
        final Map<K, ICacheElement<K, V>> elements = new HashMap<>();
        Set<K> remainingKeys = new HashSet<>(keys);

        for (final AuxiliaryCache<K, V> aux : auxCaches)
        {
            final Map<K, ICacheElement<K, V>> elementsFromAuxiliary =
                new HashMap<>();

            final CacheType cacheType = aux.getCacheType();

            if (!localOnly || cacheType == CacheType.DISK_CACHE)
            {
                log.debug("Attempting to get from aux [{0}] which is of type: {1}",
                        aux::getCacheName, () -> cacheType);

                try
                {
                    elementsFromAuxiliary.putAll(aux.getMultiple(remainingKeys));
                }
                catch (final IOException e)
                {
                    log.error("Error getting from aux", e);
                }
            }

            log.debug("Got CacheElements: {0}", elementsFromAuxiliary);

            processRetrievedElements(aux, elementsFromAuxiliary);
            elements.putAll(elementsFromAuxiliary);

            if (elements.size() == keys.size())
            {
                break;
            }
            remainingKeys = pruneKeysFound(keys, elements);
        }

        return elements;
    }

    /**
     * Gets items for the keys in the set. Returns a map: key -> result.
     *
     * @param keys
     * @return the elements found in the memory cache
     * @throws IOException
     */
    private Map<K, ICacheElement<K, V>> getMultipleFromMemory(final Set<K> keys)
        throws IOException
    {
        final Map<K, ICacheElement<K, V>> elementsFromMemory = memCache.getMultiple(keys);
        elementsFromMemory.entrySet().removeIf(entry -> {
            final ICacheElement<K, V> element = entry.getValue();
            if (isExpired(element))
            {
                log.debug("{0} - Memory cache hit, but element expired",
                        () -> cacheAttr.getCacheName());

                doExpires(element);
                return true;
            }
            log.debug("{0} - Memory cache hit", () -> cacheAttr.getCacheName());

            // Update counters
            hitCountRam.incrementAndGet();
            return false;
        });

        return elementsFromMemory;
    }

    /**
     * Gets the size attribute of the Cache object. This return the number of elements, not the byte
     * size.
     *
     * @return The size value
     */
    @Override
    public int getSize()
    {
        return memCache.getSize();
    }

    /**
     * This returns data gathered for this region and all the auxiliaries it currently uses.
     *
     * @return Statistics and Info on the Region.
     */
    public ICacheStats getStatistics()
    {
        final ICacheStats stats = new CacheStats();
        stats.setRegionName(this.getCacheName());

        // store the composite cache stats first
        stats.setStatElements(Arrays.asList(
                new StatElement<>("HitCountRam", Long.valueOf(getHitCountRam())),
                new StatElement<>("HitCountAux", Long.valueOf(getHitCountAux()))));

        // memory + aux, memory is not considered an auxiliary internally
        final ArrayList<IStats> auxStats = new ArrayList<>(auxCaches.size() + 1);

        auxStats.add(getMemoryCache().getStatistics());
        auxStats.addAll(auxCaches.stream()
                .map(AuxiliaryCache::getStatistics)
                .collect(Collectors.toList()));

        // store the auxiliary stats
        stats.setAuxiliaryCacheStats(auxStats);

        return stats;
    }

    /**
     * Gets stats for debugging.
     *
     * @return String
     */
    @Override
    public String getStats()
    {
        return getStatistics().toString();
    }

    /**
     * Gets the status attribute of the Cache object.
     *
     * @return The status value
     */
    @Override
    public CacheStatus getStatus()
    {
        return alive.get() ? CacheStatus.ALIVE : CacheStatus.DISPOSED;
    }

    /**
     * @return the updateCount.
     */
    public long getUpdateCount()
    {
        return updateCount.get();
    }

    /**
     * If there are event handlers for the item, then create an event and queue it up.
     * <p>
     * This does not call handle directly; instead the handler and the event are put into a queue.
     * This prevents the event handling from blocking normal cache operations.
     *
     * @param element the item
     * @param eventType the event type
     */
    public void handleElementEvent(final ICacheElement<K, V> element, final ElementEventType eventType)
    {
        final ArrayList<IElementEventHandler> eventHandlers = element.getElementAttributes().getElementEventHandlers();
        if (eventHandlers != null)
        {
            log.debug("Element Handlers are registered.  Create event type {0}", eventType);
            if (elementEventQ == null)
            {
                log.warn("No element event queue available for cache {0}", this::getCacheName);
                return;
            }
            final IElementEvent<ICacheElement<K, V>> event = new ElementEvent<>(element, eventType);
            for (final IElementEventHandler hand : eventHandlers)
            {
                try
                {
                   elementEventQ.addElementEvent(hand, event);
                }
                catch (final IOException e)
                {
                    log.error("Trouble adding element event to queue", e);
                }
            }
        }
    }

    /**
     * Determine if the element is expired based on the values of the element attributes
     *
     * @param element the element
     * @return true if the element is expired
     */
    public boolean isExpired(final ICacheElement<K, V> element)
    {
        return isExpired(element, System.currentTimeMillis(),
                ElementEventType.EXCEEDED_MAXLIFE_ONREQUEST,
                ElementEventType.EXCEEDED_IDLETIME_ONREQUEST);
    }

    /**
     * Check if the element is expired based on the values of the element attributes
     *
     * @param element the element
     * @param timestamp the timestamp to compare to
     * @param eventMaxlife the event to fire in case the max life time is exceeded
     * @param eventIdle the event to fire in case the idle time is exceeded
     * @return true if the element is expired
     */
    public boolean isExpired(final ICacheElement<K, V> element, final long timestamp,
            final ElementEventType eventMaxlife, final ElementEventType eventIdle)
    {
        try
        {
            final IElementAttributes attributes = element.getElementAttributes();

            if (!attributes.getIsEternal())
            {
                // Remove if maxLifeSeconds exceeded
                final long maxLifeSeconds = attributes.getMaxLife();
                final long createTime = attributes.getCreateTime();

                final long timeFactorForMilliseconds = attributes.getTimeFactorForMilliseconds();

                if (maxLifeSeconds != -1 && timestamp - createTime > maxLifeSeconds * timeFactorForMilliseconds)
                {
                    log.debug("Exceeded maxLife: {0}", element::getKey);

                    handleElementEvent(element, eventMaxlife);
                    return true;
                }
                final long idleTime = attributes.getIdleTime();
                final long lastAccessTime = attributes.getLastAccessTime();

                // Remove if maxIdleTime exceeded
                // If you have a 0 size memory cache, then the last access will
                // not get updated.
                // you will need to set the idle time to -1.
                if (idleTime != -1 && timestamp - lastAccessTime > idleTime * timeFactorForMilliseconds)
                {
                    log.debug("Exceeded maxIdle: {0}", element::getKey);

                    handleElementEvent(element, eventIdle);
                    return true;
                }
            }
        }
        catch (final Exception e)
        {
            log.error("Error determining expiration period, expiring", e);
            return true;
        }

        return false;
    }

    /**
     * Do not try to go remote or laterally for this get.
     *
     * @param key
     * @return ICacheElement
     */
    public ICacheElement<K, V> localGet(final K key)
    {
        return get(key, true);
    }

    /**
     * Build a map of all the matching elements in all of the auxiliaries and memory. Do not try to
     * go remote or laterally for this data.
     *
     * @param pattern
     * @return a map of K key to ICacheElement&lt;K, V&gt; element, or an empty map if there is no
     *         data in cache for any matching keys
     */
    public Map<K, ICacheElement<K, V>> localGetMatching(final String pattern)
    {
        return getMatching(pattern, true);
    }

    /**
     * Gets multiple items from the cache based on the given set of keys. Do not try to go remote or
     * laterally for this data.
     *
     * @param keys
     * @return a map of K key to ICacheElement&lt;K, V&gt; element, or an empty map if there is no
     *         data in cache for any of these keys
     */
    public Map<K, ICacheElement<K, V>> localGetMultiple(final Set<K> keys)
    {
        return getMultiple(keys, true);
    }

    /**
     * Do not propagate removeall laterally or remotely.
     *
     * @param key
     * @return true if the item was already in the cache.
     */
    public boolean localRemove(final K key)
    {
        return remove(key, true);
    }

    /**
     * Will not pass the remove message remotely.
     *
     * @throws IOException
     */
    public void localRemoveAll()
        throws IOException
    {
        removeAll(true);
    }

    /**
     * Standard update method.
     *
     * @param ce
     * @throws IOException
     */
    public void localUpdate(final ICacheElement<K, V> ce)
        throws IOException
    {
        update(ce, true);
    }

    /**
     * Remove expired elements retrieved from an auxiliary. Update memory with good items.
     *
     * @param aux the auxiliary cache instance
     * @param elementsFromAuxiliary
     * @throws IOException
     */
    private void processRetrievedElements(final AuxiliaryCache<K, V> aux, final Map<K, ICacheElement<K, V>> elementsFromAuxiliary)
        throws IOException
    {
        elementsFromAuxiliary.entrySet().removeIf(entry -> {
            final ICacheElement<K, V> element = entry.getValue();

            // Item found in one of the auxiliary caches.
            if (element != null)
            {
                if (isExpired(element))
                {
                    log.debug("{0} - Aux cache[{1}] hit, but element expired.",
                            () -> cacheAttr.getCacheName(), aux::getCacheName);

                    // This will tell the remote caches to remove the item
                    // based on the element's expiration policy. The elements attributes
                    // associated with the item when it created govern its behavior
                    // everywhere.
                    doExpires(element);
                    return true;
                }
                log.debug("{0} - Aux cache[{1}] hit.",
                        () -> cacheAttr.getCacheName(), aux::getCacheName);

                // Update counters
                hitCountAux.incrementAndGet();
                try
                {
                    copyAuxiliaryRetrievedItemToMemory(element);
                }
                catch (final IOException e)
                {
                    log.error("{0} failed to copy element to memory {1}",
                            cacheAttr.getCacheName(), element, e);
                }
            }

            return false;
        });
    }

    /**
     * Returns a set of keys that were not found.
     *
     * @param keys
     * @param foundElements
     * @return the original set of cache keys, minus any cache keys present in the map keys of the
     *         foundElements map
     */
    private Set<K> pruneKeysFound(final Set<K> keys, final Map<K, ICacheElement<K, V>> foundElements)
    {
        final Set<K> remainingKeys = new HashSet<>(keys);
        remainingKeys.removeAll(foundElements.keySet());

        return remainingKeys;
    }

    /**
     * Removes an item from the cache.
     *
     * @param key
     * @return true is it was removed
     * @see org.apache.commons.jcs3.engine.behavior.ICache#remove(Object)
     */
    @Override
    public boolean remove(final K key)
    {
        return remove(key, false);
    }

    /**
     * fromRemote: If a remove call was made on a cache with both, then the remote should have been
     * called. If it wasn't then the remote is down. we'll assume it is down for all. If it did come
     * from the remote then the cache is remotely configured and lateral removal is unnecessary. If
     * it came laterally then lateral removal is unnecessary. Does this assume that there is only
     * one lateral and remote for the cache? Not really, the initial removal should take care of the
     * problem if the source cache was similarly configured. Otherwise the remote cache, if it had
     * no laterals, would remove all the elements from remotely configured caches, but if those
     * caches had some other weird laterals that were not remotely configured, only laterally
     * propagated then they would go out of synch. The same could happen for multiple remotes. If
     * this looks necessary we will need to build in an identifier to specify the source of a
     * removal.
     *
     * @param key
     * @param localOnly
     * @return true if the item was in the cache, else false
     */
    protected boolean remove(final K key, final boolean localOnly)
    {
        removeCount.incrementAndGet();

        boolean removed = false;

        try
        {
            removed = memCache.remove(key);
        }
        catch (final IOException e)
        {
            log.error(e);
        }

        // Removes from all auxiliary caches.
        for (final ICache<K, V> aux : auxCaches)
        {
            if (aux == null)
            {
                continue;
            }

            final CacheType cacheType = aux.getCacheType();

            // for now let laterals call remote remove but not vice versa
            if (localOnly && (cacheType == CacheType.REMOTE_CACHE || cacheType == CacheType.LATERAL_CACHE))
            {
                continue;
            }
            try
            {
                log.debug("Removing {0} from cacheType {1}", key, cacheType);

                final boolean b = aux.remove(key);

                // Don't take the remote removal into account.
                if (!removed && cacheType != CacheType.REMOTE_CACHE)
                {
                    removed = b;
                }
            }
            catch (final IOException ex)
            {
                log.error("Failure removing from aux", ex);
            }
        }

        return removed;
    }

    /**
     * Clears the region. This command will be sent to all auxiliaries. Some auxiliaries, such as
     * the JDBC disk cache, can be configured to not honor removeAll requests.
     *
     * @see org.apache.commons.jcs3.engine.behavior.ICache#removeAll()
     */
    @Override
    public void removeAll()
        throws IOException
    {
        removeAll(false);
    }

    /**
     * Removes all cached items.
     *
     * @param localOnly must pass in false to get remote and lateral aux's updated. This prevents
     *            looping.
     * @throws IOException
     */
    protected void removeAll(final boolean localOnly)
        throws IOException
    {
        try
        {
            memCache.removeAll();

            log.debug("Removed All keys from the memory cache.");
        }
        catch (final IOException ex)
        {
            log.error("Trouble updating memory cache.", ex);
        }

        // Removes from all auxiliary disk caches.
        auxCaches.stream()
            .filter(aux -> aux.getCacheType() == CacheType.DISK_CACHE || !localOnly)
            .forEach(aux -> {
                try
                {
                    log.debug("Removing All keys from cacheType {0}",
                            aux::getCacheType);

                    aux.removeAll();
                }
                catch (final IOException ex)
                {
                    log.error("Failure removing all from aux " + aux, ex);
                }
            });
    }

    /**
     * Calling save cause the entire contents of the memory cache to be flushed to all auxiliaries.
     * Though this put is extremely fast, this could bog the cache and should be avoided. The
     * dispose method should call a version of this. Good for testing.
     */
    public void save()
    {
        if (!alive.get())
        {
            return;
        }

        auxCaches.stream()
            .filter(aux -> aux.getStatus() == CacheStatus.ALIVE)
            .forEach(aux -> {
                memCache.getKeySet().stream()
                    .map(this::localGet)
                    .filter(Objects::nonNull)
                    .forEach(ce -> {
                        try
                        {
                            aux.update(ce);
                        }
                        catch (final IOException e)
                        {
                            log.warn("Failure saving element {0} to aux {1}.", ce, aux, e);
                        }
                    });
            });

        log.debug("Called save for [{0}]", cacheAttr::getCacheName);
    }

    /**
     * This sets the list of auxiliary caches for this region.
     * It filters out null caches
     *
     * @param auxCaches
     * @since 3.1
     */
    public void setAuxCaches(final List<AuxiliaryCache<K, V>> auxCaches)
    {
        this.auxCaches = auxCaches.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(CopyOnWriteArrayList::new));
    }

    /**
     * Sets the ICompositeCacheAttributes attribute of the Cache object.
     *
     * @param cattr The new ICompositeCacheAttributes value
     */
    public void setCacheAttributes(final ICompositeCacheAttributes cattr)
    {
        this.cacheAttr = cattr;
        // need a better way to do this, what if it is in error
        this.memCache.initialize(this);
    }

    /**
     * Injector for cache manager
     *
     * @param manager
     */
    public void setCompositeCacheManager(final CompositeCacheManager manager)
    {
        this.cacheManager = manager;
    }

    /**
     * Sets the default element attribute of the Cache object.
     *
     * @param attr
     */
    public void setElementAttributes(final IElementAttributes attr)
    {
        this.attr = attr;
    }

    /**
     * Injector for Element event queue
     *
     * @param queue
     */
    public void setElementEventQueue(final IElementEventQueue queue)
    {
        this.elementEventQ = queue;
    }

    /**
     * Sets the key matcher used by get matching.
     *
     * @param keyMatcher
     */
    @Override
    public void setKeyMatcher(final IKeyMatcher<K> keyMatcher)
    {
        if (keyMatcher != null)
        {
            this.keyMatcher = keyMatcher;
        }
    }

    /**
     * @see org.apache.commons.jcs3.engine.behavior.IRequireScheduler#setScheduledExecutorService(java.util.concurrent.ScheduledExecutorService)
     */
    @Override
    public void setScheduledExecutorService(final ScheduledExecutorService scheduledExecutor)
    {
        if (cacheAttr.isUseMemoryShrinker())
        {
            future = scheduledExecutor.scheduleAtFixedRate(
                    new ShrinkerThread<>(this), 0, cacheAttr.getShrinkerIntervalSeconds(),
                    TimeUnit.SECONDS);
        }

        if (memCache instanceof IRequireScheduler)
        {
            ((IRequireScheduler) memCache).setScheduledExecutorService(scheduledExecutor);
        }
    }

    /**
     * Writes the specified element to any disk auxiliaries. Might want to rename this "overflow" in
     * case the hub wants to do something else.
     * <p>
     * If JCS is not configured to use the disk as a swap, that is if the
     * CompositeCacheAttribute diskUsagePattern is not SWAP_ONLY, then the item will not be spooled.
     *
     * @param ce The CacheElement
     */
    public void spoolToDisk(final ICacheElement<K, V> ce)
    {
        // if the item is not spoolable, return
        if (!ce.getElementAttributes().getIsSpool())
        {
            // there is an event defined for this.
            handleElementEvent(ce, ElementEventType.SPOOLED_NOT_ALLOWED);
            return;
        }

        boolean diskAvailable = false;

        // SPOOL TO DISK.
        for (final ICache<K, V> aux : auxCaches)
        {
            if (aux.getCacheType() == CacheType.DISK_CACHE)
            {
                diskAvailable = true;

                if (cacheAttr.getDiskUsagePattern() == DiskUsagePattern.SWAP)
                {
                    // write the last items to disk.2
                    try
                    {
                        handleElementEvent(ce, ElementEventType.SPOOLED_DISK_AVAILABLE);
                        aux.update(ce);
                    }
                    catch (final IOException ex)
                    {
                        // impossible case.
                        log.error("Problem spooling item to disk cache.", ex);
                        throw new IllegalStateException(ex.getMessage());
                    }

                    log.debug("spoolToDisk done for: {0} on disk cache[{1}]",
                            ce::getKey, aux::getCacheName);
                }
                else
                {
                    log.debug("DiskCache available, but JCS is not configured "
                            + "to use the DiskCache as a swap.");
                }
            }
        }

        if (!diskAvailable)
        {
            handleElementEvent(ce, ElementEventType.SPOOLED_DISK_NOT_AVAILABLE);
        }
    }

    /**
     * This returns the stats.
     *
     * @return getStats()
     */
    @Override
    public String toString()
    {
        return getStats();
    }

    /**
     * Standard update method.
     *
     * @param ce
     * @throws IOException
     */
    @Override
    public void update(final ICacheElement<K, V> ce)
        throws IOException
    {
        update(ce, false);
    }

    /**
     * Put an item into the cache. If it is localOnly, then do no notify remote or lateral
     * auxiliaries.
     *
     * @param cacheElement the ICacheElement&lt;K, V&gt;
     * @param localOnly Whether the operation should be restricted to local auxiliaries.
     * @throws IOException
     */
    protected void update(final ICacheElement<K, V> cacheElement, final boolean localOnly)
        throws IOException
    {

        if (cacheElement.getKey() instanceof String
            && cacheElement.getKey().toString().endsWith(NAME_COMPONENT_DELIMITER))
        {
            throw new IllegalArgumentException("key must not end with " + NAME_COMPONENT_DELIMITER
                + " for a put operation");
        }
        if (cacheElement.getKey() instanceof GroupId)
        {
            throw new IllegalArgumentException("key cannot be a GroupId for a put operation");
        }

        log.debug("Updating memory cache {0}", cacheElement::getKey);

        updateCount.incrementAndGet();
        memCache.update(cacheElement);
        updateAuxiliaries(cacheElement, localOnly);

        cacheElement.getElementAttributes().setLastAccessTimeNow();
    }

    /**
     * This method is responsible for updating the auxiliaries if they are present. If it is local
     * only, any lateral and remote auxiliaries will not be updated.
     * <p>
     * Before updating an auxiliary it checks to see if the element attributes permit the operation.
     * <p>
     * Disk auxiliaries are only updated if the disk cache is not merely used as a swap. If the disk
     * cache is merely a swap, then items will only go to disk when they overflow from memory.
     * <p>
     * This is called by update(cacheElement, localOnly) after it updates the memory cache.
     * <p>
     * This is protected to make it testable.
     *
     * @param cacheElement
     * @param localOnly
     * @throws IOException
     */
    protected void updateAuxiliaries(final ICacheElement<K, V> cacheElement, final boolean localOnly)
        throws IOException
    {
        // UPDATE AUXILLIARY CACHES
        // There are 3 types of auxiliary caches: remote, lateral, and disk
        // more can be added if future auxiliary caches don't fit the model
        // You could run a database cache as either a remote or a local disk.
        // The types would describe the purpose.
        if (!auxCaches.isEmpty())
        {
            log.debug("Updating auxiliary caches");
        }
        else
        {
            log.debug("No auxiliary cache to update");
        }

        for (final ICache<K, V> aux : auxCaches)
        {
            if (aux == null)
            {
                continue;
            }

            log.debug("Auxiliary cache type: {0}", aux.getCacheType());

            switch (aux.getCacheType())
            {
                // SEND TO REMOTE STORE
                case REMOTE_CACHE:
                    log.debug("ce.getElementAttributes().getIsRemote() = {0}",
                        cacheElement.getElementAttributes()::getIsRemote);

                    if (cacheElement.getElementAttributes().getIsRemote() && !localOnly)
                    {
                        try
                        {
                            // need to make sure the group cache understands that
                            // the key is a group attribute on update
                            aux.update(cacheElement);
                            log.debug("Updated remote store for {0} {1}",
                                    cacheElement.getKey(), cacheElement);
                        }
                        catch (final IOException ex)
                        {
                            log.error("Failure in updateExclude", ex);
                        }
                    }
                    break;

                // SEND LATERALLY
                case LATERAL_CACHE:
                    // lateral can't do the checking since it is dependent on the
                    // cache region restrictions
                    log.debug("lateralcache in aux list: cattr {0}", cacheAttr::isUseLateral);
                    if (cacheAttr.isUseLateral() && cacheElement.getElementAttributes().getIsLateral() && !localOnly)
                    {
                        // DISTRIBUTE LATERALLY
                        // Currently always multicast even if the value is
                        // unchanged, to cause the cache item to move to the front.
                        aux.update(cacheElement);
                        log.debug("updated lateral cache for {0}", cacheElement::getKey);
                    }
                    break;

                // update disk if the usage pattern permits
                case DISK_CACHE:
                    log.debug("diskcache in aux list: cattr {0}", cacheAttr::isUseDisk);
                    if (cacheAttr.isUseDisk()
                        && cacheAttr.getDiskUsagePattern() == DiskUsagePattern.UPDATE
                        && cacheElement.getElementAttributes().getIsSpool())
                    {
                        aux.update(cacheElement);
                        log.debug("updated disk cache for {0}", cacheElement::getKey);
                    }
                    break;

                default: // CACHE_HUB
                    break;
            }
        }
    }
}
