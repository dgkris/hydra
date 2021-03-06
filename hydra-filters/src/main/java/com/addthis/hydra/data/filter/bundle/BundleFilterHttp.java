/*
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
package com.addthis.hydra.data.filter.bundle;

import java.io.File;
import java.io.IOException;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.addthis.basis.collect.HotMap;
import com.addthis.basis.util.Bytes;
import com.addthis.basis.util.Files;

import com.addthis.bundle.core.Bundle;
import com.addthis.bundle.value.ValueFactory;
import com.addthis.codec.Codec;
import com.addthis.codec.CodecJSON;
import com.addthis.hydra.common.hash.MD5HashFunction;
import com.addthis.hydra.data.filter.value.ValueFilterHttpGet;

import org.apache.commons.httpclient.URIException;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;
/**
 * This {@link BundleFilter BundleFilter} <span class="hydra-summary">does something with http</span>.
 * <p/>
 * <p/>
 * <p>Example:</p>
 * <pre>
 * </pre>
 *
 * @user-reference
 * @hydra-name http
 */
public class BundleFilterHttp extends BundleFilter {

    public static BundleFilterHttp create(BundleFilterTemplate url, String set) {
        BundleFilterHttp bfh = new BundleFilterHttp();
        bfh.url = url;
        bfh.set = set;
        return bfh;
    }

    private static final Logger log = LoggerFactory.getLogger(BundleFilterHttp.class);
    private static final Codec codec = new CodecJSON();

    @Codec.Set(codable = true)
    private CacheConfig cache;
    @Codec.Set(codable = true)
    private HttpConfig http;
    @Codec.Set(codable = true)
    private String defaultValue;
    @Codec.Set(codable = true)
    private boolean trace;
    @Codec.Set(codable = true)
    private BundleFilterTemplate url;
    @Codec.Set(codable = true)
    private String set;

    private File persistTo;
    private String fields[];
    private HotMap<String, CacheObject> ocache;

    public static class CacheConfig {

        @Codec.Set(codable = true)
        private int size = 1000;
        @Codec.Set(codable = true)
        private long age;
        @Codec.Set(codable = true)
        private String dir;
    }

    public static class HttpConfig {

        @Codec.Set(codable = true)
        private int timeout = 60000;
        @Codec.Set(codable = true)
        private int retries = 1;
        @Codec.Set(codable = true)
        private long retryTimeout = 1000;
    }

    public static class CacheObject implements Codec.Codable, Comparable<CacheObject> {

        @Codec.Set(codable = true)
        private long time;
        @Codec.Set(codable = true)
        private String key;
        @Codec.Set(codable = true)
        private String data;

        private String hash;

        @Override
        public int compareTo(CacheObject o) {
            return (int) (time - o.time);
        }
    }

    @Override
    public void initialize() {
        fields = new String[]{set};
        if (cache == null) {
            cache = new CacheConfig();
        }
        if (http == null) {
            http = new HttpConfig();
        }
        ocache = new HotMap<>(new ConcurrentHashMap());
        if (cache.dir != null) {
            persistTo = Files.initDirectory(cache.dir);
            LinkedList<CacheObject> list = new LinkedList<>();
            for (File file : persistTo.listFiles()) {
                if (file.isFile()) {
                    try {
                        CacheObject cached = codec.decode(CacheObject.class, Files.read(file));
                        cached.hash = file.getName();
                        list.add(cached);
                        if (log.isDebugEnabled()) {
                            log.debug("restored " + cached.hash + " as " + cached.key);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            // sort so that hot map has the most recent inserted last
            CacheObject sort[] = new CacheObject[list.size()];
            list.toArray(sort);
            Arrays.sort(sort);
            for (CacheObject cached : sort) {
                if (log.isDebugEnabled()) {
                    log.debug("insert into hot " + cached.hash + " as " + cached.key);
                }
                ocache.put(cached.key, cached);
            }
        }
    }

    private synchronized CacheObject cacheGet(String key) {
        return ocache.get(key);
    }

    private synchronized CacheObject cachePut(String key, String value) {
        CacheObject cached = new CacheObject();
        cached.time = System.currentTimeMillis();
        cached.key = key;
        cached.data = value;
        cached.hash = MD5HashFunction.hash(key);
        ocache.put(cached.key, cached);
        CacheObject old;
        if (cache.dir != null) {
            try {
                Files.write(new File(persistTo, cached.hash), codec.encode(cached), false);
                if (log.isDebugEnabled()) {
                    log.debug("creating " + cached.hash + " for " + cached.key);
                }
            } catch (Exception ex)  {
                log.warn("", ex);
            }
        }
        while (ocache.size() > cache.size) {
            old = ocache.removeEldest();
            if (cache.dir != null) {
                new File(persistTo, old.hash).delete();
                if (log.isDebugEnabled()) {
                    log.debug("deleted " + old.hash + " containing " + old.key);
                }
            }
        }
        return cached;
    }

    public static byte[] httpGet(String url, Map<String, String> requestHeaders,
            Map<String, String> responseHeaders,
            int timeoutms, boolean traceError) throws IOException {
        return ValueFilterHttpGet.httpGet(url, requestHeaders, responseHeaders, timeoutms, traceError);
    }

    @Override
    public boolean filterExec(Bundle bundle) {
        String urlValue = url.template(bundle);
        CacheObject cached = cacheGet(urlValue);
        if (cached == null || (cache.age > 0 && System.currentTimeMillis() - cached.time > cache.age)) {
            if (log.isDebugEnabled() && cached != null && cache.age > 0 && System.currentTimeMillis() - cached.time > cache.age) {
                log.warn("aging out, replacing " + cached.hash + " or " + cached.key);
            }
            int retries = http.retries;
            while (retries-- > 0) {
                try {
                    byte val[] = httpGet(urlValue, null, null, http.timeout, trace);
                    if (val != null && val.length >= 0) {
                        cached = cachePut(urlValue, Bytes.toString(val));
                        break;
                    } else if (trace) {
                        System.err.println(urlValue + " returned " + (val != null ? val.length : -1) + " retries left = " + retries);
                    }
                } catch (URIException e)  {
                    log.warn("", e, "error creating url: " + urlValue);
                    break;
                } catch (IOException e)  {
                    log.warn("", e, "error accessing url: " + urlValue);
                }
                try {
                    Thread.sleep(http.retryTimeout);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if (cached == null && defaultValue != null) {
                cachePut(urlValue, defaultValue);
            }
        }
        if (cached != null) {
            bundle.setValue(getBindings(bundle, fields)[0], ValueFactory.create(cached.data));
        }
        return true;
    }
}
