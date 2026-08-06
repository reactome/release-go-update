package org.reactome.release.goupdate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.FileInputStream;
import java.util.Properties;

/**
 * @author sshorser
 *
 */
public class Main {
	private static final Logger logger = LogManager.getLogger();

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		GoUpdateStep step = new GoUpdateStep();
		String pathToResources = args.length > 0 ? args[0] : "./go-update.properties";

		try {
			Properties props = new Properties();
			props.load(new FileInputStream(pathToResources));

			step.executeStep(props);
		} catch (Exception e) {
			// Release steps run unattended under Jenkins, which decides whether the step passed from the exit
			// code alone, so a failure must not leave the JVM exiting 0.
			logger.error("Error during GO update", e);
			System.exit(1);
		}
	}
}
