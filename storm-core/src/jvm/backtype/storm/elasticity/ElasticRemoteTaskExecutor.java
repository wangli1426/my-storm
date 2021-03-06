package backtype.storm.elasticity;

import backtype.storm.elasticity.actors.Slave;
import backtype.storm.elasticity.config.Config;
import backtype.storm.elasticity.message.taksmessage.CleanPendingTupleRequestToken;
import backtype.storm.elasticity.message.taksmessage.ITaskMessage;
import backtype.storm.elasticity.message.taksmessage.PendingTupleCleanedMessage;
import backtype.storm.elasticity.message.taksmessage.RemoteState;
import backtype.storm.elasticity.routing.TwoTireRouting;
import backtype.storm.elasticity.routing.PartialHashingRouting;
import backtype.storm.elasticity.routing.RoutingTable;
import backtype.storm.elasticity.routing.RoutingTableUtils;
import backtype.storm.elasticity.utils.MonitorUtils;
import backtype.storm.tuple.Tuple;
import backtype.storm.elasticity.state.*;
import backtype.storm.utils.Utils;
import org.apache.commons.lang.SerializationUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Created by Robert on 11/14/15.
 */
public class ElasticRemoteTaskExecutor {

    ElasticExecutor _elasticExecutor;

    private ArrayBlockingQueue<ITaskMessage> _resultQueue;

    private ArrayBlockingQueue<Object> _inputQueue = new ArrayBlockingQueue<>(Config.RemoteExecutorInputQueueCapacity);

    BaseElasticBolt _bolt;

    private Thread _inputRoutingThread;

    private Thread _stateCheckpointingThread;

    public ElasticRemoteTaskExecutor(ElasticExecutor tasks, ArrayBlockingQueue resultQueue, BaseElasticBolt bolt ) {
        _elasticExecutor = tasks;
        _resultQueue = resultQueue;
        _bolt = bolt;
    }

    public void prepare(Map<Serializable, Serializable> state ) {
        final RemoteElasticOutputCollector outputCollector = new RemoteElasticOutputCollector(_resultQueue, _elasticExecutor.get_id());
        KeyValueState keyValueState = new KeyValueState();
        keyValueState.getState().putAll(state);
        _elasticExecutor.prepare(outputCollector, keyValueState);
        _elasticExecutor.createAndLaunchLocalTasks();
        createInputRoutingThread();

        //this is ignore now, as state fault tolerance is not support now. And state should be cloned before calling
        // serialize() to avoid the concurrent modification exception.
//        createStateCheckpointingThread();
    }

    private void createInputRoutingThread() {
        _inputRoutingThread = new Thread(new InputTupleRouting());
        _inputRoutingThread.start();
        MonitorUtils.instance().registerQueueMonitor(_inputQueue, "Remote Execute Input Queue",
                Config.RemoteExecutorInputQueueCapacity, null, 0.8, 5);
        MonitorUtils.instance().registerThreadMonitor(_inputRoutingThread.getId(), "remote dispatch thread", 0.9, 5);
//        ElasticTaskHolder.instance().createQueueUtilizationMonitoringThread(_inputQueue, "Remote Input Queue", Config.RemoteExecutorInputQueueCapacity, 0.9, 0.1);

        System.out.println("processing thread is created!");
    }

    public void createStateCheckpointingThread() {
        final StateCheckoutPointing checkPointing = new StateCheckoutPointing(Config.StateCheckPointingCycleInSecs);
        _stateCheckpointingThread = new Thread(checkPointing);
        _stateCheckpointingThread.start();

        System.out.println("state checkpointing thread is created");
    }

    class InputTupleRouting implements Runnable {

        boolean _terminated = false;
        boolean _terminating = false;

        public void terminate() {
            while(!_terminated) {
                Utils.sleep(1);
            }
        }

        @Override
        public void run() {
            int count = 0;
            try {
                while (!_terminating) {
                    try {
                        //                    System.out.println("poll...");
                        Object item = _inputQueue.poll(5, TimeUnit.MILLISECONDS);
                        //                    System.out.println("polled!");

                        if(item == null)
                           continue;

                        if(item instanceof Tuple) {
                            final Tuple input = (Tuple) item;
                            boolean handled = _elasticExecutor.dispatch(input, _bolt.getKey(input));
                            count++;
                            if (count % 10000 == 0) {
                                System.out.println("A remote tuple for " + _elasticExecutor.get_id() + "." + _elasticExecutor.get_routingTable().route(_bolt.getKey(input)).originalRoute + "has been processed");
                                count = 0;
                            }

                            if (!handled)
                                System.err.println("Failed to handle a remote tuple. There is possibly something wrong with the routing table!");

                        } else if(item instanceof CleanPendingTupleRequestToken) {
                            final CleanPendingTupleRequestToken token = (CleanPendingTupleRequestToken) item;
                            //The protocol guarantees that no tuples will be sent to the target route. So a new thread can be created to do the clean job, avoiding
                            // interfering the process of other route.
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    System.out.println("to handle handleCleanPendingTupleToken");
                                    _elasticExecutor.makesSureNoPendingTuples(token.routeId);
                                    PendingTupleCleanedMessage message = new PendingTupleCleanedMessage(token.executorId, token.routeId);
                                    System.out.println(String.format("Pending tuple for %s.%s is cleaned!", token.executorId, token.routeId));
                                    ElasticTaskHolder.instance().communicator._remoteExecutorIdToPrioritizedSender.get(token.executorId).send(token.executorId, SerializationUtils.serialize(message));
                                    System.out.println(String.format("PendingTupleCleanedMessage is sent back to %s",ElasticTaskHolder.instance().communicator._remoteExecutorIdToPrioritizedSender.get(token.executorId).toString()));
                                }
                            }).start();
                        } else {
                            System.err.println("Received Unexpected Object: " + item);
                        }
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) {
                            System.out.println("InputTupleRouting thread is interrupted!");
                            throw e;
                        }
                        System.out.println(String.format("_elasticExecutor: %s, _bolt: %s", _elasticExecutor, _bolt));
                        e.printStackTrace();
                    }

                }

                } catch (InterruptedException  e) {
                e.printStackTrace();

            }
            _terminated = true;

            System.out.println("Routing process is terminated!");
        }
    }

    class StateCheckoutPointing implements Runnable {

        boolean _terminating = false;
        boolean _terminated = false;
        int _cycleInSecs;

        public StateCheckoutPointing(int cycleInSecs) {
            _cycleInSecs = cycleInSecs;
        }

        public StateCheckoutPointing() {
            this(10);
        }

        public void terminate() {
            _terminating = true;
            while(!_terminated) {
                Utils.sleep(1);
            }
        }

        @Override
        public void run() {

                while (!_terminating) {
                    try {
                        Thread.sleep(5000 * _cycleInSecs);
                        RemoteState state = getStateForRoutes(_elasticExecutor.get_routingTable().getRoutes());
                        System.out.println("State (" + state._state.size() + " element) has been added into the sending queue!");
                        _resultQueue.put(state);

                    } catch (InterruptedException e) {
                        System.out.println("StateCheckoutPointing is interrupted!");
                    }
                }
                _terminated = true;

        }
    }


    public RemoteState getStateForRoutes(List<Integer> routes) throws InterruptedException {
        KeyValueState state = _elasticExecutor.get_bolt().getState();
        KeyValueState stateForRoutes = new KeyValueState();
        HashSet<Integer> routeSet = new HashSet<>(routes);
        for (Serializable key: state.getState().keySet()) {
            if(routeSet.contains(_elasticExecutor.get_routingTable().route(key).originalRoute)) {
                stateForRoutes.setValueByKey(key, state.getValueByKey(key));
            }
        }
        RemoteState remoteState = new RemoteState(_elasticExecutor.get_id(),stateForRoutes.getState(),routes);
        return remoteState;
    }

    public RemoteState getStateForRoutes(int i) throws InterruptedException {
        ArrayList<Integer> routes = new ArrayList<>();
        routes.add(i);
        return getStateForRoutes(routes);
    }

    public ArrayBlockingQueue<Object> get_inputQueue() {
        return _inputQueue;
    }

    public void mergeRoutingTableAndCreateCreateWorkerThreads(RoutingTable routingTable) {

        if(!(routingTable instanceof PartialHashingRouting) || !(_elasticExecutor.get_routingTable() instanceof PartialHashingRouting)) {
            System.out.println("Routing table cannot be merged, when either of the routing table is not an instance of PartialHashingRouting");
            return;
        }

        if(RoutingTableUtils.getBalancecHashRouting(routingTable)!=null && RoutingTableUtils.getBalancecHashRouting(_elasticExecutor.get_routingTable())!=null) {
            TwoTireRouting exitingRouting = RoutingTableUtils.getBalancecHashRouting(_elasticExecutor.get_routingTable());
            TwoTireRouting incomingRouting = RoutingTableUtils.getBalancecHashRouting(routingTable);
            exitingRouting.update(incomingRouting);
        } else {
            Slave.getInstance().sendMessageToMaster("Routing table cannot be updated as balanced routing table cannot be extracted!");
        }

        List<Integer> newRoutes = routingTable.getRoutes();
        ((PartialHashingRouting) _elasticExecutor.get_routingTable()).addValidRoutes(newRoutes);

        for(int i:routingTable.getRoutes()) {
            _elasticExecutor.createAndLaunchElasticTasksForGivenRoute(i);
        }
        System.out.println("routing table is merged and the worker threads are created!");
    }

    /** There are two ways to terminate a running thread:
     *  (1) employ a termination flag to notify the thread upon termination request. The thread
     *  terminates at desirable points of execution once the flag is detected.
     *  (2) employ Thread.terminate() to terminate the thread at cancellation points (e.g., sleep,
     *  blocking system calls).
     *
     *  Method (2) is easier to implement and typically terminates as early as possible, but the
     *  termination points are out of control and hence may introduce inconsistency to the programming logic.
     *
     *  We use method (2) because termination of any cancellation points is acceptable in our logic.
     */
    public void close() {


//        _checkPointing.terminate();
//        _processingRunnable.terminate();

        _stateCheckpointingThread.interrupt();

        MonitorUtils.instance().unregister(_inputRoutingThread.getId());
        _inputRoutingThread.interrupt();
        try {
            _stateCheckpointingThread.join();
            _inputRoutingThread.join();

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

}
