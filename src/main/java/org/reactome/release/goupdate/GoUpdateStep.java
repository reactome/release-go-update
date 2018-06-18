package org.reactome.release.goupdate;

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
			// 
			// New process:
			// 1) load GO file lines
			// 2) load ec2go file lines
			// 3) use these to sets of data to build in-memory data structure of all GO terms from the files
			// 4) use this data structure to create/update/mark-for-deletion instances in database.
			// 5) delete the marked-for-deletion instances.
			// 6) update relationships between remaining instances, based on content of data structure.
			
			MySQLAdaptor adaptor = getMySQLAdaptorFromProperties(props);
			this.loadTestModeFromProperties(props);
			
			long personID = new Long(props.getProperty("person.id"));
			
			String pathToGOFile = props.getProperty("pathToGOFile","src/main/resources/gene_ontology_ext.obo");
			String pathToEC2GOFile = props.getProperty("pathToEC2GOFile","src/main/resources/ec2go");
			
			// Load the files.
			List<String> goLines = Files.readAllLines(Paths.get(pathToGOFile));
			List<String> ec2GoLines = Files.readAllLines(Paths.get(pathToEC2GOFile));

			// Start a transaction. If that fails, the program will exit.
			try
			{
				adaptor.startTransaction();
			}
			catch (TransactionsNotSupportedException e1)
			{
				e1.printStackTrace();
				logger.error("This program should run within a transaction. Exiting.");
				System.exit(1);
			}

			// Do the updatess.
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