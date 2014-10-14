package com.howbuy.appframework.cacheserver.core;

import java.util.Map;

import org.noggit.JSONUtil;

import com.howbuy.appframework.cacheserver.zookeeper.ZkNodeProps;

public class Replica extends ZkNodeProps {
	private final String name;
	private final String nodeName;

	public Replica(String name, Map<String, Object> propMap) {
		super(propMap);
		this.name = name;
		nodeName = (String) propMap.get("node_name");
	}

	public String getName() {
		return name;
	}

	
	public String getNodeName() {
		return nodeName;
	}

	@Override
	public String toString() {
		return name + ':' + JSONUtil.toJSON(propMap, -1); 
	}

}
