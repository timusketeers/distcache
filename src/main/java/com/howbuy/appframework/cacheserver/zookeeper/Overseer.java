package com.howbuy.appframework.cacheserver.zookeeper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.howbuy.appframework.cacheserver.core.CacheCollection;
import com.howbuy.appframework.cacheserver.core.Range;
import com.howbuy.appframework.cacheserver.core.Replica;
import com.howbuy.appframework.cacheserver.core.Router;
import com.howbuy.appframework.cacheserver.core.Shard;

public class Overseer {

	public static final String QUEUE_OPERATION = "operation";
	public static final String DELETECORE = "deletecore";
	public static final String REMOVECOLLECTION = "removecollection";
	public static final String REMOVESHARD = "removeshard";

	private static final int STATE_UPDATE_DELAY = 1500;

	private static Logger log = LoggerFactory.getLogger(Overseer.class);

	private class ClusterStateUpdater implements Runnable, ClosableThread {

		private final ZkStateReader reader;
		private final ZooKeeper zkClient;
		private final String myId;

		private final DistributedQueue stateUpdateQueue;

		private final DistributedQueue workQueue;
		private volatile boolean isClosed;


		public ClusterStateUpdater(final ZkStateReader reader, final String myId) {
			this.zkClient = reader.getZkClient();
			this.stateUpdateQueue = getInQueue(zkClient);
			this.workQueue = getInternalQueue(zkClient);
			this.myId = myId;
			this.reader = reader;
		}


		public void run() {

			if (!this.isClosed && amILeader()) {

				synchronized (reader.getUpdateLock()) {
					try {
						byte[] head = workQueue.peek();

						if (head != null) {
							reader.updateClusterState(true);
							ClusterState clusterState = reader
									.getClusterState();

							while (head != null && amILeader()) {
								final ZkNodeProps message = ZkNodeProps
										.load(head);
								final String operation = message
										.getStr(QUEUE_OPERATION);
								clusterState = processMessage(clusterState,
										message, operation);
								zkClient.setData(ZkStateReader.CLUSTER_STATE,
										ZkStateReader.toJSON(clusterState), -1);

								workQueue.poll();

								head = workQueue.peek();
							}
						}
					} catch (KeeperException e) {
						if (e.code() == KeeperException.Code.SESSIONEXPIRED) {
							log.warn(
									"cannot talk to ZK, exiting Overseer work queue loop",
									e);
							return;
						}
						log.error("Exception in Overseer work queue loop", e);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;

					} catch (Exception e) {
						log.error("Exception in Overseer work queue loop", e);
					}
				}

			}

			log.info("Starting to work on the main queue");
			// /overseer/queue(stateUpdateQueue)===>/overseer/queue-work(workQueue)
			while (!this.isClosed && amILeader()) {
				synchronized (reader.getUpdateLock()) {
					try {
						byte[] head = stateUpdateQueue.peek();
						if (head != null) {
							reader.updateClusterState(true);
							ClusterState clusterState = reader
									.getClusterState();

							while (head != null) {
								final ZkNodeProps message = ZkNodeProps
										.load(head);
								final String operation = message
										.getStr(QUEUE_OPERATION);

								clusterState = processMessage(clusterState,
										message, operation);
								workQueue.offer(head);

								stateUpdateQueue.poll();
								head = stateUpdateQueue.peek();
							}
							zkClient.setData(ZkStateReader.CLUSTER_STATE,
									ZkStateReader.toJSON(clusterState), -1);
						}
						// clean work queue
						while (workQueue.poll() != null)
							;

					} catch (KeeperException e) {
						if (e.code() == KeeperException.Code.SESSIONEXPIRED) {
							log.warn(
									"cannot talk to ZK, exiting Overseer main queue loop",
									e);
							return;
						}
						log.error("Exception in Overseer main queue loop", e);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return;

					} catch (Exception e) {
						log.error("Exception in Overseer main queue loop", e);
					}
				}

				try {
					Thread.sleep(STATE_UPDATE_DELAY);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}

		private ClusterState processMessage(ClusterState clusterState,
				final ZkNodeProps message, final String operation) {
			if ("state".equals(operation)) {
				clusterState = updateState(clusterState, message);
			} else if (DELETECORE.equals(operation)) {
				clusterState = removeCore(clusterState, message);
			} else if (REMOVECOLLECTION.equals(operation)) {
				clusterState = removeCollection(clusterState, message);
			} else if (REMOVESHARD.equals(operation)) {
				clusterState = removeShard(clusterState, message);
			} else if (ZkStateReader.LEADER_PROP.equals(operation)) {

				StringBuilder sb = new StringBuilder();
				String baseUrl = message.getStr(ZkStateReader.BASE_URL_PROP);
				String coreName = message.getStr(ZkStateReader.CORE_NAME_PROP);
				sb.append(baseUrl);
				if (baseUrl != null && !baseUrl.endsWith("/"))
					sb.append("/");
				sb.append(coreName == null ? "" : coreName);
				if (!(sb.substring(sb.length() - 1).equals("/")))
					sb.append("/");
				clusterState = setShardLeader(clusterState,
						message.getStr(ZkStateReader.COLLECTION_PROP),
						message.getStr(ZkStateReader.SHARD_ID_PROP),
						sb.length() > 0 ? sb.toString() : null);

			} else if ("createshard".equals(operation)) {
				clusterState = createShard(clusterState, message);
			} else if ("updateshardstate".equals(operation)) {
				clusterState = updateShardState(clusterState, message);
			} else {
				throw new RuntimeException("unknown operation:" + operation
						+ " contents:" + message.getProperties());
			}
			return clusterState;
		}

		private ClusterState updateShardState(ClusterState clusterState,
				ZkNodeProps message) {
			String collection = message.getStr(ZkStateReader.COLLECTION_PROP);
			log.info("Update shard state invoked for collection: " + collection);
			for (String key : message.keySet()) {
				if (ZkStateReader.COLLECTION_PROP.equals(key))
					continue;
				if (QUEUE_OPERATION.equals(key))
					continue;

				Shard slice = clusterState.getSlice(collection, key);
				if (slice == null) {
					throw new RuntimeException(
							"Overseer.updateShardState unknown collection: "
									+ collection + " slice: " + key);
				}
				log.info("Update shard state " + key + " to "
						+ message.getStr(key));
				Map<String, Object> props = slice.shallowCopy();
				props.put(Shard.STATE, message.getStr(key));
				Shard newSlice = new Shard(slice.getName(),
						slice.getReplicasCopy(), props);
				clusterState = updateShard(clusterState, collection, newSlice);
			}

			return clusterState;
		}

		private ClusterState createShard(ClusterState clusterState,
				ZkNodeProps message) {
			String collection = message.getStr(ZkStateReader.COLLECTION_PROP);
			String shardId = message.getStr(ZkStateReader.SHARD_ID_PROP);
			Shard slice = clusterState.getSlice(collection, shardId);
			if (slice == null) {
				Map<String, Replica> replicas = Collections.EMPTY_MAP;
				Map<String, Object> sliceProps = new HashMap<String, Object>();
				String shardRange = message
						.getStr(ZkStateReader.SHARD_RANGE_PROP);
				String shardState = message
						.getStr(ZkStateReader.SHARD_STATE_PROP);
				sliceProps.put(Shard.RANGE, shardRange);
				sliceProps.put(Shard.STATE, shardState);
				slice = new Shard(shardId, replicas, sliceProps);
				clusterState = updateShard(clusterState, collection, slice);
			} else {
				log.error("Unable to create Shard: " + shardId
						+ " because it already exists in collection: "
						+ collection);
			}
			return clusterState;
		}

		private boolean amILeader() {
			try {
				ZkNodeProps props = ZkNodeProps.load(zkClient.getData(
						"/overseer_elect/leader", null, null));
				if (myId.equals(props.getStr("id"))) {
					return true;
				}
			} catch (KeeperException e) {
				log.warn("", e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			log.info("no longer a leader.");
			return false;
		}


		private ClusterState updateState(ClusterState state,
				final ZkNodeProps message) {
			final String collection = message
					.getStr(ZkStateReader.COLLECTION_PROP);
			String coreNodeName = message
					.getStr(ZkStateReader.CORE_NODE_NAME_PROP);
			if (coreNodeName == null) {
				coreNodeName = getAssignedCoreNodeName(state, message);
				if (coreNodeName != null) {
					log.info("node=" + coreNodeName + " is already registered");
				} else {

					// coreNodeName = Assign.assignNode(collection, state);
				}
				message.getProperties().put(ZkStateReader.CORE_NODE_NAME_PROP,
						coreNodeName);
			}
			Integer numShards = message.getStr(ZkStateReader.NUM_SHARDS_PROP) != null ? Integer
					.parseInt(message.getStr(ZkStateReader.NUM_SHARDS_PROP))
					: null;

			boolean collectionExists = state.getCollections().contains(
					collection);
			if (!collectionExists && numShards != null) {
				state = createCollection(state, collection, numShards);
			}


			String sliceName = message.getStr(ZkStateReader.SHARD_ID_PROP);
			if (sliceName == null) {

				sliceName = getAssignedId(state, coreNodeName, message);
				if (sliceName != null) {
					log.info("shard=" + sliceName + " is already registered");
				}
			}
			if (sliceName == null) {
				// request new shardId
				if (collectionExists) {
					// use existing numShards
					numShards = state.getCollectionStates().get(collection)
							.getShards().size();
					log.info("Collection already exists with "
							+ ZkStateReader.NUM_SHARDS_PROP + "=" + numShards);
				}
				// sliceName = Assign.assignShard(collection, state, numShards);
				log.info("Assigning new node to shard shard=" + sliceName);
			}

			Shard slice = state.getSlice(collection, sliceName);

			Map<String, Object> replicaProps = new LinkedHashMap<String, Object>();

			replicaProps.putAll(message.getProperties());
			// System.out.println("########## UPDATE MESSAGE: " +
			// JSONUtil.toJSON(message));
			if (slice != null) {
				String sliceState = slice.getState();

				Replica oldReplica = slice.getReplicasMap().get(coreNodeName);
				if (oldReplica != null
						&& oldReplica.containsKey(ZkStateReader.LEADER_PROP)) {
					replicaProps.put(ZkStateReader.LEADER_PROP,
							oldReplica.get(ZkStateReader.LEADER_PROP));
				}
			}


			replicaProps.remove(ZkStateReader.NUM_SHARDS_PROP);
			replicaProps.remove(ZkStateReader.CORE_NODE_NAME_PROP);
			replicaProps.remove(ZkStateReader.SHARD_ID_PROP);
			replicaProps.remove(ZkStateReader.COLLECTION_PROP);
			replicaProps.remove(QUEUE_OPERATION);


			Set<Entry<String, Object>> entrySet = replicaProps.entrySet();
			List<String> removeKeys = new ArrayList<String>();
			for (Entry<String, Object> entry : entrySet) {
				if (entry.getValue() == null) {
					removeKeys.add(entry.getKey());
				}
			}
			for (String removeKey : removeKeys) {
				replicaProps.remove(removeKey);
			}
			replicaProps.remove(ZkStateReader.CORE_NODE_NAME_PROP);

			String shardRange = (String) replicaProps
					.remove(ZkStateReader.SHARD_RANGE_PROP);
			String shardState = (String) replicaProps
					.remove(ZkStateReader.SHARD_STATE_PROP);

			Replica replica = new Replica(coreNodeName, replicaProps);


			Map<String, Object> sliceProps = null;
			Map<String, Replica> replicas;

			if (slice != null) {
				sliceProps = slice.getProperties();
				replicas = slice.getReplicasCopy();
			} else {
				replicas = new HashMap<String, Replica>(1);
				sliceProps = new HashMap<String, Object>();
				sliceProps.put(Shard.RANGE, shardRange);
				sliceProps.put(Shard.STATE, shardState);
			}

			replicas.put(replica.getName(), replica);
			slice = new Shard(sliceName, replicas, sliceProps);

			ClusterState newClusterState = updateShard(state, collection, slice);
			return newClusterState;
		}

		private Map<String, Object> defaultCollectionProps() {
			HashMap<String, Object> props = new HashMap<String, Object>(2);
			props.put(CacheCollection.DOC_ROUTER, Router.DEFAULT_NAME);
			return props;
		}

		private ClusterState createCollection(ClusterState state,
				String collectionName, int numShards) {
			log.info("Create collection {} with numShards {}", collectionName,
					numShards);

			Router router = Router.DEFAULT;
			List<Range> ranges = router.partitionRange(numShards,
					router.fullRange());

			Map<String, CacheCollection> newCollections = new LinkedHashMap<String, CacheCollection>();

			Map<String, Shard> newSlices = new LinkedHashMap<String, Shard>();
			newCollections.putAll(state.getCollectionStates());
			for (int i = 0; i < numShards; i++) {
				final String sliceName = "shard" + (i + 1);

				Map<String, Object> sliceProps = new LinkedHashMap<String, Object>(
						1);
				sliceProps.put(Shard.RANGE, ranges.get(i));

				newSlices
						.put(sliceName, new Shard(sliceName, null, sliceProps));
			}


			Map<String, Object> collectionProps = defaultCollectionProps();

			CacheCollection newCollection = new CacheCollection(collectionName,
					newSlices, collectionProps, router);

			newCollections.put(collectionName, newCollection);
			ClusterState newClusterState = new ClusterState(
					state.getLiveNodes(), newCollections);
			return newClusterState;
		}


		private String getAssignedId(final ClusterState state,
				final String nodeName, final ZkNodeProps coreState) {
			Collection<Shard> slices = state.getSlices(coreState
					.getStr(ZkStateReader.COLLECTION_PROP));
			if (slices != null) {
				for (Shard slice : slices) {
					if (slice.getReplicasMap().get(nodeName) != null) {
						return slice.getName();
					}
				}
			}
			return null;
		}

		private String getAssignedCoreNodeName(ClusterState state,
				ZkNodeProps message) {
			Collection<Shard> slices = state.getSlices(message
					.getStr(ZkStateReader.COLLECTION_PROP));
			if (slices != null) {
				for (Shard slice : slices) {
					for (Replica replica : slice.getReplicas()) {
						String baseUrl = replica
								.getStr(ZkStateReader.BASE_URL_PROP);
						String core = replica
								.getStr(ZkStateReader.CORE_NAME_PROP);

						String msgBaseUrl = message
								.getStr(ZkStateReader.BASE_URL_PROP);
						String msgCore = message
								.getStr(ZkStateReader.CORE_NAME_PROP);

						if (baseUrl.equals(msgBaseUrl) && core.equals(msgCore)) {
							return replica.getName();
						}
					}
				}
			}
			return null;
		}

		private ClusterState updateShard(ClusterState state,
				String collectionName, Shard slice) {
			// System.out.println("###!!!### OLD CLUSTERSTATE: " +
			// JSONUtil.toJSON(state.getCollectionStates()));
			// System.out.println("Updating slice:" + slice);

			Map<String, CacheCollection> newCollections = new LinkedHashMap<String, CacheCollection>(
					state.getCollectionStates()); // make a shallow copy
			CacheCollection coll = newCollections.get(collectionName);
			Map<String, Shard> slices;
			Map<String, Object> props;
			Router router = null;

			if (coll == null) {

				slices = new HashMap<String, Shard>(1);
				props = new HashMap<String, Object>(1);

			} else {
				props = coll.getProperties();
				router = coll.getRouter();
				slices = new LinkedHashMap<String, Shard>(coll.getShardsMap());
			}
			slices.put(slice.getName(), slice);
			CacheCollection newCollection = new CacheCollection(collectionName,
					slices, props, router);
			newCollections.put(collectionName, newCollection);



			return new ClusterState(state.getLiveNodes(), newCollections);
		}

		private ClusterState setShardLeader(ClusterState state,
				String collectionName, String sliceName, String leaderUrl) {

			final Map<String, CacheCollection> newCollections = new LinkedHashMap<String, CacheCollection>(
					state.getCollectionStates());
			CacheCollection coll = newCollections.get(collectionName);
			if (coll == null) {
				log.error("Could not mark shard leader for non existing collection:"
						+ collectionName);
				return state;
			}

			Map<String, Shard> slices = coll.getShardsMap();

			slices = new LinkedHashMap<String, Shard>(slices);

			Shard slice = slices.get(sliceName);
			if (slice == null) {
				slice = coll.getShard(sliceName);
			}

			if (slice == null) {
				log.error("Could not mark leader for non existing/active slice:"
						+ sliceName);
				return state;
			} else {

				Replica oldLeader = slice.getLeader();

				final Map<String, Replica> newReplicas = new LinkedHashMap<String, Replica>();

				for (Replica replica : slice.getReplicas()) {


					String coreURL = ZkCoreNodeProps.getCoreUrl(
							replica.getStr(ZkStateReader.BASE_URL_PROP),
							replica.getStr(ZkStateReader.CORE_NAME_PROP));

					if (replica == oldLeader && !coreURL.equals(leaderUrl)) {
						Map<String, Object> replicaProps = new LinkedHashMap<String, Object>(
								replica.getProperties());
						replicaProps.remove(Shard.LEADER);
						replica = new Replica(replica.getName(), replicaProps);
					} else if (coreURL.equals(leaderUrl)) {
						Map<String, Object> replicaProps = new LinkedHashMap<String, Object>(
								replica.getProperties());
						replicaProps.put(Shard.LEADER, "true");
						replica = new Replica(replica.getName(), replicaProps);
					}

					newReplicas.put(replica.getName(), replica);
				}

				Map<String, Object> newSliceProps = slice.shallowCopy();
				newSliceProps.put(Shard.REPLICAS, newReplicas);
				Shard newSlice = new Shard(slice.getName(), newReplicas,
						slice.getProperties());
				slices.put(newSlice.getName(), newSlice);
			}

			CacheCollection newCollection = new CacheCollection(coll.getName(),
					slices, coll.getProperties(), coll.getRouter());
			newCollections.put(collectionName, newCollection);
			return new ClusterState(state.getLiveNodes(), newCollections);
		}


		private ClusterState removeCollection(final ClusterState clusterState,
				ZkNodeProps message) {

			final String collection = message.getStr("name");

			final Map<String, CacheCollection> newCollections = new LinkedHashMap<String, CacheCollection>(
					clusterState.getCollectionStates());
			newCollections.remove(collection);

			ClusterState newState = new ClusterState(
					clusterState.getLiveNodes(), newCollections);
			return newState;
		}


		private ClusterState removeShard(final ClusterState clusterState,
				ZkNodeProps message) {

			final String collection = message
					.getStr(ZkStateReader.COLLECTION_PROP);
			final String sliceId = message.getStr(ZkStateReader.SHARD_ID_PROP);

			final Map<String, CacheCollection> newCollections = new LinkedHashMap<String, CacheCollection>(
					clusterState.getCollectionStates());
			CacheCollection coll = newCollections.get(collection);

			Map<String, Shard> newSlices = new LinkedHashMap<String, Shard>(
					coll.getShardsMap());
			newSlices.remove(sliceId);

			CacheCollection newCollection = new CacheCollection(coll.getName(),
					newSlices, new HashMap<String, Object>(), coll.getRouter());
			newCollections.put(newCollection.getName(), newCollection);

			return new ClusterState(clusterState.getLiveNodes(), newCollections);
		}


		private ClusterState removeCore(final ClusterState clusterState,
				ZkNodeProps message) {

			String cnn = message.getStr(ZkStateReader.CORE_NODE_NAME_PROP);

			final String collection = message
					.getStr(ZkStateReader.COLLECTION_PROP);

			final Map<String, CacheCollection> newCollections = new LinkedHashMap<String, CacheCollection>(
					clusterState.getCollectionStates());
			CacheCollection coll = newCollections.get(collection);
			if (coll == null) {

				try {
					new ZookeeperClient(zkClient).clean("/collections/"
							+ collection);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} catch (KeeperException e) {
					log.error("Exception", e);
				}
				return clusterState;
			}

			Map<String, Shard> newSlices = new LinkedHashMap<String, Shard>();
			boolean lastSlice = false;
			for (Shard slice : coll.getShards()) {
				Replica replica = slice.getReplica(cnn);
				if (replica != null) {
					Map<String, Replica> newReplicas = slice.getReplicasCopy();
					newReplicas.remove(cnn);

					if (newReplicas.size() == 0) {
						slice = null;
						lastSlice = true;
					} else {
						slice = new Shard(slice.getName(), newReplicas,
								new HashMap<String, Object>());
					}
				}

				if (slice != null) {
					newSlices.put(slice.getName(), slice);
				}
			}

			if (lastSlice) {

				for (Shard slice : coll.getShards()) {
					if (slice.getReplicas().size() == 0) {
						newSlices.remove(slice.getName());
					}
				}
			}


			if (newSlices.size() == 0) {
				newCollections.remove(coll.getName());


				try {
					new ZookeeperClient(zkClient).clean("/collections/"
							+ collection);
				} catch (InterruptedException e) {
					log.error("Exception", e);
					Thread.currentThread().interrupt();
				} catch (KeeperException e) {
					log.error("Exception", e);
				}

			} else {
				CacheCollection newCollection = new CacheCollection(
						coll.getName(), newSlices,
						new HashMap<String, Object>(), coll.getRouter());
				newCollections.put(newCollection.getName(), newCollection);
			}

			ClusterState newState = new ClusterState(
					clusterState.getLiveNodes(), newCollections);
			return newState;
		}


		public void close() {
			this.isClosed = true;
		}


		public boolean isClosed() {
			return this.isClosed;
		}

	}

	class OverseerThread extends Thread implements ClosableThread {

		private volatile boolean isClosed;

		public OverseerThread(ThreadGroup tg,
				ClusterStateUpdater clusterStateUpdater) {
			super(tg, clusterStateUpdater);
		}

		public OverseerThread(ThreadGroup ccTg,
				OverseerCollectionProcessor overseerCollectionProcessor,
				String string) {
			super(ccTg, overseerCollectionProcessor, string);
		}


		public void close() {
			this.isClosed = true;
		}

		public boolean isClosed() {
			return this.isClosed;
		}

	}

	private OverseerThread ccThread;

	private OverseerThread updaterThread;

	private volatile boolean isClosed;

	private ZkStateReader reader;

	private String adminPath;

	public Overseer(final ZkStateReader reader) throws KeeperException,
			InterruptedException {
		this.reader = reader;
	}

	public void start(String id) {
		log.info("Overseer (id=" + id + ") starting");
		createOverseerNode(reader.getZkClient());

		ThreadGroup tg = new ThreadGroup("Overseer state updater.");
		updaterThread = new OverseerThread(tg, new ClusterStateUpdater(reader,
				id));
		updaterThread.setDaemon(true);

		ThreadGroup ccTg = new ThreadGroup(
				"Overseer collection creation process.");
		ccThread = new OverseerThread(ccTg, new OverseerCollectionProcessor(
				reader, id, adminPath), "Overseer-" + id);
		ccThread.setDaemon(true);

		updaterThread.start();
		ccThread.start();
	}


	public void interuptThread() {
		if (updaterThread != null) {
			updaterThread.interrupt();
		}
		if (ccThread != null) {
			ccThread.interrupt();
		}
	}

	public void close() {
		isClosed = true;
		if (updaterThread != null) {
			try {
				updaterThread.close();
				updaterThread.interrupt();
			} catch (Throwable t) {
				log.error("Error closing updaterThread", t);
			}
		}
		if (ccThread != null) {
			try {
				ccThread.close();
				ccThread.interrupt();
			} catch (Throwable t) {
				log.error("Error closing ccThread", t);
			}
		}

		try {
			reader.close();
		} catch (Throwable t) {
			log.error("Error closing zkStateReader", t);
		}
	}


	public static DistributedQueue getInQueue(final ZooKeeper zkClient) {
		createOverseerNode(zkClient);
		return new DistributedQueue(zkClient, "/overseer/queue", null);
	}


	static DistributedQueue getInternalQueue(final ZooKeeper zkClient) {
		createOverseerNode(zkClient);
		return new DistributedQueue(zkClient, "/overseer/queue-work", null);
	}


	static DistributedQueue getCollectionQueue(final ZooKeeper zkClient) {
		createOverseerNode(zkClient);
		return new DistributedQueue(zkClient,
				"/overseer/collection-queue-work", null);
	}

	private static void createOverseerNode(final ZooKeeper zkClient) {
		try {
			zkClient.create("/overseer", new byte[0],
					ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
		} catch (KeeperException.NodeExistsException e) {
			log.warn(e.getMessage());
		} catch (InterruptedException e) {
			log.error("Could not create Overseer node", e);
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		} catch (KeeperException e) {
			log.error("Could not create Overseer node", e);
			throw new RuntimeException(e);
		}
	}
}
