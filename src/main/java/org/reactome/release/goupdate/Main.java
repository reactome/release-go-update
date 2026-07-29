package org.reactome.release.goupdate;

import java.io.FileInputStream;
import java.sql.SQLException;
import java.util.Properties;

/**
 * @author sshorser
 *
 */
public class Main
{
	/**
	 * @param args
	 * @throws SQLException 
	 */
	public static void main(String[] args) {

		GoUpdateStep step = new GoUpdateStep();
		String pathToResources = args.length > 0 ? args[0] : "./go-update.properties";

		try {
			Properties props = new Properties();
			props.load(new FileInputStream(pathToResources));

			step.executeStep(props);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
