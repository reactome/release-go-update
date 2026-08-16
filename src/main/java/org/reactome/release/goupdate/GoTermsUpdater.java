package org.reactome.release.goupdate;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.release.goupdate.editor.GOInstanceCreator;
import org.reactome.release.goupdate.editor.GOInstanceDeleter;
import org.reactome.release.goupdate.editor.GOInstanceUpdater;
import org.reactome.release.goupdate.model.GoTerm;
import org.reactome.release.goupdate.model.ObsoleteGoTerm;
import org.reactome.release.goupdate.model.parser.GoTermParser;
import org.reactome.release.goupdate.reconciler.GoTermsReconciler;
import org.reactome.release.goupdate.reports.*;
import org.reactome.release.goupdate.utils.CuratorToolAPI;
import org.reactome.release.goupdate.utils.Utils;
import org.reactome.server.graph.domain.model.DatabaseObject;

import static org.reactome.release.goupdate.model.ObsoleteGoTerm.isObsolete;
import static org.reactome.release.goupdate.utils.Utils.*;

/**
 * This class can be used to update GOTerms in the "gk_central" database.
 * @author sshorser
 *
 */
class GoTermsUpdater {
	private static final Logger logger = LogManager.getLogger();

	private CuratorToolAPI curatorToolAPI;
	private List<String> goLines;
	private List<String> ec2GoLines;
	private Map<String, List<SimpleInstance>> allGoInstances;

	private CategoryMismatchReport categoryMismatchReport;
	private NewGOTermsReport newGOTermsReport;
	private NewMolecularFunctionReport newMolecularFunctionReport;
	private ObsoleteAccessionReport obsoleteAccessionReport;
	private ReplacedGOTermsReport replacedGOTermsReport;

	/**
	 * Creates a new GoTermsUpdater
	 * @param curatorToolAPI - The curatorToolAPI to communicate with the graph db.
	 * @param goLines - The lines from the GO file, probably it was named "gene_ontology_ext.obo". They <em>must</em>
	 *                  be in the same sequences as they were in the original file!!
	 * @param ec2GoLines - The lines from the EC-to-GO mapping file, probably named "ec2go".
	 * @param allGoInstances - The GO instances currently in the database, keyed by GO accession. The update
	 *                         changes this map as it goes, adding the instances it creates and dropping the ones
	 *                         it deletes, and then <em>empties</em> it once the update is done so that the
	 *                         instances can be collected while reconciliation reads them back. A caller must
	 *                         therefore not use this map again after {@link #updateGoTerms()}.
	 */
	public GoTermsUpdater(
		CuratorToolAPI curatorToolAPI,
		List<String> goLines,
		List<String> ec2GoLines,
		Map<String, List<SimpleInstance>> allGoInstances
	) {
		this.curatorToolAPI = curatorToolAPI;

		this.goLines = goLines;
		this.ec2GoLines = ec2GoLines;
		this.allGoInstances = allGoInstances;

		initReports();
	}

	/**
	 * Executes the GO Terms updates, and then reconciles the database against the GO file.
	 *
	 * @return the GO instances as reconciliation read them back from the database, keyed by GO accession, so
	 *         that a caller reporting on the state after the update does not have to read them all again.
	 */
	public Map<String, List<SimpleInstance>> updateGoTerms() throws Exception {
		// Maps GO IDs to EC Numbers.
		Map<String,List<String>> goToECNumbers = new HashMap<>();
		ec2GoLines.stream().filter(line -> !line.startsWith("!")).forEach(
			line -> processEc2GoLine(line, goToECNumbers)
		);

		GoTermParser goTermParser = new GoTermParser(this.goLines, goToECNumbers);
		processGOTerms(goTermParser, this.allGoInstances);

		// Emptied rather than just dropped: the caller passed this map in and its own reference to it stays
		// live for as long as this call, so clearing the entries is what actually lets the instances be
		// collected before the ones read below are held alongside them.
		logger.info("Releasing the {} GO accessions read before the update.", this.allGoInstances.size());
		this.allGoInstances.clear();

		logger.info("Reconciling GO database instances with go obo file...");
		// Read again rather than reconciling against the map the update has been changing: reconciliation's job
		// is to check what actually ended up in the database.
		Map<String, List<SimpleInstance>> goInstancesAfterUpdate =
			getCuratorToolAPI().fetchGOInstancesByAccession();
		new GoTermsReconciler(goInstancesAfterUpdate).reconcile(goTermParser);

		closeReports();

		return goInstancesAfterUpdate;
	}

	private void initReports() {
		this.categoryMismatchReport = new CategoryMismatchReport();
		this.newGOTermsReport = new NewGOTermsReport();
		this.newMolecularFunctionReport = new NewMolecularFunctionReport();
		this.obsoleteAccessionReport = new ObsoleteAccessionReport();
		this.replacedGOTermsReport = new ReplacedGOTermsReport();
	}

	private void processGOTerms(GoTermParser goTermParser, Map<String, List<SimpleInstance>> allGoInstances)
		throws Exception {

		Iterator<? extends GoTerm> goTermIterator = goTermParser.getGoTermIterator();

		while (goTermIterator.hasNext()) {
			GoTerm goTerm = goTermIterator.next();
			processGOTerm(goTerm, allGoInstances);
		}
	}

	private void processGOTerm(GoTerm goTerm, Map<String, List<SimpleInstance>> allGoInstances) throws Exception {
		logger.info("Processing GO Term " + goTerm.getId());

		GOInstanceCreator goInstanceCreator = new GOInstanceCreator(getCuratorToolAPI());
		GOInstanceDeleter goInstanceDeleter = new GOInstanceDeleter(getCuratorToolAPI(), obsoleteAccessionReport);
		GOInstanceUpdater goInstanceUpdater = new GOInstanceUpdater(getCuratorToolAPI());

		List<SimpleInstance> existingGOInstances = allGoInstances.get(goTerm.getId());
		if (existingGOInstances != null) {
			// The instances were read before the update began, so any that this run has already written to (as a
			// referrer of a deleted GO term, for example) have to be re-read before they are changed again.
			refreshInstances(existingGOInstances, getCuratorToolAPI());
		}

		if (existingGOInstances == null) {
			SimpleInstance newGOInstance = goInstanceCreator.createNewGOInstanceIfNotObsolete(goTerm);
			if (newGOInstance != null) {
				reportNewGOInstance(newGOInstance.getDbId(), goTerm);
				allGoInstances.computeIfAbsent(goTerm.getId(), k -> new ArrayList<>()).add(newGOInstance);
			}
		} else {
			// This list *is* the map's list for this accession, so the instances replacing the ones whose class
			// does not match are collected and added once the loop is done -- adding them as they are created is
			// a concurrent modification of the list being iterated. The instance each one replaces is dropped
			// from the list as it is deleted, so that the rest of this update works from the live instances only.
			List<SimpleInstance> replacementGOInstances = new ArrayList<>();
			Iterator<SimpleInstance> existingGOInstanceIterator = existingGOInstances.iterator();
			while (existingGOInstanceIterator.hasNext()) {
				SimpleInstance existingGOInstance = existingGOInstanceIterator.next();
				if (hasClassForNamespace(existingGOInstance, goTerm.getNamespace())) {
					goInstanceUpdater.updateGOInstance(existingGOInstance, goTerm);
				} else {
					categoryMismatchReport.printCategoryMismatchRecord(existingGOInstance, goTerm);
					goInstanceDeleter.deleteGOInstance(existingGOInstance);
					existingGOInstanceIterator.remove();

					SimpleInstance newGOInstance = goInstanceCreator.createNewGOInstance(goTerm);
					reportNewGOInstance(newGOInstance.getDbId(), goTerm);
					replacementGOInstances.add(newGOInstance);
				}
			}
			existingGOInstances.addAll(replacementGOInstances);
		}
		processAlternateIds(goTerm, allGoInstances);

		if (isObsolete(goTerm) && existingGOInstances != null) {
			List<SimpleInstance> instancesForDeletion = processObsoleteGOTerm((ObsoleteGoTerm) goTerm, existingGOInstances);

			Map<SimpleInstance, Collection<SimpleInstance>> undeletableInstanceToReferrers =
				goInstanceDeleter.deleteFlaggedInstances(
					instancesForDeletion,
					(ObsoleteGoTerm) goTerm,
					getReplacementGoInstance((ObsoleteGoTerm) goTerm, allGoInstances)
				);

			goInstanceDeleter.logUndeletableInstances(undeletableInstanceToReferrers);

		}

		goInstanceUpdater.updateRelationships(goTerm, allGoInstances);
	}

	private void reportNewGOInstance(long dbId, GoTerm goTerm) throws IOException {
		this.newGOTermsReport.printNewGOTermRecord(dbId, goTerm);
		if (isMolecularFunction(goTerm)) {
			this.newMolecularFunctionReport.printNewMFRecord(dbId, goTerm);
		}
	}

	/**
	 * Processes a single GO Term that is obsolete. This involves examining them and flagging them for deletion if
	 * possible. If it is not possible to delete the instance (usually because there ARE referrers and there is NO
	 * suggested replacement), a message will be logged suggesting manual cleanup.
	 * @param goTerm - The GO terms from the file.
 	 * @param goInstances - A list of GO instances that are identified by goID
	 * @return instancesForDeletion - A list of instances that must be deleted.
	 */
	private List<SimpleInstance> processObsoleteGOTerm(ObsoleteGoTerm goTerm, List<SimpleInstance> goInstances)
		throws Exception {

		List<SimpleInstance> instancesForDeletion = new ArrayList<>();
		//Map<GKSchemaAttribute, Integer> referrersCount = new HashMap<>();
		// If an obsolete term has no replacement AND also has no referrers, it can be
		// safely deleted because nothing will be affected.
		for (SimpleInstance goInstance : goInstances) {
			if (hasNonGoReferrers(goInstance, getCuratorToolAPI())) {
				this.obsoleteAccessionReport.printObsoleteAccessionRecord(
					goInstance,
					"Manual cleanup (referrers exist)",
					goTerm.getReplacedByOrConsiderString()
				);
			} else {
				instancesForDeletion.add(goInstance);
			}
		}

		return instancesForDeletion;
	}

	private SimpleInstance getReplacementGoInstance(
		ObsoleteGoTerm goTerm, Map<String, List<SimpleInstance>> allGoInstances) {

		String replacedByAccession = goTerm.getReplacedBy().replace("GO:","");
		// Looked up rather than created if absent: an entry inserted here for an accession the database has no
		// instances of makes the term that accession belongs to look, when its turn comes, like one whose
		// instances are already in hand, so no instance would be created for it.
		return allGoInstances.getOrDefault(replacedByAccession, Collections.emptyList())
			.stream()
			.findFirst()
			.orElse(null);
	}

	/**
	 * Process alternate GO terms for a given GO Term. This involves deleting secondary identifiers and then
	 * redirecting the referrers for those to the instance whose GO ID is <code>goID</code>
	 * @param goTerm - GO term with alternate ids
	 * @param allGoInstances - A map of ALL GO Terms in the database.
	 */
	private void processAlternateIds(
		GoTerm goTerm,
		Map<String, List<SimpleInstance>> allGoInstances
	) {
		GOInstanceDeleter goInstanceDeleter = new GOInstanceDeleter(curatorToolAPI, obsoleteAccessionReport);
		String goID = goTerm.getId();
		if (!goTerm.getAlternateIds().isEmpty() && allGoInstances.containsKey(goID)) {
			for (SimpleInstance primaryGOInst : allGoInstances.get(goID)) {
				// Now that we have a list of alternates for *this* accession, we need to mark them for deletion and
				// have their referrers refer to *this* accession.
				for (String secondaryAccession : goTerm.getAlternateIds()) {
					// Check that we're even using this secondary accession.
					if (allGoInstances.get(secondaryAccession) != null) {
						for (SimpleInstance altGoInst : allGoInstances.get(secondaryAccession)) {
							logger.info("{} is an alternate/secondary ID for {} - " +
								"{} will be deleted and its referrers will refer to {}.",
								secondaryAccession, goID, secondaryAccession, goID
							);
							try {
								this.replacedGOTermsReport.printReplacedGOTermsRecord(
									primaryGOInst.getDbId(),
									primaryGOInst.getDisplayName(),
									goID,
									primaryGOInst.getSchemaClassName(),
									altGoInst.getDbId(),
									secondaryAccession,
									altGoInst.getSchemaClassName(),
									getReferrersFilteredByClass(altGoInst, getCuratorToolAPI(), isNotGOEntity)
										.stream()
										.map(DatabaseObject::getDisplayName)
										.collect(Collectors.joining("; "))
								);
							} catch (Exception e) {
								e.printStackTrace();
							}
							goInstanceDeleter.deleteSecondaryGOInstance(altGoInst, primaryGOInst);
						}
					}
				}
			}
		}
	}

	private boolean isMolecularFunction(GoTerm goTerm) {
		return goTerm.getNamespace().getReactomeName().equals(ReactomeJavaConstants.GO_MolecularFunction);
	}

	/**
	 * Processes a line from the EC-to-GO file.
	 * @param line - The line.
	 * @param goToECNumbers - The map of GO to EC numbers, which will be updated by this function.
	 */
	private void processEc2GoLine(String line, Map<String, List<String>> goToECNumbers) {
		final Pattern ecNumberRegexPattern = Pattern.compile("^EC:([0-9\\.]+) > GO:.*GO:([0-9]+)");

		Matcher ecNumberRegexMatcher = ecNumberRegexPattern.matcher(line);
		if (ecNumberRegexMatcher.matches()) {
			String ecNumber = ecNumberRegexMatcher.group(1);
			String goNumber = ecNumberRegexMatcher.group(2);

			goToECNumbers.computeIfAbsent(goNumber, k -> new ArrayList<>()).add(ecNumber);
		}
	}

	private void closeReports() throws IOException {
		this.categoryMismatchReport.close();
		this.newGOTermsReport.close();
		this.newMolecularFunctionReport.close();
		this.obsoleteAccessionReport.close();
		this.replacedGOTermsReport.close();
	}

	private CuratorToolAPI getCuratorToolAPI() {
		return this.curatorToolAPI;
	}
}
