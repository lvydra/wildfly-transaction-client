/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2022 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
package org.wildfly.transaction.client;

import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.wildfly.transaction.client.provider.jboss.TestImportedTransaction;
import org.wildfly.transaction.client.provider.jboss.TestTransaction;
import org.wildfly.transaction.client.provider.jboss.TestTransactionManager;
import org.wildfly.transaction.client.provider.jboss.TestTransactionProvider;
import org.wildfly.transaction.client.provider.jboss.TestXAResource;
import org.wildfly.transaction.client.provider.jboss.TestXid;

import javax.transaction.xa.XAResource;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks the functionality of the LocalUserTransacton, that is the transaction created by the client that runs within the application server.
 */
public class LocalUserTransactionTestCase {

    @BeforeClass
    public static void init() {
        final LocalTransactionContext transactionContext = new LocalTransactionContext(new TestTransactionProvider(500, Path.of("./target")));
        LocalTransactionContext.getContextManager().setGlobalDefault(transactionContext);
    }

    @Before
    public void reset() {
        TestTransactionProvider.reset();
        TestTransactionManager.reset();
    }

    /**
     * Sanity check of LocalUserTransaction commit scenario. Check whether the transaction has been correctly started,
     * associated with the current context, and finally that the commit operation was propagated to the mocktransaction manager.
     *
     * @throws Exception
     */
    @Test
    public void simpleCommitTest() throws Exception {
        ContextTransactionManager tm = ContextTransactionManager.getInstance();
        Assert.assertNull(tm.stateRef.get().transaction);
        tm.begin();

        Assert.assertTrue(TestTransactionProvider.newTransactionCreated);
        Assert.assertNotNull(tm.stateRef.get().transaction);

        Transaction t = tm.stateRef.get().transaction;
        Assert.assertNotNull(t);
        Assert.assertTrue(t instanceof LocalTransaction);

        tm.commit();
        Assert.assertNull(tm.stateRef.get().transaction);
        Assert.assertTrue(TestTransactionManager.committed);
        Assert.assertFalse(TestTransactionManager.rolledback);
    }

    /**
     * Sanity check of LocalUserTransaction rollback scenario. Check whether the transaction has been correctly started,
     * associated with the current context, and finally that the rollback operation was propagated to the mock transaction manager.
     *
     * @throws Exception
     */
    @Test
    public void simpleRollbackTest() throws Exception {
        ContextTransactionManager tm = ContextTransactionManager.getInstance();
        Assert.assertNull(tm.stateRef.get().transaction);
        tm.begin();

        Assert.assertTrue(TestTransactionProvider.newTransactionCreated);
        Assert.assertNotNull(tm.stateRef.get().transaction);

        Transaction t = tm.stateRef.get().transaction;
        Assert.assertNotNull(t);
        Assert.assertTrue(t instanceof LocalTransaction);

        tm.rollback();
        Assert.assertNull(tm.stateRef.get().transaction);
        Assert.assertFalse(TestTransactionManager.committed);
        Assert.assertTrue(TestTransactionManager.rolledback);
    }

    /**
     * Tests LocalUserTransaction import scenario. Checks whether the imported transaction has been created
     * and associated with the current context, that imported transaction cannot initiate commit operation and whether
     * XA calls are correctly propagated to the mock transaction manager.
     * @throws Exception
     */
    @Test
    public void importedTransactionTest() throws Exception {
        LocalTransactionContext ltc = LocalTransactionContext.getCurrent();
        LocalTransaction lt = ltc.findOrImportTransaction(new TestXid(), 5000, false).getTransaction();

        boolean exception = false;
        try {
            lt.commit();
        } catch (SystemException expected) {
            exception = true;
        }
        Assert.assertTrue(exception);


        TestImportedTransaction it = TestImportedTransaction.latest;
        Assert.assertEquals(it.getState(), TestTransaction.State.ACTIVE);

        it.doPrepare();
        Assert.assertEquals(it.getState(), TestTransaction.State.PREPARED);

        it.doCommit();
        Assert.assertEquals(it.getState(), TestTransaction.State.COMMITTED);


    }

    /**
     * Tests LocalUserTransaction XA resources enlistment. Checks whether the transaction has been created
     * and associated with the current context, enlists two mock XA resources to it, and after performing commit checks
     * that those two resources have been correctly enlisted in the transaction.
     */
    @Test
    public void xaTest() throws Exception {
        ContextTransactionManager tm = ContextTransactionManager.getInstance();
        Assert.assertNull(tm.stateRef.get().transaction);
        tm.begin();
        Assert.assertTrue(TestTransactionProvider.newTransactionCreated);
        Assert.assertNotNull(tm.stateRef.get().transaction);

        Transaction t = tm.stateRef.get().transaction;
        Assert.assertNotNull(t);
        Assert.assertTrue(t instanceof LocalTransaction);
        LocalTransaction lt = (LocalTransaction) t;

        TestXAResource xaResource1 = new TestXAResource();
        lt.enlistResource(xaResource1);
        TestXAResource xaResource2 = new TestXAResource();
        lt.enlistResource(xaResource2);

        tm.commit();
        Assert.assertNull(tm.stateRef.get().transaction);
        Assert.assertTrue(TestTransactionManager.committed);
        Assert.assertFalse(TestTransactionManager.rolledback);

        TestTransaction testTransaction = TestTransaction.latest;

        List<XAResource> enlistedResources = testTransaction.getEnlistedResources();

        Assert.assertEquals(enlistedResources.size(), 2);
        Assert.assertTrue(enlistedResources.contains(xaResource1));
        Assert.assertTrue(enlistedResources.contains(xaResource2));
    }

    /**
     * Checks outflowing local transaction to the other node. Checks whether the transaction has been created
     * and associated with the current context, ouflows the transaction to the other node and checks whether
     * {@link SubordinateXAResource}, which represents this node, has been enlisted to the local transaction.
     */
    @Test
    public void outflowedTransactionTest() throws Exception {
        ContextTransactionManager tm = ContextTransactionManager.getInstance();
        Assert.assertNull(tm.stateRef.get().transaction);
        tm.begin();
        Assert.assertTrue(TestTransactionProvider.newTransactionCreated);

        Transaction t = tm.stateRef.get().transaction;
        Assert.assertNotNull(t);
        Assert.assertTrue(t instanceof LocalTransaction);
        LocalTransaction lt = (LocalTransaction) t;

        URI location = new URI("remote://localhost:12345");

        RemoteTransactionContext.getInstance().outflowTransaction(location, lt);

        tm.commit();

        TestTransaction testTransaction = TestTransaction.latest;

        List<XAResource> enlistedResources = testTransaction.getEnlistedResources();

        Assert.assertEquals(enlistedResources.size(), 1);
        XAResource resource = enlistedResources.get(0);
        Assert.assertTrue(resource instanceof SubordinateXAResource);

        Assert.assertNull(tm.stateRef.get().transaction);
        Assert.assertTrue(TestTransactionManager.committed);
        Assert.assertFalse(TestTransactionManager.rolledback);
    }

}
