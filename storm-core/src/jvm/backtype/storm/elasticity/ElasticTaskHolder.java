package backtype.storm.elasticity;

import backtype.storm.elasticity.common.RouteId;
import backtype.storm.elasticity.common.ShardWorkload;
import backtype.storm.elasticity.common.SubTaskWorkload;
import backtype.storm.elasticity.config.Config;
import backtype.storm.elasticity.exceptions.BucketNotExistingException;
import backtype.storm.elasticity.message.actormessage.*;
import backtype.storm.elasticity.actors.Slave;
import backtype.storm.elasticity.exceptions.InvalidRouteException;
import backtype.storm.elasticity.exceptions.RoutingTypeNotSupportedException;
import backtype.storm.elasticity.exceptions.TaskNotExistingException;
import backtype.storm.elasticity.message.taksmessage.*;
import backtype.storm.elasticity.metrics.ExecutionLatencyForRoutes;
import backtype.storm.elasticity.metrics.ThroughputForRoutes;
import backtype.storm.elasticity.metrics.WorkerMetrics;
import backtype.storm.elasticity.resource.ResourceMonitor;
import backtype.storm.elasticity.routing.TwoTireRouting;
import backtype.storm.elasticity.routing.PartialHashingRouting;
import backtype.storm.elasticity.routing.RoutingTable;
import backtype.storm.elasticity.routing.RoutingTableUtils;
import backtype.storm.elasticity.scheduler.ShardReassignment;
import backtype.storm.elasticity.scheduler.ShardReassignmentPlan;
import backtype.storm.elasticity.state.*;
import backtype.storm.elasticity.utils.FirstFitDoubleDecreasing;
import backtype.storm.elasticity.utils.Histograms;
import backtype.storm.elasticity.utils.MonitorUtils;
import backtype.storm.elasticity.utils.serialize.RemoteTupleExecuteResultDeserializer;
import backtype.storm.elasticity.utils.serialize.RemoteTupleExecuteResultSerializer;
import backtype.storm.elasticity.utils.timer.SmartTimer;
import backtype.storm.elasticity.utils.timer.SubtaskWithdrawTimer;
import backtype.storm.generated.HostNotExistException;
import backtype.storm.messaging.IConnection;
import backtype.storm.messaging.IContext;
import backtype.storm.messaging.TaskMessage;
import backtype.storm.messaging.netty.Client;
import backtype.storm.messaging.netty.Context;
import backtype.storm.serialization.KryoTupleDeserializer;
import backtype.storm.serialization.KryoTupleSerializer;
import backtype.storm.task.WorkerTopologyContext;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.TupleImpl;
import backtype.storm.utils.Utils;
import org.apache.commons.lang.SerializationException;
import org.apache.commons.lang.SerializationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Created by Robert on 11/3/15.
 */
public class ElasticTaskHolder {

    public static Logger LOG = LoggerFactory.getLogger(ElasticTaskHolder.class);

    private static ElasticTaskHolder _instance;

    private String _workerId;

    private int _port;

    Slave _slaveActor;

    private WorkerTopologyContext _workerTopologyContext;

    private Map stormConf;

    private Map<Integer, BaseElasticBoltExecutor> _bolts = new HashMap<>();

    private Map<Integer, ElasticRemoteTaskExecutor> _originalTaskIdToRemoteTaskExecutor = new HashMap<>();

    private Map<String, Semaphore> _taskIdRouteToStateWaitingSemaphore = new ConcurrentHashMap<>();

    Map<Integer, Map<Integer, Semaphore>> _taskIdToRouteToSendingWaitingSemaphore = new ConcurrentHashMap<>();

    private  Map<String, Semaphore> _taskIdRouteToCleanPendingTupleSemaphore = new ConcurrentHashMap<>();

    ResourceMonitor resourceMonitor;

    Map<RouteId, String> routeIdToRemoteHost = new HashMap<>();

    IntraExecutorCommunicator communicator = new IntraExecutorCommunicator();

    class IntraExecutorCommunicator {

        final int sendingBatchSize = 4096 * 2;

        private KryoTupleSerializer tupleSerializer;
        private KryoTupleDeserializer tupleDeserializer;

        private RemoteTupleExecuteResultSerializer remoteTupleExecuteResultSerializer;
        private RemoteTupleExecuteResultDeserializer remoteTupleExecuteResultDeserializer;

        private Map<String, IConnection> _taskIdRouteToSender = new ConcurrentHashMap<>();
        private Map<Integer, IConnection> _executorIdToRemoteTaskExecutorResultSender = new ConcurrentHashMap<>();
        private Map<Integer, IConnection> _remoteExecutorIdToSender = new ConcurrentHashMap<>();
        Map<Integer, IConnection> _remoteExecutorIdToPrioritizedSender = new ConcurrentHashMap<>();

        private IConnection _inputReceiver;
        private IConnection _prioritizedInputReceiver;
        private IConnection _remoteExecutionResultReceiver;

        private IContext _context;

        ArrayBlockingQueue<ITaskMessage> _sendingQueue = new ArrayBlockingQueue<>(Config
                .ElasticTaskHolderOutputQueueCapacity);

        void addRemoteTaskOutputConnection(int executorId, IConnection connection) {
            _executorIdToRemoteTaskExecutorResultSender.put(executorId, connection);
        }

        void initialize(String workerId) {
            _context = new Context();
            _context.prepare(stormConf);

            _inputReceiver = _context.bind(workerId, _port);
            _prioritizedInputReceiver = _context.bind(workerId, _port + 5);
            _remoteExecutionResultReceiver = _context.bind(workerId, _port + 10);

            createExecuteResultSendingThread();
            createExecuteResultReceivingThread();
            createPriorityReceivingThread();
            createRemoteExecutorResultReceivingThread();
        }

        boolean connectionReady(String taskIdRoute) {
            return _taskIdRouteToSender.containsKey(taskIdRoute);
        }

        boolean connectionExisting(String taskIdRoute) {
            return _taskIdRouteToSender.containsKey(taskIdRoute);
        }

        void disconnectToRoute(String taskIdRoute) {
            _taskIdRouteToSender.remove(taskIdRoute);
        }

        IConnection getConnectionOfRoute(String taskIdRoute) {
            return _taskIdRouteToSender.get(taskIdRoute);
        }

        private void establishConnectionToRemoteTaskHolder(int taksId, int route, String remoteIp, int remotePort) {
            Client connection = (Client) _context.connect("", remoteIp, remotePort);
            while (connection.status() != Client.Status.Ready)
                Utils.sleep(1);
            _taskIdRouteToSender.put(taksId + "." + route, connection);
            System.out.println("Established connection with remote task holder for " + taksId + "." + route);
        }

        private void establishConnectionToOriginalTaskHolder(int executorId, String ip, int port) {
            Client iConnection = (Client) _context.connect(ip + ":" + port + "-" +
                    executorId, ip, port);
            while (iConnection.status() != Client.Status.Ready)
                Utils.sleep(1);
            communicator._remoteExecutorIdToSender.put(executorId, iConnection);

            Client prioritizedConnection = (Client) _context.connect(ip + ":" + (port + 5) + "-"
                    + executorId, ip, port + 5);
            while (prioritizedConnection.status() != Client.Status.Ready)
                Utils.sleep(1);
            communicator._remoteExecutorIdToPrioritizedSender.put(executorId, prioritizedConnection);

            Client remoteExecutionResultConnection = (Client) _context.connect(ip + ":"
                    + (port + 10) + "-" + executorId, ip, port + 10);
            while (remoteExecutionResultConnection.status() != Client.Status.Ready)
                Utils.sleep(1);
            communicator.addRemoteTaskOutputConnection(executorId,
                    remoteExecutionResultConnection);

            System.out.println("Connected with original Task Holders");
        }

        private void insertToConnectionToTaskMessageArray(Map<String, ArrayList<TaskMessage>> map,
                                                                 Map<String, IConnection> connectionNameToIConnection,
                                                                 IConnection connection, TaskMessage message) {
            String connectionName = connection.toString();
            if (!map.containsKey(connectionName)) {
                map.put(connectionName, new ArrayList<TaskMessage>());
                connectionNameToIConnection.put(connectionName, connection);
            }
            map.get(connectionName).add(message);
        }

        private void createExecuteResultSendingThread() {

            final Thread sendingThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    ArrayList<ITaskMessage> drainer = new ArrayList<>();
                    while (true) {
                        Object object = null;
                        try {

                            Map<String, ArrayList<TaskMessage>> iConnectionNameToTaskMessageArray = new HashMap<>();
                            Map<String, IConnection> connectionNameToIConnection = new HashMap<>();

                            object = _sendingQueue.take();
                            ITaskMessage firstMessage = (ITaskMessage) object;
                            drainer.add(firstMessage);
                            _sendingQueue.drainTo(drainer, sendingBatchSize);

                            for (ITaskMessage message : drainer) {
                                if (message instanceof RemoteTupleExecuteResult) {
                                    RemoteTupleExecuteResult remoteTupleExecuteResult = (RemoteTupleExecuteResult)
                                            message;
                                    if (_executorIdToRemoteTaskExecutorResultSender.containsKey
                                            (remoteTupleExecuteResult._originalTaskID)) {
                                        byte[] bytes = remoteTupleExecuteResultSerializer.serialize
                                                (remoteTupleExecuteResult);
                                        WorkerMetrics.getInstance().recordRemoteTaskTupleOrExecutionResultTransfer
                                                (bytes.length);
                                        TaskMessage taskMessage = new TaskMessage(remoteTupleExecuteResult
                                                ._originalTaskID, bytes);
                                        insertToConnectionToTaskMessageArray(iConnectionNameToTaskMessageArray,
                                                connectionNameToIConnection,
                                                _executorIdToRemoteTaskExecutorResultSender.get
                                                        (remoteTupleExecuteResult._originalTaskID), taskMessage);
                                    } else {
                                        System.err.println("RemoteTupleExecuteResult will be ignored, because we " +
                                                "cannot" +
                                                " find the connection for tasks "
                                                + remoteTupleExecuteResult._originalTaskID);
                                    }
                                } else if (message instanceof RemoteTuple) {

                                    RemoteTuple remoteTuple = (RemoteTuple) message;
                                    final String key = remoteTuple.taskIdAndRoutePair();
                                    long startTime = -1;
                                    while (!communicator.connectionReady(key)) {
                                        Utils.sleep(1);
                                        if (startTime == -1)
                                            startTime = System.currentTimeMillis();

                                        if (System.currentTimeMillis() - startTime > 1000) {
                                            System.out.println("Cannot send tuples to " + remoteTuple._taskId + "." +
                                                    remoteTuple._route + ", as it is not ready now!");
                                            sendMessageToMaster("Cannot send tuples to " + remoteTuple._taskId + "."
                                                    + remoteTuple._route + ", as it istaskidRoute not ready now!");
                                            startTime = System.currentTimeMillis();
                                        }
                                    }
                                    byte[] bytes = tupleSerializer.serialize(remoteTuple._tuple);
                                    WorkerMetrics.getInstance().recordRemoteTaskTupleOrExecutionResultTransfer(bytes
                                            .length);
                                    TaskMessage taskMessage = new TaskMessage(remoteTuple._taskId, bytes);
                                    taskMessage.setRemoteTuple();
                                    insertToConnectionToTaskMessageArray(iConnectionNameToTaskMessageArray,
                                            connectionNameToIConnection, _taskIdRouteToSender.get(key),
                                            taskMessage);
                                } else if (message instanceof RemoteSubtaskTerminationToken) {
                                    RemoteSubtaskTerminationToken remoteSubtaskTerminationToken =
                                            (RemoteSubtaskTerminationToken) message;
                                    final String key = remoteSubtaskTerminationToken.taskid + "." +
                                            remoteSubtaskTerminationToken.route;
                                    if (_taskIdRouteToSender.containsKey(key)) {
                                        final byte[] bytes = SerializationUtils.serialize
                                                (remoteSubtaskTerminationToken);
                                        TaskMessage taskMessage = new TaskMessage(remoteSubtaskTerminationToken
                                                .taskid, bytes);
                                        insertToConnectionToTaskMessageArray(iConnectionNameToTaskMessageArray,
                                                connectionNameToIConnection, _taskIdRouteToSender.get(key),
                                                taskMessage);
                                        System.out.println("RemoteSubtaskTerminationToken is sent to " +
                                                _taskIdRouteToSender.get(key));
                                    } else {
                                        System.err.println("RemoteSubtaskTerminationToken does not have a valid " +
                                                "taskid and route: "
                                                + key);
                                    }
                                } else if (message instanceof RemoteState) {
                                    RemoteState remoteState = (RemoteState) message;
                                    byte[] bytes = SerializationUtils.serialize(remoteState);
                                    IConnection connection = _remoteExecutorIdToSender.get(remoteState._taskId);
                                    if (connection != null) {
                                        if (!remoteState.finalized && !_originalTaskIdToRemoteTaskExecutor
                                                .containsKey(remoteState._taskId)) {
                                            System.out.println("Remote state is ignored to send, as the state is not " +
                                                    "finalized ans the original RemoteTaskExecutor does not exist!");
                                            continue;
                                        }
                                        TaskMessage taskMessage = new TaskMessage(remoteState._taskId, bytes);
                                        insertToConnectionToTaskMessageArray(iConnectionNameToTaskMessageArray,
                                                connectionNameToIConnection, connection, taskMessage);

                                        System.out.println("RemoteState is sent back!");
                                    } else {
                                        System.err.println("Cannot find the connection for task " + remoteState._state);
                                        System.out.println("TaskId: " + remoteState._taskId);
                                        System.out.println("Connections: " + _remoteExecutorIdToSender);
                                    }
                                } else if (message instanceof MetricsForRoutesMessage) {
                                    MetricsForRoutesMessage latencyForRoutes = (MetricsForRoutesMessage) message;
                                    byte[] bytes = SerializationUtils.serialize(latencyForRoutes);
                                    IConnection connection = _remoteExecutorIdToSender.get(latencyForRoutes.taskId);
                                    if (connection != null) {
                                        TaskMessage taskMessage = new TaskMessage(latencyForRoutes.taskId, bytes);
                                        insertToConnectionToTaskMessageArray(iConnectionNameToTaskMessageArray,
                                                connectionNameToIConnection, connection, taskMessage);
//                                    System.out.println("MetricsForRoutesMessage is sent!");
                                    } else {
                                        System.err.println("Cannot find the connection for task " + latencyForRoutes
                                                .toString());
                                    }
                                } else if (message instanceof CleanPendingTupleRequestToken) {
//                                  sendMessageToMaster("CleanPendingTuplesToken will be sent!");
                                    CleanPendingTupleRequestToken cleanPendingTupleRequestToken = (CleanPendingTupleRequestToken) message;
                                    byte[] bytes = SerializationUtils.serialize(cleanPendingTupleRequestToken);
                                    IConnection connection = _taskIdRouteToSender.get(cleanPendingTupleRequestToken
                                            .executorId + "." + cleanPendingTupleRequestToken.routeId);
                                    if (connection != null) {
                                        TaskMessage taskMessage = new TaskMessage(cleanPendingTupleRequestToken.executorId,
                                                bytes);
                                        insertToConnectionToTaskMessageArray(iConnectionNameToTaskMessageArray,
                                                connectionNameToIConnection, connection, taskMessage);
                                    } else {
                                        sendMessageToMaster("CleanPendingTupleRequestToken cannot be sent, as the connection" +
                                                " to the target is not found!!!");
                                        System.out.println("\"CleanPendingTupleRequestToken canot be sent, as the connection" +
                                                " to the target is not found!!!");
                                    }
                                } else {
                                    System.err.print("Unknown element from the sending queue");
                                }
                            }

                            for (String connectionName : iConnectionNameToTaskMessageArray.keySet()) {
                                if (!iConnectionNameToTaskMessageArray.get(connectionName).isEmpty()) {
                                    connectionNameToIConnection.get(connectionName).send
                                            (iConnectionNameToTaskMessageArray.get(connectionName).iterator());
                                }
                            }
                            drainer.clear();
                        } catch (SerializationException ex) {
                            System.err.println("Serialization Error!");
                            ex.printStackTrace();
                        } catch (ClassCastException e) {
                            e.printStackTrace();
                            System.out.println("Class case exception happens!");
                            if(object != null)
                                System.out.println(String.format("Message: %s", object.toString()));
                            sendMessageToMaster("Class case exception happens!");
                            System.exit(1024);
                        } catch (Exception eee) {
                            eee.printStackTrace();
                        }
                    }
                }
            });
            sendingThread.start();
            System.out.println("sending thread is created!");

            MonitorUtils.instance().registerThreadMonitor(sendingThread.getId(), "Sending Thread", 0.2, 5);
            MonitorUtils.instance().registerQueueMonitor(_sendingQueue, "hold sending queue",
                    Config.ElasticTaskHolderOutputQueueCapacity, null, 0.7, 5);
        }

        private void createExecuteResultReceivingThread() {
            Thread receivingThread =
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            while (true) {
                                TaskMessage message = null;
                                try {
                                    Iterator<TaskMessage> messageIterator = _inputReceiver.recv(0, 0);
                                    while (messageIterator.hasNext()) {
                                        message = messageIterator.next();
                                        int targetTaskId = message.task();

                                        if (message.remoteTuple == 1) {
                                            Tuple remoteTuple = tupleDeserializer.deserialize(message.message());
                                            try {
                                                ElasticRemoteTaskExecutor elasticRemoteTaskExecutor =
                                                        _originalTaskIdToRemoteTaskExecutor.get(targetTaskId);
                                                ArrayBlockingQueue<Object> queue = elasticRemoteTaskExecutor
                                                        .get_inputQueue();
                                                queue.put(remoteTuple);
                                            } catch (NullPointerException e) {
                                                System.out.println(String.format("TaskId: %d, ", targetTaskId));
                                                e.printStackTrace();
                                            }

                                        } else {
                                            Object object = SerializationUtils.deserialize(message.message());
                                            if (object instanceof RemoteTupleExecuteResult) {
                                                RemoteTupleExecuteResult result = (RemoteTupleExecuteResult) object;
                                                result.spaceEfficientDeserialize(remoteTupleExecuteResultDeserializer);
                                                ((TupleImpl) result._inputTuple).setContext(_workerTopologyContext);
                                                _bolts.get(targetTaskId).insertToResultQueue(result);
                                            } else if (object instanceof RemoteState) {
                                                System.out.println("Received RemoteState!");
                                                RemoteState remoteState = (RemoteState) object;
                                                handleRemoteState(remoteState);

                                            } else if (object instanceof RemoteSubtaskTerminationToken) {
                                                System.out.print("Received a RemoteSubtaskTerminationToken!");
                                                sendMessageToMaster("Received a RemoteSubtaskTerminationToken!");
                                                RemoteSubtaskTerminationToken remoteSubtaskTerminationToken =
                                                        (RemoteSubtaskTerminationToken) object;
                                                terminateRemoteRoute(remoteSubtaskTerminationToken.taskid,
                                                        remoteSubtaskTerminationToken.route);
                                            } else if (object instanceof ShardToRouteReassignment) {
                                                System.out.println("Received ShardToRouteReassignment");
                                                ShardToRouteReassignment reassignment = (ShardToRouteReassignment) object;
                                                handleShardToRouteReassignment(reassignment);
                                            } else if (object instanceof StateFlushToken) {
                                                System.out.println("Received StateFlushToken!");
                                                StateFlushToken token = (StateFlushToken) object;
                                                handleStateFlushToken(token);
                                            } else if (object instanceof MetricsForRoutesMessage) {
                                                System.out.println("MetricsForRoutesMessage");
                                                MetricsForRoutesMessage latencyForRoutesMessage =
                                                        (MetricsForRoutesMessage) object;
                                                int taskId = latencyForRoutesMessage.taskId;
                                                _bolts.get(taskId).updateLatencyMetrics(latencyForRoutesMessage
                                                        .latencyForRoutes);
                                                _bolts.get(taskId).updateThroughputMetrics(latencyForRoutesMessage
                                                        .throughputForRoutes);

                                            } else if (object instanceof CleanPendingTupleRequestToken) {
                                                System.out.println("CleanPendingTupleRequestToken");
                                                CleanPendingTupleRequestToken cleanPendingTupleRequestToken = (CleanPendingTupleRequestToken)
                                                        object;
                                                final int taskId = cleanPendingTupleRequestToken.executorId;
                                                _originalTaskIdToRemoteTaskExecutor.get(taskId).get_inputQueue().put
                                                        (cleanPendingTupleRequestToken);

                                            } else if (object instanceof String) {
                                                System.out.println("Received Message: " + object);
                                                sendMessageToMaster("Received Message: " + object);
                                            } else {
                                                System.err.println("Unexpected Object: " + object);
                                            }
                                        }
                                    }

                                } catch (Exception e) {
                                    System.out.println("TaskMessage: " + message.toString());
                                    e.printStackTrace();
                                }
                            }
                        }
                    });
            receivingThread.start();
            MonitorUtils.instance().registerThreadMonitor(receivingThread.getId(), "Receiving Thread,", 0.7, 10);
        }

        private void createPriorityReceivingThread() {
            Thread receivingThread =
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            while (true) {
                                try {
                                    Iterator<TaskMessage> messageIterator = _prioritizedInputReceiver.recv(0, 0);
                                    while (messageIterator.hasNext()) {
                                        TaskMessage message = messageIterator.next();
                                        Object object = SerializationUtils.deserialize(message.message());
                                        if (object instanceof RemoteState) {
                                            System.out.println("[PrioritizedInput:] received RemoteState!");
                                            RemoteState remoteState = (RemoteState) object;
                                            handleRemoteState(remoteState);
                                            System.out.println("[PrioritizedInput:] handled RemoteState!");
                                        } else if (object instanceof PendingTupleCleanedMessage) {
                                            System.out.println("[PrioritizedInput:] received PendingTupleCleanedMessage!");
                                            PendingTupleCleanedMessage cleanedMessage = (PendingTupleCleanedMessage) object;
                                            handlePendingTupleCleanedMessage(cleanedMessage);
                                            System.out.println("[PrioritizedInput:] handled PendingTupleCleanedMessage!");
                                        } else if (object instanceof MetricsForRoutesMessage) {
                                            MetricsForRoutesMessage latencyForRoutesMessage = (MetricsForRoutesMessage)
                                                    object;
                                            int taskId = latencyForRoutesMessage.taskId;
                                            _bolts.get(taskId).updateLatencyMetrics(latencyForRoutesMessage
                                                    .latencyForRoutes);
                                            _bolts.get(taskId).updateThroughputMetrics(latencyForRoutesMessage
                                                    .throughputForRoutes);
                                        } else {
                                            System.err.println(" -_- Priority input connection receives unexpected " +
                                                    "object: " + object);
                                            _slaveActor.sendMessageToMaster("-_- Priority input connection receives " +
                                                    "unexpected object: " + object);
                                        }
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    });
            receivingThread.start();
            MonitorUtils.instance().registerThreadMonitor(receivingThread.getId(), "Priority Receiving Thread", 0.7, 10);
        }

        private void createRemoteExecutorResultReceivingThread() {
            Thread receivingThread =
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            while (true) {
                                try {
                                    Iterator<TaskMessage> messageIterator = _remoteExecutionResultReceiver.recv(0,
                                            0);
                                    while (messageIterator.hasNext()) {
                                        TaskMessage message = messageIterator.next();
                                        Object object = remoteTupleExecuteResultDeserializer.deserializeToTuple(message
                                                .message());
                                        if (object instanceof RemoteTupleExecuteResult) {
                                            RemoteTupleExecuteResult result = (RemoteTupleExecuteResult) object;
                                            if (result._inputTuple != null)
                                                ((TupleImpl) result._inputTuple).setContext(_workerTopologyContext);
                                            _bolts.get(result._originalTaskID).insertToResultQueue(result);
                                        } else {
                                            System.err.println("Remote Execution Result input connection receives " +
                                                    "unexpected object: " + object);
                                            _slaveActor.sendMessageToMaster("Remote Execution Result input connection " +
                                                    "receives unexpected object: " + object);
                                        }
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                }
                            }
                        }
                    });
            receivingThread.start();
            MonitorUtils.instance().registerThreadMonitor(receivingThread.getId(), "RemoteExecutorResult Receiving " +
                    "Thread", 0.8, 5);
        }
    }

    static public ElasticTaskHolder instance() {
        return _instance;
    }

    static public ElasticTaskHolder createAndGetInstance(Map stormConf, String workerId, int port) {
        if (_instance == null) {
            _instance = new ElasticTaskHolder(stormConf, workerId, port);
        }
        return _instance;
    }

    private ElasticTaskHolder(Map stormConf, String workerId, int port) {
        LOG.info("Initializing Elastic Task Holder...");
        Config.overrideFromStormConf(stormConf);
        this.stormConf = stormConf;
        _port = port + 10000;

        _workerId = workerId;
        _slaveActor = Slave.createActor(_workerId, Integer.toString(port));
        if (_slaveActor == null)
            System.out.println("NOTE: _slaveActor is null!!***************\n");
        communicator.initialize(workerId);

        LOG.info("ElasticTaskHolder is launched.");
        LOG.info("storm id:" + workerId + " port:" + port);
        Utils.sleep(2000);
        resourceMonitor = new ResourceMonitor();
        createMetricsReportThread();
        createParallelismPredicationThread();
        LOG.info("Elastic Task Holder initialized.");
    }

    public void registerElasticBolt(BaseElasticBoltExecutor bolt, int taskId) {
        _bolts.put(taskId, bolt);
        _taskIdToRouteToSendingWaitingSemaphore.put(taskId, new ConcurrentHashMap<Integer, Semaphore>());
        _slaveActor.registerOriginalElasticTaskToMaster(taskId);
        LOG.info("A new ElasticTask is registered." + taskId);
    }

    public void sendMessageToMaster(String message) {
        _slaveActor.sendMessageToMaster(message);
    }


    public ElasticTaskMigrationMessage generateRemoteElasticTasks(int taskid, int route)
            throws RoutingTypeNotSupportedException, InvalidRouteException {
        if (!_bolts.containsKey(taskid)) {
            LOG.error("task " + taskid + " does not exist! Remember to use withdraw command if you want to " +
                    "move" + " a remote subtask to the original host!");
            throw new RuntimeException("task " + taskid + " does not exist! Remember to use withdraw command if you " +
                    "want" + " to move a remote subtask to the original host!");
        }

        try {
            LOG.info("Add exceptions to the routing table...");
            /* set exceptions for existing routing table and get the complement routing table */
            PartialHashingRouting complementHashingRouting = _bolts.get(taskid).get_elasticExecutor()
                    .addExceptionForHashRouting(route);
            SmartTimer.getInstance().stop("SubtaskMigrate", "rerouting 1");
            SmartTimer.getInstance().start("SubtaskMigrate", "state construction");
            //        if(complementHashingRouting==null) {
            //            return null;
            //        }

            LOG.info("Constructing the instance of ElasticTask for remote execution...");
            /* construct the instance of ElasticExecutor to be executed remotely */
            ElasticExecutor existingElasticExecutor = _bolts.get(taskid).get_elasticExecutor();
            ElasticExecutor remoteElasticExecutor = new ElasticExecutor(existingElasticExecutor.get_bolt(),
                    existingElasticExecutor.get_id(), complementHashingRouting);
            remoteElasticExecutor.setRemoteElasticTasks();

            LOG.info("Packing the involved state...");
            long start = System.currentTimeMillis();
            KeyValueState existingState = existingElasticExecutor.get_bolt().getState();

            KeyValueState state = new KeyValueState();

            if (existingState != null) {
                for (Serializable key : existingState.getState().keySet()) {
                    if (complementHashingRouting.route(key).route != RoutingTable.REMOTE) {
                        state.setValueByKey(key, existingState.getValueByKey(key));
                    }
                }
            } else {
                LOG.info("It's a stateless operator!");
            }

            LOG.info("State in ElasticTaskMigrationMessage: " + state.toString());

//            if(!state.getState().containsKey("payload")) {
//                final int stateSize = 1024;
//                Slave.getInstance().logOnMaster(String.format("%d bytes have been added to the state!", stateSize));
//                state.getState().put("payload", new byte[stateSize]);
//            }

//            sendMessageToMaster((System.currentTimeMillis() - start) + "ms to prepare the state to migrate!");
            LOG.info("State for migration is ready!");
            return new ElasticTaskMigrationMessage(remoteElasticExecutor, _port, state);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }


    }

    public ElasticTaskMigrationConfirmMessage handleGuestElasticTasks(ElasticTaskMigrationMessage message) {
        try {
            LOG.info("ElasticTaskMigrationMessage: " + message.getString());
            LOG.info("#. of routes" + message._elasticTask.get_routingTable().getRoutes().size());

            if (!_originalTaskIdToRemoteTaskExecutor.containsKey(message._elasticTask.get_id())) {
                //This is the first RemoteTasks assigned to this host.
                LOG.info("creating new remote task executor!");

                communicator.establishConnectionToOriginalTaskHolder(message._elasticTask.get_id(), message._ip, message._port);

                Slave.getInstance().logOnMaster("A new Remote Task Executor is created!##");

                ElasticRemoteTaskExecutor remoteTaskExecutor = new ElasticRemoteTaskExecutor(message._elasticTask,
                        communicator._sendingQueue, message._elasticTask.get_bolt());

                LOG.info("ElasticRemoteTaskExecutor is created!");

                _originalTaskIdToRemoteTaskExecutor.put(message._elasticTask.get_id(), remoteTaskExecutor);

                _taskIdToRouteToSendingWaitingSemaphore.put(message._elasticTask.get_id(),
                        new ConcurrentHashMap<Integer, Semaphore>());

                LOG.info("ElasticRemoteTaskExecutor is added to the map!");
                remoteTaskExecutor.prepare(message.state);
                LOG.info("ElasticRemoteTaskExecutor is prepared!");
                LOG.info("Remote Task Executor is launched");
            } else {
                //There is already a RemoteTasks for that tasks on this host, so we just need to update the routing
                // table and create processing thread accordingly.
                LOG.info("integrate new subtask into existing remote task executor!");

                ElasticRemoteTaskExecutor remoteTaskExecutor = _originalTaskIdToRemoteTaskExecutor
                        .get(message._elasticTask.get_id());
                remoteTaskExecutor._elasticExecutor.get_bolt().getState().update(message.state);
                LOG.info("New state is added!");
                remoteTaskExecutor.mergeRoutingTableAndCreateCreateWorkerThreads(message._elasticTask
                        .get_routingTable());
            }

            return new ElasticTaskMigrationConfirmMessage(
                    message._elasticTask.get_id(),
                    _slaveActor.getIp(), _port, message._elasticTask.get_routingTable().getRoutes());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private void handleRemoteState(RemoteState remoteState) {
        if (_bolts.containsKey(remoteState._taskId)) {
            if (getState(remoteState._taskId) == null)
                sendMessageToMaster("getState(remoteState._taskId) is null!");
            if (remoteState._state == null) {
                sendMessageToMaster("remoteState._state is null!");
            }
            getState(remoteState._taskId).update(remoteState._state);
            System.out.println("State (" + remoteState._state.size() + " elements) has been updated!");
            System.out.println("[2] Remote State: " + remoteState._state);
            if (remoteState.finalized) {
                System.out.println("Its finalized!");
                for (int route : remoteState._routes) {
                    if ( _taskIdRouteToStateWaitingSemaphore.containsKey(remoteState._taskId + "." + route)) {
                        _taskIdRouteToStateWaitingSemaphore.get(remoteState._taskId + "." + route).release();
                        LOG.info("State Semaphore for " + remoteState._taskId + "." + route + " has been released");
                    }
                }
            } else {
                LOG.info("It's not finalized!");
            }

        } else if (_originalTaskIdToRemoteTaskExecutor.containsKey(remoteState._taskId)) {
            getState(remoteState._taskId).update(remoteState._state);
            LOG.info("State (" + remoteState._state.size() + " elements) has been updated!");
            System.out.println("[3] Remote State: " + remoteState._state);
        }
    }

    private void makeSureTargetRouteNoPendingTuples(int taskId, int routeId) {
        try {
            if (_bolts.containsKey(taskId)) {
                if (_bolts.get(taskId).get_elasticExecutor().get_routingTable().getRoutes().contains(routeId)) {
                    System.out.println(String.format("Waiting for pending tuples of %d.%d to be cleaned!", taskId,
                            routeId));
                    _bolts.get(taskId).get_elasticExecutor().makesSureNoPendingTuples(routeId);
                    System.out.println(String.format("Waiting tuples of %d.%d are cleaned!", taskId, routeId));
                } else {
                    _taskIdRouteToCleanPendingTupleSemaphore.put(taskId + "." + routeId, new Semaphore(0));
                    communicator._sendingQueue.put(new CleanPendingTupleRequestToken(taskId, routeId));
                    System.out.println(String.format("Waiting for pending tuples of %d.%d to be cleaned! [Remote]",
                            taskId, routeId));
                    _taskIdRouteToCleanPendingTupleSemaphore.get(taskId + "." + routeId).acquire();
                    _taskIdRouteToCleanPendingTupleSemaphore.remove(taskId + "." + routeId);
                    System.out.println(String.format("Waiting tuples of %d.%d are cleaned! [Remote]", taskId, routeId));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            sendMessageToMaster(e.getMessage());
        }
    }

    private void handlePendingTupleCleanedMessage(PendingTupleCleanedMessage message) {
        if (!_taskIdRouteToCleanPendingTupleSemaphore.containsKey(message.taskId + "." + message.routeId)) {
            System.out.println("PendingTupleCleanedMessage is not correct!");
        }
        _taskIdRouteToCleanPendingTupleSemaphore.get(message.taskId + "." + message.routeId).release();
        System.out.println("PendingTupleCleanedMessage is received for " + message.taskId + "." + message.routeId);
    }

    private void handleStateFlushToken(StateFlushToken token) {

        KeyValueState partialState = getState(token._taskId).getValidState(token._filter);
        RemoteState remoteState = new RemoteState(token._taskId, partialState.getState(), token._targetRoute);

//        final int stateSize = 1024;
//        Slave.getInstance().logOnMaster(String.format("%d bytes have been added to the state!", stateSize));
//        remoteState._state.put("payload", new byte[stateSize]);

        remoteState.markAsFinalized();
        if (communicator._remoteExecutorIdToPrioritizedSender.containsKey(token._taskId)) {
            final byte[] bytes = SerializationUtils.serialize(remoteState);
            WorkerMetrics.getInstance().recordStateMigration(bytes.length);
            communicator._remoteExecutorIdToPrioritizedSender.get(token._taskId).send(token._taskId, bytes);
            System.out.print("Remote state is send back to the original elastic holder!");
            System.out.println("Final State: " + remoteState._state);
        } else {

            System.out.print("Remote state does not need to be sent, as the remote state is already in the original " +
                    "holder!");
        }
    }

    private KeyValueState getState(int taskId) {
        KeyValueState ret = null;
        if (_bolts.containsKey(taskId)) {
            ret = _bolts.get(taskId).get_elasticExecutor().get_bolt().getState();
            if (ret == null)
                sendMessageToMaster("_bolts.get(executorId).get_elasticExecutor().get_bolt().getState() is null!");
        } else if (_originalTaskIdToRemoteTaskExecutor.containsKey(taskId)) {
            ret = _originalTaskIdToRemoteTaskExecutor.get(taskId)._bolt.getState();
            if (ret == null) {
                sendMessageToMaster("_originalTaskIdToRemoteTaskExecutor.get(executorId)._bolt.getState() is null!");
            }
        } else {
            sendMessageToMaster("State is not found for Task " + taskId);
        }
        return ret;
    }

    public KryoTupleSerializer getTupleSerializer() {
        return communicator.tupleSerializer;
    }

    private TwoTireRouting getTwoTireRoutingTableFromOriginalBolt(int taskid) {
        if (_bolts.containsKey(taskid)) {

            RoutingTable routingTable = _bolts.get(taskid).get_elasticExecutor().get_routingTable();
            if (routingTable instanceof TwoTireRouting) {
                return (TwoTireRouting) routingTable;
            } else if ((routingTable instanceof PartialHashingRouting) && (((PartialHashingRouting) routingTable)
                    .getOriginalRoutingTable() instanceof TwoTireRouting)) {
                return (TwoTireRouting) ((PartialHashingRouting) routingTable).getOriginalRoutingTable();
            }


        }
        return null;
    }

    private TwoTireRouting getTwoTireRoutingTableFromRemoteBolt(int taskid) {
        if (_originalTaskIdToRemoteTaskExecutor.containsKey(taskid)) {

            RoutingTable routingTable = _originalTaskIdToRemoteTaskExecutor.get(taskid)._elasticExecutor
                    .get_routingTable();
            if (routingTable instanceof TwoTireRouting) {
                return (TwoTireRouting) routingTable;
            } else if ((routingTable instanceof PartialHashingRouting) && (((PartialHashingRouting) routingTable)
                    .getOriginalRoutingTable() instanceof TwoTireRouting)) {
                return (TwoTireRouting) ((PartialHashingRouting) routingTable).getOriginalRoutingTable();
            }
        }
        return null;
    }


    private void handleShardToRouteReassignment(ShardToRouteReassignment reassignment) {
        if (_bolts.containsKey(reassignment.taskid)) {
            TwoTireRouting twoTireRouting = getTwoTireRoutingTableFromOriginalBolt(reassignment.taskid);
            if (twoTireRouting == null)
                throw new RuntimeException("Balanced Hash Routing is null!");
            for (int bucket : reassignment.reassignment.keySet()) {
                twoTireRouting.reassignBucketToRoute(bucket, reassignment.reassignment.get(bucket));
                System.out.println(bucket + " is reassigned to " + reassignment.reassignment.get(bucket) + " in the " +
                        "original elastic task");
            }
        }
        if (_originalTaskIdToRemoteTaskExecutor.containsKey(reassignment.taskid)) {
            TwoTireRouting twoTireRouting = getTwoTireRoutingTableFromRemoteBolt(reassignment.taskid);
            for (int bucket : reassignment.reassignment.keySet()) {
                twoTireRouting.reassignBucketToRoute(bucket, reassignment.reassignment.get(bucket));
                System.out.println(bucket + " is reassigned to " + reassignment.reassignment.get(bucket) + " in the " +
                        "remote elastic task");
            }
        }
    }

    public void createRouting(int taskid, int numberOfRouting, String type) throws TaskNotExistingException,
            RoutingTypeNotSupportedException {
        if (!_bolts.containsKey(taskid)) {
            throw new TaskNotExistingException(taskid);
        }

        if (type.equals("balanced_hash")) {
            createBalancedHashRouting(taskid, numberOfRouting);
        } else {

            if (!type.equals("hash"))
                throw new RoutingTypeNotSupportedException("Only support hash routing now!");
            _bolts.get(taskid).get_elasticExecutor().setHashRouting(numberOfRouting);
        }
        _slaveActor.sendMessageToMaster("New RoutingTable has been created!");
        System.out.println("RoutingTable has been created");
    }

    public void createBalancedHashRouting(int taskid, int numberOfRouting) throws TaskNotExistingException {
        if (!_bolts.containsKey(taskid)) {
            throw new TaskNotExistingException(taskid);
        }

        Long[] buckets = new Long[Config.NumberOfShard];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = 1L;
        }

        FirstFitDoubleDecreasing firstFitDoubleDecreasing = new FirstFitDoubleDecreasing(Arrays.asList(buckets),
                numberOfRouting);

        final int result = firstFitDoubleDecreasing.getResult();
        if (result == numberOfRouting) {
            _bolts.get(taskid).get_elasticExecutor().setHashBalancedRouting(numberOfRouting, firstFitDoubleDecreasing
                    .getBucketToPartitionMap());
        } else {
            _slaveActor.sendMessageToMaster("Failed to partition the buckets!");
        }
    }

    public void withdrawRemoteElasticTasks(int taskid, int route) throws TaskNotExistingException,
            RoutingTypeNotSupportedException, InvalidRouteException, HostNotExistException {
        SubtaskWithdrawTimer.getInstance().start();
        if (!_bolts.containsKey(taskid)) {
            throw new TaskNotExistingException(taskid);
        }
        RoutingTable routingTable = _bolts.get(taskid).get_elasticExecutor().get_routingTable();
        if (!(routingTable instanceof PartialHashingRouting)) {
            throw new RoutingTypeNotSupportedException("Can only withdraw remote tasks for PartialHashingRouting!");
        }
        PartialHashingRouting partialHashingRouting = (PartialHashingRouting) routingTable;
        if (!partialHashingRouting.getOriginalRoutes().contains(route)) {
            throw new InvalidRouteException("Route " + route + " is not valid");
        }
        if (partialHashingRouting.getRoutes().contains(route)) {
            throw new InvalidRouteException("Route " + route + " is not in exception list");
        }

        _bolts.get(taskid).get_elasticExecutor().addValidRoute(route);
        System.out.println("Route " + route + " has been added into the routing table!");
        sendFinalTuple(taskid, route);

        SubtaskWithdrawTimer.getInstance().prepared();

        System.out.println("RemoteSubtaskTerminationToken has been sent!");


        _taskIdRouteToStateWaitingSemaphore.put(taskid + "." + route, new Semaphore(0));
        try {
            System.out.println("Waiting for the remote state");
            _taskIdRouteToStateWaitingSemaphore.get(taskid + "." + route).acquire();

            System.out.println("Remote state arrives!");

            System.out.println("launch the thread for " + taskid + "." + route + ".");
            _bolts.get(taskid).get_elasticExecutor().launchElasticTasksForGivenRoute(route);
            System.out.println("Remote " + taskid + "." + route + "has been withdrawn!");
            _slaveActor.sendMessageToMaster("Remote " + taskid + "." + route + "has been withdrawn!");
            SubtaskWithdrawTimer.getInstance().terminated();
//            _slaveActor.sendMessageToMaster(SubtaskWithdrawTimer.getInstance().toString());
            String originalHost = routeIdToRemoteHost.get(new RouteId(taskid, route));
            routeIdToRemoteHost.remove(new RouteId(taskid, route));
//            _taskidRouteToConnection.remove(taskid + "." + route);
            communicator.disconnectToRoute(taskid + "." + route);
            _slaveActor.sendMessageToNode(originalHost, new TestAliveMessage("Alive after withdraw!"));

        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void sendFinalTuple(int taskid, int route) {

        RemoteSubtaskTerminationToken remoteSubtaskTerminationToken = new RemoteSubtaskTerminationToken(taskid, route);
        try {
            communicator._sendingQueue.put(remoteSubtaskTerminationToken);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * First terminate the query thread and clear the associated resources until all the local tuples for the route
     * has been processed
     * Then get the state for the route and send the state back to the original ElasticExecutor.
     * Finally, remove the route balls the routingTable.
     *
     * @param taskid the taskid
     * @param route  the route to remove
     */
    private void terminateRemoteRoute(int taskid, int route) {
        //terminate the thread and cleanup the resources.
        ElasticRemoteTaskExecutor remoteTaskExecutor = _originalTaskIdToRemoteTaskExecutor.get(taskid);
        remoteTaskExecutor._elasticExecutor.terminateGivenQuery(route);

        if (remoteTaskExecutor._elasticExecutor.get_routingTable().getRoutes().size() == 0) {
            System.out.println("Removing the elastic task...");
            removeEmptyRemoteTaskExecutor(taskid);
            System.out.println("Removed the elastic task...");
        }

        try {
            //get state and send back
            RemoteState state = remoteTaskExecutor.getStateForRoutes(route);
            state.markAsFinalized();
            communicator._sendingQueue.put(state);
            System.out.println("Final State for " + taskid + "." + route + " has been sent back");
            System.out.println("Final State: " + state._state);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        ((PartialHashingRouting) remoteTaskExecutor._elasticExecutor.get_routingTable()).addExceptionRoute(route);
        System.out.println("Route " + route + " has been removed from the routing table");
    }

    private void removeEmptyRemoteTaskExecutor(int taskid) {
        ElasticRemoteTaskExecutor remoteTaskExecutor = _originalTaskIdToRemoteTaskExecutor.get(taskid);
        remoteTaskExecutor.close();
        _originalTaskIdToRemoteTaskExecutor.remove(taskid);

        System.out.println("RemoteTaskExecutor " + taskid + " is interrupted!");
    }

    public void setworkerTopologyContext(WorkerTopologyContext context) {
        _workerTopologyContext = context;
        communicator.tupleDeserializer = new KryoTupleDeserializer(stormConf, context);
        communicator.tupleSerializer = new KryoTupleSerializer(stormConf, context);

        communicator.remoteTupleExecuteResultDeserializer = new RemoteTupleExecuteResultDeserializer(stormConf, context,
                communicator.tupleDeserializer);
        communicator.remoteTupleExecuteResultSerializer = new RemoteTupleExecuteResultSerializer(stormConf, context,
                communicator.tupleSerializer);


    }

    public WorkerTopologyContext getWorkerTopologyContext() {
        return _workerTopologyContext;
    }

    public double getThroughput(int taskid) {
        if (!_bolts.containsKey(taskid))
            return -1;
        return _bolts.get(taskid).getMetrics().getRecentThroughput(5000);
    }

    public Histograms getDistribution(int taskid) throws TaskNotExistingException {
        if (!_bolts.containsKey(taskid)) {
            throw new TaskNotExistingException("Task " + taskid + " does not exist!");
        } else {
            return _bolts.get(taskid).get_elasticExecutor().get_routingTable().getRoutingDistribution();
        }
    }

    public RoutingTable getOriginalRoutingTable(int taskid) throws TaskNotExistingException {
        if (!_bolts.containsKey(taskid)) {
            throw new TaskNotExistingException("task " + taskid + "does not exist!");
        } else {
            return _bolts.get(taskid).get_elasticExecutor().get_routingTable();
        }
    }

    public RoutingTable getRoutingTable(int taskid) throws TaskNotExistingException {
        if (!_bolts.containsKey(taskid)) {
            throw new TaskNotExistingException("task " + taskid + "does not exist!");
        } else {
            return _bolts.get(taskid).getCompleteRoutingTable();
        }
    }

    /**
     * The protocol of a shard reassignment has four phases.
     * (1) pause sending tuples to the source task and the destination task.
     * (2) make sure pending tuples sent to the source tasks are complete processed.
     * (3) migrate the state (omitted, if shard is reassigned between two tasks on the same host.).
     * (4) resume sending tuples to the source tasks and the destination task.
     *
     * @param taskid the id of the executor within which the shard is reassigned
     * @param shardId the id of the shard to reassign
     * @param orignalRoute the route that the shard currently belongs to
     * @param targetRoute the route that the shard will be reassigned
     * @throws TaskNotExistingException
     * @throws RoutingTypeNotSupportedException
     * @throws InvalidRouteException
     * @throws BucketNotExistingException
     */
    public void performShardReassignment(int taskid, int shardId, int orignalRoute, int targetRoute) throws
            TaskNotExistingException, RoutingTypeNotSupportedException, InvalidRouteException,
            BucketNotExistingException {
        SmartTimer.getInstance().start("ShardReassignment", "prepare");


        System.out.println("===Start Shard Reassignment " + shardId + " " + taskid + "." + orignalRoute + "---->" +
                taskid + "." + targetRoute);

        if (!_bolts.containsKey(taskid)) {
            throw new TaskNotExistingException("Task " + taskid + " does not exist balls the ElasticHolder!");
        }
        if (getTwoTireRoutingTableFromOriginalBolt(taskid) == null) {
            throw new RoutingTypeNotSupportedException("ReassignHashBucketToRoute only applies on TwoTireRouting or " +
                    "PartialHashRouting with a internal TwoTireRouting");
        }

        TwoTireRouting twoTireRouting;
        if (_bolts.get(taskid).get_elasticExecutor().get_routingTable() instanceof TwoTireRouting) {
            twoTireRouting = (TwoTireRouting) _bolts.get(taskid).get_elasticExecutor().get_routingTable();
        } else {
            twoTireRouting = (TwoTireRouting) ((PartialHashingRouting) _bolts.get(taskid).get_elasticExecutor()
                    .get_routingTable()).getOriginalRoutingTable();
        }

        if (!(twoTireRouting.getRoutes().contains(orignalRoute))) {
            throw new InvalidRouteException("Original Route " + orignalRoute + " does not exist!");
        }

        if (!(twoTireRouting.getRoutes().contains(targetRoute))) {
            throw new InvalidRouteException("Target Route " + targetRoute + " does not exist!");
        }


        if (!twoTireRouting.getBucketSet().contains(shardId)) {
            throw new BucketNotExistingException("Bucket " + shardId + " does not exist balls the balanced hash " +
                    "routing table!");
        }

        ShardToRouteReassignment reassignment = new ShardToRouteReassignment(taskid, shardId, targetRoute);

        String targetHost, originalHost;
        if (routeIdToRemoteHost.containsKey(new RouteId(taskid, orignalRoute)))
            originalHost = routeIdToRemoteHost.get(new RouteId(taskid, orignalRoute));
        else
            originalHost = "local";

        if (routeIdToRemoteHost.containsKey(new RouteId(taskid, targetRoute)))
            targetHost = routeIdToRemoteHost.get(new RouteId(taskid, targetRoute));
        else
            targetHost = "local";

        System.out.println("From " + originalHost + " to " + targetHost);
        SmartTimer.getInstance().stop("ShardReassignment", "prepare");

        SmartTimer.getInstance().start("ShardReassignment", "rerouting");

        // 1. Pause sending RemoteTuples to the source and target task.
        System.out.println("Begin to pause sending to the two routes...");
        pauseSendingToTargetTask(taskid, targetRoute);
        pauseSendingToTargetTask(taskid, orignalRoute);


        SmartTimer.getInstance().stop("ShardReassignment", "rerouting");



        // 2. before state migration, we should make sure there is no pending tuples for consistency!
        SmartTimer.getInstance().start("ShardReassignment", "state migration 1");
        makeSureTargetRouteNoPendingTuples(taskid, orignalRoute);
        SmartTimer.getInstance().stop("ShardReassignment", "state migration 1");


        // Update the routing table on original ElasticTaskHolder
        handleShardToRouteReassignment(reassignment);

        // Update the routing table on the target subtask
        if (communicator.connectionExisting(taskid + "." + targetRoute)) {
            communicator.getConnectionOfRoute(taskid + "." + targetRoute).send(taskid,
                    SerializationUtils.serialize(reassignment));
        }

        // Update the routing table on the source
        SmartTimer.getInstance().start("ShardReassignment", "state migration 2");
        if (communicator.connectionExisting(taskid + "." + orignalRoute)) {
//            sendMessageToMaster("ShardToRouteReassignment is sent to the original Host ");
            communicator.getConnectionOfRoute(taskid + "." + orignalRoute).send(taskid, SerializationUtils.serialize
                    (reassignment));
        }


        // 3. handle state for that shard, if necessary
        SmartTimer.getInstance().stop("ShardReassignment", "state migration 2");
        if (!targetHost.equals(originalHost)) {
            // shard is reassigned between two tasks on different nodes.

            // 3.1. fetch remote state
            SmartTimer.getInstance().start("ShardReassignment", "state migration 3");
            HashBucketFilter filter = new HashBucketFilter(twoTireRouting.getNumberOfBuckets(), shardId);
            if (!originalHost.equals("local")) {
                StateFlushToken stateFlushToken = new StateFlushToken(taskid, orignalRoute, filter);
                final Semaphore semaphore = new Semaphore(0);
                _taskIdRouteToStateWaitingSemaphore.put(taskid + "." + orignalRoute, semaphore);
                communicator.getConnectionOfRoute(taskid + "." + orignalRoute).send(taskid, SerializationUtils
                        .serialize(stateFlushToken));
                System.out.println("State Flush Token has been sent to " + originalHost);
                try {
                    System.out.println("Waiting for remote state!");
                    semaphore.acquire();
                    System.out.println("Remote state arrived!");
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            SmartTimer.getInstance().stop("ShardReassignment", "state migration 3");

            // 3.2. forward the local up-to-date state to the remote holder
            SmartTimer.getInstance().start("ShardReassignment", "state migration 4");
            if (!targetHost.equals("local")) {
                KeyValueState partialState = getState(taskid).getValidState(filter);

                if (!partialState.getState().containsKey("payload")) {
//                    final int stateSize = 1024 * 1024 * 32;
//                    Slave.getInstance().logOnMaster(String.format("%d bytes have been added to the state!",
// stateSize));
//                    partialState.getState().put("payload", new byte[stateSize]);
                }

                RemoteState remoteState = new RemoteState(taskid, partialState.getState(), targetRoute);
                final byte[] bytes = SerializationUtils.serialize(remoteState);
                communicator.getConnectionOfRoute(taskid + "." + targetRoute).send(taskid, bytes);
//                Slave.getInstance().reportStateMigrationToMaster(bytes.length);
                WorkerMetrics.getInstance().recordStateMigration(bytes.length);
//                sendMessageToMaster("State has been sent to " + targetHost);
            }
            SmartTimer.getInstance().stop("ShardReassignment", "state migration 4");

        }

        // 4. resume sending RemoteTuples to the target subtask
        SmartTimer.getInstance().start("ShardReassignment", "resume");
        System.out.println("Begin to resume!");
        resumeSendingToTargetTask(taskid, targetRoute);
        resumeSendingToTargetTask(taskid, orignalRoute);
        System.out.println("Resumed!");
        SmartTimer.getInstance().stop("ShardReassignment", "resume");
        System.out.print(SmartTimer.getInstance().getTimerString("ShardReassignment"));
        System.out.println("===End Shard Reassignment " + shardId + " " + taskid + "." + orignalRoute + "---->" +
                taskid + "." + targetRoute);

    }

    public boolean waitIfStreamToTargetSubtaskIsPaused(int targetTask, int route) {
//        System.out.println("waitIfStreamToTargetSubtaskIsPaused!");
        if (_taskIdToRouteToSendingWaitingSemaphore.get(targetTask).containsKey(route)) {
            final String key = targetTask + "." + route;
            try {
                System.out.println("Sending stream to " + targetTask + "." + route + " is paused. Waiting for " +
                        "resumption!");
//                _taskIdRouteToSendingWaitingSemaphore.get(key).acquire();
                Semaphore semaphore = _taskIdToRouteToSendingWaitingSemaphore.get(targetTask).get(route);
                semaphore.acquire();
                synchronized (_taskIdToRouteToSendingWaitingSemaphore.get(targetTask)) {
                    if (_taskIdToRouteToSendingWaitingSemaphore.get(targetTask).containsKey(route) &&
                            _taskIdToRouteToSendingWaitingSemaphore.get(targetTask).get(route).equals(semaphore)) {
                        _taskIdToRouteToSendingWaitingSemaphore.get(targetTask).remove(route);
                        System.out.println("Semaphore for " + key + " is removed.");
                    } else {
                        System.out.println("Semaphore for " + key + " is not removed, because the semaphore is not the " +

                                "original one!");
                    }
                }
                System.out.println(key + " is resumed!!!!!");
                return true;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    private void pauseSendingToTargetTask(int targetTask, int route) {
        synchronized (_taskIdToRouteToSendingWaitingSemaphore.get(targetTask)) {
            _taskIdToRouteToSendingWaitingSemaphore.get(targetTask).put(route, new Semaphore(0));
        }
        final String key = targetTask + "." + route;
        System.out.println("Sending to " + key + " is paused!");
    }

    private void resumeSendingToTargetTask(int targetTask, int route) {
        String key = targetTask + "." + route;
        synchronized (_taskIdToRouteToSendingWaitingSemaphore.get(targetTask)) {
            if (!_taskIdToRouteToSendingWaitingSemaphore.get(targetTask).containsKey(route)) {
                System.out.println("cannot resume " + key + " because the semaphore does not exist!");
                return;
            }
            _taskIdToRouteToSendingWaitingSemaphore.get(targetTask).get(route).release();
        }
        System.out.println("Sending to " + key + "is resumed!");
    }

    public Histograms getBucketDistributionForBalancedRoutingTable(int taskId) {
        if (!_bolts.containsKey(taskId)) {
            System.out.println("Task " + taskId + "does not exist!");
            return null;
        }
        RoutingTable routingTable = _bolts.get(taskId).get_elasticExecutor().get_routingTable();
        return ((TwoTireRouting) RoutingTableUtils.getBalancecHashRouting(routingTable)).getBucketsDistribution();
    }

    public void migrateTask(String targetHost, int taskId, int routeId) throws InvalidRouteException,
            RoutingTypeNotSupportedException, HostNotExistException, TaskNotExistingException {
        String workerLogicalName = _slaveActor.getLogicalName();
        String workerName = _slaveActor.getName();

        String routeName = taskId + "." + routeId;

        if (!_bolts.containsKey(taskId))
            throw new TaskNotExistingException(String.format("Task %d does not exist or is not running on %s",
                    taskId, workerName));

        if (!RoutingTableUtils.getOriginalRoutes(_bolts.get(taskId).get_elasticExecutor().get_routingTable())
                .contains(routeId))
            throw new InvalidRouteException(routeId);

        if (targetHost.equals(routeIdToRemoteHost.get(new RouteId(taskId, routeId))))
            throw new RuntimeException("Cannot migrate " + taskId + "." + routeId + ", because the task is already " +
                    "running on the host ");

        if (_bolts.containsKey(taskId) && _bolts.get(taskId).get_elasticExecutor().get_routingTable().getRoutes()
                .contains(routeId)) {
            if (!workerLogicalName.equals(targetHost)) {
                _slaveActor.sendMessageToMaster("========== Migration from local to remote ========= " + routeName);
                System.out.println("========== Migration from local to remote ========= " + routeName);
                migrateTaskToARemoteHost(targetHost, taskId, routeId);
                _slaveActor.sendMessageToMaster("====================== E N D ====================== " + routeName);
                System.out.println("====================== E N D ====================== " + routeName);

            } else
                throw new RuntimeException("Cannot migrate " + taskId + "." + routeId + " on " + targetHost + ", " +
                        "because the subtask is already running on the host!");
        } else {
            if (workerName.equals(targetHost)) {
                _slaveActor.sendMessageToMaster("========== Migration from remote to local! ========== " + routeName);
                withdrawRemoteElasticTasks(taskId, routeId);
                _slaveActor.sendMessageToMaster("====================== E N D ====================== " + routeName);

            } else {
                _slaveActor.sendMessageToMaster("========== Migration from remote to remote! ==========  " + routeName);
                System.out.println("===Withdraw Start===");
                withdrawRemoteElasticTasks(taskId, routeId);
                System.out.println("===Withdraw End===");
                _slaveActor.sendMessageToMaster("========== Remote->local is done ==========  " + routeName);
                _slaveActor.sendMessageToMaster("========== local->remote is starting ==========  " + routeName);
                System.out.println("===Migrate Start===");
                migrateTaskToARemoteHost(targetHost, taskId, routeId);
                System.out.println("===Migrate End===");
                migrateTaskToARemoteHost(targetHost, taskId, routeId);
                _slaveActor.sendMessageToMaster("====================== E N D ====================== " + routeName);
            }
        }
    }

    public void migrateTaskToARemoteHost(String targetHost, int taskId, int routeId) throws InvalidRouteException,
            RoutingTypeNotSupportedException, HostNotExistException {
        SmartTimer.getInstance().start("SubtaskMigrate", "rerouting 1");
        System.out.println("Begin to construct the RemoteElasticTasks!");
        ElasticTaskMigrationMessage migrationMessage = ElasticTaskHolder.instance().generateRemoteElasticTasks
                (taskId, routeId);
        System.out.println("RemoteElasticTasks is constructed!");
        SmartTimer.getInstance().stop("SubtaskMigrate", "state construction");
        SmartTimer.getInstance().start("SubtaskMigrate", "state migration");
        routeIdToRemoteHost.put(new RouteId(taskId, routeId), targetHost);
        System.out.println("Sending the ElasticTaskMigrationMessage to " + targetHost);
        final RemoteState remoteState = new RemoteState(-1, migrationMessage.state, -1);
        System.out.println("[1] Remote State: " + remoteState._state);
        WorkerMetrics.getInstance().recordStateMigration(SerializationUtils.serialize(remoteState).length);
        ElasticTaskMigrationConfirmMessage confirmMessage = (ElasticTaskMigrationConfirmMessage) _slaveActor
                .sendMessageToNodeAndWaitForResponse(targetHost, migrationMessage);
        SmartTimer.getInstance().stop("SubtaskMigrate", "state migration");
        SmartTimer.getInstance().start("SubtaskMigrate", "reconnect");
        System.out.println("Received ElasticTaskMigrationConfirmMessage!");
        if (confirmMessage != null)
            handleElasticTaskMigrationConfirmMessage(confirmMessage);
        else {
            sendMessageToMaster("Waiting for confirm message time out!");
        }
        SmartTimer.getInstance().stop("SubtaskMigrate", "reconnect");
    }

    private void handleElasticTaskMigrationConfirmMessage(ElasticTaskMigrationConfirmMessage confirmMessage) {

        String ip = confirmMessage._ip;
        int port = confirmMessage._port;
        int taskId = confirmMessage._taskId;

        System.out.print("Received ElasticTaskMigrationConfirmMessage #. routes: " + confirmMessage._routes.size());
        for (int i : confirmMessage._routes) {
            communicator.establishConnectionToRemoteTaskHolder(taskId, i, ip, port);
        }
        sendMessageToMaster("Task Migration completes!");
    }

    public String handleSubtaskLevelLoadBalancingCommand(int taskId) {
        try {
            if (!_bolts.containsKey(taskId)) {
                throw new TaskNotExistingException(taskId);
            }

            SmartTimer.getInstance().start("Subtask Level Load Balancing", "Prepare");

            RoutingTable routingTable = _bolts.get(taskId).get_elasticExecutor().get_routingTable();

            TwoTireRouting twoTireRouting = RoutingTableUtils.getBalancecHashRouting(routingTable);

            if (twoTireRouting == null) {
                throw new RoutingTypeNotSupportedException("Only support balanced hash routing for scaling out now!");
            }

            int numberOfSubtasks = twoTireRouting.getNumberOfRoutes();
            // collect the necessary metrics first.
            Histograms histograms = twoTireRouting.getBucketsDistribution();
            Map<Integer, Integer> shardToRoutingMapping = twoTireRouting.getBucketToRouteMapping();

            ArrayList<SubTaskWorkload> subTaskWorkloads = new ArrayList<>();
            for (int i = 0; i < numberOfSubtasks; i++) {
                subTaskWorkloads.add(new SubTaskWorkload(i));
            }
            for (int shardId : histograms.histograms.keySet()) {
                subTaskWorkloads.get(shardToRoutingMapping.get(shardId)).increaseOrDecraeseWorkload(histograms
                        .histograms.get(shardId));
            }

            Map<Integer, Set<ShardWorkload>> subtaskToShards = new HashMap<>();
            for (int i = 0; i < numberOfSubtasks; i++) {
                subtaskToShards.put(i, new HashSet<ShardWorkload>());
            }


            for (int shardId : shardToRoutingMapping.keySet()) {
                int subtask = shardToRoutingMapping.get(shardId);
                final Set<ShardWorkload> shardWorkloads = subtaskToShards.get(subtask);
                final long workload = histograms.histograms.get(shardId);
                shardWorkloads.add(new ShardWorkload(shardId, workload));
            }

            SmartTimer.getInstance().stop("Subtask Level Load Balancing", "Prepare");
            SmartTimer.getInstance().start("Subtask Level Load Balancing", "Algorithm");
            Comparator<ShardWorkload> shardComparator = ShardWorkload.createReverseComparator();
            Comparator<SubTaskWorkload> subTaskComparator = SubTaskWorkload.createReverseComparator();
            ShardReassignmentPlan plan = new ShardReassignmentPlan();

            boolean moved = true;

            while (moved) {
                moved = false;
                Collections.sort(subTaskWorkloads, subTaskComparator);
                for (int i = subTaskWorkloads.size() - 1; i > 0; i--) {
                    SubTaskWorkload subtaskWorloadToMoveIn = subTaskWorkloads.get(i);
                    SubTaskWorkload subtaskWorloadToMoveFrom = subTaskWorkloads.get(0);
                    Set<ShardWorkload> shardWorkloadsInTheSubtaskWithLargestWorkload = subtaskToShards.get
                            (subtaskWorloadToMoveFrom.subtaskId);
                    List<ShardWorkload> sortedShardWorkloadsInTheSubtaskWithLargestWorkload = new ArrayList<>
                            (shardWorkloadsInTheSubtaskWithLargestWorkload);
                    Collections.sort(sortedShardWorkloadsInTheSubtaskWithLargestWorkload, shardComparator);
                    boolean localMoved = false;
                    for (ShardWorkload shardWorkload : sortedShardWorkloadsInTheSubtaskWithLargestWorkload) {
                        if (shardWorkload.workload + subtaskWorloadToMoveIn.workload < subtaskWorloadToMoveFrom
                                .workload) {
                            plan.addReassignment(taskId, shardWorkload.shardId, subtaskWorloadToMoveFrom.subtaskId,
                                    subtaskWorloadToMoveIn.subtaskId);
                            localMoved = true;
                            moved = true;
                            shardWorkloadsInTheSubtaskWithLargestWorkload.remove(shardWorkload);
                            subtaskWorloadToMoveFrom.increaseOrDecraeseWorkload(-shardWorkload.workload);
                            subtaskToShards.get(subtaskWorloadToMoveIn.subtaskId).add(shardWorkload);
                            subtaskWorloadToMoveIn.increaseOrDecraeseWorkload(shardWorkload.workload);
                        }
                    }
                    if (localMoved)
                        break;

                }
            }
            SmartTimer.getInstance().stop("Subtask Level Load Balancing", "Algorithm");
            sendMessageToMaster(plan.toString());
            SmartTimer.getInstance().start("Subtask Level Load Balancing", "Deploy");
            for (ShardReassignment reassignment : plan.getReassignmentList()) {
//                sendMessageToMaster("=============== START ===============");
                performShardReassignment(taskId, reassignment.shardId, reassignment.originalRoute, reassignment
                        .newRoute);
//                sendMessageToMaster("=============== END ===============");
            }
            SmartTimer.getInstance().stop("Subtask Level Load Balancing", "Deploy");
            sendMessageToMaster(SmartTimer.getInstance().getTimerString("Subtask Level Load Balancing"));
            sendMessageToMaster("Subtask Level Load Balancing finishes with " + plan.getReassignmentList().size() + "" +
                    " movements!");


        } catch (Exception e) {
            e.printStackTrace();
            sendMessageToMaster(e.getMessage());
        }
        return "Succeed!";
    }

    public Status handleScalingInSubtaskCommand(int taskId) {
        /**
         * Subtask with the largest index will be removed to achieve scaling in.
         * Shard already assigned to that subtask will be moved to other existing subtask in a load balance manner.
         *
         */

        try {
            System.out.println("begin to handle scaling in subtask command for TaskID" + taskId);
            SmartTimer.getInstance().start("Scaling In Subtask", "prepare");
            if (!_bolts.containsKey(taskId)) {
                throw new TaskNotExistingException(taskId);
            }
            RoutingTable routingTable = _bolts.get(taskId).get_elasticExecutor().get_routingTable();

            TwoTireRouting twoTireRouting = RoutingTableUtils.getBalancecHashRouting(routingTable);

            if (twoTireRouting == null) {
                throw new RoutingTypeNotSupportedException("Only support balanced hash routing for scaling in now!");
            }

            if (twoTireRouting.getNumberOfRoutes() == 1) {
                return Status.Error("Scaling in fails, since the current DOP is 1!");
            }

            int targetSubtaskId = twoTireRouting.getNumberOfRoutes() - 1;
            int numberOfSubtasks = twoTireRouting.getNumberOfRoutes();
            System.out.println(String.format("Scaling: %d --> %d", numberOfSubtasks, numberOfSubtasks - 1));
            // collect the necessary metrics first.
            System.out.println("begin to collect the metrics...");
            Histograms histograms = twoTireRouting.getBucketsDistribution();
            Map<Integer, Integer> shardToRoutingMapping = twoTireRouting.getBucketToRouteMapping();

            ArrayList<SubTaskWorkload> subTaskWorkloads = new ArrayList<>();
            for (int i = 0; i < numberOfSubtasks; i++) {
                subTaskWorkloads.add(new SubTaskWorkload(i));
            }
            for (int shardId : histograms.histograms.keySet()) {
                subTaskWorkloads.get(shardToRoutingMapping.get(shardId)).increaseOrDecraeseWorkload(histograms
                        .histograms.get(shardId));
            }

            Map<Integer, Set<ShardWorkload>> subtaskToShards = new HashMap<>();
            for (int i = 0; i < numberOfSubtasks; i++) {
                subtaskToShards.put(i, new HashSet<ShardWorkload>());
            }
            for (int shardId : shardToRoutingMapping.keySet()) {
                int subtask = shardToRoutingMapping.get(shardId);
                final Set<ShardWorkload> shardWorkloads = subtaskToShards.get(subtask);
                final long workload = histograms.histograms.get(shardId);
                shardWorkloads.add(new ShardWorkload(shardId, workload));
            }
            SmartTimer.getInstance().stop("Scaling In Subtask", "prepare");
            SmartTimer.getInstance().start("Scaling In Subtask", "algorithm");
            System.out.println("begin to compute the shard reassignments...");
            Set<ShardWorkload> shardsForTargetSubtask = subtaskToShards.get(targetSubtaskId);
            List<ShardWorkload> sortedShards = new ArrayList<>(shardsForTargetSubtask);
            Collections.sort(sortedShards, ShardWorkload.createReverseComparator());

            ShardReassignmentPlan plan = new ShardReassignmentPlan();

            Comparator<SubTaskWorkload> subTaskComparator = SubTaskWorkload.createReverseComparator();
            subTaskWorkloads.remove(subTaskWorkloads.size() - 1);
            for (ShardWorkload shardWorkload : sortedShards) {
                Collections.sort(subTaskWorkloads, subTaskComparator);
                SubTaskWorkload subtaskWorkloadToMoveIn = subTaskWorkloads.get(0);
                plan.addReassignment(taskId, shardWorkload.shardId, targetSubtaskId, subtaskWorkloadToMoveIn.subtaskId);
                subtaskWorkloadToMoveIn.increaseOrDecraeseWorkload(shardWorkload.workload);
            }
            SmartTimer.getInstance().stop("Scaling In Subtask", "algorithm");
            System.out.println(plan.toString());
            SmartTimer.getInstance().start("Scaling In Subtask", "deploy");
            System.out.println("begin to conduct shard reassignments...");
            sendMessageToMaster(String.format("Scaling in will conduct %d reassignments!", plan.getReassignmentList()
                    .size()));
            int count = 0;
            for (ShardReassignment reassignment : plan.getReassignmentList()) {
//                sendMessageToMaster("=============== START ===============");
                performShardReassignment(taskId, reassignment.shardId, reassignment.originalRoute, reassignment
                        .newRoute);
                count++;
                if (count % 10 == 0) {
                    System.out.println(String.format("%d reassignments have been conducted!", count));
                }
//                sendMessageToMaster("=============== END ===============");
            }
            SmartTimer.getInstance().stop("Scaling In Subtask", "deploy");

            SmartTimer.getInstance().start("Scaling In Subtask", "Termination");
            if (communicator.connectionExisting(taskId + "." + targetSubtaskId)) {
                withdrawRemoteElasticTasks(taskId, targetSubtaskId);
            }
            _bolts.get(taskId).get_elasticExecutor().terminateGivenQuery(targetSubtaskId);
            SmartTimer.getInstance().stop("Scaling In Subtask", "Termination");

            SmartTimer.getInstance().start("Scaling In Subtask", "update routing table");
            twoTireRouting.scalingIn();
            SmartTimer.getInstance().stop("Scaling In Subtask", "update routing table");


//            sendMessageToMaster(SmartTimer.getInstance().getTimerString("Scaling In Subtask"));

            sendMessageToMaster("Scaling in completes with " + plan.getReassignmentList().size() + " movements.");

            String string = "";
            for (ShardReassignment reassignment : plan.getReassignmentList()) {
                string += reassignment.shardId + " ";
            }
//            sendMessageToMaster(string);

//            sendMessageToMaster("Current DOP: " + twoTireRouting.getRoutes().size());
            System.out.println("scaling in subtask command is done, current Dop = " + twoTireRouting.getRoutes().size
                    ());

        } catch (Exception e) {
            e.printStackTrace();
            return Status.Error(e.getMessage());
        }
        return Status.OK();
    }

    public Status handleScalingOutSubtaskCommand(int taskId) {
        try {
            System.out.println("Begin to handle scaling out subtask command!");
            SmartTimer.getInstance().start("ScalingOut", "Create Empty subtask");
            if (!_bolts.containsKey(taskId)) {
                throw new TaskNotExistingException(taskId);
            }
            RoutingTable routingTable = _bolts.get(taskId).get_elasticExecutor().get_routingTable();

            TwoTireRouting twoTireRouting = RoutingTableUtils.getBalancecHashRouting(routingTable);

            if (twoTireRouting == null) {
                throw new RoutingTypeNotSupportedException("Only support balanced hash routing for scaling out now!");
            }


            // collect necessary statistics first, otherwise those data might not be available after scaling out of
            // the routing table.
            Histograms histograms = twoTireRouting.getBucketsDistribution();
            Map<Integer, Integer> shardToRoutingMapping = twoTireRouting.getBucketToRouteMapping();

            int newSubtaskId = routingTable.scalingOut();

            _bolts.get(taskId).get_elasticExecutor().createAndLaunchElasticTasksForGivenRoute(newSubtaskId);

            // so far, a new, empty subtask is create. The next step is to move some shards from existing subtasks.


            SmartTimer.getInstance().stop("ScalingOut", "Create Empty subtask");
            SmartTimer.getInstance().start("ScalingOut", "Algorithm");
            System.out.println("Begin to compute shard reassignments...");
            ArrayList<SubTaskWorkload> subTaskWorkloads = new ArrayList<>();
            for (int i = 0; i < newSubtaskId; i++) {
                subTaskWorkloads.add(new SubTaskWorkload(i));
            }
            for (int shardId : histograms.histograms.keySet()) {
                subTaskWorkloads.get(shardToRoutingMapping.get(shardId)).increaseOrDecraeseWorkload(histograms
                        .histograms.get(shardId));
            }

            Map<Integer, Set<ShardWorkload>> subtaskToShards = new HashMap<>();
            for (int i = 0; i < newSubtaskId; i++) {
                subtaskToShards.put(i, new HashSet<ShardWorkload>());
            }


            for (int shardId : shardToRoutingMapping.keySet()) {
                int subtask = shardToRoutingMapping.get(shardId);
                final Set<ShardWorkload> shardWorkloads = subtaskToShards.get(subtask);
                final long workload = histograms.histograms.get(shardId);
                shardWorkloads.add(new ShardWorkload(shardId, workload));
            }

            long targetSubtaskWorkload = 0;
            Comparator<ShardWorkload> shardComparator = ShardWorkload.createReverseComparator();
            Comparator<SubTaskWorkload> subTaskReverseComparator = SubTaskWorkload.createReverseComparator();
            ShardReassignmentPlan plan = new ShardReassignmentPlan();
            boolean moved = true;
            while (moved) {
                moved = false;
                Collections.sort(subTaskWorkloads, subTaskReverseComparator);
                for (SubTaskWorkload subTaskWorkload : subTaskWorkloads) {
                    int subtask = subTaskWorkload.subtaskId;
                    List<ShardWorkload> shardWorkloads = new ArrayList<>(subtaskToShards.get(subtask));
                    Collections.sort(shardWorkloads, shardComparator);
                    boolean localMoved = false;
                    for (ShardWorkload shardWorkload : shardWorkloads) {
                        if (targetSubtaskWorkload + shardWorkload.workload < subTaskWorkload.workload) {
                            plan.addReassignment(taskId, shardWorkload.shardId, subTaskWorkload.subtaskId,
                                    newSubtaskId);
                            subtaskToShards.get(subTaskWorkload.subtaskId).remove(new ShardWorkload(shardWorkload
                                    .shardId));
                            targetSubtaskWorkload += shardWorkload.workload;
                            subTaskWorkload.increaseOrDecraeseWorkload(-shardWorkload.workload);
                            localMoved = true;
                            moved = true;
//                            System.out.println("Move " + shardWorkload.shardId + " from " + subTaskWorkload
// .subtaskId + " to " + newSubtaskId);
                            break;
                        }
                    }
                    if (localMoved) {
                        break;
                    }

                }
            }
            SmartTimer.getInstance().stop("ScalingOut", "Algorithm");

//            for(int i = 0; i < newSubtaskId; i++) {
//                sendMessageToMaster("Subtask " + i + ": " + subTaskWorkloads.get(i).workload);
//            }
//            sendMessageToMaster("new subtask: " + targetSubtaskWorkload);

            SmartTimer.getInstance().start("ScalingOut", "Conduct");
            System.out.println("Begin to conduct shard reassignments...");


            // We do not need to do shard reassignments, as this will be done by the load balancing mechanism.
//            plan.getReassignmentList().clear();


//            sendMessageToMaster(String.format("Scaling out will conduct %d reassignments!", plan
// .getReassignmentList().size()));
            for (ShardReassignment reassignment : plan.getReassignmentList()) {
//                sendMessageToMaster("=============== START ===============");
                performShardReassignment(taskId, reassignment.shardId, reassignment.originalRoute, reassignment
                        .newRoute);
//                sendMessageToMaster("=============== END ===============");
            }
            SmartTimer.getInstance().stop("ScalingOut", "Conduct");
            sendMessageToMaster(SmartTimer.getInstance().getTimerString("ScalingOut"));
            sendMessageToMaster("Scaling out to " + twoTireRouting.getRoutes().size() + " succeeds with " +
                    plan.getReassignmentList().size() + " movements!");
            String shardMovements = "";
            for (ShardReassignment reassignment : plan.getReassignmentList()) {
                shardMovements += reassignment.shardId + " ";
            }
//            sendMessageToMaster(shardMovements);
//            sendMessageToMaster("Current DOP: " + twoTireRouting.getRoutes().size());
            System.out.println("Scaling out command is handed. The current Dop is " + twoTireRouting.getRoutes().size
                    ());
            return Status.OK();

        } catch (Exception e) {
            e.printStackTrace();
            sendMessageToMaster(e.getMessage());
            return Status.Error(e.getMessage());
        }

    }

    private void createMetricsReportThread() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(1000);

                        for (int remoteTaskId : _originalTaskIdToRemoteTaskExecutor.keySet()) {
                            ExecutionLatencyForRoutes latencyForRoutes = _originalTaskIdToRemoteTaskExecutor.get
                                    (remoteTaskId)._elasticExecutor.getExecutionLatencyForRoutes();
                            ThroughputForRoutes throughputForRoutes = _originalTaskIdToRemoteTaskExecutor.get
                                    (remoteTaskId)._elasticExecutor.getThroughputForRoutes();
                            MetricsForRoutesMessage message = new MetricsForRoutesMessage(remoteTaskId,
                                    latencyForRoutes, throughputForRoutes);

                            message.setTimeStamp(Calendar.getInstance().getTime().toString());
                            communicator._remoteExecutorIdToPrioritizedSender.get(remoteTaskId).send(remoteTaskId,
                                    SerializationUtils.serialize(message));
//                            _sendingQueue.put(message);
                        }
                        for (int taskId : _bolts.keySet()) {
                            ExecutionLatencyForRoutes latencyForRoutes = _bolts.get(taskId).get_elasticExecutor()
                                    .getExecutionLatencyForRoutes();
                            ThroughputForRoutes throughputForRoutes = _bolts.get(taskId).get_elasticExecutor()
                                    .getThroughputForRoutes();
                            _bolts.get(taskId).getMetrics().updateLatency(latencyForRoutes);
                            _bolts.get(taskId).getMetrics().updateThroughput(throughputForRoutes);

//                            System.out.println("Latency is added to the local metrics!");
//                            System.out.println(latencyForRoutes);

                            // get state size;
                            long stateSize = 0;
                            KeyValueState state = _bolts.get(taskId).get_elasticExecutor().get_bolt().getState();
                            if (state != null)
                                stateSize = state.getStateSize();

                            // get data transfer rate
                            final long dataTransferRate = _bolts.get(taskId).getDataTransferRateInBytesPerSecond();

                            // emit the metric message to master.
                            _slaveActor.sendMessageObjectToMaster(new ElasticExecutorMetricsReportMessage(taskId,
                                    stateSize, dataTransferRate));
                        }

                        _slaveActor.reportStateMigrationToMaster(WorkerMetrics.getInstance()
                                .getStateMigrationSizeAndReset());
                        _slaveActor.reportIntraExecutorDataTransferToMaster(WorkerMetrics.getInstance()
                                .getDataTransferSizeAndReset());


                    } catch (InterruptedException e) {
                        System.out.println("Metrics report thread is terminated!");
                    } catch (Exception e) {
                        e.printStackTrace();
                        sendMessageToMaster(e.getMessage());
                    }
                }
            }
        }).start();

        sendMessageToMaster("Metrics report thread is created!");
    }

    private void createParallelismPredicationThread() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        Thread.sleep(1000);
                        for (int taskId : _bolts.keySet()) {
                            int currentParallelism = _bolts.get(taskId).getCurrentParallelism();
                            int desirableParallelism = _bolts.get(taskId).getDesirableParallelism();
//                            sendMessageToMaster("Task: " + executorId + " average latency: " + _bolts.get
// (executorId).getMetrics().getAverageLatency());
//                            sendMessageToMaster("Task: " + executorId + " rate: " + _bolts.get(executorId)
// .getInputRate());
//                            sendMessageToMaster("Task " + executorId + ":  " + currentParallelism + "---->" +
// desirableParallelism);
                            if (currentParallelism < desirableParallelism) {
                                ExecutorScalingOutRequestMessage requestMessage = new
                                        ExecutorScalingOutRequestMessage(taskId);
                                _slaveActor.sendMessageObjectToMaster(requestMessage);
                            } else if (currentParallelism > desirableParallelism) {
                                ExecutorScalingInRequestMessage requestMessage = new ExecutorScalingInRequestMessage
                                        (taskId);
                                _slaveActor.sendMessageObjectToMaster(requestMessage);
                            }

                            _slaveActor.sendMessageObjectToMaster(new DesirableParallelismMessage(taskId,
                                    desirableParallelism));

                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    sendMessageToMaster("Error happens on createParallelismPredicationThread on " + _slaveActor.getIp
                            () + e.getMessage());
                }
            }
        }).start();

        sendMessageToMaster("Parallelism Predication thread is created on" + _slaveActor.getIp());
    }

    public String generateExecutorStatusString(int taskId) {
        if (!_bolts.containsKey(taskId))
            return String.format("Task %d does not exist on %s", taskId, Slave.getInstance().getLogicalName());
        String ret = String.format("Task %d:\n", taskId);
        List<Integer> routes = RoutingTableUtils.getOriginalRoutes(_bolts.get(taskId).get_elasticExecutor()
                .get_routingTable());
        for (Integer route : routes) {
            ret += String.format("Route %d: %s\n", route, routeIdToRemoteHost.get(new RouteId(taskId, route)));
        }
        return ret;
    }

}
