package org.reactome.goupdate;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.persistence.MySQLAdaptor;
import org.gk.persistence.TransactionsNotSupportedException;
import org.reactome.release.common.ReleaseStep;

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
