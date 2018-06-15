package org.reactome.goupdate;

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

public class GoUpdateStep extends ReleaseStep
{
	private static final Logger logger = LogManager.getLogger("GoUpdateLogger");
	
	@Override
	public void executeStep(Properties props) throws SQLException
	{
		Long startTime = System.currentTimeMillis();
		try
		{
			// First part:
			// 1) Get the GO files:
			// - http://geneontology.org/ontology/obo_format_1_2/gene_ontology_ext.obo
			// - http://geneontology.org/external2go/ec2go
			// 2) from database, get list of all things where:
			//    biological_process=GO_BiologicalProcess, molecular_function=GO_MolecularFunction, cellular_component=GO_CellularComponent 
			// 3) Read gene_ontology_ext.obo
			// 4) Update objects from Database based on GO file.
			// 5) print Wiki output.
			//
			// Second part:
			// 1) Read ec2go file
			// 2) extact EC to GO mapping.
			// 3) Update GO objects in Database.
			//
			// ...Of course, we could just do these together in one program: Read both files and populate one data structure containing everything.
			
			MySQLAdaptor adaptor = getMySQLAdaptorFromProperties(props);
			boolean testMode = new Boolean(props.getProperty("testMode", "true"));
			if (!testMode)
			{
				logger.info("Test mode is OFF - database will be updated!");
			}
			else
			{
				logger.info("Test mode is ON - no database changes will be made.");
			}
			long personID = new Long(props.getProperty("person.id"));
			
			String pathToGOFile = "src/main/resources/gene_ontology_ext.obo";
			String pathToEC2GOFile = "src/main/resources/ec2go";
			List<String> goLines = Files.readAllLines(Paths.get(pathToGOFile));
			List<String> ec2GoLines = Files.readAllLines(Paths.get(pathToEC2GOFile));

			try
			{
				adaptor.startTransaction();
			}
			catch (TransactionsNotSupportedException e1)
			{
				e1.printStackTrace();
				logger.error("This program should run within a transaction. Aborting.");
				System.exit(1);
			}

			// Load the file.

			GoTermsUpdater goTermsUpdator = new GoTermsUpdater(adaptor, goLines, ec2GoLines, personID);

			StringBuilder report = goTermsUpdator.updateGoTerms();
			logger.info(report);
			testMode = true;
			if (testMode)
			{
				adaptor.rollback();
			}
			else
			{
				adaptor.commit();
			}
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
		Long endTime = System.currentTimeMillis();
		logger.info("Elapsed time: " + Duration.ofMillis(endTime-startTime).toString());
	}
}