
/*
 * Copyright 2001-2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jcs.yajcache.lang.ref;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import org.apache.jcs.yajcache.lang.annotation.*;

import org.apache.jcs.yajcache.lang.annotation.*;

/**
 * Soft reference with an embedded key.
 *
 * @author Hanson Char
 */
@CopyRightApache
public class KeyedSoftReference<T> extends SoftReference<T> implements IKey {
    private final @NonNullable String key;
    
//    KeyedSoftRef(String key, T value) {
//	super(value);
//        this.key = key;
//    }
    public KeyedSoftReference(@NonNullable String key, @NonNullable T referrent, 
            ReferenceQueue<? super T> q) 
    {
        super(referrent, q);
        this.key = key;
    }
    @Implements(IKey.class)
    public String getKey() {
        return this.key;
    }
}
