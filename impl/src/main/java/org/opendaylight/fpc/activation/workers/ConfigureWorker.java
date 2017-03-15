/*
 * Copyright © 2016 Copyright (c) Sprint, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.fpc.activation.workers;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.fpc.activation.cache.HierarchicalCache;
import org.opendaylight.fpc.activation.cache.OpCache;
import org.opendaylight.fpc.activation.cache.PayloadCache;
import org.opendaylight.fpc.activation.cache.StorageCacheUtils;
import org.opendaylight.fpc.activation.cache.transaction.Transaction;
import org.opendaylight.fpc.activation.cache.transaction.Transaction.OperationStatus;
import org.opendaylight.fpc.dpn.DpnHolder;
import org.opendaylight.fpc.tenant.TenantManager;
import org.opendaylight.fpc.utils.ErrorLog;
import org.opendaylight.fpc.utils.ErrorTypeIndex;
import org.opendaylight.fpc.utils.NameResolver;
import org.opendaylight.fpc.utils.Worker;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcagent.rev160803.ConfigureBundlesInput;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcagent.rev160803.ConfigureInput;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcagent.rev160803.ErrorTypeId;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcagent.rev160803.OpInput;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcagent.rev160803.Payload;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcagent.rev160803.RefScope;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcagent.rev160803.configure.bundles.input.Bundles;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcagent.rev160803.op.input.op_body.DeleteOrQuery;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcagent.rev160803.payload.Contexts;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcagent.rev160803.result.body.ResultType;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcagent.rev160803.result.body.result.type.Err;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcagent.rev160803.result.body.result.type.ErrBuilder;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcbase.rev160803.FpcContext;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcbase.rev160803.FpcDpnId;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcbase.rev160803.FpcIdentity;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcbase.rev160803.FpcPort;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcbase.rev160803.fpc.context.Dpns;
import org.opendaylight.yang.gen.v1.urn.ietf.params.xml.ns.yang.fpcbase.rev160803.targets.value.Targets;
import org.opendaylight.yangtools.yang.binding.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Primary worker for CONF and CONF_BUNDLES activation.
 */
public class ConfigureWorker
        implements Worker {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigureWorker.class);
    private static final AtomicLong entrants = new AtomicLong(0L);
    private static Pattern deleteContextHandle = Pattern.compile("^/ctxt:(.+)");
    private static Pattern deletePortHandle = Pattern.compile("^/port:(.+)");

    private boolean run;
    private final BlockingQueue<Object> blockingConfigureQueue;
    //private final DataBroker db;

    /**
     * Constructor.
     * @param db - DataBroker
     * @param blockingConfigureQueue - Work queue
     */
    protected ConfigureWorker(DataBroker db, BlockingQueue<Object> blockingConfigureQueue) {
        //this.db = db;
        this.blockingConfigureQueue = blockingConfigureQueue;
        LOG.info("ConfigureWorker has been initialized");
    }

    /**
     * Retrieves the work queue.
     * @return - the work queue for this instance
     */
    public BlockingQueue<Object> getQueue() {
        return blockingConfigureQueue;
    }

    /**
     * Generic Error Handler dealing with the Err Return value Generation, exception printing and Transaction
     * Management.
     * @param id - Error Type Identifier
     * @param e - Exception that occurred
     * @param message - String message
     * @param tx - Associated Transaction
     * @param duration - length of time passed since last time stamp
     * @return An Err object
     */
    private Err processActivationError(ErrorTypeId id,
            Exception e,
            String message,
            Transaction tx,
            long duration) {
        String mess = (e != null) ? message + e.getMessage() : message;
        Err rt = new ErrBuilder()
                .setErrorTypeId(id)
                .setErrorInfo(mess)
                .build();
        tx.setResultType(rt);
        tx.fail(duration);
        if (e != null) {
        	ErrorLog.logError(e.getStackTrace());
        }
        return rt;
    }

    /**
     * Primary execution method for individual operations.
     * @param oCache - Object Cache
     * @param tx - Transaction
     * @return a ResultType if an error occurs otherwise null
     */
    private ResultType executeOperation(PayloadCache oCache,
                                          Transaction tx) {
        long sysTime = System.currentTimeMillis();
        OpInput input = tx.getOpInput();
        DpnHolder dpnInfo = null;

        DeleteOrQuery doq = null;
        switch (input.getOpType()) {
        case Create:
        case Update:
            for (Contexts context : (oCache.getPayloadContexts() == null) ? Collections.<Contexts>emptyList() : oCache.getPayloadContexts()) {
                for (Dpns dpn : (context.getDpns() == null) ? Collections.<Dpns>emptyList() : context.getDpns() ) {
                    dpnInfo = tx.getTenantContext().getDpnInfo().get(dpn.getDpnId().toString());
                    if (dpnInfo.activator != null) {
                        try {
                            dpnInfo.activator.activate(input.getOpType(), (context.getInstructions() != null) ?
                                    context.getInstructions() : input.getInstructions(), context, oCache);
                            dpnInfo.activator.getResponseManager().enqueueChange(context, oCache, tx);
                        } catch (Exception e) {
                            return processActivationError(new ErrorTypeId(ErrorTypeIndex.CONTEXT_ACTIVATION_FAIL),
                                    e,
                                    "PROTOCOL - operation failed - ERROR - Context Activation - ",
                                    tx,
                                    System.currentTimeMillis() - sysTime);
                        }
                    } else {
                        LOG.info("No activator found for DPN" + dpn.getDpnId().toString());
                    }
                }
            }

            tx.setStatus(OperationStatus.AWAITING_RESPONSES, System.currentTimeMillis() - sysTime);
            return null;
        case Query:
            doq = (DeleteOrQuery) input.getOpBody();
            try {
                OpCache result = StorageCacheUtils.read(doq.getTargets(), tx.getTenantContext());
                tx.setResultType(result.getConfigSuccess());
                tx.complete(System.currentTimeMillis());
                tx.publish(false);
                return null;
            } catch (Exception e) {
                return processActivationError(new ErrorTypeId(ErrorTypeIndex.QUERY_FAILURE),
                        e,
                        "PROTOCOL - operation failed - ERROR - Query Failed - ",
                        tx,
                        System.currentTimeMillis() - sysTime);
            }
        case Delete:
            doq = (DeleteOrQuery) input.getOpBody();
            for (Targets target : (doq.getTargets() != null) ? doq.getTargets() :
                    Collections.<Targets>emptyList()) {
                FpcDpnId ident = null;
                DataObject dObj = tx.getTenantContext().getSc().read(NameResolver.extractString(target.getTarget()));
                FpcContext context =(dObj instanceof FpcContext) ? (FpcContext) dObj : null;
                FpcPort port = (dObj instanceof FpcPort) ? (FpcPort) dObj : null;
                if ((context == null) && (port == null)) {
                    String s = NameResolver.extractString(target.getTarget());
                    Matcher m1 = deleteContextHandle.matcher(s);
                    if (m1.matches()) {
                        LOG.info("Value = {}",m1.group(0));
                        LOG.info("Value = {}",m1.group(1));
                        context = tx.getTenantContext().getSc().getContext(new FpcIdentity(m1.group(1)));
                    } else {
                        Matcher m2 = deletePortHandle.matcher(s);
                        if (m2.matches()) {
                            port = tx.getTenantContext().getSc().getPort(new FpcIdentity(m1.group(1)));
                        }
                    }
                }

                if (context != null) {
                    if (context.getDpns() != null) {
                        if (context.getDpns().size() > 1) {
                            tx.addTaskCount(context.getDpns().size()-1);
                        }
                        for (Dpns dpn : context.getDpns()) {
                            ident = dpn.getDpnId();

                            if (ident != null) {
                                dpnInfo = tx.getTenantContext().getDpnInfo().get(dpn.getDpnId().toString());
                                if (dpnInfo.activator != null) {
                                    try {
                                        dpnInfo.activator.delete(input.getInstructions(), target, context);
                                        dpnInfo.activator.getResponseManager().enqueueDelete(target, tx);
                                    } catch (Exception e) {
                                        return processActivationError(new ErrorTypeId(ErrorTypeIndex.DELETE_FAILURE),
                                                e,
                                                "PROTOCOL - operation failed - ERROR - Delete Failed - ",
                                                tx,
                                                System.currentTimeMillis() - sysTime);
                                    }
                                }  else {
                                    LOG.info("No activator found for DPN" + dpn.getDpnId().toString());
                                }
                            }
                        }
                    }
                }
            }

            tx.setStatus(OperationStatus.AWAITING_RESPONSES, System.currentTimeMillis() - sysTime);
            return null;
        default:
            return processActivationError(new ErrorTypeId(ErrorTypeIndex.DELETE_WO_PAYLOAD),
                    null,
                    "PROTOCOL - operation failed - An unknown / unsuported OpType was sent.  " +
                    "Code MUST use pre-check and did not.",
                    tx,
                    System.currentTimeMillis() - sysTime);
        }
    }

    /**
     * CONF request processor.
     * @param baseTx - Transaction
     * @param input - Client request
     */
    private void configure(Transaction baseTx,ConfigureInput input) {
        Transaction t = Transaction.get(input.getClientId(), input.getOpId());
        LOG.debug("Configure has been called");
        if (t == null) {
            t = baseTx;
        }
        HierarchicalCache oCache = new HierarchicalCache((input.getOpRefScope() != null) ?
                input.getOpRefScope() : RefScope.Unknown,
                t.getTenantContext().getSc(),
                true);
        if (input.getOpBody() instanceof Payload) {
            oCache.newOpCache((Payload)input.getOpBody());
            t.setPayloadCache(oCache.getOpCache());
        }
        executeOperation(oCache, t);
    }

    /**
     * CONF_BUNDLES request processing
     * @param txs - List of Transactions
     * @param input - Client request
     */
    private void configureBundles(List<Transaction> txs, ConfigureBundlesInput input) {
        LOG.info("Configure-Bundles has been called");
        HierarchicalCache bundleCache;
        PayloadCache workingOpCache;
        boolean usingGlobal = false;

        TenantManager tenant = TenantManager.getTenantManager(input.getClientId());
        if (tenant == null) {
          LOG.warn("No tenant found for bundle. How did this happen? client-id is {}", input.getClientId());
          return;
        }

        switch (input.getHighestOpRefScope()) {
            case None :
            case Op :
                bundleCache = new HierarchicalCache(RefScope.Op, tenant.getSc(), false);
                break;
            case Bundle :
                bundleCache = new HierarchicalCache(RefScope.Bundle, tenant.getSc(), false);
                break;
            default:
                bundleCache = new HierarchicalCache(RefScope.Bundle, tenant.getSc(),true);
                usingGlobal = true;
        }

        Iterator<Transaction> txIt = txs.iterator();
        for (Bundles op : (input.getBundles() != null) ? input.getBundles() : Collections.<Bundles>emptyList()) {
            Transaction t = Transaction.get(op.getClientId(), op.getOpId());
            Transaction u = txIt.next();
            if (t == null) {
                t = u;
            }
            PayloadCache pc = (op.getOpBody() instanceof Payload)? bundleCache.newOpCache((Payload)op.getOpBody()):
                null;
            if (op.getOpRefScope() == null) {
                workingOpCache = bundleCache;
            } else {
                switch (op.getOpRefScope() ) {
                case None:
                    workingOpCache = null;
                    break;
                case Op :
                    workingOpCache = pc;
                    break;
                case Bundle :
                    bundleCache.setGlobalUse(false);
                    workingOpCache = bundleCache;
                    break;
                default :
                    bundleCache.setGlobalUse(usingGlobal);
                    workingOpCache = bundleCache;
                    break;
                }
            }
            ResultType rt = executeOperation(workingOpCache, t);

            if ((rt != null) && (rt instanceof Err)) {
                // TODO - Flag a cleanup call here
            }
            bundleCache.mergeOpCache();
        }
    }

    @Override
    public void stop() {
        this.run = false;
    }

    @Override
    public void close() throws Exception {
    }

    @SuppressWarnings("unchecked")
    @Override
    public void run() {
        this.run = true;
        LOG.info("ActivationWorker RUN started");
        try {
            while(run) {
                entrants.incrementAndGet();
                if ((entrants.get() % 100) == 0) {
                    LOG.info("Configure Entries = {}", entrants.get());
                }
                AbstractMap.SimpleEntry<Object,Object> obj =
                        (AbstractMap.SimpleEntry<Object,Object>) blockingConfigureQueue.take();
                if (obj.getValue() instanceof ConfigureInput) {
                    configure((Transaction) obj.getKey(),(ConfigureInput)obj.getValue());
                } else if (obj.getValue() instanceof ConfigureBundlesInput) {
                    configureBundles((List<Transaction>)obj.getKey(),(ConfigureBundlesInput)obj.getValue());
                }
            }
        } catch (InterruptedException e) {
        	ErrorLog.logError(e.getStackTrace());
        }
    }

    @Override
    public void open() {
        // Does nothing
    }

	@Override
	public boolean isOpen() {
		return true;
	}
}
