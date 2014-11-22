/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.jmeter.visualizers.backend;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.engine.util.NoThreadClone;
import org.apache.jmeter.samplers.Remoteable;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleListener;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.testelement.AbstractTestElement;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestStateListener;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;

/**
 * Async Listener that delegates SampleResult handling to implementations of {@link BackendListenerClient}
 * @since 2.13
 */
public class BackendListener extends AbstractTestElement
    implements Serializable, SampleListener, TestStateListener, NoThreadClone, Remoteable  {

    /**
     * 
     */
    private static final long serialVersionUID = 8184103677832024335L;

    private static final Logger LOGGER = LoggingManager.getLoggerForClass();

    /**
     * Property key representing the classname of the BackendListenerClient to user.
     */
    public static final String CLASSNAME = "classname";

    /**
     * Queue size
     */
    public static final String QUEUE_SIZE = "QUEUE_SIZE";
    
    /**
     * Property key representing the arguments for the BackendListenerClient.
     */
    public static final String ARGUMENTS = "arguments";

    /**
     * The BackendListenerClient class used by this sampler.
     * Created by testStarted; copied to cloned instances.
     */
    private Class<?> clientClass;

    /**
     * If true, the BackendListenerClient class implements teardownTest.
     * Created by testStarted; copied to cloned instances.
     */
    private boolean isToBeRegistered;

    /**
     * The BackendListenerClient instance 
     */
    private transient BackendListenerClient backendListenerClient = null;


    private static final int DEFAULT_QUEUE_SIZE = 5000;
    
    private transient BlockingQueue<SampleResult> queue; // created by server in readResolve method
    
    private AtomicLong queueWaits; // how many times we had to wait to queue a sample
    
    private AtomicLong queueWaitTime; // how long we had to wait (nanoSeconds)

    // Create unique object as marker for end of queue
    private transient static final SampleResult FINAL_EVENT = new SampleResult();

    /**
     * Create a BackendListener.
     */
    public BackendListener() {
        setArguments(new Arguments());    
    }

    /*
     * Ensure that the required class variables are cloned,
     * as this is not currently done by the super-implementation.
     */
    @Override
    public Object clone() {
        BackendListener clone = (BackendListener) super.clone();
        clone.clientClass = this.clientClass;
        clone.isToBeRegistered = this.isToBeRegistered;
        return clone;
    }

    private void initClass() {
        String name = getClassname().trim();
        try {
            clientClass = Class.forName(name, false, Thread.currentThread().getContextClassLoader());
            Method method = clientClass.getMethod("teardownTest", new Class[]{BackendListenerContext.class});
            isToBeRegistered = !method.getDeclaringClass().equals(AbstractBackendListenerClient.class);
            LOGGER.info("Created class: " + name + ". Uses teardownTest: " + isToBeRegistered);
        } catch (Exception e) {
            LOGGER.error(whoAmI() + "\tException initialising: " + name, e);
        }   
    }

    /**
     * Generate a String identifier of this instance for debugging purposes.
     *
     * @return a String identifier for this sampler instance
     */
    private String whoAmI() {
        StringBuilder sb = new StringBuilder();
        sb.append(Thread.currentThread().getName());
        sb.append("@");
        sb.append(Integer.toHexString(hashCode()));
        sb.append("-");
        sb.append(getName());
        return sb.toString();
    }

    // TestStateListener implementation
    /**
     *  Implements TestStateListener.testStarted() 
     **/
    @Override
    public void testStarted() {
        testStarted("");
    }

    /** Implements TestStateListener.testStarted(String) 
     **/
    @Override
    public void testStarted(String host) {
        if(LOGGER.isDebugEnabled()){
            LOGGER.debug(whoAmI() + "\ttestStarted(" + host + ")");
        }
        queue = new ArrayBlockingQueue<SampleResult>(getQueueSize()); 
        initClass();
        queueWaits = new AtomicLong(0L);
        queueWaitTime = new AtomicLong(0L);
        LOGGER.info(getName()+":Starting worker with class:"+clientClass +" and queue capacity:"+getQueueSize());

        backendListenerClient = createBackendListenerClientImpl(clientClass);
        BackendListenerContext context = new BackendListenerContext((Arguments)getArguments().clone());
        
        try {
            backendListenerClient.setupTest(context);
        } catch (Exception e) {
            throw new java.lang.IllegalStateException("Failed calling setupTest", e);
        }

        Worker worker = new Worker(backendListenerClient, (Arguments) getArguments().clone(), queue);
        worker.setDaemon(true);
        worker.start();
        LOGGER.info(getName()+": Started  worker with class:"+clientClass);
        
    }

    /* (non-Javadoc)
     * @see org.apache.jmeter.samplers.SampleListener#sampleOccurred(org.apache.jmeter.samplers.SampleEvent)
     */
    @Override
    public void sampleOccurred(SampleEvent event) {
        Arguments args = getArguments();
        BackendListenerContext context = new BackendListenerContext(args);

        SampleResult sr = backendListenerClient.createSampleResult(context, event.getResult());
        try {
            if (!queue.offer(sr)){ // we failed to add the element first time
                queueWaits.incrementAndGet();
                long t1 = System.nanoTime();
                queue.put(sr);
                long t2 = System.nanoTime();
                queueWaitTime.addAndGet(t2-t1);
            }
        } catch (Exception err) {
            LOGGER.error("sampleOccurred, failed to queue the sample", err);
        }
    }
    
    /**
     * Thread that dequeus data from queue to send it to {@link BackendListenerClient}
     */
    private static final class Worker extends Thread {
        
        private final BlockingQueue<SampleResult> queue;
        private final BackendListenerContext context;
        private final BackendListenerClient backendListenerClient;
        private Worker(BackendListenerClient backendListenerClient, Arguments arguments, BlockingQueue<SampleResult> q){
            queue = q;
            // Allow BackendListenerClient implementations to get access to test element name
            arguments.addArgument(TestElement.NAME, getName()); 
            context = new BackendListenerContext(arguments);
            this.backendListenerClient = backendListenerClient;
        }

        
        @Override
        public void run() {
            boolean isDebugEnabled = LOGGER.isDebugEnabled();
            List<SampleResult> sampleResults = new ArrayList<SampleResult>(queue.size());
            try {
                boolean eof = false;
                while (!eof) {
                    if(isDebugEnabled) {
                        LOGGER.debug("Thread:"+Thread.currentThread().getName()+" taking SampleResult from queue:"+queue.size());
                    }
                    SampleResult e = queue.take();
                    if(isDebugEnabled) {
                        LOGGER.debug("Thread:"+Thread.currentThread().getName()+" took SampleResult:"+e+", isFinal:" + (e==FINAL_EVENT));
                    }
                    while (!(eof = (e == FINAL_EVENT)) && e != null ) { // try to process as many as possible
                        sampleResults.add(e);
                        if(isDebugEnabled) {
                            LOGGER.debug("Thread:"+Thread.currentThread().getName()+" polling from queue:"+queue.size());
                        }
                        e = queue.poll(); // returns null if nothing on queue currently
                        if(isDebugEnabled) {
                            LOGGER.debug("Thread:"+Thread.currentThread().getName()+" took from queue:"+e+", isFinal:" + (e==FINAL_EVENT));
                        }
                    }
                    if(isDebugEnabled) {
                        LOGGER.debug("Thread:"+Thread.currentThread().getName()+
                                " exiting with FINAL EVENT:"+(e == FINAL_EVENT)
                                +", null:" + (e==null));
                    }
                    int size = sampleResults.size();
                    if (size > 0) {
                        backendListenerClient.handleSampleResults(sampleResults, context);
                        sampleResults.clear();
                    }
                    if(!eof) {
                        LockSupport.parkNanos(100);
                    }
                }
            } catch (InterruptedException e) {
                // NOOP
            }
            // We may have been interrupted
            int size = sampleResults.size();
            if (size > 0) {
                backendListenerClient.handleSampleResults(sampleResults, context);
                sampleResults.clear();
            }
            LOGGER.info("Worker ended");
        }
    }
    

    /**
     * Returns reference to <code>BackendListenerClient</code>.
     *
     *
     * @return BackendListenerClient reference.
     */
    static BackendListenerClient createBackendListenerClientImpl(Class<?> clientClass) {
        if (clientClass == null) { // failed to initialise the class
            return new ErrorBackendListenerClient();
        }
        BackendListenerClient client;
        try {
            client = (BackendListenerClient) clientClass.newInstance();
        } catch (Exception e) {
            LOGGER.error("Exception creating: " + clientClass, e);
            client = new ErrorBackendListenerClient();
        }
        return client;
    }

    /**
     * Method called at the end of the test. This is called only on one instance
     * of BackendListener. This method will loop through all of the other
     * BackendListenerClients which have been registered (automatically in the
     * constructor) and notify them that the test has ended, allowing the
     * BackendListenerClients to cleanup.
     */
    @Override
    public void testEnded() {
        try {
            queue.put(FINAL_EVENT);
        } catch (Exception ex) {
            LOGGER.warn("testEnded() with exception:"+ex.getMessage(), ex);
        }
        if (queueWaits.get() > 0) {
            LOGGER.warn("QueueWaits: "+queueWaits+"; QueueWaitTime: "+queueWaitTime+" (nanoseconds), you may need to increase queue capacity, see property 'backend_queue_capacity'");            
        }
        
        try {
            BackendListenerContext context = new BackendListenerContext(getArguments());
            backendListenerClient.teardownTest(context);
        } catch (Exception e) {
            throw new java.lang.IllegalStateException("Failed calling teardownTest", e);
        }
    }

    /** Implements TestStateListener.testEnded(String) 
     **/
    @Override
    public void testEnded(String host) {
        testEnded();
    }

    /**
     * A {@link BackendListenerClient} implementation used for error handling. If an
     * error occurs while creating the real BackendListenerClient object, it is
     * replaced with an instance of this class. Each time a sample occurs with
     * this class, the result is marked as a failure so the user can see that
     * the test failed.
     */
    static class ErrorBackendListenerClient extends AbstractBackendListenerClient {
        /**
         * Return SampleResult with data on error.
         *
         * @see BackendListenerClient#handleSampleResults(List, BackendListenerContext)
         */
        @Override
        public void handleSampleResults(List<SampleResult> sampleResults, BackendListenerContext context) {
            LOGGER.warn("ErrorBackendListenerClient#handleSampleResult called, noop");
            Thread.yield();
        }
    }
    
    /* (non-Javadoc)
     * @see org.apache.jmeter.samplers.SampleListener#sampleStarted(org.apache.jmeter.samplers.SampleEvent)
     */
    @Override
    public void sampleStarted(SampleEvent e) {
        // NOOP
        
    }

    /* (non-Javadoc)
     * @see org.apache.jmeter.samplers.SampleListener#sampleStopped(org.apache.jmeter.samplers.SampleEvent)
     */
    @Override
    public void sampleStopped(SampleEvent e) {
        // NOOP
        
    }

    /**
     * Set the arguments (parameters) for the BackendListenerClient to be executed
     * with.
     *
     * @param args
     *            the new arguments. These replace any existing arguments.
     */
    public void setArguments(Arguments args) {
        setProperty(new TestElementProperty(ARGUMENTS, args));
    }

    /**
     * Get the arguments (parameters) for the BackendListenerClient to be executed
     * with.
     *
     * @return the arguments
     */
    public Arguments getArguments() {
        return (Arguments) getProperty(ARGUMENTS).getObjectValue();
    }

    /**
     * Sets the Classname of the BackendListenerClient object
     *
     * @param classname
     *            the new Classname value
     */
    public void setClassname(String classname) {
        setProperty(CLASSNAME, classname);
    }

    /**
     * Gets the Classname of the BackendListenerClient object
     *
     * @return the Classname value
     */
    public String getClassname() {
        return getPropertyAsString(CLASSNAME);
    }
    
    /**
     * Sets the queue size
     *
     * @param queueSize
     *            
     */
    public void setQueueSize(int queueSize) {
        setProperty(QUEUE_SIZE, queueSize, DEFAULT_QUEUE_SIZE);
    }

    /**
     * Gets the queue size
     *
     * @return int queueSize
     */
    public int getQueueSize() {
        return getPropertyAsInt(QUEUE_SIZE, DEFAULT_QUEUE_SIZE);
    }
}