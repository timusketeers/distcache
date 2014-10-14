package com.howbuy.appframework.cacheserver.zookeeper;

public interface ClosableThread {
	public void close();

	public boolean isClosed();
}
