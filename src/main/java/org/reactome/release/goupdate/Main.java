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
	public static void main(String[] args) throws SQLException
	{

		GoUpdateStep step = new GoUpdateStep();
		String pathToResources = "./go-update.properties";
		if (args.length > 0)
		{
			pathToResources = args[0];
		}
		try
		{
			Properties props = new Properties();
			props.load(new FileInputStream(pathToResources));

			step.executeStep(props);
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
	}

}
