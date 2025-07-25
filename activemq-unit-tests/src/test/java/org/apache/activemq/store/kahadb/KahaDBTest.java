/**
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
package org.apache.activemq.store.kahadb;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;

import java.util.concurrent.atomic.AtomicInteger;
import junit.framework.TestCase;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.util.DefaultIOExceptionHandler;
import org.apache.activemq.util.IOExceptionHandler;
import org.apache.activemq.util.Wait;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.core.layout.MessageLayout;

/**
 * @author chirino
 */
public class KahaDBTest extends TestCase {

    protected BrokerService createBroker(KahaDBStore kaha) throws Exception {

        BrokerService broker = new BrokerService();
        broker.setUseJmx(false);
        broker.setPersistenceAdapter(kaha);
        broker.start();
        return broker;
    }

    private KahaDBStore createStore(boolean delete) throws IOException {
        KahaDBStore kaha = new KahaDBStore();
        kaha.setDirectory(new File("target/activemq-data/kahadb"));
        if( delete ) {
            kaha.deleteAllMessages();
        }
        return kaha;
    }

    public void testIgnoreMissingJournalfilesOptionSetFalse() throws Exception {
        KahaDBStore kaha = createStore(true);
        kaha.setJournalMaxFileLength(1024*100);
        assertFalse(kaha.isIgnoreMissingJournalfiles());
        BrokerService broker = createBroker(kaha);
        sendMessages(1000);
        broker.stop();

        // Delete some journal files..
        assertExistsAndDelete(new File(kaha.getDirectory(), "db-4.log"));
        assertExistsAndDelete(new File(kaha.getDirectory(), "db-8.log"));

        kaha = createStore(false);
        kaha.setJournalMaxFileLength(1024*100);
        assertFalse(kaha.isIgnoreMissingJournalfiles());
        try {
            broker = createBroker(kaha);
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue( e.getMessage().startsWith("Detected missing/corrupt journal files") );
        }

    }


    public void testIgnoreMissingJournalfilesOptionSetTrue() throws Exception {
        KahaDBStore kaha = createStore(true);
        kaha.setJournalMaxFileLength(1024*100);
        assertFalse(kaha.isIgnoreMissingJournalfiles());
        BrokerService broker = createBroker(kaha);
        sendMessages(1000);
        broker.stop();

        // Delete some journal files..
        assertExistsAndDelete(new File(kaha.getDirectory(), "db-4.log"));
        assertExistsAndDelete(new File(kaha.getDirectory(), "db-8.log"));

        kaha = createStore(false);
        kaha.setIgnoreMissingJournalfiles(true);
        kaha.setJournalMaxFileLength(1024*100);
        broker = createBroker(kaha);

        // We know we won't get all the messages but we should get most of them.
        int count = receiveMessages();
        assertTrue( count > 800 );
        assertTrue( count < 1000 );

        broker.stop();
    }


    public void testCheckCorruptionNotIgnored() throws Exception {
        KahaDBStore kaha = createStore(true);
        assertTrue(kaha.isChecksumJournalFiles());
        assertFalse(kaha.isCheckForCorruptJournalFiles());

        kaha.setJournalMaxFileLength(1024*100);
        kaha.setChecksumJournalFiles(true);
        BrokerService broker = createBroker(kaha);
        sendMessages(1000);
        broker.stop();

        // Modify/Corrupt some journal files..
        assertExistsAndCorrupt(new File(kaha.getDirectory(), "db-4.log"));
        assertExistsAndCorrupt(new File(kaha.getDirectory(), "db-8.log"));

        kaha = createStore(false);
        kaha.setJournalMaxFileLength(1024*100);
        kaha.setChecksumJournalFiles(true);
        kaha.setCheckForCorruptJournalFiles(true);
        assertFalse(kaha.isIgnoreMissingJournalfiles());
        try {
            broker = createBroker(kaha);
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue( e.getMessage().startsWith("Detected missing/corrupt journal files") );
        }

    }


    public void testMigrationOnNewDefaultForChecksumJournalFiles() throws Exception {
        KahaDBStore kaha = createStore(true);
        kaha.setChecksumJournalFiles(false);
        assertFalse(kaha.isChecksumJournalFiles());
        assertFalse(kaha.isCheckForCorruptJournalFiles());

        kaha.setJournalMaxFileLength(1024*100);
        BrokerService broker = createBroker(kaha);
        sendMessages(1000);
        broker.stop();

        kaha = createStore(false);
        kaha.setJournalMaxFileLength(1024*100);
        kaha.setCheckForCorruptJournalFiles(true);
        assertFalse(kaha.isIgnoreMissingJournalfiles());
        createBroker(kaha);
        assertEquals(1000, receiveMessages());
    }


    private void assertExistsAndCorrupt(File file) throws IOException {
        assertTrue(file.exists());
        RandomAccessFile f = new RandomAccessFile(file, "rw");
        try {
            f.seek(1024*5+134);
            f.write("... corruption string ...".getBytes());
        } finally {
            f.close();
        }
    }


    public void testCheckCorruptionIgnored() throws Exception {
        KahaDBStore kaha = createStore(true);
        kaha.setJournalMaxFileLength(1024*100);
        BrokerService broker = createBroker(kaha);
        sendMessages(1000);
        broker.stop();

        // Delete some journal files..
        assertExistsAndCorrupt(new File(kaha.getDirectory(), "db-4.log"));
        assertExistsAndCorrupt(new File(kaha.getDirectory(), "db-8.log"));

        kaha = createStore(false);
        kaha.setIgnoreMissingJournalfiles(true);
        kaha.setJournalMaxFileLength(1024*100);
        kaha.setCheckForCorruptJournalFiles(true);
        broker = createBroker(kaha);

        // We know we won't get all the messages but we should get most of them.
        int count = receiveMessages();
        assertTrue("Expected to received a min # of messages.. Got: "+count,  count > 990 );
        assertTrue( count < 1000 );

        broker.stop();
    }

    public void testNoReplayOnStopStart() throws Exception {
        KahaDBStore kaha = createStore(true);
        BrokerService broker = createBroker(kaha);
        sendMessages(100);
        broker.stop();
        broker.waitUntilStopped();

        kaha = createStore(false);
        kaha.setCheckForCorruptJournalFiles(true);

        final AtomicBoolean didSomeRecovery = new AtomicBoolean(false);
        final var logger = org.apache.logging.log4j.core.Logger.class.cast(LogManager.getRootLogger());
        final var appender = new AbstractAppender("testAppender", new AbstractFilter() {}, new MessageLayout(), false, new Property[0]) {
            @Override
            public void append(LogEvent event) {
                if (Level.INFO.equals(event.getLevel()) && event.getMessage().getFormattedMessage().contains("Recovering from the journal @")) {
                    didSomeRecovery.set(true);
                }
            }
        };
        appender.start();

        logger.get().addAppender(appender, Level.DEBUG, new AbstractFilter() {});
        logger.addAppender(appender);

        broker = createBroker(kaha);

        int count = receiveMessages();
        assertEquals("Expected to received all messages.", count, 100);
        broker.stop();

        logger.removeAppender(appender);
        assertFalse("Did not replay any records from the journal", didSomeRecovery.get());
    }


    /**
     * Test the checkpoint runner task continues to run if the configured
     * IOExceptionHandler throws a runtime exception while processing
     * the IOException it is handling and the broker is not shut down. This
     * could happen if using the DefaultIOExceptionHandler and startStopConnectors
     * is set to true, or if a user provides their own IOExceptionHandler that
     * throws an exception.
     */
    public void testCheckpointExceptionKeepRunning() throws Exception {
        testCheckpointIOException(true);
    }

    /**
     * Test the broker shuts down by when DefaultIOExceptionHandler
     * handles an IOException thrown by the checkpoint runner task. This is the
     * default behavior of the broker if not configured with a custom
     * IOExceptionHandler and startStopConnectors is false
     */
    public void testCheckpointExceptionShutdown() throws Exception {
        testCheckpointIOException(false);
    }

    private void testCheckpointIOException(boolean startStopConnectors) throws Exception {
        final AtomicInteger iterations = new AtomicInteger();
        // Create a store that always throws an IOException when checkpoint is called
        final KahaDBStore kaha = new KahaDBStore() {
            @Override
            protected void checkpointCleanup(boolean cleanup) throws IOException {
                iterations.incrementAndGet();
                throw new IOException("fail");
            }
        };
        kaha.setDirectory(new File("target/activemq-data/kahadb"));
        kaha.deleteAllMessages();
        // Set the checkpoint interval to be very short so we can quickly
        // check number of iterations
        kaha.setCheckpointInterval(100);

        BrokerService broker = createBroker(kaha);
        DefaultIOExceptionHandler ioExceptionHandler = new DefaultIOExceptionHandler();
        ioExceptionHandler.setStopStartConnectors(startStopConnectors);
        broker.setIoExceptionHandler(ioExceptionHandler);
        broker.start();

        try {
            if (startStopConnectors) {
                // If startStopConnectors is true, the task should continue with future iterations
                // as the SuppressReplyException that will be thrown is now handled so just verify
                // we see 10 iterations which should happen quickly
                assertTrue(Wait.waitFor(() -> iterations.get() == 10, 2000, 100));
                // broker should not be stopped
                assertFalse(broker.isStopped());
            } else {
                // If startStopConnectors is false, an IOException should shut down the broker
                // which is the normal behavior
                assertTrue(Wait.waitFor(broker::isStopped, 2000, 100));
            }
        } finally {
            broker.stop();
        }
    }

    private void assertExistsAndDelete(File file) {
        assertTrue(file.exists());
        file.delete();
        assertFalse(file.exists());
    }

    private void sendMessages(int count) throws JMSException {
        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("vm://localhost");
        Connection connection = cf.createConnection();
        try {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer producer = session.createProducer(new ActiveMQQueue("TEST"));
            for (int i = 0; i < count; i++) {
                producer.send(session.createTextMessage(createContent(i)));
            }
        } finally {
            connection.close();
        }
    }

    private int receiveMessages() throws JMSException {
        int rc=0;
        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("vm://localhost");
        Connection connection = cf.createConnection();
        try {
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer messageConsumer = session.createConsumer(new ActiveMQQueue("TEST"));
            while ( messageConsumer.receive(1000) !=null ) {
                rc++;
            }
            return rc;
        } finally {
            connection.close();
        }
    }

    private String createContent(int i) {
        StringBuilder sb = new StringBuilder(i+":");
        while( sb.length() < 1024 ) {
            sb.append("*");
        }
        return sb.toString();
    }

}
