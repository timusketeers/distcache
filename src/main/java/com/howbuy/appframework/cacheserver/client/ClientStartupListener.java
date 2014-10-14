package com.howbuy.appframework.cacheserver.client;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientStartupListener implements ServletContextListener {
	private static final Logger logger = LoggerFactory.getLogger(ClientStartupListener.class);

	public void contextDestroyed(ServletContextEvent arg0) {

	}

	public void contextInitialized(ServletContextEvent arg0) {
		try {
			// OssConfig osConfig = OssConfig.getInstance();
			// String zkUrl =
			// osConfig.getProperties().getProperty("cache.zk_url");
			String zkUrl = arg0.getServletContext().getInitParameter("zkUrl");
			ClusterStateCacheManager.INSTANCE.init(zkUrl);
			ClusterStateCacheManager.INSTANCE.createClusterStateWatcher();
		} catch (Exception e) {
			logger.error("Exception", e);
		}
		logger.info(ClusterStateCacheManager.INSTANCE.getClusterState().toString());
	}

}
