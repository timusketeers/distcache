package com.howbuy.appframework.cacheserver.client;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.howbuy.appframework.cacheserver.core.Replica;
import com.howbuy.appframework.cacheserver.core.Router;
import com.howbuy.appframework.cacheserver.core.Shard;
import com.howbuy.appframework.cacheserver.zookeeper.ClusterState;
import com.jolbox.bonecp.BoneCP;
import com.jolbox.bonecp.BoneCPConfig;

public class CacheClient {
	private static final Logger logger = LoggerFactory.getLogger(CacheClient.class);
	private static final int RETRY = 3;
	private static final long WAIT_TIME = 3000;
	private static Random random = new Random();

	public static void main1(String[] args) {
		try {
			ClusterStateCacheManager.INSTANCE.createClusterStateWatcher();
		} catch (Exception e1) {
			logger.error("Exception", e1);
		}
		logger.info(ClusterStateCacheManager.INSTANCE.getClusterState().toString());
		loadIntoRedis();
		try {
			Thread.sleep(3000);
		} catch (InterruptedException e) {
			logger.error(e.getMessage());
		}

	}

	public static void main(String[] args) throws Exception {
		ClusterStateCacheManager.INSTANCE.init("10.1.1.25");
		ClusterStateCacheManager.INSTANCE.createClusterStateWatcher();

		String key = "000000000000000005";
		queryByKey(key, 10);
		// CacheClient.INSTANCE.read(urls[0], new Integer(urls[1]), key);
		queryByKey(key, 10);
	}

	/**
	 * write data to redis
	 * 
	 * @param key
	 *            redis key
	 * @param hash
	 *            value of redis,type is HashMap
	 */
	public static void writeByKey(String key, Map<String, String> hash) {
		writeByKey(key, hash, RETRY);
	}

	/**
	 * write data to redis
	 * 
	 * @param key
	 *            redis key
	 * @param mapKey
	 *            the value map key
	 * @param mapValue
	 *            the value map value
	 */
	public static void writeByKey(String key, String mapKey, String mapValue) {
		writeByKey(key, mapKey, mapValue, RETRY);
	}

	public static void writeByKey(String key, String mapKey, String mapValue, int tryCnt) {
		String writerUrl = null;
		try {
			writerUrl = getWriteUrl(key);
			String[] urls = writerUrl.split(":");
			CacheOperation.INSTANCE.insert(urls[0], new Integer(urls[1]), key, mapKey, mapValue);
		} catch (Exception e) {
			logger.error("Exception:" + writerUrl, e);
			tryCnt--;
			if (tryCnt > 0) {
				writeByKey(key, mapKey, mapValue, tryCnt);
			}
		}
	}

	/**
	 * for test
	 * 
	 * @param key
	 */
	public static void writeByKey(String key, Map<String, String> hash, int tryCnt) {
		String writerUrl = null;
		try {
			writerUrl = getWriteUrl(key);
			String[] urls = writerUrl.split(":");
			CacheOperation.INSTANCE.insert(urls[0], new Integer(urls[1]), key, hash);
		} catch (Exception e) {
			logger.error("Exception:" + writerUrl, e);
			tryCnt--;
			if (tryCnt > 0) {
				writeByKey(key, hash, tryCnt);
			}
		}
	}

	public static void main3(String[] args) {
		String key = "x";
		int num = new java.util.Random().nextInt(999999);
		String numStr = new java.lang.Integer(num).toString();
		if (numStr.length() < 8) {
			int xx = 8 - numStr.length();
			while (xx > 0) {
				key = key + "0";
				xx--;
			}
		}
		key = key + numStr;
		System.out.println(key.length());
		System.out.println("***" + num + "****" + key);
	}

	/**
	 * query data by key
	 * 
	 * @param key
	 *            redis key
	 */
	public static Map<String, String> queryByKey(String key) {
		return queryByKey(key, RETRY);
	}

	/**
	 * 
	 * @param key
	 *            redis key
	 * @param mapKey
	 *            the value Map key
	 * @return
	 */
	public static String queryByKey(String key, String mapKey) {
		return queryByKey(key, mapKey, RETRY);
	}

	/**
	 * 
	 * @param key
	 *            redis key
	 * @param mapKey
	 *            the value Map key
	 * @return
	 */
	public static String queryByKey(String key, String mapKey, int tryCnt) {
		String readerUrl = getReaderUrl(key);
		String[] urls = readerUrl.split(":");
		try {
			return CacheOperation.INSTANCE.read(urls[0], new Integer(urls[1]), key, mapKey);
		} catch (Exception e) {
			logger.error("Exception:" + readerUrl, e);
			tryCnt--;
			if (tryCnt > 0) {
				return queryByKey(key, mapKey, tryCnt);
			}
		}
		return null;

	}

	/**
	 * for test
	 * 
	 * @param key
	 */
	public static Map<String, String> queryByKey(String key, int tryCnt) {
		String readerUrl = getReaderUrl(key);
		String[] urls = readerUrl.split(":");
		Map<String, String> ret = null;
		try {
			ret = CacheOperation.INSTANCE.read(urls[0], new Integer(urls[1]), key);
		} catch (Exception e) {
			logger.error("Exception:" + readerUrl, e);
			tryCnt--;
			if (tryCnt > 0) {
				return queryByKey(key, tryCnt);
			}
		}
		return ret;

	}

	public static void loadIntoRedis() {
		BoneCP connectionPool = null;

		Connection connection = null;
		try {
			// load the database driver (make sure this is in your classpath!)
			Class.forName("oracle.jdbc.driver.OracleDriver");
		} catch (Exception e) {
			logger.error("Exception:", e);
			return;
		}

		try {
			// setup the connection pool
			BoneCPConfig config = null;
			try {
				config = new BoneCPConfig("bonecp-config.xml");
			} catch (Exception e) {
				logger.error("Exception:", e);
			}
			connectionPool = new BoneCP(config); // setup the connection pool

			long startTime = System.currentTimeMillis();
			connection = connectionPool.getConnection(); // fetch a
															// connection
			if (connection != null) {
				System.out.println("Connection successful!");
				Statement stmt = connection.createStatement();
				ResultSet rs = stmt.executeQuery("select * from lsmp_lottery_user");

				while (rs.next()) {
					String key = rs.getString("LUSER_NUM");
					String userName = rs.getString("LUSER_NAME");
					String userId = rs.getString("ID");
					String userProvinceNum = rs.getString("LUSER_PROVINCE_NUM");
					String userLevel = rs.getString("LUSER_LEVEL");
					String telephone = rs.getString("LUSER_TELEPHONE");
					String email = "yangbutao@newcosoft.com";
					int errorLoginCnt = 10;
					long lastLoginTime = 1988922384994L;
					String cityNum = rs.getString("LUSER_CITY_NUM");
					String group = "1";
					String realName = rs.getString("LUSER_REAL_NAME");
					String area = "xxxxxxxxxxxxxxxxxx";
					String lastLoginIp = "109.222.44.32";
					Map<String, String> hash = new HashMap<String, String>();
					hash.put("userNum", key);
					hash.put("userId", userId);
					hash.put("userName", userName);
					hash.put("userProvinceNum", userProvinceNum);
					hash.put("userLevel", userLevel);
					hash.put("telephone", telephone);
					hash.put("email", email);
					hash.put("errorLoginCnt", new Integer(errorLoginCnt).toString());
					hash.put("lastLoginTime", new Long(lastLoginTime).toString());
					hash.put("cityNum", cityNum);
					hash.put("group", group);
					hash.put("realName", realName);
					hash.put("area", area);
					hash.put("lastLoginIp", lastLoginIp);

					String writeUrl = getWriteUrl(key);
					String[] urls = writeUrl.split(":");
					CacheOperation.INSTANCE.insert(urls[0], new Integer(urls[1]), key, hash);
				}
			}
			connectionPool.shutdown(); // shutdown connection pool.
		} catch (SQLException e) {
			logger.error("Exception:", e);
		} finally {
			if (connection != null) {
				try {
					connection.close();
				} catch (SQLException e) {
					logger.error(e.getMessage());
				}
			}
		}

	}

	public static String getWriteUrl(String key) {
		ClusterState clusterState = ClusterStateCacheManager.INSTANCE.getClusterState();
		Set<String> liveNodes = clusterState.getLiveNodes();
		Shard shard = Router.DEFAULT.getTargetShard(key, clusterState.getCollection("DEFAULT_COL"));
		Replica leader = shard.getLeader();
		for (int a = 0; a < 3; a++) {
			if (!liveNodes.contains(leader.getName())) {
				try {
					Thread.sleep(WAIT_TIME);
				} catch (InterruptedException e) {
					logger.error(e.getMessage());
				}
				leader = shard.getLeader();
			} else {
				break;
			}
		}
		return leader.getStr("base_url");
	}

	public static String getReaderUrl(String key) {
		ClusterState clusterState = ClusterStateCacheManager.INSTANCE.getClusterState();
		Shard shard = Router.DEFAULT.getTargetShard(key, clusterState.getCollection("DEFAULT_COL"));

		Replica reader = null;
		Replica[] replicas = shard.getReplicas().toArray(new Replica[shard.getReplicas().size()]);
		if (replicas.length == 1) {
			reader = replicas[0];
		} else {
			reader = replicas[random.nextInt(replicas.length)];
			Set<String> liveNodes = clusterState.getLiveNodes();
			for (int a = 0; a < 5; a++) {
				if (!liveNodes.contains(reader.getName())) {
					reader = replicas[random.nextInt(replicas.length)];
				} else {
					break;
				}
			}
		}
		return reader.getStr("base_url");
	}
}
