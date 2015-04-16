/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.visor.query;

import org.apache.ignite.*;
import org.apache.ignite.cache.query.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.internal.processors.cache.*;
import org.apache.ignite.internal.processors.query.*;
import org.apache.ignite.internal.processors.timeout.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.internal.visor.*;
import org.apache.ignite.lang.*;

import javax.cache.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

import static org.apache.ignite.internal.visor.query.VisorQueryUtils.*;
import static org.apache.ignite.internal.visor.util.VisorTaskUtils.*;

/**
 * Job for execute SCAN or SQL query and get first page of results.
 */
public class VisorQueryJob extends VisorJob<VisorQueryArg, IgniteBiTuple<? extends Exception, VisorQueryResultEx>> {
    /** */
    private static final long serialVersionUID = 0L;

    /**
     * Create job with specified argument.
     *
     * @param arg Job argument.
     * @param debug Debug flag.
     */
    protected VisorQueryJob(VisorQueryArg arg, boolean debug) {
        super(arg, debug);
    }

    /**
     * @param cacheName Cache name.
     * @return Cache to execute query.
     */
    protected IgniteCache<Object, Object> cache(String cacheName) {
        GridCacheProcessor cacheProcessor = ignite.context().cache();

        return cacheProcessor.jcache(cacheName);
    }

    /**
     * @return Query task class name.
     */
    protected Class<? extends VisorQueryTask> task() {
        return VisorQueryTask.class;
    }

    /** {@inheritDoc} */
    @Override protected IgniteBiTuple<? extends Exception, VisorQueryResultEx> run(VisorQueryArg arg) {
        try {
            String cacheName = arg.cacheName();

            UUID nid = ignite.localNode().id();

            // If node was not specified then we need to check if this node could be used for query
            // or we need to send task to appropriate node.
            if (arg.nodeId() == null) {
                ClusterGroup prj = ignite.cluster().forDataNodes(cacheName);

                if (prj.node() == null)
                    throw new IgniteException("No data nodes for cache: " + escapeName(cacheName));

                // Current node does not fit.
                if (prj.node(nid) == null) {
                    Collection<ClusterNode> prjNodes = prj.nodes();

                    Collection<UUID> nids = new ArrayList<>(prjNodes.size());

                    for (ClusterNode node : prjNodes)
                        nids.add(node.id());

                    return ignite.compute(prj).withNoFailover().execute(task(), new VisorTaskArgument<>(nids, arg, false));
                }
            }

            boolean scan = arg.queryTxt().toUpperCase().startsWith("SCAN");

            String qryId = (scan ? SCAN_QRY_NAME : SQL_QRY_NAME) + "-" +
                UUID.randomUUID();

            IgniteCache<Object, Object> c = cache(arg.cacheName());

            if (scan) {
                ScanQuery<Object, Object> qry = new ScanQuery<>(null);
                qry.setPageSize(arg.pageSize());

                long start = U.currentTimeMillis();

                VisorQueryCursor<Cache.Entry<Object, Object>> cur = new VisorQueryCursor<>(c.query(qry));

                List<Object[]> rows = fetchScanQueryRows(cur, arg.pageSize());

                long duration = U.currentTimeMillis() - start; // Scan duration + fetch duration.

                boolean hasNext = cur.hasNext();

                if (hasNext) {
                    ignite.cluster().<String, VisorQueryCursor>nodeLocalMap().put(qryId, cur);

                    scheduleResultSetHolderRemoval(qryId);
                }
                else
                    cur.close();

                return new IgniteBiTuple<>(null, new VisorQueryResultEx(ignite.localNode().id(), qryId,
                    SCAN_COL_NAMES, rows, hasNext, duration));
            }
            else {
                SqlFieldsQuery qry = new SqlFieldsQuery(arg.queryTxt());
                qry.setPageSize(arg.pageSize());

                long start = U.currentTimeMillis();

                VisorQueryCursor<List<?>> cur = new VisorQueryCursor<>(c.query(qry));

                Collection<GridQueryFieldMetadata> meta = cur.fieldsMeta();

                if (meta == null)
                    return new IgniteBiTuple<Exception, VisorQueryResultEx>(
                        new SQLException("Fail to execute query. No metadata available."), null);
                else {
                    List<VisorQueryField> names = new ArrayList<>(meta.size());

                    for (GridQueryFieldMetadata col : meta)
                        names.add(new VisorQueryField(col.schemaName(), col.typeName(),
                            col.fieldName(), col.fieldTypeName()));

                    List<Object[]> rows = fetchSqlQueryRows(cur, arg.pageSize());

                    long duration = U.currentTimeMillis() - start; // Query duration + fetch duration.

                    boolean hasNext = cur.hasNext();

                    if (hasNext) {
                        ignite.cluster().<String, VisorQueryCursor<List<?>>>nodeLocalMap().put(qryId, cur);

                        scheduleResultSetHolderRemoval(qryId);
                    }
                    else
                        cur.close();

                    return new IgniteBiTuple<>(null, new VisorQueryResultEx(ignite.localNode().id(), qryId,
                        names, rows, hasNext, duration));
                }
            }
        }
        catch (Exception e) {
            return new IgniteBiTuple<>(e, null);
        }
    }

    /**
     * @param qryId Unique query result id.
     */
    private void scheduleResultSetHolderRemoval(final String qryId) {
        ignite.context().timeout().addTimeoutObject(new GridTimeoutObjectAdapter(RMV_DELAY) {
            @Override public void onTimeout() {
                ConcurrentMap<String, VisorQueryCursor> storage = ignite.cluster().nodeLocalMap();

                VisorQueryCursor cur = storage.get(qryId);

                if (cur != null) {
                    // If cursor was accessed since last scheduling, set access flag to false and reschedule.
                    if (cur.accessed()) {
                        cur.accessed(false);

                        scheduleResultSetHolderRemoval(qryId);
                    }
                    else {
                        // Remove stored cursor otherwise.
                        storage.remove(qryId);

                        cur.close();
                    }
                }
            }
        });
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(VisorQueryJob.class, this);
    }
}
