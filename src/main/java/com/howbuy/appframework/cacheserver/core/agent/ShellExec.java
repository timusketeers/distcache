package com.howbuy.appframework.cacheserver.core.agent;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShellExec {
	private static final Logger logger = LoggerFactory.getLogger(ShellExec.class);

	public static String runExec(String scriptPath) {
		// ./redis_slave.sh master port

		Process process;
		try {
			process = Runtime.getRuntime().exec(scriptPath);

			InputStreamReader ir = new InputStreamReader(process.getInputStream());

			LineNumberReader input = new LineNumberReader(ir);

			return input.readLine();
		} catch (IOException e) {
			logger.error(e.getMessage());
		}
		return null;
	}

}
