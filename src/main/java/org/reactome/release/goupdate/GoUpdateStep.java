package org.reactome.release.goupdate;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.persistence.MySQLAdaptor;
import org.gk.persistence.TransactionsNotSupportedException;
import org.reactome.release.common.ReleaseStep;
import org.reactome.util.general.DBUtils;

public class GoUpdateStep extends ReleaseStep
{
	private static final String PATH_TO_REPORTS_DIRECTORY = "reports";

	private static final Logger logger = LogManager.getLogger();

	private CSVPrinter duplicatePrinter ;

	@Override
	public void executeStep(Properties props) throws SQLException
	{
		long startTime = System.currentTimeMillis();
		try
		{
			// First part:
			// 1) Get the GO files:
			// - http://current.geneontology.org/ontology/go.obo (replaces outdated URL http://geneontology.org/ontology/obo_format_1_2/gene_ontology_ext.obo)
			// - http://current.geneontology.org/ontology/external2go/ec2go (replaces outdated URL http://geneontology.org/external2go/ec2go)			
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
			
			MySQLAdaptor adaptor = DBUtils.getCuratorDbAdaptor(props);
			this.loadTestModeFromProperties(props);
			
			long personID = Long.parseLong(props.getProperty("personId"));
			GoUpdateInstanceEditUtils.setAdaptor(adaptor);
			GoUpdateInstanceEditUtils.setPersonID(personID);
			String pathToGOFile = props.getProperty("pathToGOFile","src/main/resources/go.obo");
			String pathToEC2GOFile = props.getProperty("pathToEC2GOFile","src/main/resources/ec2go");
			
			
			if (Files.notExists(Paths.get(pathToGOFile)))
			{
				throw new FileNotFoundException("Sorry, but the GO file \""+pathToGOFile+"\" could not be found.");
			}
			if (Files.notExists(Paths.get(pathToEC2GOFile)))
			{
				throw new FileNotFoundException("Sorry, but the EC2GO file \""+pathToEC2GOFile+"\" could not be found.");
			}
			
			// Load the files.
			List<String> goLines = Files.readAllLines(Paths.get(pathToGOFile));
			List<String> ec2GoLines = Files.readAllLines(Paths.get(pathToEC2GOFile));

			String dateString = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
			Files.createDirectories(Paths.get(PATH_TO_REPORTS_DIRECTORY));
			try(BufferedWriter writer = Files.newBufferedWriter(Paths.get(PATH_TO_REPORTS_DIRECTORY,"duplicate_GO_terms_"+dateString+".csv")))
			{
				duplicatePrinter = new CSVPrinter(writer, GoTermsUpdater.GO_REPORT_FORMAT.withHeader("DB_ID", "Name", "Accession", "GO type", "Before or After GO Update process?", "Number of referrers"));
				reportOnDuplicateAccessions(adaptor, "BEFORE GO Update");
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

				// Do the updates.
				GoTermsUpdater goTermsUpdator = new GoTermsUpdater(adaptor, goLines, ec2GoLines);
				StringBuilder report = goTermsUpdator.updateGoTerms();
				logger.info(report);

				logger.info("Post-GO Update check for duplicated accessions...");
				reportOnDuplicateAccessions(adaptor, "AFTER GO Update");
				duplicatePrinter.close();
			}
			
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
		catch (Exception e)
		{
			e.printStackTrace();
			throw new RuntimeException(e);
		}
		long endTime = System.currentTimeMillis();
		logger.info("Elapsed time: {}", Duration.ofMillis(endTime-startTime).toString());
	}

	private void reportOnDuplicateAccessions(MySQLAdaptor adaptor, String when) throws Exception
	{
		DuplicateReporter duplicateReporter = new DuplicateReporter(adaptor);
		Map<String, Integer> duplicatedAccessions = duplicateReporter.getDuplicateAccessions();
		if (duplicatedAccessions!=null && !duplicatedAccessions.keySet().isEmpty())
		{
			logger.warn("Duplicated GO accessions exist! Check report.");
			for (String accession : duplicatedAccessions.keySet())
			{
				Map<Long,Integer> referrerCounts = duplicateReporter.getReferrerCountForAccession(accession);
				for (Entry<Long, Integer> entry : referrerCounts.entrySet())
				{
					Long dbId = entry.getKey();
					GKInstance inst = (GKInstance) adaptor.fetchInstance(dbId);
					duplicatePrinter.printRecord(dbId, inst.getDisplayName(), accession, inst.getSchemClass().getName(), when, entry.getValue());
				}
			}
		}
		else
		{
			logger.info("No duplicated GO accessions were detected.");
		}
	}
}