package org.reactome.release.goupdate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.persistence.MySQLAdaptor;
import org.gk.persistence.TransactionsNotSupportedException;
import org.reactome.release.common.ReleaseStep;
import org.reactome.release.goupdate.duplicate.DuplicateFinder;
import org.reactome.release.goupdate.reports.DuplicatesReport;
import org.reactome.util.general.DBUtils;

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
	private MySQLAdaptor adaptor;
	private DuplicatesReport duplicatesReport;

	@Override
	public void executeStep(Properties props) {
		long startTime = System.currentTimeMillis();
		try {
			initialize(props);
			processGoUpdate(props);
		} catch (Exception e) {
			logger.error("Error during GO update", e);
			throw new RuntimeException("GO update failed", e);
		} finally {
			logExecutionTime(startTime);
		}
	}

	private void initialize(Properties props) throws SQLException {
		adaptor = DBUtils.getCuratorDbAdaptor(props);
		loadTestModeFromProperties(props);
		initializeInstanceEditUtils(props);
	}

	private void processGoUpdate(Properties props) throws Exception {
		GoFiles goFiles = loadGoFiles(props);

		processUpdateWithTransaction(goFiles);

		finalizeTransaction();
	}

	private void initializeInstanceEditUtils(Properties props) {
		long personID = Long.parseLong(props.getProperty("personId"));
		GoUpdateInstanceEditUtils.setAdaptor(adaptor);
		GoUpdateInstanceEditUtils.setPersonID(personID);
	}

	private GoFiles loadGoFiles(Properties props) throws IOException {
		String pathToGOFile = props.getProperty("pathToGOFile", "src/main/resources/go.obo");
		String pathToEC2GOFile = props.getProperty("pathToEC2GOFile", "src/main/resources/ec2go");

		validateFilesExist(pathToGOFile, pathToEC2GOFile);

		return new GoFiles(
			Files.readAllLines(Paths.get(pathToGOFile)),
			Files.readAllLines(Paths.get(pathToEC2GOFile))
		);
	}

	private void processUpdateWithTransaction(GoFiles goFiles) throws Exception {
		startDatabaseTransaction();

		this.duplicatesReport = new DuplicatesReport();

		reportOnDuplicateAccessions("BEFORE GO Update");
		performUpdate(goFiles);
		reportOnDuplicateAccessions("AFTER GO Update");
	}

	private void validateFilesExist(String pathToGOFile, String pathToEC2GOFile) throws IOException {
		if (Files.notExists(Paths.get(pathToGOFile))) {
			throw new IOException("GO file not found: " + pathToGOFile);
		}
		if (Files.notExists(Paths.get(pathToEC2GOFile))) {
			throw new IOException("EC2GO file not found: " + pathToEC2GOFile);
		}
	}


	private void startDatabaseTransaction() throws Exception {
		try {
			adaptor.startTransaction();
		} catch (TransactionsNotSupportedException e) {
			logger.error("Transactions not supported", e);
			throw new Exception("This program requires transaction support", e);
		}
	}

	private void performUpdate(GoFiles goFiles) throws Exception {
		GoTermsUpdater goTermsUpdator = new GoTermsUpdater(adaptor, goFiles.goLines, goFiles.ec2GoLines);
		goTermsUpdator.updateGoTerms();
	}

	private void reportOnDuplicateAccessions(String when) throws Exception {
		DuplicateFinder duplicateReporter = new DuplicateFinder(adaptor);
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
			for (Map.Entry<Long, Integer> entry : referrerCounts.entrySet()) {
				GKInstance inst = adaptor.fetchInstance(entry.getKey());
				this.duplicatesReport.printDuplicateRecord(
					entry.getKey(), inst.getDisplayName(), accession,
					inst.getSchemClass().getName(), when, entry.getValue()
				);
			}
		}
	}

	private void finalizeTransaction() throws Exception {
		if (testMode) {
			adaptor.rollback();
		} else {
			adaptor.commit();
		}
	}

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