package org.reactome.release.goupdate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.release.common.ReleaseStep;
import org.reactome.release.goupdate.duplicate.DuplicateFinder;
import org.reactome.release.goupdate.reports.DuplicatesReport;
import org.reactome.release.goupdate.utils.CuratorToolAPI;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class GoUpdateStep extends ReleaseStep {
	private static final Logger logger = LogManager.getLogger();
	private CuratorToolAPI curatorToolAPI;
	private DuplicatesReport duplicatesReport;

	@Override
	public void executeStep(Properties props) throws Exception {
		long startTime = System.currentTimeMillis();
		try {
			initialize(props);
			processGoUpdate(props);
		} finally {
			// The Spring context holds the H2 connection pool open, so it must be closed even when the update
			// fails part way through; otherwise the JVM lingers and the next run finds the database file locked.
			if (curatorToolAPI != null) {
				curatorToolAPI.close();
			}
			logExecutionTime(startTime);
		}
	}

	private void initialize(Properties props) throws SQLException {
		long personId = Long.parseLong(props.getProperty("personId"));

		curatorToolAPI = new CuratorToolAPI(personId);
		//loadTestModeFromProperties(props);
		//initializeInstanceEditUtils(props);
	}

	private void processGoUpdate(Properties props) throws Exception {
		GoFiles goFiles = loadGoFiles(props);

		processUpdate(goFiles);

		//finalizeTransaction();
	}

//	private void initializeInstanceEditUtils(Properties props) {
//		long personID = Long.parseLong(props.getProperty("personId"));
//		GoUpdateInstanceEditUtils.setAdaptor(adaptor);
//		GoUpdateInstanceEditUtils.setPersonID(personID);
//	}

	private GoFiles loadGoFiles(Properties props) throws IOException {
		String pathToGOFile = props.getProperty("pathToGOFile", "src/main/resources/go.obo");
		String pathToEC2GOFile = props.getProperty("pathToEC2GOFile", "src/main/resources/ec2go");

		validateFilesExist(pathToGOFile, pathToEC2GOFile);

		return new GoFiles(
			Files.readAllLines(Paths.get(pathToGOFile)),
			Files.readAllLines(Paths.get(pathToEC2GOFile))
		);
	}

	private void processUpdate(GoFiles goFiles) throws Exception {
		//startDatabaseTransaction();

		this.duplicatesReport = new DuplicatesReport();

		// Reading every GO instance costs a query per instance, so the "before" duplicate report and the update
		// itself share the one reading of them. The update changes the instances in this map as it goes and
		// empties it when it is finished, so it must not be used again below; the "after" report works from what
		// reconciliation read back instead.
		Map<String, List<SimpleInstance>> goInstancesBeforeUpdate = curatorToolAPI.fetchGOInstancesByAccession();

		reportOnDuplicateAccessions("BEFORE GO Update", goInstancesBeforeUpdate);
		Map<String, List<SimpleInstance>> goInstancesAfterUpdate = performUpdate(goFiles, goInstancesBeforeUpdate);
		reportOnDuplicateAccessions("AFTER GO Update", goInstancesAfterUpdate);
	}

	private void validateFilesExist(String pathToGOFile, String pathToEC2GOFile) throws IOException {
		if (Files.notExists(Paths.get(pathToGOFile))) {
			throw new IOException("GO file not found: " + pathToGOFile);
		}
		if (Files.notExists(Paths.get(pathToEC2GOFile))) {
			throw new IOException("EC2GO file not found: " + pathToEC2GOFile);
		}
	}


//	private void startDatabaseTransaction() throws Exception {
//		try {
//			adaptor.startTransaction();
//		} catch (TransactionsNotSupportedException e) {
//			logger.error("Transactions not supported", e);
//			throw new Exception("This program requires transaction support", e);
//		}
//	}

	private Map<String, List<SimpleInstance>> performUpdate(
		GoFiles goFiles, Map<String, List<SimpleInstance>> goInstancesBeforeUpdate) throws Exception {

		GoTermsUpdater goTermsUpdator = new GoTermsUpdater(
			curatorToolAPI, goFiles.goLines, goFiles.ec2GoLines, goInstancesBeforeUpdate);
		return goTermsUpdator.updateGoTerms();
	}

	private void reportOnDuplicateAccessions(
		String when, Map<String, List<SimpleInstance>> goInstancesByAccession) throws Exception {

		DuplicateFinder duplicateReporter = new DuplicateFinder(curatorToolAPI, goInstancesByAccession);
		Map<String, Integer> duplicatedAccessions = duplicateReporter.getDuplicateAccessions();

		if (duplicatedAccessions == null || duplicatedAccessions.isEmpty()) {
			logger.info("No duplicated GO accessions were detected.");
			return;
		}

		logger.warn("Duplicated GO accessions exist! Check report.");
		recordDuplicateAccessions(duplicateReporter, duplicatedAccessions, when);
	}

	private void recordDuplicateAccessions(DuplicateFinder duplicateFinder, Map<String, Integer> duplicatedAccessions,
	                                       String when) throws Exception {
		for (String accession : duplicatedAccessions.keySet()) {
			Map<Long, Integer> referrerCounts = duplicateFinder.getReferrerCountForAccession(accession);
			// The instances come from the finder's own GO instances rather than being read back one dbId at a
			// time; the displayName and schema class the report needs are already on them.
			for (SimpleInstance goInstance : duplicateFinder.getInstancesByAccession(accession)) {
				this.duplicatesReport.printDuplicateRecord(
					goInstance.getDbId(), goInstance.getDisplayName(), accession,
					goInstance.getSchemaClassName(), when, referrerCounts.get(goInstance.getDbId())
				);
			}
		}
	}

//	private void finalizeTransaction() throws Exception {
//		if (testMode) {
//			adaptor.rollback();
//		} else {
//			adaptor.commit();
//		}
//	}

	private void logExecutionTime(long startTime) {
		long endTime = System.currentTimeMillis();
		Duration duration = Duration.ofMillis(endTime - startTime);
		logger.info("Elapsed time: {}", duration);
	}

	// - http://current.geneontology.org/ontology/go.obo (replaces outdated URL http://geneontology.org/ontology/obo_format_1_2/gene_ontology_ext.obo)
	// - http://current.geneontology.org/ontology/external2go/ec2go (replaces outdated URL http://geneontology.org/external2go/ec2go)
	private static class GoFiles {
		final List<String> goLines;
		final List<String> ec2GoLines;

		GoFiles(List<String> goLines, List<String> ec2GoLines) {
			this.goLines = goLines;
			this.ec2GoLines = ec2GoLines;
		}
	}
}