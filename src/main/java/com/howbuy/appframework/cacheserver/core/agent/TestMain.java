package com.howbuy.appframework.cacheserver.core.agent;

public class TestMain {
	public static void main(String[] args) {
		String scriptPath = "/opt/lsmp/monitor-redis.sh";
		String result = ShellExec.runExec(scriptPath);
		System.out.println("**************"+result);
	}
}
