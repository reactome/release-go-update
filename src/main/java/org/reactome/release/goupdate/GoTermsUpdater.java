package org.reactome.release.goupdate;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.GKSchemaAttribute;
import org.reactome.release.goupdate.editor.GOInstanceCreator;
import org.reactome.release.goupdate.editor.GOInstanceDeleter;
import org.reactome.release.goupdate.editor.GOInstanceUpdater;
import org.reactome.release.goupdate.model.GoTerm;
import org.reactome.release.goupdate.model.ObsoleteGoTerm;
import org.reactome.release.goupdate.model.parser.GoTermParser;
import org.reactome.release.goupdate.reconciler.GoTermsReconciler;
import org.reactome.release.goupdate.reports.*;

import static org.reactome.release.goupdate.model.ObsoleteGoTerm.isObsolete;
import static org.reactome.release.goupdate.utils.Utils.*;

/**
 * This class can be used to update GOTerms in the "gk_central" database.
 * @author sshorser
 *
 */
class GoTermsUpdater {
	private static final Logger logger = LogManager.getLogger();

	private MySQLAdaptor adaptor;
	private List<String> goLines;
	private List<String> ec2GoLines;

	private CategoryMismatchReport categoryMismatchReport;
	private NewGOTermsReport newGOTermsReport;
	private NewMolecularFunctionReport newMolecularFunctionReport;
	private ObsoleteAccessionReport obsoleteAccessionReport;
	private ReplacedGOTermsReport replacedGOTermsReport;

	/**
	 * Creates a new GoTermsUpdater
	 * @param dba - The adaptor to use.
	 * @param goLines - The lines from the GO file, probably it was named "gene_ontology_ext.obo". They <em>must</em>
	 *                  be in the same sequences as they were in the original file!!
	 * @param ec2GoLines - The lines from the EC-to-GO mapping file, probably named "ec2go".
	 */
	public GoTermsUpdater(MySQLAdaptor dba, List<String> goLines, List<String> ec2GoLines) {
		this.adaptor = dba;

		this.goLines = goLines;
		this.ec2GoLines = ec2GoLines;

		initReports();
	}

	/**
	 * Executes the GO Terms updates. Returns a StringBuilder, which contains a report about what happened.
	 * @return
	 */
	public void updateGoTerms() throws Exception {
		// This map is keyed by the GO Accession number (GO ID).
		Map<String, List<GKInstance>> allGoInstances = getAccessionToGOInstancesMap(adaptor);

		// Maps GO IDs to EC Numbers.
		Map<String,List<String>> goToECNumbers = new HashMap<>();
		ec2GoLines.stream().filter(line -> !line.startsWith("!")).forEach(
			line -> processEc2GoLine(line, goToECNumbers)
		);

		GoTermParser goTermParser = new GoTermParser(this.goLines, goToECNumbers);
		processGOTerms(goTermParser, allGoInstances);

		logger.info("Reconciling GO database instances with go obo file...");
		GoTermsReconciler reconciler = new GoTermsReconciler(this.adaptor);
		reconciler.reconcile(goTermParser);

		closeReports();
	}

	private void initReports() {
		this.categoryMismatchReport = new CategoryMismatchReport();
		this.newGOTermsReport = new NewGOTermsReport();
		this.newMolecularFunctionReport = new NewMolecularFunctionReport();
		this.obsoleteAccessionReport = new ObsoleteAccessionReport();
		this.replacedGOTermsReport = new ReplacedGOTermsReport();
	}

	private void processGOTerms(GoTermParser goTermParser, Map<String, List<GKInstance>> allGoInstances)
		throws Exception {

		Iterator<? extends GoTerm> goTermIterator = goTermParser.getGoTermIterator();

		while (goTermIterator.hasNext()) {
			GoTerm goTerm = goTermIterator.next();
			processGOTerm(goTerm, allGoInstances);
		}
	}

	private void processGOTerm(GoTerm goTerm, Map<String, List<GKInstance>> allGoInstances) throws Exception {
		logger.debug("Processing GO Term " + goTerm.getId());

		GOInstanceCreator goInstanceCreator = new GOInstanceCreator(adaptor);
		GOInstanceDeleter goInstanceDeleter = new GOInstanceDeleter(adaptor, obsoleteAccessionReport);
		GOInstanceUpdater goInstanceUpdater = new GOInstanceUpdater(adaptor);

		List<GKInstance> existingGOInstances = allGoInstances.get(goTerm.getId());
		if (existingGOInstances == null) {
			GKInstance newGOInstance = goInstanceCreator.createNewGOInstanceIfNotObsolete(goTerm);
			if (newGOInstance != null) {
				reportNewGOInstance(newGOInstance.getDBID(), goTerm);
				allGoInstances.computeIfAbsent(goTerm.getId(), k -> new ArrayList<>()).add(newGOInstance);
			}
		} else {
			for (GKInstance existingGOInstance : existingGOInstances) {
				if (categoryIsOkay(existingGOInstance, goTerm)) {
					goInstanceUpdater.updateGOInstance(existingGOInstance, goTerm);
				} else {
					categoryMismatchReport.printCategoryMismatchRecord(existingGOInstance, goTerm);
					goInstanceDeleter.deleteGOInstance(existingGOInstance);

					GKInstance newGOInstance = goInstanceCreator.createNewGOInstance(goTerm);
					reportNewGOInstance(newGOInstance.getDBID(), goTerm);
					allGoInstances.computeIfAbsent(goTerm.getId(), k -> new ArrayList<>()).add(newGOInstance);
				}
			}
		}
		processAlternateIds(goTerm, allGoInstances);

		if (isObsolete(goTerm) && existingGOInstances != null) {
			List<GKInstance> instancesForDeletion = processObsoleteGOTerm((ObsoleteGoTerm) goTerm, existingGOInstances);

			Map<GKInstance, Collection<GKInstance>> undeletableInstanceToReferrers =
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

	private boolean categoryIsOkay(GKInstance existingGOInstance, GoTerm goTerm) {
		GONamespace currentCategory = goTerm.getNamespace();

		boolean isCellularComponentOrSubclass =
			(existingGOInstance.getSchemClass().isa(ReactomeJavaConstants.Compartment) ||
			existingGOInstance.getSchemClass().isa(ReactomeJavaConstants.EntityCompartment)) &&
			currentCategory.getReactomeName().equals(ReactomeJavaConstants.GO_CellularComponent);

		// The category is "OK" (i.e., NOT a mismatch) if it matches the Reactome name,
		// OR if it doesn't match exactly, but the current category is CellularComponent
		// and the instance itself is (Entity)Compartment.
		return existingGOInstance.getSchemClass().getName().equals(currentCategory.getReactomeName()) ||
			isCellularComponentOrSubclass;
	}

	/**
	 * Processes a single GO Term that is obsolete. This involves examining them and flagging them for deletion if
	 * possible. If it is not possible to delete the instance (usually because there ARE referrers and there is NO
	 * suggested replacement), a message will be logged suggesting manual cleanup.
	 * @param goTerm - The GO terms from the file.
 	 * @param goInstances - A list of GO instances that are identified by goID
	 * @return instancesForDeletion - A list of instances that must be deleted.
	 */
	private List<GKInstance> processObsoleteGOTerm(ObsoleteGoTerm goTerm, List<GKInstance> goInstances)
		throws Exception {

		List<GKInstance> instancesForDeletion = new ArrayList<>();
		//Map<GKSchemaAttribute, Integer> referrersCount = new HashMap<>();
		// If an obsolete term has no replacement AND also has no referrers, it can be
		// safely deleted because nothing will be affected.
		for (GKInstance goInstance : goInstances) {
			if (hasNonGoReferrers(goInstance)) {
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

	private GKInstance getReplacementGoInstance(ObsoleteGoTerm goTerm, Map<String, List<GKInstance>> allGoInstances) {
		String replacedByAccession = goTerm.getReplacedBy().replace("GO:","");
		return allGoInstances.computeIfAbsent(replacedByAccession, k -> new ArrayList<>())
			.stream()
			.findFirst()
			.orElse(null);
	}

	private List<GKInstance> getReferrersFilteredByClass(GKInstance inst, Predicate<? super GKInstance> classFilter)
		throws Exception {

		List<GKInstance> referrers= new ArrayList<>();
		for (GKSchemaAttribute attrib : (Collection<GKSchemaAttribute>)inst.getSchemClass().getReferers()) {
			Collection<GKInstance> attribReferrers = (Collection<GKInstance>) inst.getReferers(attrib);

			attribReferrers = attribReferrers.stream().filter(classFilter).collect(Collectors.toList());

			if ( attribReferrers!=null && !attribReferrers.isEmpty()) {
				referrers.addAll(attribReferrers);
			}
		}
		return referrers;
	}

	/**
	 * Process alternate GO terms for a given GO Term. This involves deleting secondary identifiers and then
	 * redirecting the referrers for those to the instance whose GO ID is <code>goID</code>
	 * @param goTerm - GO term with alternate ids
	 * @param allGoInstances - A map of ALL GO Terms in the database.
	 */
	private void processAlternateIds(
		GoTerm goTerm,
		Map<String, List<GKInstance>> allGoInstances
	) {
		GOInstanceDeleter goInstanceDeleter = new GOInstanceDeleter(adaptor, obsoleteAccessionReport);
		String goID = goTerm.getId();
		if (!goTerm.getAlternateIds().isEmpty() && allGoInstances.containsKey(goID)) {
			for (GKInstance primaryGOInst : allGoInstances.get(goID)) {
				// Now that we have a list of alternates for *this* accession, we need to mark them for deletion and
				// have their referrers refer to *this* accession.
				for (String secondaryAccession : goTerm.getAlternateIds()) {
					// Check that we're even using this secondary accession.
					if (allGoInstances.get(secondaryAccession) != null) {
						for (GKInstance altGoInst : allGoInstances.get(secondaryAccession)) {
							logger.info("{} is an alternate/secondary ID for {} - " +
								"{} will be deleted and its referrers will refer to {}.",
								secondaryAccession, goID, secondaryAccession, goID
							);
							try {
								this.replacedGOTermsReport.printReplacedGOTermsRecord(
									primaryGOInst.getDBID(),
									primaryGOInst.getDisplayName(),
									goID,
									primaryGOInst.getSchemClass().getName(),
									altGoInst.getDBID(),
									secondaryAccession,
									altGoInst.getSchemClass().getName(),
									getReferrersFilteredByClass(altGoInst, isNotGOEntity)
										.stream()
										.map(inst -> inst.toString())
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
	 * Returns a map of all GO-related instances in the databases.
	 * @param dba
	 *
	 * @return
	 */
	private Map<String, List<GKInstance>> getAccessionToGOInstancesMap(MySQLAdaptor dba) throws Exception {
		Collection<GKInstance> bioProcesses = getGOInstances(dba, ReactomeJavaConstants.GO_BiologicalProcess);
		Collection<GKInstance> molecularFunctions = getGOInstances(dba, ReactomeJavaConstants.GO_MolecularFunction);
		Collection<GKInstance> cellComponents = getGOInstances(dba, ReactomeJavaConstants.GO_CellularComponent);

		Map<String, List<GKInstance>> allGoInstances = new HashMap<>();
		Consumer<? super GKInstance> populateInstMap = inst -> {
			allGoInstances.computeIfAbsent(getAccession(inst), k -> new ArrayList<>()).add(inst);
		};

		bioProcesses.forEach(populateInstMap);
		cellComponents.forEach(populateInstMap);
		molecularFunctions.forEach(populateInstMap);

		return allGoInstances;
	}

	@SuppressWarnings("unchecked")
	private List<GKInstance> getGOInstances(MySQLAdaptor dba, String goClassName) throws Exception {
		return new ArrayList<GKInstance>(dba.fetchInstancesByClass(goClassName));
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
}
