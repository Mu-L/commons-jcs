package org.apache.commons.jcs3.engine.logging;

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

import org.apache.commons.jcs3.engine.logging.behavior.ICacheEvent;
import org.apache.commons.jcs3.engine.logging.behavior.ICacheEventLogger;

/**
 * For testing the configurator.
 */
public class MockCacheEventLogger
    implements ICacheEventLogger
{
    /** test property */
    private String testProperty;

    /**
     * @param source
     * @param region
     * @param eventName
     * @param optionalDetails
     * @param key
     * @return ICacheEvent
     */
    @Override
    public <T> ICacheEvent<T> createICacheEvent( final String source, final String region, final String eventName, final String optionalDetails,
                                          final T key )
    {
        return new CacheEvent<>();
    }

    /**
     * @return testProperty
     */
    public String getTestProperty()
    {
        return testProperty;
    }

    /**
     * @param source
     * @param eventName
     * @param optionalDetails
     */
    @Override
    public void logApplicationEvent( final String source, final String eventName, final String optionalDetails )
    {
        // TODO Auto-generated method stub
    }

    /**
     * @param source
     * @param eventName
     * @param errorMessage
     */
    @Override
    public void logError( final String source, final String eventName, final String errorMessage )
    {
        // TODO Auto-generated method stub
    }

    /**
     * @param event
     */
    @Override
    public <T> void logICacheEvent( final ICacheEvent<T> event )
    {
        // TODO Auto-generated method stub
    }

    /**
     * @param testProperty
     */
    public void setTestProperty( final String testProperty )
    {
        this.testProperty = testProperty;
    }
}
