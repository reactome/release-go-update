package org.reactome.release.goupdate.reconciler;

import java.util.*;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.release.goupdate.GONamespace;
import org.reactome.release.goupdate.model.GoTerm;
import org.reactome.release.goupdate.model.parser.GoTermParser;
import org.reactome.release.goupdate.utils.CuratorToolAPI;

import static org.reactome.release.goupdate.model.ObsoleteGoTerm.isObsolete;

/**
 * This class should be used to reconcile between the GO file and the database, after updates have been attempted.
 * @author sshorser
 *
 */
public class GoTermsReconciler {
	private static final String IS_A = "is_a";
	private static final String HAS_PART = "has_part";
	private static final String PART_OF = "part_of";

	private static final Logger logger = LogManager.getLogger();
	private static final Logger reconciliationLogger = LogManager.getLogger("reconciliationLog");

	private CuratorToolAPI curatorToolAPI;
	
	public GoTermsReconciler(CuratorToolAPI curatorToolAPI) {
		this.curatorToolAPI = curatorToolAPI;
	}
	
	/**
	 * Attempts to reconcile between the database and the terms from the parser.
	 * Reconciliation reports are logged to a file (not returned).
	 * @param goTermParser GoTerm parser used to iterate over GO Terms
	 * @throws Exception
	 */
	public void reconcile(GoTermParser goTermParser) throws Exception {
		Iterator<? extends GoTerm> goTermIterator = goTermParser.getGoTermIterator();
		while (goTermIterator.hasNext()) {
			GoTerm goTerm = goTermIterator.next();
			@SuppressWarnings("unchecked")
			Collection<SimpleInstance> goInstances = getInstancesForGoTerm(goTerm);
			if (goInstances == null) {
				// It should be reported if there were no instances returned, but the term is not obsolete.
				if (!isObsolete(goTerm)) {
					reconciliationLogger.warn("GO Accession {} is not present in the database, " +
						"but is NOT marked as obsolete. GO Term might have been deleted in error, or not properly " +
						"created.", goTerm.getId()
					);
				}
				continue;
			}

			if (goInstances.size() > 1) {
				reconciliationLogger.warn("GO Accession {} appears {} times in the database. " +
					"It should probably only appear once.", goTerm.getId(), goInstances.size());
			}

			logger.debug("Reconciling GO Term {}...", goTerm.getId());
			for (SimpleInstance goInstance : goInstances) {
				reconcileDefinition(goInstance, goTerm);
				reconcileName(goInstance, goTerm);
				reconcileNamespace(goInstance, goTerm);
				reconcileIsA(goInstance, goTerm);
				reconcilePartOf(goInstance, goTerm);
				reconcileHasPart(goInstance, goTerm);
				reconcileECNumbers(goInstance, goTerm);
			}
		}
	}

	private void reconcileDefinition(SimpleInstance goInstance, GoTerm goTerm) {
		String goInstanceDefinition = (String) goInstance.getAttribute(ReactomeJavaConstants.definition);
		if (!goTerm.getDef().equals(goInstanceDefinition)) {
			reconciliationLogger.error(
				"Reconciliation error: GO:{}; Attribute: 'definition';\n" +
				"\tValue from file: '{}';\n" +
				"\tValue from database: '{}'",
				goTerm.getId(), goTerm.getDef(), goInstanceDefinition
			);
		}
	}

	private void reconcileName(SimpleInstance goInstance, GoTerm goTerm) {
		String goInstanceName = (String) goInstance.getAttribute(ReactomeJavaConstants.name);
		if (!goTerm.getName().equals(goInstanceName)) {
			reconciliationLogger.error(
				"Reconciliation error: GO:{}; Attribute: 'name';\n" +
				"\tValue from file: \"{}\";\n" +
				"\tValue from database: \"{}\"",
				goTerm.getId(), goTerm.getName(), goInstanceName
			);
		}
	}

	private void reconcileNamespace(SimpleInstance goInstance, GoTerm goTerm) {
		String dbNameSpace = goInstance.getSchemaClassName();
		String fileNameSpace = goTerm.getNamespace().getReactomeName();
		if (!(dbNameSpace.equals(fileNameSpace)
			|| ((dbNameSpace.equals(ReactomeJavaConstants.Compartment) || dbNameSpace.equals(ReactomeJavaConstants.EntityCompartment))
			&& fileNameSpace.equals(GONamespace.cellular_component.getReactomeName())))
		) {
			reconciliationLogger.error(
				"Reconciliation error: GO:{}; Attribute: 'namespace/SchemaClass';\n" +
				"\tValue from file: '{}';\n" +
				"\tValue from database: '{}'",
				goTerm.getId(), fileNameSpace, dbNameSpace
			);
		}
	}

	private void reconcileIsA(SimpleInstance goInstance, GoTerm goTerm) {
		if (goInstance.getSchemaClassName().equals(ReactomeJavaConstants.GO_CellularComponent)) {
			List<SimpleInstance> instancesOf =
				(List<SimpleInstance>) goInstance.getAttribute(ReactomeJavaConstants.instanceOf);
			reconcileRelationship(goTerm.getId(), goTerm.getIsA(), instancesOf, IS_A);
		}
	}

	private void reconcilePartOf(SimpleInstance goInstance, GoTerm goTerm) {
		if (goInstance.getSchemaClassName().equals(ReactomeJavaConstants.GO_CellularComponent)) {
			List<SimpleInstance> partsOf =
				(List<SimpleInstance>) goInstance.getAttribute(ReactomeJavaConstants.componentOf);
			reconcileRelationship(goTerm.getId(), goTerm.getPartOf(), partsOf, PART_OF);
		}
	}

	private void reconcileHasPart(SimpleInstance goInstance, GoTerm goTerm) {
		if (goInstance.getSchemaClassName().equals(ReactomeJavaConstants.GO_CellularComponent)) {
			List<SimpleInstance> hasParts =
				(List<SimpleInstance>) goInstance.getAttribute(ReactomeJavaConstants.hasPart);
			reconcileRelationship(goTerm.getId(), goTerm.getHasPart(), hasParts, HAS_PART);
		}
	}

	/**
	 * Reconciles a relationship for a GO term. Will not return, but will log an ERROR message if reconciliation fails.
	 * @param goAccession - The accession of the term to reconcile.
	 * @param goTermRelationAccessions - The relationship accessions from the GO term.
	 * @param relationInstances - A list of GKInstances associated with the corresponding database instance,
	 *                            associated by some relationship.
	 * @throws Exception
	 */
	private void reconcileRelationship(
		String goAccession,
		List<String> goTermRelationAccessions,
		List<SimpleInstance> relationInstances,
		String relationship
	) {
		boolean found = false;
		for (String goTermRelationAccession: goTermRelationAccessions) {
			for (SimpleInstance relationInstance : relationInstances) {
				relationInstance = getCuratorToolAPI().inflate(relationInstance);
				String accessionFromDB = (String) relationInstance.getAttribute(ReactomeJavaConstants.identifier);
				if (accessionFromDB.equals(goTermRelationAccession)) {
					found = true;
					// exit the loop early, since a match for accession was found.
					break;
				}
			}
			if (!found) {
				reconciliationLogger.error("Reconciliation error: GO:{}; Attribute: \"{}\"; " +
						"File says that GO:{} should be present but it is not in the database.",
					goAccession, relationship, goTermRelationAccession
				);
			}
			found = false;
		}
	}

	/**
	 * Reconciles EC Numbers for a GO term, between the data from ec2go file and the database.
	 * Logs an ERROR if EC numbers fail to reconcile.
	 * @param instance - the instance to reconcile.
	 * @param goTerm  - goTerm to compare
	 * @throws Exception
	 */
	private void reconcileECNumbers(SimpleInstance instance, GoTerm goTerm) throws Exception {
		if (goTerm.getNamespace().getReactomeName().equals(ReactomeJavaConstants.GO_MolecularFunction)) {
			@SuppressWarnings("unchecked")
			List<String> ecNumberValues =
				(List<String>) instance.getAttribute(ReactomeJavaConstants.ecNumber);
			// An instance with no EC number at all has no value for the attribute.
			Set<String> ecNumbersFromDB =
				ecNumberValues != null ? new HashSet<>(ecNumberValues) : new HashSet<>();
			List<String> ecNumbersFromTerm = goTerm.getEcNumbers();
			for (String ecNumberFromTerm : ecNumbersFromTerm) {
				if (!ecNumbersFromDB.contains(ecNumberFromTerm)) {
					reconciliationLogger.error(
						"EC Number {} is in the file for GO Accession {} but is not in the db for that accession.",
						ecNumberFromTerm, instance.getAttribute(ReactomeJavaConstants.identifier)
					);
				}
			}
		}
	}

	private List<SimpleInstance> getInstancesForGoTerm(GoTerm goTerm) throws Exception {
		return getCuratorToolAPI().fetchGOInstancesForClassByAccession(
			goTerm.getNamespace().getReactomeName(), goTerm.getId()
		);
	}

	private CuratorToolAPI getCuratorToolAPI() {
		return this.curatorToolAPI;
	}
}
